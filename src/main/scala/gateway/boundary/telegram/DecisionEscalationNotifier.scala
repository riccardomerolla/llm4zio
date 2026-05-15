package gateway.boundary.telegram

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream

import _root_.config.entity.ConfigRepository
import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import decision.entity.{ Decision, DecisionRepository }
import gateway.control.{ ChannelRegistry, TelegramClient }
import gateway.entity.{
  TelegramClientError,
  TelegramInlineKeyboardButton,
  TelegramInlineKeyboardMarkup,
  TelegramSendMessage,
}
import shared.errors.PersistenceError
import shared.ids.Ids.DecisionId

/** Phase 3 (big-review R7): GateEscalationPublisher.
  *
  * Subscribes to the ActivityHub and, when a `DecisionEscalated` event lands,
  * pushes a quick-reply message to the supervisor's Telegram chat. The message
  * carries Decision.renderableQuickOptions as inline-keyboard buttons; the
  * `callback_data` payload is `decision:{decisionId}:{optionKey}` so a future
  * R8 callback-query handler can parse the supervisor's tap back into
  * `DecisionInbox.resolve`.
  *
  * Telegram-specific by design — Phase 3 R9 will add a parallel web supervisor
  * inbox that subscribes to the same activity event. Both renderers consume
  * the same channel-agnostic `Decision + QuickOption[]` model from R6.
  *
  * Skips silently when:
  *   - the `telegram.enabled` setting is unset/false (Telegram channel disabled)
  *   - the `telegram.supervisorChatId` setting is unset (no destination)
  *   - the Telegram channel is not registered (gateway not bound to Telegram)
  *
  * Configuration keys:
  *   - `telegram.enabled` (string boolean) — must be true for any push
  *   - `telegram.supervisorChatId` (numeric) — chat_id to deliver to
  */
enum DecisionEscalationNotifierError:
  case Persistence(error: PersistenceError)
  case Telegram(error: TelegramClientError)
  case Channel(message: String)
  case MissingSupervisor
  case InvalidPayload(detail: String)

trait DecisionEscalationNotifier:
  def runOnce: UIO[Unit]
  def runLoop: UIO[Nothing]

object DecisionEscalationNotifier:

  /** Wire format for inline-keyboard callbacks. Parsed by the R8 callback
    * handler back into a `(DecisionId, optionKey)` pair so the resolve path
    * can find the matching QuickOption on the Decision and apply it.
    *
    * Format: `decision:resolve:{decisionId}:{optionKey}`. The `resolve`
    * infix disambiguates from the legacy 3-part `decision:escalate:{id}`
    * payload that the existing escalation button uses.
    */
  def callbackData(decisionId: DecisionId, optionKey: String): String =
    s"decision:resolve:${decisionId.value}:${optionKey.trim}"

  /** Inverse of `callbackData`. Returns None for any payload that doesn't
    * carry our prefix (other callbacks coexist in Telegram).
    */
  def parseCallbackData(payload: String): Option[(DecisionId, String)] =
    payload.split(":", 4).toList match
      case "decision" :: "resolve" :: id :: key :: Nil if id.nonEmpty && key.nonEmpty =>
        Some(DecisionId(id) -> key)
      case _                                                                          => None

  /** Build the supervisor message body. Pure — exported for testing. */
  def buildMessageText(decision: Decision, reason: String): String =
    val urgency = decision.urgency.toString
    val opts    = decision.renderableQuickOptions
    val list    =
      if opts.isEmpty then ""
      else
        "\n\nQuick reply:\n" + opts.map(o => s"• [${o.key}] ${o.label}").mkString("\n")
    s"""🚨 Decision needs attention ($urgency)
       |
       |${decision.title}
       |
       |${decision.context}
       |
       |Reason: $reason$list""".stripMargin

  /** Build the inline keyboard from QuickOption[]. Pure — exported for testing.
    * Returns None if the option list is empty (Telegram rejects empty markup).
    */
  def buildKeyboard(decision: Decision): Option[TelegramInlineKeyboardMarkup] =
    val opts = decision.renderableQuickOptions
    if opts.isEmpty then None
    else
      Some(
        TelegramInlineKeyboardMarkup(
          inline_keyboard = List(
            opts.map(o =>
              TelegramInlineKeyboardButton(
                text = o.label,
                callback_data = Some(callbackData(decision.id, o.key)),
              )
            )
          )
        )
      )

  val live
    : ZLayer[ActivityHub & DecisionRepository & ChannelRegistry & ConfigRepository, Nothing, DecisionEscalationNotifier] =
    ZLayer.scoped {
      for
        hub             <- ZIO.service[ActivityHub]
        decisionRepo    <- ZIO.service[DecisionRepository]
        channelRegistry <- ZIO.service[ChannelRegistry]
        configRepo      <- ZIO.service[ConfigRepository]
        service          = DecisionEscalationNotifierLive(hub, decisionRepo, channelRegistry, configRepo)
        _               <- service.runLoop.forkScoped
      yield service
    }

final case class DecisionEscalationNotifierLive(
  hub: ActivityHub,
  decisionRepository: DecisionRepository,
  channelRegistry: ChannelRegistry,
  configRepository: ConfigRepository,
) extends DecisionEscalationNotifier:

  override def runOnce: UIO[Unit] =
    hub.subscribe.flatMap { queue =>
      ZStream.fromQueue(queue).take(1).mapZIO(handleEventSafely).runDrain
    }

  override def runLoop: UIO[Nothing] =
    hub.subscribe.flatMap { queue =>
      ZStream.fromQueue(queue).mapZIO(handleEventSafely).runDrain
    }.forever

  private def handleEventSafely(event: ActivityEvent): UIO[Unit] =
    if event.eventType != ActivityEventType.DecisionEscalated then ZIO.unit
    else
      handleEvent(event).catchAll {
        case DecisionEscalationNotifierError.MissingSupervisor =>
          // Quiet skip — supervisor not configured yet (first-run / policy-locked)
          ZIO.unit
        case other                                             =>
          ZIO.logWarning(s"DecisionEscalationNotifier skipped escalation: $other")
      }

  private def handleEvent(event: ActivityEvent): IO[DecisionEscalationNotifierError, Unit] =
    for
      payloadJson    <- ZIO
                          .fromOption(event.payload)
                          .orElseFail(DecisionEscalationNotifierError.InvalidPayload("missing payload"))
      decisionId     <- decisionIdFromPayload(payloadJson)
      reason          = reasonFromPayload(payloadJson).getOrElse("Decision escalated")
      _              <- ensureTelegramEnabled
      supervisorChat <- loadSupervisorChatId
      decision       <- decisionRepository
                          .get(decisionId)
                          .mapError(DecisionEscalationNotifierError.Persistence.apply)
      _              <- send(supervisorChat, decision, reason)
    yield ()

  private def decisionIdFromPayload(payload: String): IO[DecisionEscalationNotifierError, DecisionId] =
    ZIO
      .fromEither(payload.fromJson[Json.Obj])
      .mapError(err => DecisionEscalationNotifierError.InvalidPayload(s"payload not a JSON object: $err"))
      .flatMap { obj =>
        obj.fields.collectFirst { case ("decisionId", Json.Str(value)) if value.trim.nonEmpty => value.trim } match
          case Some(value) => ZIO.succeed(DecisionId(value))
          case None        =>
            ZIO.fail(DecisionEscalationNotifierError.InvalidPayload("payload missing decisionId"))
      }

  private def reasonFromPayload(payload: String): Option[String] =
    payload.fromJson[Json.Obj].toOption.flatMap { obj =>
      obj.fields.collectFirst { case ("reason", Json.Str(value)) if value.trim.nonEmpty => value.trim }
    }

  private def ensureTelegramEnabled: IO[DecisionEscalationNotifierError, Unit] =
    configRepository
      .getSetting("telegram.enabled")
      .mapError(err => DecisionEscalationNotifierError.Persistence(err))
      .map(_.flatMap(row => Option(row.value)).map(_.trim.toLowerCase).contains("true"))
      .flatMap { enabled =>
        if enabled then ZIO.unit
        else ZIO.fail(DecisionEscalationNotifierError.MissingSupervisor)
      }

  private def loadSupervisorChatId: IO[DecisionEscalationNotifierError, Long] =
    configRepository
      .getSetting("telegram.supervisorChatId")
      .mapError(err => DecisionEscalationNotifierError.Persistence(err))
      .map(_.flatMap(row => Option(row.value)).map(_.trim).filter(_.nonEmpty).flatMap(_.toLongOption))
      .flatMap {
        case Some(value) => ZIO.succeed(value)
        case None        => ZIO.fail(DecisionEscalationNotifierError.MissingSupervisor)
      }

  private def resolveTelegramClient: IO[DecisionEscalationNotifierError, TelegramClient] =
    channelRegistry
      .get("telegram")
      .mapError(err => DecisionEscalationNotifierError.Channel(s"telegram channel unavailable: $err"))
      .flatMap {
        case channel: TelegramChannel => ZIO.succeed(channel.client)
        case _                        => ZIO.fail(DecisionEscalationNotifierError.Channel("telegram channel type mismatch"))
      }

  private def send(chatId: Long, decision: Decision, reason: String): IO[DecisionEscalationNotifierError, Unit] =
    for
      client <- resolveTelegramClient
      text    = DecisionEscalationNotifier.buildMessageText(decision, reason)
      keyboard = DecisionEscalationNotifier.buildKeyboard(decision)
      _      <- client
                  .sendMessage(TelegramSendMessage(chat_id = chatId, text = text, reply_markup = keyboard))
                  .mapError(DecisionEscalationNotifierError.Telegram.apply)
                  .unit
    yield ()
