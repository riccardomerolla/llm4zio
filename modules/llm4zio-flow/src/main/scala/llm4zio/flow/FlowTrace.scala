package llm4zio.flow

import zio.json.*

/** One recorded event: either a high-level [[FlowEvent]] lifted into the trace, or a low-level provider signal. */
enum TraceEvent:
  case FromFlow(event: FlowEvent)
  case RawLine(provider: String, model: Option[String], line: String)
  case StreamError(provider: String, model: Option[String], message: String)

  def kind: String = this match
    case FromFlow(e)    => TraceEvent.flowKind(e)
    case _: RawLine     => "RawLine"
    case _: StreamError => "StreamError"

  def fields: Map[String, String] = this match
    case FromFlow(e)                           => TraceEvent.flowFields(e)
    case RawLine(provider, model, line)        =>
      Map("provider" -> provider, "line" -> line) ++ model.map("model" -> _)
    case StreamError(provider, model, message) =>
      Map("provider" -> provider, "message" -> message) ++ model.map("model" -> _)

object TraceEvent:
  def fromFlow(e: FlowEvent): TraceEvent = FromFlow(e)

  private def flowKind(e: FlowEvent): String = e match
    case _: FlowEvent.StageStarted     => "StageStarted"
    case _: FlowEvent.StageCompleted   => "StageCompleted"
    case _: FlowEvent.StageFailed      => "StageFailed"
    case _: FlowEvent.Aborted          => "Aborted"
    case _: FlowEvent.Info             => "Info"
    case _: FlowEvent.ToolUse          => "ToolUse"
    case _: FlowEvent.AssistantMessage => "AssistantMessage"
    case _: FlowEvent.TokensUsed       => "TokensUsed"

  private def flowFields(e: FlowEvent): Map[String, String] = e match
    case FlowEvent.StageStarted(stage)             => Map("stage" -> stage)
    case FlowEvent.StageCompleted(stage)           => Map("stage" -> stage)
    case FlowEvent.StageFailed(stage, msg)         => Map("stage" -> stage, "message" -> msg)
    case FlowEvent.Aborted(message)                => Map("message" -> message)
    case FlowEvent.Info(message)                   => Map("message" -> message)
    case FlowEvent.ToolUse(tool, args)             => Map("tool" -> tool, "args" -> args)
    case FlowEvent.AssistantMessage(text)          => Map("text" -> text)
    case FlowEvent.TokensUsed(agent, model, usage) =>
      Map(
        "agent"      -> agent,
        "prompt"     -> usage.prompt.toString,
        "completion" -> usage.completion.toString,
        "total"      -> usage.total.toString,
      ) ++ model.map("model" -> _)

/** The on-disk shape of one trace line. Flat by design so zio-json can derive an encoder without codecs for
  * `LlmError`/`TokenUsage`.
  */
final case class TraceLine(
  seq: Long,
  ts: String,
  runId: String,
  kind: String,
  fields: Map[String, String],
) derives JsonEncoder:
  def toJson: String = JsonEncoder[TraceLine].encodeJson(this, None).toString

object FlowTrace:
  import java.nio.file.{ Files, Path }
  import java.time.ZoneId
  import java.time.format.DateTimeFormatter
  import scala.jdk.CollectionConverters.*

  import zio.*

  private val runIdFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault)

  /** A timestamp-based run id, unique per run at millisecond resolution. */
  val runId: UIO[String] = Clock.instant.map(runIdFormat.format)

  /** Keep the newest `keep` `trace-*.jsonl` files in `dir` by mtime; delete the rest. Best-effort: any I/O error is
    * swallowed (retention must never break a run).
    */
  def prune(dir: Path, keep: Int): UIO[Unit] =
    ZIO
      .attemptBlocking {
        if Files.isDirectory(dir) then
          val traces = Files
            .list(dir)
            .iterator
            .asScala
            .filter { p =>
              val n = p.getFileName.toString
              n.startsWith("trace-") && n.endsWith(".jsonl")
            }
            .toList
          traces
            .sortBy(p => -Files.getLastModifiedTime(p).toMillis)
            .drop(math.max(0, keep))
            .foreach(Files.deleteIfExists(_))
      }
      .ignore
