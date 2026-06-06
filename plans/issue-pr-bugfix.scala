//> using dep "io.github.riccardomerolla::llm4zio-runner:2.7.1"
//> using scala "3.8.3"
//> using jvm 21

/** Bug-report → fix flow for a Scala project — the ZIO-native counterpart of
  * orca's `issue-pr-bugfix.sc`.
  *
  * Given a `owner/repo#number` issue reference, the flow:
  *   1. Reads the issue from GitHub.
  *   2. Triages it (NotABug / Untestable / Testable).
  *      - NotABug / Untestable → comment the verdict and stop.
  *   3. For a Testable bug: branch, write the failing test, push, open a PR with
  *      a tentative description.
  *   4. Wait for CI to go red (fail loudly if it passes — the repro is wrong).
  *   5. Plan + implement the fix on the same branch (review per task).
  *   6. Push the fix and regenerate the PR title/body from the full diff.
  *
  * Usage: `scala-cli run issue-pr-bugfix.sc -- "acme/widgets#42"`.
  * Requires `claude` and `gh` authenticated; the repo must have CI that runs the tests.
  */

import zio.*
import java.nio.file.Path

import llm4zio.core.{CliConnectorConfig, ConnectorId}
import llm4zio.flow.*
import llm4zio.runner.Llm4zio

object Main extends ZIOAppDefault:

  // CLI for both reasoning (triage / review / PR summary) and coding — no API key.
  private val reasoning = CliConnectorConfig(ConnectorId.ClaudeCli)
  private val coder     = CliConnectorConfig(ConnectorId.ClaudeCli, flags = Map("permission-mode" -> "acceptEdits"))
  private val CiTimeout  = 30.minutes

  def run =
    getArgs.flatMap { args =>
      val workDir = Path.of(".").toAbsolutePath.normalize
      val refOpt  = args.headOption.flatMap(IssueRef.parse)

      Llm4zio.run(workDir, reasoning, coder) { ctx =>
        given FlowEvents = ctx.events
        refOpt match
          case None      => fail("usage: issue-pr-bugfix.sc -- \"owner/repo#number\"")
          case Some(ref) => bugfix(ctx, workDir, ref)
      }
    }

  private def bugfix(ctx: FlowContext, workDir: Path, ref: IssueRef)(using FlowEvents): IO[FlowError, Unit] =
    for
      issue   <- stage(s"Read issue ${ref.shortRef}")(ctx.gh.readIssue(ref))
      verdict <- stage("Triage")(Planner.triage(ctx.reasoning, issue.title, issue.body))
      _       <- verdict match
                   case Triage.NotABug(explanation) =>
                     stage("Comment: not a bug")(ctx.gh.writeIssueComment(ref, explanation))
                   case Triage.Untestable(_, steps) =>
                     stage("Comment: reproduction steps")(ctx.gh.writeIssueComment(ref, s"## Reproduction\n\n$steps"))
                   case Triage.Testable(summary, branchName, failingTestPath) =>
                     fixTestable(ctx, workDir, ref, issue, summary, branchName, failingTestPath)
    yield ()

  private def fixTestable(
    ctx: FlowContext,
    workDir: Path,
    ref: IssueRef,
    issue: Issue,
    summary: String,
    branchName: String,
    failingTestPath: String,
  )(using FlowEvents): IO[FlowError, Unit] =
    for
      _     <- stage("Branch")(ctx.git.checkoutOrCreate(branchName))
      coder <- Chat.start(ctx.coder, system = Some("You write code in the current repo."))
      _     <- stage("Write the failing test") {
                 coder
                   .ask(s"Write a failing unit test at `$failingTestPath` that reproduces: ${issue.title}\n\n${issue.body}")
                   .mapError(e => FlowError.Llm(e.toString)) *>
                   ctx.git.commitAll(s"Add failing test: $summary").unit
               }
      _       <- stage("Push branch")(ctx.git.push("origin", branchName))
      tentative <- stage("Tentative PR summary") {
                     summarisePr(
                       ctx.reasoning,
                       diff = "", // only the failing test has landed; let the model lead on the issue context
                       context = Some(s"Originating issue: ${ref.shortRef}\nTitle: ${issue.title}\n(Only a failing test has been added so far.)"),
                     )
                   }
      pr      <- stage("Open PR")(ctx.gh.createPr(tentative.title, s"${tentative.body}\n\nCloses ${ref.shortRef}."))
      status  <- stage("Wait for CI to fail")(ctx.gh.waitForBuild(pr, CiTimeout))
      _       <- ZIO.when(status == BuildOutcome.Success)(
                   fail("CI passed on the failing-test commit — the reproduction doesn't reproduce.")
                 )
      _       <- stage("Comment on PR")(ctx.gh.writePrComment(pr, s"CI is red as expected for: $summary. Implementing the fix."))
      fixPlan <- stage("Plan the fix")(
                   Planner.from(ctx.reasoning, s"Fix ${ref.shortRef} on branch $branchName so the failing test passes without regressing others.")
                 )
      planPath = workDir.resolve(s".llm4zio/fix-${ref.number}.md")
      _       <- PlanStore.save(planPath, fixPlan)
      _       <- implementTaskLoop(planPath, fixPlan) { task =>
                   for
                     _ <- coder.ask(task.description).mapError(e => FlowError.Llm(e.toString))
                     _ <- reviewAndFixLoop(Reviewers.minimal, ctx.reasoning, coder, task.title, ctx.git.diff)
                     _ <- ctx.git.commitAll(s"$branchName: ${task.title}").unit
                   yield ()
                 }
      _       <- stage("Push the fix")(ctx.git.push("origin", branchName))
      diff    <- ctx.git.diff
      finalSummary <- stage("Final PR summary")(
                        summarisePr(
                          ctx.reasoning,
                          diff,
                          context = Some(s"Issue ${ref.shortRef}: ${issue.title}\nThe branch now has the failing test AND the fix."),
                        )
                      )
      _       <- stage("Update PR")(ctx.gh.updatePr(pr, finalSummary.title, s"${finalSummary.body}\n\nCloses ${ref.shortRef}."))
      _       <- PlanStore.delete(planPath)
    yield ()
