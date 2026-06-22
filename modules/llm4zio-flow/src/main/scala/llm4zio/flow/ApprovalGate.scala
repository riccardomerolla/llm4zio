package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.{ IO, ZIO }

/** A human approval gate between flow phases. The approval signal is an **in-artifact marker** — a checkbox a reviewer
  * flips in the spec/plan they are already reading — so it travels with the artifact (repo, specs dir, or workspace)
  * and is self-auditing via `git blame` when the file is tracked.
  *
  * A draft is written with `- [ ] Approved`; a reviewer flips it to `- [x] Approved`. [[gate]] then either proceeds,
  * asks interactively, or halts — so the same primitive serves a separate-scripts enterprise hand-off and a single
  * resumable script's interactive pause.
  */
object ApprovalGate:

  val DraftMarker: String    = "- [ ] Approved"
  val ApprovedMarker: String = "- [x] Approved"

  private val approved = """(?im)^[ \t]*-[ \t]*\[[ \t]*[xX][ \t]*\][ \t]*Approved\b""".r
  private val draft    = """(?im)^[ \t]*-[ \t]*\[[ \t]*\][ \t]*Approved\b""".r

  /** True if the artifact carries a flipped approval checkbox. */
  def isApproved(content: String): Boolean = approved.findFirstIn(content).isDefined

  /** Carries an approval marker already (checked or unchecked)? */
  private def hasMarker(content: String): Boolean = isApproved(content) || draft.findFirstIn(content).isDefined

  /** Append an unchecked draft marker, unless the artifact already carries one. */
  def withDraftMarker(content: String): String =
    if hasMarker(content) then content else s"${content.stripTrailing}\n\n$DraftMarker\n"

  /** Flip an unchecked marker to approved (idempotent; appends an approved marker if none is present). */
  def approve(content: String): String =
    if isApproved(content) then content
    else if draft.findFirstIn(content).isDefined then
      draft.replaceFirstIn(content, java.util.regex.Matcher.quoteReplacement(ApprovedMarker))
    else s"${content.stripTrailing}\n\n$ApprovedMarker\n"

  /** Gate on the artifact at `path`:
    *   - approved marker present → proceed;
    *   - absent and [[Interaction]] is interactive → ask; on a yes, flip the marker on disk and proceed; on a no, halt;
    *   - absent and non-interactive (CI) → halt with actionable guidance.
    * A halt is a recoverable [[FlowError.Aborted]] (the runner renders it and exits non-zero), not a defect.
    */
  def gate(path: Path, interaction: Interaction)(using events: FlowEvents): IO[FlowError, Unit] =
    read(path).flatMap { content =>
      if isApproved(content) then events.publish(FlowEvent.Info(s"approved: $path"))
      else if interaction.interactive then
        interaction.ask(s"Approve $path? (or set '$ApprovedMarker' in the file) [y/N]").flatMap { answer =>
          if isYes(answer) then write(path, approve(content)) *> events.publish(FlowEvent.Info(s"approved: $path"))
          else awaiting(path)
        }
      else awaiting(path)
    }

  private def awaiting(path: Path)(using events: FlowEvents): IO[FlowError, Nothing] =
    val message = s"awaiting approval: set '$ApprovedMarker' in $path and re-run"
    events.publish(FlowEvent.Aborted(message)) *> ZIO.fail(FlowError.Aborted(message))

  private def isYes(answer: String): Boolean =
    val a = answer.trim.toLowerCase
    a == "y" || a == "yes"

  private def read(path: Path): IO[FlowError, String] =
    ZIO
      .attemptBlocking(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
      .mapError(e => FlowError.Persistence(s"failed to read $path", Some(e)))

  private def write(path: Path, content: String): IO[FlowError, Unit] =
    ZIO
      .attemptBlocking(Files.write(path, content.getBytes(StandardCharsets.UTF_8)))
      .mapError(e => FlowError.Persistence(s"failed to write $path", Some(e)))
      .unit
