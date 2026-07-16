//> using dep "io.github.riccardomerolla:llm4zio-java:3.19.0"
//> using scala "3.8.3"
//> using jvm 21

// Bug-report → fix flow, authored in Java — the Java-surface counterpart of issue-pr-bugfix.sc.
//
// Read + triage the issue (NotABug / Untestable are commented and the flow stops). For a Testable bug: branch, write
// the failing test, push, open a tentative PR, wait for CI to go RED (fail loudly on green — the repro is wrong), then
// plan + implement the fix (reviewed + briefed), push, and regenerate the PR title/body from the full diff.
//
// Run: scala-cli run IssuePrBugfix.java -- "owner/repo#number"
// Requires `claude` and `gh` authenticated; the repo must have CI that runs the tests.

import llm4zio.javaapi.*;
import llm4zio.flow.Triage;

public class IssuePrBugfix {
  static final long CI_TIMEOUT_SECONDS = 30 * 60;

  public static void main(String[] args) {
    Llm4zioJava.flow(args, "owner/repo#number", flow -> {
      var maybeRef = Refs.issue(flow.userPrompt());
      if (maybeRef.isEmpty()) {
        flow.fail("usage: scala-cli run IssuePrBugfix.java -- \"owner/repo#number\"");
        return;
      }
      var ref = maybeRef.get();
      var issue = flow.stage("Read issue " + ref.shortRef(), () -> flow.gh().readIssue(ref));
      var verdict = flow.stage("Triage", () -> flow.triage(issue.title(), issue.body()));

      if (verdict instanceof Triage.NotABug notABug) {
        flow.stage("Comment: not a bug", () -> flow.gh().writeIssueComment(ref, notABug.explanation()));
        return;
      }
      if (verdict instanceof Triage.Untestable untestable) {
        flow.stage("Comment: reproduction steps",
            () -> flow.gh().writeIssueComment(ref, "## Reproduction\n\n" + untestable.reproductionSteps()));
        return;
      }
      var testable = (Triage.Testable) verdict;
      var summary = testable.summary();
      var branchName = testable.branchName();
      var failingTestPath = testable.failingTestPath();

      var start = flow.git().currentBranch();
      flow.stage("Branch", () -> flow.git().checkoutOrCreate(branchName));
      var chat = flow.startChat("You write code in the current repo.");
      flow.stage("Write the failing test", () -> {
        chat.ask("Write a failing unit test at `" + failingTestPath + "` that reproduces: "
            + issue.title() + "\n\n" + issue.body());
        flow.git().commitAll("Add failing test: " + summary);
      });
      flow.stage("Push branch", () -> flow.git().push("origin", branchName));
      var tentative = flow.stage("Tentative PR summary", () -> flow.summarisePr("",
          "Originating issue: " + ref.shortRef() + "\nTitle: " + issue.title()
              + "\n(Only a failing test has been added so far.)"));
      var pr = flow.stage("Open PR",
          () -> flow.gh().createPr(tentative.title(), tentative.body() + "\n\nCloses " + ref.shortRef() + "."));
      var status = flow.stage("Wait for CI to fail", () -> flow.gh().waitForBuild(pr, CI_TIMEOUT_SECONDS));
      if (status.isSuccess()) {
        flow.fail("CI passed on the failing-test commit — the reproduction doesn't reproduce.");
      }
      flow.stage("Comment on PR",
          () -> flow.gh().writePrComment(pr, "CI is red as expected for: " + summary + ". Implementing the fix."));

      var fixPrompt = "Fix " + ref.shortRef() + " on branch " + branchName
          + " so the failing test passes without regressing others.";
      var fixPlan = flow.stage("Plan the fix (review + brief)",
          () -> flow.briefedPlan(flow.reviewedPlan(flow.planFrom(fixPrompt)), fixPrompt));
      var planPath = flow.workDir().resolve(".llm4zio/fix-" + ref.number() + ".md");
      flow.savePlan(planPath, fixPlan);
      flow.implementTaskLoop(planPath, fixPlan, task -> {
        chat.ask(fixPlan.taskPrompt(task));
        flow.reviewAndFixLoop(Reviewers.minimal(), chat, task.title(), () -> flow.git().diff());
        flow.git().commitAll(branchName + ": " + task.title());
      });
      flow.stage("Push the fix", () -> flow.git().push("origin", branchName));
      var diff = flow.git().diff();
      var finalSum = flow.stage("Final PR summary", () -> flow.summarisePr(diff,
          "Issue " + ref.shortRef() + ": " + issue.title() + "\nThe branch now has the failing test AND the fix."));
      flow.stage("Update PR",
          () -> flow.gh().updatePr(pr, finalSum.title(), finalSum.body() + "\n\nCloses " + ref.shortRef() + "."));
      flow.deletePlan(planPath);
      flow.stage("Return to " + start, () -> flow.git().checkout(start));
    });
  }
}
