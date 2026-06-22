package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.util.Base64

import zio.json.*
import zio.{ IO, ZIO }

/** Render a flow's stage structure as a Mermaid flowchart — from the live event stream or a recorded trace. The diagram
  * reflects what the flow actually did: stages top-to-bottom (mirroring "a flow reads top-to-bottom"), repeated stage
  * names collapsed to one node annotated `×N` (so a per-task loop reads as one node, not noise), and a failed stage
  * styled distinctly. Output is plain Markdown (renders natively on GitHub) plus a one-click view link.
  */
object Mermaid:

  /** A top-down flowchart of the stages in `events`, in first-occurrence order. */
  def fromEvents(events: List[FlowEvent]): String =
    val starts      = events.collect { case FlowEvent.StageStarted(s) => s }
    val failedNames = events.collect { case FlowEvent.StageFailed(s, _) => s }.toSet
    if starts.isEmpty then "flowchart TD\n  none[\"(no stages)\"]"
    else
      val order    = starts.distinct
      val counts   = starts.groupBy(identity).view.mapValues(_.size).toMap
      val ids      = order.zipWithIndex.map { case (n, i) => n -> s"n$i" }.toMap
      val nodes    = order.map { n =>
        val label  = if counts(n) > 1 then s"$n ×${counts(n)}" else n
        val failed = if failedNames.contains(n) then ":::failed" else ""
        s"""  ${ids(n)}["${escape(label)}"]$failed"""
      }
      val edges    = order.sliding(2).collect { case List(a, b) => s"  ${ids(a)} --> ${ids(b)}" }.toList
      val classDef = if failedNames.nonEmpty then List("  classDef failed fill:#fdd,stroke:#c00,color:#900;") else Nil
      ("flowchart TD" :: (nodes ++ edges ++ classDef)).mkString("\n")

  /** Render the flowchart from a recorded `trace-*.jsonl` file — the offline counterpart to [[fromEvents]]. Non-stage
    * trace lines (tool use, tokens, raw provider output) and any unparseable line are ignored.
    */
  def fromTrace(path: Path): IO[FlowError, String] =
    ZIO
      .attemptBlocking(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
      .mapError(e => FlowError.Persistence(s"failed to read $path", Some(e)))
      .map { text =>
        fromEvents(text.linesIterator.filter(_.trim.nonEmpty).flatMap(line =>
          line.fromJson[TraceLine].toOption.flatMap(stageEvent)
        ).toList)
      }

  /** The stage [[FlowEvent]] a trace line stands for, if it is one. */
  private def stageEvent(line: TraceLine): Option[FlowEvent] =
    line.kind match
      case "StageStarted"   => line.fields.get("stage").map(FlowEvent.StageStarted(_))
      case "StageCompleted" => line.fields.get("stage").map(FlowEvent.StageCompleted(_))
      case "StageFailed"    =>
        line.fields.get("stage").map(s => FlowEvent.StageFailed(s, line.fields.getOrElse("message", "")))
      case _                => None

  /** A shareable render link: the diagram base64url-encoded into a mermaid.ink URL (dependency-free, no deflate). */
  def liveUrl(diagram: String): String =
    s"https://mermaid.ink/svg/${Base64.getUrlEncoder.encodeToString(diagram.getBytes(StandardCharsets.UTF_8))}"

  /** The diagram as a `.flow.md` document: a fenced `mermaid` block plus the live link. */
  def document(diagram: String): String =
    s"""|```mermaid
        |$diagram
        |```
        |
        |[View on mermaid.ink](${liveUrl(diagram)})
        |""".stripMargin

  /** Mermaid node text can't contain raw double quotes; swap them for single quotes. */
  private def escape(label: String): String = label.replace("\"", "'")
