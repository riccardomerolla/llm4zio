//> using dep "io.github.riccardomerolla::llm4zio-runner:2.9.1"
//> using scala "3.8.3"
//> using jvm 21

/** GitHub-issue → PR flow, fully autonomous — the ZIO-native counterpart of
  * orca's `issue-pr.sc`.
  *
  * Given an `owner/repo#number` reference (the prompt), the flow:
  *   1. Reads the issue from GitHub (title, body, author).
  *   2. Resumes `.llm4zio/issue-<n>.md` if one exists (crash recovery); otherwise
  *      skeptically assesses the report (`Planner.assessThenPlan`): a `Blocked`
  *      verdict posts the reason as an issue comment and the flow stops; a
  *      `Proceed` verdict creates the epic branch and persists the plan.
  *   3. Runs `implementTaskLoop` — each task gets the review-and-fix loop, a
  *      checkbox tick on disk, and a `<epicId>: <title>` commit.
  *   4. Pushes the branch (only PR-bound flows push; the loop only commits).
  *   5. Summarises the branch-vs-base diff into a PR title + body and opens the PR.
  *
  * Usage: `scala-cli run issue-pr.scala -- "owner/repo#number"`.
  * Requires `claude` and `gh` authenticated, and a repo with a remote.
  */

import zio.*
import java.nio.file.Path

import llm4zio.core.{CliConnectorConfig, ConnectorId}
import llm4zio.flow.*
import llm4zio.runner.Llm4zio

object Main extends ZIOAppDefault:

  // CLI for both reasoning (assess / review / PR summary) and coding — no API key.
  private val reasoning = CliConnectorConfig(ConnectorId.ClaudeCli)
  private val coder     = CliConnectorConfig(ConnectorId.ClaudeCli, flags = Map("permission-mode" -> "acceptEdits"))

  def run =
    getArgs.flatMap { args =>
      val workDir = Path.of(".").toAbsolutePath.normalize
      val refOpt  = args.headOption.flatMap(IssueRef.parse)

      Llm4zio.run(workDir, reasoning, coder) { ctx =>
        given FlowEvents = ctx.events
        refOpt match
          case None      => fail("usage: issue-pr.scala -- \"owner/repo#number\"")
          case Some(ref) => issueToPr(ctx, workDir, ref)
      }
    }

  private def issueToPr(ctx: FlowContext, workDir: Path, ref: IssueRef)(using FlowEvents): IO[FlowError, Unit] =
    for
      issue    <- stage(s"Read issue ${ref.shortRef}")(ctx.gh.readIssue(ref))
      payload   = s"Issue: ${issue.title}\n\nReporter: ${issue.author}\n\n${issue.body}"
      planPath  = workDir.resolve(s".llm4zio/issue-${ref.number}.md")
      // Resume a persisted plan, else assess: Blocked → comment & stop; Proceed → branch + persist.
      maybePlan <- stage("Acquire plan") {
                     PlanStore.load(planPath).flatMap {
                       case Some(plan) => ZIO.some(plan)
                       case None       =>
                         Planner.assessThenPlan(ctx.reasoning, payload).flatMap {
                           case Verdict.Blocked(reason) =>
                             stage("Post assessment on the issue")(ctx.gh.writeIssueComment(ref, reason)).as(None)
                           case Verdict.Proceed(plan)   =>
                             ctx.git.checkoutOrCreate(plan.epicId) *> PlanStore.save(planPath, plan).as(Some(plan))
                         }
                     }
                   }
      _         <- maybePlan match
                     case None       => ZIO.unit
                     case Some(plan) => implementAndOpen(ctx, ref, issue, plan, planPath)
    yield ()

  private def implementAndOpen(
    ctx: FlowContext,
    ref: IssueRef,
    issue: Issue,
    plan: Plan,
    planPath: Path,
  )(using FlowEvents): IO[FlowError, Unit] =
    for
      coderChat <- Chat.start(ctx.coder, system = Some("You implement one task at a time in the current repo."))
      _         <- implementTaskLoop(planPath, plan) { task =>
                     for
                       _ <- coderChat.ask(task.description).mapError(e => FlowError.Llm(e.message, Some(e)))
                       _ <- reviewAndFixLoop(Reviewers.all, ctx.reasoning, coderChat, task.title, ctx.git.diff)
                       _ <- ctx.git.commitAll(s"${plan.epicId}: ${task.title}").unit
                     yield ()
                   }
      _         <- stage("Push branch")(ctx.git.push("origin", plan.epicId))
      base      <- ctx.git.defaultBase
      diff      <- ctx.git.diffVsBase(base)
      summary   <- stage("Summarise PR")(
                     summarisePr(
                       ctx.reasoning,
                       diff,
                       context = Some(s"Originating issue: ${ref.shortRef}\nTitle: ${issue.title}"),
                     )
                   )
      _         <- stage("Open PR")(
                     ctx.gh.createPr(summary.title, s"${summary.body}\n\nCloses ${ref.shortRef}.", base = Some(base))
                   )
      _         <- PlanStore.delete(planPath)
    yield ()
