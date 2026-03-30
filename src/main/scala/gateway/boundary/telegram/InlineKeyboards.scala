package gateway.boundary.telegram

import gateway.entity.{ TelegramInlineKeyboardButton, TelegramInlineKeyboardMarkup }
import taskrun.entity.RunStatus

case class InlineKeyboardAction(
  action: String,
  runId: Long,
  paused: Boolean,
)

case class DecisionKeyboardAction(
  action: String,
  decisionId: String,
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
    raw.trim.split(":").toList match
      case DecisionPrefix :: action :: decisionId :: Nil if decisionId.trim.nonEmpty =>
        Right(DecisionKeyboardAction(action.trim.toLowerCase, decisionId.trim))
      case _                                                                         =>
        Left(s"invalid decision callback payload: $raw")

  private def encode(action: String, runId: Long, paused: Boolean): String =
    s"$Prefix:$action:$runId:${if paused then "paused" else "running"}"

  private def encodeDecision(action: String, decisionId: String): String =
    s"$DecisionPrefix:$action:$decisionId"
