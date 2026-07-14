//> using dep "io.github.riccardomerolla::llm4zio-runner:3.15.0"
//> using scala "3.8.3"
//> using jvm 21

/** GitHub-issue → PR flow, fully autonomous — the ZIO-native counterpart of
  * orca's `issue-pr.sc`.
  *
  * Given an `owner/repo#number` reference: read the issue; resume a persisted
  * plan or skeptically assess (`Planner.assessThenPlan`) — Blocked posts the
  * reason on the issue and stops; Proceed branches and persists. Implement each
  * task with the review loop, push, summarise the diff, open the PR, and return
  * to the starting branch.
  *
  * Run: scala-cli run issue-pr.sc -- "owner/repo#number"
  * Requires `claude` and `gh` authenticated, and a repo with a remote.
  */

import zio.{ IO, ZIO }

import llm4zio.flow.*
import llm4zio.runner.*

flow(args): // no default: an issue reference is required
  IssueRef.parse(userPrompt) match
    case None      => fail("usage: scala-cli run issue-pr.sc -- \"owner/repo#number\"")
    case Some(ref) => issueToPr(ref)

def issueToPr(ref: IssueRef)(using FlowContext): IO[FlowError, Unit] =
  for
    start     <- git.currentBranch // return here at the end
    issue     <- stage(s"Read issue ${ref.shortRef}")(gh.readIssue(ref))
    payload    = s"Issue: ${issue.title}\n\nReporter: ${issue.author}\n\n${issue.body}"
    planPath   = workDir.resolve(s".llm4zio/issue-${ref.number}.md")
    maybePlan <- stage("Acquire plan") {
                   PlanStore.load(planPath).flatMap {
                     case Some(plan) => ZIO.some(plan)
                     case None       =>
                       Planner.assessThenPlan(reasoning, payload).flatMap {
                         case Verdict.Blocked(why)  =>
                           stage("Post assessment on the issue")(gh.writeIssueComment(ref, why)).as(None)
                         case Verdict.Proceed(plan) =>
                           git.checkoutOrCreate(plan.epicId) *> PlanStore.save(planPath, plan).as(Some(plan))
                       }
                   }
                 }
    _         <- maybePlan match
                   case None       => ZIO.unit // Blocked: never switched branch
                   case Some(plan) => implementAndOpen(ref, issue, plan, planPath, start)
  yield ()

def implementAndOpen(
  ref: IssueRef,
  issue: Issue,
  plan: Plan,
  planPath: java.nio.file.Path,
  startBranch: String,
)(using FlowContext): IO[FlowError, Unit] =
  for
    coderChat <- Chat.start(coder, system = Some("You implement one task at a time in the current repo."))
    _         <- implementTaskLoop(planPath, plan) { task =>
                   coderChat.ask(task.description) *>
                     reviewAndFixLoop(Reviewers.all, reasoning, coderChat, task.title, git.diff) *>
                     git.commitAll(s"${plan.epicId}: ${task.title}").unit
                 }
    _         <- stage("Push branch")(git.push("origin", plan.epicId))
    base      <- git.defaultBase
    diff      <- git.diffVsBase(base)
    summary   <- stage("Summarise PR")(
                   summarisePr(reasoning, diff, context = Some(s"Originating issue: ${ref.shortRef}\nTitle: ${issue.title}"))
                 )
    _         <- stage("Open PR")(
                   gh.createPr(summary.title, s"${summary.body}\n\nCloses ${ref.shortRef}.", base = Some(base))
                 )
    _         <- PlanStore.delete(planPath)
    _         <- stage(s"Return to $startBranch")(git.checkout(startBranch))
  yield ()
