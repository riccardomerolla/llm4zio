package llm4zio.flow

import llm4zio.core.TokenUsage

/** One replayable LLM outcome reconstructed from a trace: the assistant text a turn produced, or the stream error it
  * failed with. Segmented in recorded order from a trace's [[TraceLine]]s.
  */
enum ReplayTurn:
  case Success(text: String, usage: Option[TokenUsage], model: Option[String])
  case Failure(message: String, model: Option[String])

object ReplayTurn:
  /** Fold trace lines into ordered turns. `TokensUsed` sets the pending usage/model for the next turn; an
    * `AssistantMessage` closes a Success, a `StreamError` closes a Failure. All other kinds (stage/info/tool/raw) are
    * ignored for turn boundaries. `TokensUsed` precedes its `AssistantMessage` in a turn (usage is emitted on the final
    * chunk, the message at stream-end flush), so "pending then close" is correct.
    */
  def segment(lines: List[TraceLine]): List[ReplayTurn] =
    val init        = (Option.empty[TokenUsage], Option.empty[String], Vector.empty[ReplayTurn])
    val (_, _, out) = lines.foldLeft(init) {
      case ((pendingUsage, pendingModel, acc), line) =>
        line.kind match
          case "TokensUsed"       =>
            val usage =
              for
                p <- line.fields.get("prompt").flatMap(_.toIntOption)
                c <- line.fields.get("completion").flatMap(_.toIntOption)
                t <- line.fields.get("total").flatMap(_.toIntOption)
              yield TokenUsage(p, c, t)
            (usage.orElse(pendingUsage), line.fields.get("model").orElse(pendingModel), acc)
          case "AssistantMessage" =>
            (None, None, acc :+ Success(line.fields.getOrElse("text", ""), pendingUsage, pendingModel))
          case "StreamError"      =>
            (None, None, acc :+ Failure(line.fields.getOrElse("message", ""), line.fields.get("model")))
          case _                  =>
            (pendingUsage, pendingModel, acc)
    }
    out.toList
