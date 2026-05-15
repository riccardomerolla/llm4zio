package gateway.boundary.telegram

import gateway.entity.{ TelegramInlineKeyboardButton, TelegramInlineKeyboardMarkup }
import taskrun.entity.RunStatus

case class InlineKeyboardAction(
  action: String,
  runId: Long,
  paused: Boolean,
)

/** Action parsed from a Telegram inline-keyboard callback in the `decision:`
  * namespace. `optionKey` is set only for the Phase 3 R7/R8 resolve path
  * (`decision:resolve:{id}:{key}`); the legacy `escalate` action leaves it
  * empty.
  */
case class DecisionKeyboardAction(
  action: String,
  decisionId: String,
  optionKey: Option[String] = None,
)

object InlineKeyboards:
  private val Prefix         = "wf"
  private val DecisionPrefix = "decision"

  def workflowControls(
    runId: Long,
    paused: Boolean = false,
  ): TelegramInlineKeyboardMarkup =
    TelegramInlineKeyboardMarkup(
      inline_keyboard = List(
        List(
          TelegramInlineKeyboardButton(
            text = "View Details",
            callback_data = Some(encode("details", runId, paused)),
          ),
          TelegramInlineKeyboardButton(
            text = if paused then "Resume" else "Pause",
            callback_data = Some(encode("toggle", runId, paused)),
          ),
        ),
        List(
          TelegramInlineKeyboardButton(
            text = "Cancel",
            callback_data = Some(encode("cancel", runId, paused)),
          ),
          TelegramInlineKeyboardButton(
            text = "Retry",
            callback_data = Some(encode("retry", runId, paused)),
          ),
        ),
      )
    )

  def taskStatusKeyboard(runId: Long, status: RunStatus): Option[TelegramInlineKeyboardMarkup] =
    status match
      case RunStatus.Running                         =>
        Some(
          TelegramInlineKeyboardMarkup(
            inline_keyboard = List(
              List(
                TelegramInlineKeyboardButton(
                  text = "Pause",
                  callback_data = Some(encode("toggle", runId, paused = false)),
                ),
                TelegramInlineKeyboardButton(
                  text = "Cancel",
                  callback_data = Some(encode("cancel", runId, paused = false)),
                ),
              )
            )
          )
        )
      case RunStatus.Pending                         =>
        Some(
          TelegramInlineKeyboardMarkup(
            inline_keyboard = List(
              List(
                TelegramInlineKeyboardButton(
                  text = "Pause",
                  callback_data = Some(encode("toggle", runId, paused = false)),
                ),
                TelegramInlineKeyboardButton(
                  text = "Cancel",
                  callback_data = Some(encode("cancel", runId, paused = false)),
                ),
              )
            )
          )
        )
      case RunStatus.Paused                          =>
        Some(
          TelegramInlineKeyboardMarkup(
            inline_keyboard = List(
              List(
                TelegramInlineKeyboardButton(
                  text = "Resume",
                  callback_data = Some(encode("toggle", runId, paused = true)),
                ),
                TelegramInlineKeyboardButton(
                  text = "Cancel",
                  callback_data = Some(encode("cancel", runId, paused = true)),
                ),
              )
            )
          )
        )
      case RunStatus.Failed                          =>
        Some(
          TelegramInlineKeyboardMarkup(
            inline_keyboard = List(
              List(
                TelegramInlineKeyboardButton(
                  text = "Retry",
                  callback_data = Some(encode("retry", runId, paused = false)),
                )
              )
            )
          )
        )
      case RunStatus.Completed | RunStatus.Cancelled =>
        None

  def decisionControls(decisionId: String): TelegramInlineKeyboardMarkup =
    TelegramInlineKeyboardMarkup(
      inline_keyboard = List(
        List(
          TelegramInlineKeyboardButton(
            text = "Escalate",
            callback_data = Some(encodeDecision("escalate", decisionId)),
          )
        )
      )
    )

  def parseCallbackData(raw: String): Either[String, InlineKeyboardAction] =
    val parts = raw.trim.split(":").toList
    parts match
      case Prefix :: action :: runIdRaw :: pausedRaw :: Nil =>
        for
          runId  <- runIdRaw.toLongOption.filter(_ > 0L).toRight(s"invalid run id: $runIdRaw")
          paused <- pausedRaw.toLowerCase match
                      case "paused"  => Right(true)
                      case "running" => Right(false)
                      case other     => Left(s"invalid keyboard state: $other")
        yield InlineKeyboardAction(action = action, runId = runId, paused = paused)
      case _                                                =>
        Left(s"invalid callback payload: $raw")

  def parseDecisionCallbackData(raw: String): Either[String, DecisionKeyboardAction] =
    // -1 limit preserves trailing empty fields so we can reject `decision:resolve:id:` cleanly.
    raw.trim.split(":", -1).toList match
      // Phase 3 R7/R8: `decision:resolve:{id}:{optionKey}`
      case DecisionPrefix :: "resolve" :: decisionId :: optionKey :: Nil
           if decisionId.trim.nonEmpty && optionKey.trim.nonEmpty =>
        Right(DecisionKeyboardAction("resolve", decisionId.trim, Some(optionKey.trim)))
      // Legacy `decision:{action}:{id}` (currently only "escalate")
      case DecisionPrefix :: action :: decisionId :: Nil if decisionId.trim.nonEmpty =>
        Right(DecisionKeyboardAction(action.trim.toLowerCase, decisionId.trim))
      case _                                                                         =>
        Left(s"invalid decision callback payload: $raw")

  private def encode(action: String, runId: Long, paused: Boolean): String =
    s"$Prefix:$action:$runId:${if paused then "paused" else "running"}"

  private def encodeDecision(action: String, decisionId: String): String =
    s"$DecisionPrefix:$action:$decisionId"
