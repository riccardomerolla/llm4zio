//> using dep "io.github.riccardomerolla::llm4zio-runner:3.0.0"
//> using scala "3.8.3"
//> using jvm 21

/** Bug-report → fix flow for a Scala project — the ZIO-native counterpart of
  * orca's `issue-pr-bugfix.sc`.
  *
  * Given an `owner/repo#number` reference: read + triage the issue (NotABug /
  * Untestable verdicts are commented and the flow stops). For a Testable bug:
  * branch, write the failing test, push, open a tentative PR, wait for CI to go
  * red (fail loudly on green — the repro is wrong), then plan + implement the
  * fix (reviewed + briefed), push, and regenerate the PR title/body from the
  * full diff.
  *
  * Run: scala-cli run issue-pr-bugfix.sc -- "owner/repo#number"
  * Requires `claude` and `gh` authenticated; the repo must have CI that runs the tests.
  */

import zio.{ durationInt, IO, ZIO }

import llm4zio.flow.*
import llm4zio.runner.*

val CiTimeout = 30.minutes

flow(args): // no default: an issue reference is required
  IssueRef.parse(userPrompt) match
    case None      => fail("usage: scala-cli run issue-pr-bugfix.sc -- \"owner/repo#number\"")
    case Some(ref) => bugfix(ref)

def bugfix(ref: IssueRef)(using FlowContext): IO[FlowError, Unit] =
  for
    issue   <- stage(s"Read issue ${ref.shortRef}")(gh.readIssue(ref))
    verdict <- stage("Triage")(Planner.triage(reasoning, issue.title, issue.body))
    _       <- verdict match
                 case Triage.NotABug(explanation) =>
                   stage("Comment: not a bug")(gh.writeIssueComment(ref, explanation))
                 case Triage.Untestable(_, steps) =>
                   stage("Comment: reproduction steps")(gh.writeIssueComment(ref, s"## Reproduction\n\n$steps"))
                 case Triage.Testable(summary, branchName, failingTestPath) =>
                   fixTestable(ref, issue, summary, branchName, failingTestPath)
  yield ()

def fixTestable(
  ref: IssueRef,
  issue: Issue,
  summary: String,
  branchName: String,
  failingTestPath: String,
)(using FlowContext): IO[FlowError, Unit] =
  for
    start     <- git.currentBranch // return here at the end
    _         <- stage("Branch")(git.checkoutOrCreate(branchName))
    coderChat <- Chat.start(coder, system = Some("You write code in the current repo."))
    _         <- stage("Write the failing test") {
                   coderChat.ask(
                     s"Write a failing unit test at `$failingTestPath` that reproduces: ${issue.title}\n\n${issue.body}"
                   ) *> git.commitAll(s"Add failing test: $summary").unit
                 }
    _         <- stage("Push branch")(git.push("origin", branchName))
    tentative <- stage("Tentative PR summary") {
                   summarisePr(
                     reasoning,
                     diff = "", // only the failing test has landed; let the model lead on the issue context
                     context = Some(
                       s"Originating issue: ${ref.shortRef}\nTitle: ${issue.title}\n(Only a failing test has been added so far.)"
                     ),
                   )
                 }
    pr        <- stage("Open PR")(gh.createPr(tentative.title, s"${tentative.body}\n\nCloses ${ref.shortRef}."))
    status    <- stage("Wait for CI to fail")(gh.waitForBuild(pr, CiTimeout))
    _         <- ZIO.when(status == BuildOutcome.Success)(
                   fail("CI passed on the failing-test commit — the reproduction doesn't reproduce.")
                 )
    _         <- stage("Comment on PR")(gh.writePrComment(pr, s"CI is red as expected for: $summary. Implementing the fix."))
    fixPrompt  = s"Fix ${ref.shortRef} on branch $branchName so the failing test passes without regressing others."
    fixPlan   <- stage("Plan the fix (review + brief)") {
                   Planner.from(reasoning, fixPrompt).reviewed(reasoning).briefed(reasoning, fixPrompt)
                 }
    planPath   = workDir.resolve(s".llm4zio/fix-${ref.number}.md")
    _         <- PlanStore.save(planPath, fixPlan)
    _         <- implementTaskLoop(planPath, fixPlan) { task =>
                   coderChat.ask(fixPlan.taskPrompt(task)) *>
                     reviewAndFixLoop(Reviewers.minimal, reasoning, coderChat, task.title, git.diff) *>
                     git.commitAll(s"$branchName: ${task.title}").unit
                 }
    _         <- stage("Push the fix")(git.push("origin", branchName))
    diff      <- git.diff
    finalSum  <- stage("Final PR summary")(
                   summarisePr(
                     reasoning,
                     diff,
                     context = Some(s"Issue ${ref.shortRef}: ${issue.title}\nThe branch now has the failing test AND the fix."),
                   )
                 )
    _         <- stage("Update PR")(gh.updatePr(pr, finalSum.title, s"${finalSum.body}\n\nCloses ${ref.shortRef}."))
    _         <- PlanStore.delete(planPath)
    _         <- stage(s"Return to $start")(git.checkout(start))
  yield ()
