//> using dep "io.github.riccardomerolla:llm4zio-java:3.13.0"
//> using scala "3.8.3"
//> using jvm 21

// Persistent planning + coding flow, authored in Java — the Java-surface counterpart of examples/implement.sc.
//
// The reasoner breaks the prompt into a Plan, persisted under .llm4zio/ so a re-run resumes from the first
// incomplete task. Each task is implemented on one epic branch, reviewed via reviewAndFixLoop, and committed.
// Backend selectable via LLM4ZIO_CODER=claude|codex|gemini (default claude); no API key — one CLI login is enough.
//
// Run: scala-cli run Implement.java -- "Add a multiply function to the calculator crate"
//
// Note the single-colon coordinate above: llm4zio-java is published with crossPaths:=false, so it carries no
// Scala _3 suffix and reads like an ordinary Maven coordinate.

import llm4zio.javaapi.*;

public class Implement {
  public static void main(String[] args) {
    Llm4zioJava.flow(args, "Add a multiply function to the calculator crate", flow -> {
      var planPath = flow.defaultPlanPath();
      var plan = flow.recoverOrCreatePlan(planPath);
      flow.stage("branch", () -> flow.git().checkoutOrCreate(plan.epicId()));
      var chat = flow.startChat("You implement one task at a time in the current repo.");
      flow.implementTaskLoop(planPath, plan, task -> {
        chat.ask(task.description());
        flow.reviewAndFixLoop(Reviewers.minimal(), chat, task.title(), () -> flow.git().diff());
        flow.git().commitAll(plan.epicId() + ": " + task.title());
      });
    });
  }
}
