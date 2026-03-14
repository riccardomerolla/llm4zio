package workspace.control

import java.time.Instant

import zio.*

import issues.entity.{ IssueCiStatus, IssueWorkReport }
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

  final case class RequirementCheck(
    requirement: String,
    passed: Boolean,
    details: String,
  )

  private val prUrlPattern =
    """https://github\.com/[^\s/]+/[^\s/]+/pull/\d+""".r

  private val diffStatPattern =
    """(\d+) files? changed(?:, (\d+) insertions?\(\+\))?(?:, (\d+) deletions?\(-\))?""".r

  private val coveragePatterns = List(
    """coverage[^0-9]*(\d+(?:\.\d+)?)\s*%""".r,
    """(\d+(?:\.\d+)?)\s*%\s*coverage""".r,
  )

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

  def validateRequirements(requirements: List[String], report: IssueWorkReport): List[RequirementCheck] =
    val normalizedText = reportText(report)
    requirements.map(_.trim).filter(_.nonEmpty).distinct.map { requirement =>
      val lower = requirement.toLowerCase
      if lower.contains("pull request") || lower.contains("pr ") || lower == "pr" then
        RequirementCheck(
          requirement,
          report.prLink.isDefined,
          if report.prLink.isDefined then "PR linked" else "No PR link found",
        )
      else if lower.contains("test") then
        val passed = report.ciStatus.contains(IssueCiStatus.Passed) || normalizedText.contains("test")
        RequirementCheck(
          requirement,
          passed,
          if passed then "Test evidence detected" else "No passing test evidence detected",
        )
      else if lower.contains("lint") then
        val passed = normalizedText.contains("lint") && (
          normalizedText.contains("pass") || normalizedText.contains("clean") || normalizedText.contains("no lint")
        )
        RequirementCheck(
          requirement,
          passed,
          if passed then "Lint evidence detected" else "No passing lint evidence detected",
        )
      else if lower.contains("coverage") then
        val threshold = lower
          .replaceAll("[^0-9.]", " ")
          .trim
          .split("\\s+")
          .toList
          .flatMap(_.toDoubleOption)
          .headOption
        val detected  = extractCoverage(normalizedText)
        val passed    = threshold.fold(detected.isDefined)(limit => detected.exists(_ >= limit))
        val details   =
          (threshold, detected) match
            case (_, Some(value))    => f"Detected coverage ${value}%.1f%%"
            case (Some(limit), None) => f"Coverage threshold ${limit}%.1f%% requested but no coverage evidence detected"
            case _                   => "No coverage evidence detected"
        RequirementCheck(requirement, passed, details)
      else
        val normalizedRequirement = lower.replaceAll("\\s+", " ").trim
        val passed                =
          normalizedRequirement.nonEmpty && normalizedText.contains(normalizedRequirement)
        RequirementCheck(
          requirement,
          passed,
          if passed then "Matched in proof-of-work output" else "Requirement not matched in proof-of-work output",
        )
    }

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

  private def reportText(report: IssueWorkReport): String =
    List(
      report.walkthrough,
      report.agentSummary,
      report.prLink,
      Option(report.reports.map(_.content).mkString(" ")).filter(_.nonEmpty),
      Option(report.artifacts.map(_.value).mkString(" ")).filter(_.nonEmpty),
    ).flatten.mkString(" ").toLowerCase

  private def extractCoverage(text: String): Option[Double] =
    coveragePatterns.view.flatMap(_.findFirstMatchIn(text).flatMap(_.group(1).toDoubleOption)).headOption
