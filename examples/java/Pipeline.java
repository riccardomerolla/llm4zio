//> using dep "io.github.riccardomerolla:llm4zio-java:3.12.0"
//> using scala "3.8.3"
//> using jvm 21

// Specify → design → implement → verify pipeline, authored in Java — the Java-surface counterpart of pipeline.sc
// (condensed: the .sc's acceptance-scenario staging is folded into the spec; the shape — outside-in, tests first,
// TDD-discipline reviewer, mvn gate per round — is the same).
//
// Seed a starter:  examples/seed.sh pipeline --java
// Run:             scala-cli run Pipeline.java -- "Support hashtags: 'add <text> #tag' stores tags, ..."
// Requires an agent CLI logged in and `mvn` on PATH.

import java.util.List;

import llm4zio.javaapi.*;

public class Pipeline {
  static final String SPEC_INSTRUCTIONS = """
      You are a specification writer. Turn the change request into a precise spec for this repo:
      context, goals, non-goals, and numbered Given/When/Then acceptance scenarios. Explore the
      repo as needed. Plain Markdown, no task list.""";

  static final String DESIGN_INSTRUCTIONS = """
      You are a software designer. Given the spec below, describe the design: the modules and
      types to touch or add, how data flows through them, and the seams to test at. Explore the
      repo as needed. Plain Markdown, concise.""";

  static final String PLAN_INSTRUCTIONS_SUFFIX = """

      The request below is a SPEC followed by a DESIGN. The FIRST task must encode the acceptance
      scenarios as failing JUnit 5 tests (no production code); every later task implements one
      coherent slice towards making them pass, outside-in.""";

  public static void main(String[] args) {
    var coder = Connectors.coderFromEnv();
    var reasoning = Connectors.readOnly(coder);
    Llm4zioJava.flow(args,
        "Support hashtags: 'add <text> #tag' stores tags, 'list --tag <tag>' filters, and a 'tags' command lists every tag with its count",
        coder, reasoning, flow -> {
          var planPath = flow.defaultPlanPath();
          var testGate = List.of("mvn", "-q", "test");
          var buildGate = List.of("mvn", "-q", "test-compile");

          var plan = flow.stage("Specify + design + plan", () -> flow.recoverOrCreatePlan(planPath, () -> {
            var spec = flow.brief(flow.userPrompt(), SPEC_INSTRUCTIONS);
            var design = flow.brief(spec, DESIGN_INSTRUCTIONS);
            var joined = spec + "\n\n## Design\n\n" + design;
            var p = flow.planFrom(joined, Plans.defaultPlanInstructions() + PLAN_INSTRUCTIONS_SUFFIX);
            return Plans.withBrief(p, joined);
          }));
          flow.stage("Branch", () -> flow.git().checkoutOrCreate(plan.epicId()));

          var reviewers = Reviewers.plus(Reviewers.minimal(), Reviewers.tddDiscipline());
          var chat = flow.startChat("You implement one scenario slice at a time in the current repo, outside-in;"
              + " the committed tests are the contract.");
          flow.implementTaskLoop(planPath, plan, task -> {
            var testsTask = Plans.isFirstTask(plan, task);
            chat.ask(plan.taskPrompt(task));
            flow.reviewAndFixLoop(reviewers, chat, task.title(), () -> flow.git().diffAll(),
                testsTask ? buildGate : testGate, 1);
            if (testsTask && flow.lint(testGate).isClean()) {
              flow.fail("the new tests pass before any implementation — they encode nothing");
            }
            flow.git().commitAll(plan.epicId() + ": " + task.title());
          });

          flow.stage("Verify", () -> {
            if (!flow.lint(testGate).isClean()) {
              flow.fail("acceptance scenarios not green after the final task");
            }
          });
        });
  }
}
