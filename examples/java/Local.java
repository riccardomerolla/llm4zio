//> using dep "io.github.riccardomerolla:llm4zio-java:3.13.0"
//> using scala "3.8.3"
//> using jvm 21

// Fully-local agentic flow, authored in Java — no cloud, no API key. The Java-surface counterpart of local.sc.
//   - reasoning (planning + review): LM Studio's local server (http://localhost:1234/v1).
//   - coding: the `pi` CLI agent routed to a local model (pi runs YOLO, editing files unattended).
//
// Setup: run LM Studio with a model loaded; install pi + pi-lmstudio; set the two model ids below.
// Run:   scala-cli run Local.java -- "Add a multiply function to the calculator crate"

import llm4zio.javaapi.*;

public class Local {
  static final String REASONING_MODEL = "qwen/qwen3-coder-30b";
  static final String CODER_MODEL = "qwen/qwen3-coder-30b";

  public static void main(String[] args) {
    var coder = Connectors.withModel(Connectors.pi(), CODER_MODEL);
    var reasoning = Connectors.withModel(Connectors.lmStudio(), REASONING_MODEL);
    Llm4zioJava.flow(args, "Add a multiply function to the calculator crate", coder, reasoning, flow -> {
      var planPath = flow.defaultPlanPath();
      var plan = flow.recoverOrCreatePlan(planPath);
      flow.stage("branch", () -> flow.git().checkoutOrCreate(plan.epicId()));
      var chat = flow.startChat("You implement one task at a time in the current repo.");
      flow.implementTaskLoop(planPath, plan, task -> {
        chat.ask(task.description());
        // parallelism = 1: serialize the reviewer calls so a single local model isn't overwhelmed.
        flow.reviewAndFixLoop(Reviewers.minimal(), chat, task.title(), () -> flow.git().diff(), 1);
        flow.git().commitAll(plan.epicId() + ": " + task.title());
      });
    });
  }
}
