//> using dep "io.github.riccardomerolla:llm4zio-java:3.18.1"
//> using scala "3.8.3"
//> using jvm 21

// Spec-Driven Development, authored in Java — the Java-surface counterpart of sdd.sc.
//
// The cycle is Spec → Tests → Implement → Verify. The spec is written first (with numbered, testable acceptance
// criteria), committed to specs/<epicId>.md, and rides as the plan brief so every task prompt carries it. Task 1
// encodes the criteria as JUnit tests and must come out RED (green tests encode nothing — the flow fails); later
// tasks gate on `mvn -q test` each review round; a final verify stage reruns the suite.
//
// Seed a starter:  examples/seed.sh sdd --java
// Run:             scala-cli run Sdd.java -- "Add due dates: 'add <text> --due YYYY-MM-DD', ..."
// Requires an agent CLI logged in (LLM4ZIO_CODER=claude|codex|gemini|pi) and `mvn` on PATH.

import java.nio.file.Files;
import java.util.List;

import llm4zio.javaapi.*;

public class Sdd {
  static final String SPEC_INSTRUCTIONS = """
      You are a specification writer. Turn the change request into a precise spec for this repo:
      context, goals, non-goals, and a numbered list of testable acceptance criteria
      (Given/When/Then). Explore the repo as needed. Plain Markdown, no task list — the plan
      comes later, from this spec.""";

  static final String PLAN_INSTRUCTIONS_SUFFIX = """

      The request below is a SPEC with numbered acceptance criteria. The FIRST task must be
      exactly: encode the acceptance criteria as JUnit 5 tests (no production code). Every
      later task implements production code towards making those tests pass.""";

  public static void main(String[] args) {
    var coder = Connectors.coderFromEnv();
    var reasoning = Connectors.readOnly(coder);
    Llm4zioJava.flow(args,
        "Add due dates: 'add <text> --due YYYY-MM-DD', mark overdue items in 'list', and a 'due' command showing items due today",
        coder, reasoning, flow -> {
          var planPath = flow.defaultPlanPath();
          var testGate = List.of("mvn", "-q", "test");
          var buildGate = List.of("mvn", "-q", "test-compile");

          var plan = flow.stage("Spec + plan", () -> flow.recoverOrCreatePlan(planPath, () -> {
            var spec = flow.brief(flow.userPrompt(), SPEC_INSTRUCTIONS);
            var p = flow.planFrom(spec, Plans.defaultPlanInstructions() + PLAN_INSTRUCTIONS_SUFFIX);
            return Plans.withBrief(p, spec);
          }));
          flow.stage("Branch", () -> flow.git().checkoutOrCreate(plan.epicId()));
          flow.stage("Commit spec", () -> {
            try {
              var specPath = flow.workDir().resolve("specs/" + plan.epicId() + ".md");
              if (!Files.exists(specPath)) {
                Files.createDirectories(specPath.getParent());
                Files.writeString(specPath, Plans.briefOf(plan));
                flow.git().commitAll(plan.epicId() + ": spec");
              }
            } catch (java.io.IOException e) {
              throw new RuntimeException(e);
            }
          });

          var chat = flow.startChat("You implement one task at a time in the current repo. The committed spec is"
              + " the contract; the tests encode it — make them pass without weakening them.");
          flow.implementTaskLoop(planPath, plan, task -> {
            var testsTask = Plans.isFirstTask(plan, task);
            chat.ask(plan.taskPrompt(task));
            flow.reviewAndFixLoop(Reviewers.minimal(), chat, task.title(), () -> flow.git().diffAll(),
                testsTask ? buildGate : testGate, 1);
            if (testsTask && flow.lint(testGate).isClean()) {
              flow.fail("the new tests pass before any implementation — they encode nothing");
            }
            flow.git().commitAll(plan.epicId() + ": " + task.title());
          });

          flow.stage("Verify", () -> {
            var r = flow.lint(testGate);
            if (!r.isClean()) {
              flow.fail("acceptance criteria not met: " + r.summary());
            }
          });
        });
  }
}
