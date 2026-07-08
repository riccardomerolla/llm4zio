//> using dep "io.github.riccardomerolla:llm4zio-java:3.12.1"
//> using scala "3.8.3"
//> using jvm 21

// LLM-as-a-Judge as an in-flow quality GATE, authored in Java — the Java-surface counterpart of judge-gate.sc.
//
// Same shape as Implement.java, but the per-task quality mechanism is a score-driven fix loop instead of
// reviewAndFixLoop: after the coder implements a task, a Judge scores the task's diff on three 0–2 dimensions
// (correctness / scope / safety). While any dimension is below the bar (2 = full marks), the sub-bar dimensions'
// reasoning is fed back to the coder to revise, bounded by MAX_ROUNDS; the flow FAILS if the bar is never cleared.
//
// Seed a starter:  examples/seed.sh judge-gate --java
// Run:             scala-cli run JudgeGate.java -- "Add multiply and divide; divide errors on divide-by-zero"

import java.util.List;
import java.util.stream.Collectors;

import llm4zio.eval.Dimension;
import llm4zio.eval.DimensionScore;
import llm4zio.eval.Evaluator;
import llm4zio.eval.Sample;
import llm4zio.javaapi.*;

public class JudgeGate {
  static final int BAR = 2;
  static final int MAX_ROUNDS = 3;

  static final List<Dimension> DIMENSIONS = List.of(
      Evals.dimension("correctness", "Does the change correctly implement what the request asked for?"),
      Evals.dimension("scope", "Does the change stay within the request — no unrelated or unrequested edits?"),
      Evals.dimension("safety", "Is the change free of secrets, PII, and destructive or unsafe operations?"));

  public static void main(String[] args) {
    Llm4zioJava.flow(args,
        "Add `multiply` and `divide` functions to the calculator crate; `divide` must return an error on divide-by-zero",
        flow -> {
          var judge = flow.judge(DIMENSIONS);
          var planPath = flow.defaultPlanPath();
          var plan = flow.recoverOrCreatePlan(planPath);
          flow.stage("branch", () -> flow.git().checkoutOrCreate(plan.epicId()));
          var chat = flow.startChat("You implement one task at a time in the current repo.");
          flow.implementTaskLoop(planPath, plan, task -> {
            chat.ask(task.description());
            judgeAndFixLoop(flow, chat, judge, task.title());
            flow.git().commitAll(plan.epicId() + ": " + task.title());
          });
        });
  }

  /** Judge the current diff; while any dimension is below the bar, feed the reasoning back and re-judge. */
  static void judgeAndFixLoop(JavaFlow flow, JavaChat chat, Evaluator<Sample> judge, String taskTitle) {
    for (int round = 1; round <= MAX_ROUNDS; round++) {
      var diff = flow.git().diffAll();
      var result = flow.evaluate(judge, Evals.sample(diff, flow.userPrompt(), taskTitle));
      var below = Evals.belowBar(result, BAR);
      if (below.isEmpty()) {
        flow.info("judge: '" + taskTitle + "' cleared the bar");
        return;
      }
      if (round == MAX_ROUNDS) {
        flow.fail("judge gate not cleared after " + MAX_ROUNDS + " round(s):\n" + describe(below));
      }
      flow.info("judge round " + round + ": "
          + below.stream().map(DimensionScore::name).collect(Collectors.joining(", ")) + " below bar — revising");
      chat.ask("A reviewer scored your change below the quality bar. Raise each dimension to " + BAR + "/" + BAR
          + ", then stop:\n" + describe(below));
    }
  }

  static String describe(List<DimensionScore> below) {
    return below.stream()
        .map(d -> "- " + d.name() + " (" + d.score() + "/" + BAR + "): " + d.reasoning())
        .collect(Collectors.joining("\n"));
  }
}
