package workspace.control

import java.time.Instant

import zio.*

import orchestration.control.WorkReportEventBus
import orchestration.entity.DiffStats
import shared.ids.Ids.{ IssueId, TaskRunId }
import taskrun.entity.{ CiStatus, PrStatus, TaskRunEvent }

/** Extracts proof-of-work signals from worktree output lines and git commands, then emits the corresponding
  * `TaskRunEvent`s to the `WorkReportEventBus`.
  *
  * All methods are pure (no I/O) except `fromLines` which emits to the bus. This makes every extraction rule
  * individually testable without needing the bus.
  */
object ProofOfWorkExtractor:

  private val prUrlPattern =
    """https://github\.com/[^\s/]+/[^\s/]+/pull/\d+""".r

  private val diffStatPattern =
    """(\d+) files? changed(?:, (\d+) insertions?\(\+\))?(?:, (\d+) deletions?\(-\))?""".r

  /** Scan output lines for a GitHub PR URL and return the first match. */
  def extractPrUrl(lines: List[String]): Option[String] =
    lines.flatMap(prUrlPattern.findFirstIn).headOption

  /** Interpret `gh pr checks` output into a `CiStatus`. */
  def parseCiStatus(lines: List[String]): CiStatus =
    val combined = lines.map(_.toLowerCase).mkString(" ")
    if combined.contains("all checks passed") || combined.contains("pass") then CiStatus.Passed
    else if combined.contains("fail") then CiStatus.Failed
    else CiStatus.Pending

  /** Parse `git diff --stat` summary line into `DiffStats`. */
  def extractDiffStats(lines: List[String]): DiffStats =
    lines.flatMap { line =>
      diffStatPattern.findFirstMatchIn(line).map { m =>
        DiffStats(
          filesChanged = m.group(1).toIntOption.getOrElse(0),
          linesAdded = Option(m.group(2)).flatMap(_.toIntOption).getOrElse(0),
          linesRemoved = Option(m.group(3)).flatMap(_.toIntOption).getOrElse(0),
        )
      }
    }.headOption.getOrElse(DiffStats(0, 0, 0))

  /** Emit proof-of-work events extracted from `outputLines` to `bus`.
    *
    * Called after the agent process completes. Only emits events for signals that were detected.
    */
  def fromLines(
    runId: TaskRunId,
    issueId: IssueId,
    outputLines: List[String],
    bus: WorkReportEventBus,
    at: Instant,
  ): UIO[Unit] =
    val prUrlOpt = extractPrUrl(outputLines)
    for
      _ <- prUrlOpt.fold(ZIO.unit) { url =>
             bus.publishTaskRun(TaskRunEvent.PrLinked(runId, url, PrStatus.Open, at))
           }
    yield ()
