package cli.commands

import java.nio.file.{ Path, Paths }

import zio.*

import agent.entity.AgentRepository
import eval.control.*
import eval.entity.*
import workspace.control.CliAgentRunner
import workspace.entity.RunMode

/** CLI handlers for `eval run` and `eval compare`. */
object EvalCommand:

  /** Executes the dataset through the named agent, writes a run JSON, prints a summary, returns the run. Fails if any
    * case failed so the process exits non-zero and CI can catch regressions.
    */
  def runDataset(
    workspaceRaw: String,
    agentName: String,
    datasetRaw: String,
  ): ZIO[AgentRepository, String, EvalRun] =
    val workspace = Paths.get(workspaceRaw).toAbsolutePath.normalize
    val dataset   = resolveDataset(workspace, datasetRaw)
    val lookup    = slug(agentName)

    for
      agent      <- AgentRepository
                      .findByName(lookup)
                      .mapError(e => s"AgentRepository.findByName failed: $e")
                      .flatMap(opt => ZIO.fromOption(opt).orElseFail(s"No agent '$lookup' registered."))
      cases      <- EvalDataset.load(dataset)
      executor    = buildExecutor(agent.cliTool, workspace.toString, agent.envVars)
      _          <- Console
                      .printLine(s"▶ Evaluating ${cases.size} case(s) against '$lookup' (${agent.cliTool})")
                      .orDie
      run        <- EvalRunner
                      .run(
                        datasetPath = dataset.toString,
                        agentName = lookup,
                        cases = cases,
                        executor = executor,
                        onProgress = (idx, total, r) =>
                          Console.printLine(f"  [$idx/$total] ${mark(r.verdict)} ${clip(r.prompt)}").orDie,
                      )
      file       <- EvalStorage.write(workspace, run)
      _          <- Console
                      .printLine(
                        s"""|
                            |Results: ${run.summary.passed}/${run.summary.total} passed (${percent(run.summary.passRate)})
                            |Saved:   $file""".stripMargin
                      )
                      .orDie
      _          <- ZIO.when(run.summary.failed > 0)(ZIO.fail(s"${run.summary.failed} case(s) failed"))
    yield run

  /** Loads two run files, prints a diff table, fails (exit non-zero) if any case regressed. */
  def compare(baselineRaw: String, candidateRaw: String): IO[String, EvalCompare.Report] =
    val baseline  = Paths.get(baselineRaw).toAbsolutePath.normalize
    val candidate = Paths.get(candidateRaw).toAbsolutePath.normalize
    for
      a      <- EvalStorage.read(baseline)
      b      <- EvalStorage.read(candidate)
      report  = EvalCompare.compare(a, b)
      _      <- Console.printLine(formatReport(a, b, report)).orDie
      _      <- ZIO.when(report.hasRegressions)(
                  ZIO.fail(s"${report.regressions.size} regression(s) detected")
                )
    yield report

  // ── Helpers ───────────────────────────────────────────────────────────

  private def resolveDataset(workspace: Path, raw: String): Path =
    val p = Paths.get(raw)
    if p.isAbsolute then p.normalize
    else workspace.resolve(raw).normalize

  private def buildExecutor(
    cliTool: String,
    workspace: String,
    envVars: Map[String, String],
  ): EvalRunner.Executor =
    prompt =>
      val argv = CliAgentRunner.buildArgv(
        cliTool = cliTool,
        prompt = prompt,
        worktreePath = workspace,
        runMode = RunMode.Host,
        repoPath = workspace,
        envVars = envVars,
      )
      CliAgentRunner.runProcess(argv, workspace, envVars).map { (lines, code) =>
        (lines.mkString("\n"), code)
      }

  private def mark(v: EvalVerdict): String = v match
    case EvalVerdict.Pass => "PASS"
    case EvalVerdict.Fail => "FAIL"

  private def clip(s: String, n: Int = 60): String =
    val oneLine = s.replace('\n', ' ').trim
    if oneLine.length <= n then oneLine else oneLine.take(n - 1) + "…"

  private def percent(r: Double): String = f"${r * 100}%.1f%%"

  private def formatReport(a: EvalRun, b: EvalRun, report: EvalCompare.Report): String =
    val header =
      s"""|Baseline:  ${a.runId.take(8)} @ ${a.startedAt}  (${a.summary.passed}/${a.summary.total})
          |Candidate: ${b.runId.take(8)} @ ${b.startedAt}  (${b.summary.passed}/${b.summary.total})
          |""".stripMargin
    val lines =
      report.rows.map { r =>
        val tag = r.status match
          case EvalCompare.Status.Unchanged   => "   "
          case EvalCompare.Status.Improvement => "++"
          case EvalCompare.Status.Regression  => "--"
          case EvalCompare.Status.Added       => " +"
          case EvalCompare.Status.Removed     => " -"
        val a   = r.baseline.map(mark).getOrElse(" - ")
        val bb  = r.candidate.map(mark).getOrElse(" - ")
        f"  $tag%-3s $a%-5s → $bb%-5s  ${clip(r.prompt, 70)}"
      }.mkString("\n")
    val footer =
      s"""|
          |regressions: ${report.regressions.size}
          |improvements: ${report.improvements.size}""".stripMargin
    s"$header\n$lines\n$footer"

  private def slug(s: String): String =
    s.trim.toLowerCase
      .replaceAll("[^a-z0-9-]+", "-")
      .replaceAll("^-+|-+$", "")
      .replaceAll("-{2,}", "-")
