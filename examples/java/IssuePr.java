//> using dep "io.github.riccardomerolla:llm4zio-java:4.1.0"
//> using scala "3.8.3"
//> using jvm 21

// GitHub-issue → PR flow, authored in Java — the Java-surface counterpart of examples/issue-pr.sc.
//
// Given an owner/repo#number reference: read the issue; resume a persisted plan or skeptically assess
// (assessThenPlan) — Blocked posts the reason on the issue and stops; Proceed branches and persists. Implement each
// task with the review loop, push, summarise the diff, open the PR, and return to the starting branch.
//
// Run: scala-cli run IssuePr.java -- "owner/repo#number"
// Requires `claude` and `gh` authenticated, and a repo with a remote.

import llm4zio.javaapi.*;
import llm4zio.flow.Plan;

public class IssuePr {
  public static void main(String[] args) {
    Llm4zioJava.flow(args, "owner/repo#number", flow -> {
      var maybeRef = Refs.issue(flow.userPrompt());
      if (maybeRef.isEmpty()) {
        flow.fail("usage: scala-cli run IssuePr.java -- \"owner/repo#number\"");
        return;
      }
      var ref = maybeRef.get();
      var start = flow.git().currentBranch();
      var issue = flow.stage("Read issue " + ref.shortRef(), () -> flow.gh().readIssue(ref));
      var payload = "Issue: " + issue.title() + "\n\nReporter: " + issue.author() + "\n\n" + issue.body();
      var planPath = flow.workDir().resolve(".llm4zio/issue-" + ref.number() + ".md");

      Plan plan;
      var existing = flow.loadPlan(planPath);
      if (existing.isPresent()) {
        plan = existing.get();
      } else {
        var assessment = flow.assessThenPlan(payload);
        if (assessment.isBlocked()) {
          flow.stage("Post assessment on the issue", () -> flow.gh().writeIssueComment(ref, assessment.getReason()));
          return; // Blocked: never switched branch
        }
        plan = assessment.getPlan();
        flow.git().checkoutOrCreate(plan.epicId());
        flow.savePlan(planPath, plan);
      }

      var epic = plan;
      var chat = flow.startChat("You implement one task at a time in the current repo.");
      flow.implementTaskLoop(planPath, epic, task -> {
        chat.ask(task.description());
        flow.reviewAndFixLoop(Reviewers.all(), chat, task.title(), () -> flow.git().diff());
        flow.git().commitAll(epic.epicId() + ": " + task.title());
      });

      flow.stage("Push branch", () -> flow.git().push("origin", epic.epicId()));
      var base = flow.git().defaultBase();
      var diff = flow.git().diffVsBase(base);
      var summary = flow.stage("Summarise PR", () -> flow.summarisePr(diff, "Originating issue: " + ref.shortRef()));
      flow.stage("Open PR", () -> {
        flow.gh().createPr(summary.title(), summary.body() + "\n\nCloses " + ref.shortRef() + ".", base);
      });
      flow.deletePlan(planPath);
      flow.stage("Return to " + start, () -> flow.git().checkout(start));
    });
  }
}
