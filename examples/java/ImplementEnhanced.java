//> using dep "io.github.riccardomerolla:llm4zio-java:3.19.0"
//> using scala "3.8.3"
//> using jvm 21

// Enhanced planning flow, authored in Java — the Java-surface counterpart of implement-enhanced.sc.
//
// Like Implement.java, but the plan is self-reviewed by the reasoner and carries a shared codebase brief, so every
// task prompt gives a cold coder the orientation the planner already gathered. Review runs the full lens roster.
//
// Seed a starter:  examples/seed.sh implement-enhanced --java
// Run:             scala-cli run ImplementEnhanced.java -- "Add a multiply function to the calculator crate"

import llm4zio.javaapi.*;

public class ImplementEnhanced {
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
    });
  }
}
