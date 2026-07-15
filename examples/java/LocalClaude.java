//> using dep "io.github.riccardomerolla:llm4zio-java:3.17.0"
//> using scala "3.8.3"
//> using jvm 21

// Fully-local agentic flow with Claude Code as the coder, authored in Java — no cloud, no API key. The Java-surface
// counterpart of local-claude.sc.
//   - reasoning (planning + review): LM Studio's local server.
//   - coding: the `claude` CLI pointed at LM Studio's Anthropic-compatible endpoint via ANTHROPIC_BASE_URL +
//     ANTHROPIC_AUTH_TOKEN, injected into the coder connector (llm4zio passes them through to `claude`).
//
// Setup: start LM Studio (>=25K context) and load a model; install Claude Code; set the model ids below.
// Run:   scala-cli run LocalClaude.java -- "Add a multiply function to the calculator crate"

import java.util.Map;

import llm4zio.javaapi.*;

public class LocalClaude {
  static final String REASONING_MODEL = "qwen/qwen3.6-35b-a3b";
  static final String CODER_MODEL = "qwen/qwen3.6-35b-a3b";

  public static void main(String[] args) {
    var coder = Connectors.withEnv(
        Connectors.withModel(Connectors.claude(), CODER_MODEL),
        Map.of("ANTHROPIC_BASE_URL", "http://localhost:1234", "ANTHROPIC_AUTH_TOKEN", "lmstudio"));
    var reasoning = Connectors.withModel(Connectors.lmStudio(), REASONING_MODEL);
    Llm4zioJava.flow(args, "Add a multiply function to the calculator crate", coder, reasoning, flow -> {
      var planPath = flow.defaultPlanPath();
      var plan = flow.recoverOrCreatePlan(planPath);
      flow.stage("branch", () -> flow.git().checkoutOrCreate(plan.epicId()));
      var chat = flow.startChat("You implement one task at a time in the current repo.");
      flow.implementTaskLoop(planPath, plan, task -> {
        chat.ask(task.description());
        flow.reviewAndFixLoop(Reviewers.minimal(), chat, task.title(), () -> flow.git().diff(), 1);
        flow.git().commitAll(plan.epicId() + ": " + task.title());
      });
    });
  }
}
