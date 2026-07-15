//> using dep "io.github.riccardomerolla:llm4zio-java:3.18.0"
//> using scala "3.8.3"
//> using jvm 21

// Enhanced plan → implement → push → PR, authored in Java — the Java-surface counterpart of implement-enhanced-pr.sc.
//
// ImplementEnhanced.java plus the delivery tail: push the epic branch, summarise the full diff over the reasoner,
// and open a GitHub PR. Needs a remote and `gh` authenticated.
//
// Seed a starter:  examples/seed.sh implement-enhanced-pr --java
// Run:             scala-cli run ImplementEnhancedPr.java -- "Add a multiply function to the calculator crate"

import llm4zio.javaapi.*;

public class ImplementEnhancedPr {
  public static void main(String[] args) {
    Llm4zioJava.flow(args, "Add a multiply function to the calculator crate", flow -> {
      var planPath = flow.defaultPlanPath();
      var plan = flow.stage("Plan (review + brief)", () -> flow.recoverOrCreatePlan(planPath,
          () -> flow.briefedPlan(flow.reviewedPlan(flow.planFrom(flow.userPrompt())), flow.userPrompt())));
      flow.stage("Branch", () -> flow.git().checkoutOrCreate(plan.epicId()));
      var chat = flow.startChat("You implement one task at a time in the current repo.");
      flow.implementTaskLoop(planPath, plan, task -> {
        chat.ask(plan.taskPrompt(task));
        flow.reviewAndFixLoop(Reviewers.all(), chat, task.title(), () -> flow.git().diff());
        flow.git().commitAll(plan.epicId() + ": " + task.title());
      });
      flow.stage("Push branch", () -> flow.git().push("origin", plan.epicId()));
      var base = flow.git().defaultBase();
      var diff = flow.git().diffVsBase(base);
      var summary = flow.stage("Summarise PR", () -> flow.summarisePr(diff, "Change request: " + flow.userPrompt()));
      flow.stage("Open PR", () -> {
        var pr = flow.gh().createPr(summary.title(), summary.body(), base);
        flow.info("PR opened: " + pr.url());
      });
    });
  }
}
