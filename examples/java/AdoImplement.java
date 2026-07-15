//> using dep "io.github.riccardomerolla:llm4zio-java:3.17.0"
//> using scala "3.8.3"
//> using jvm 21

// Azure DevOps implementation, authored in Java — the Java-surface counterpart of ado-implement.sc.
//
// Given an Approved work-item id: read the card, branch, plan from its acceptance criteria (spec rides as the plan
// brief), tests-first with an mvn gate per review round, verify, push, open an ADO PR linked to the work item, and
// move the card to In Review. Config from the ADO pipeline env or LLM4ZIO_ADO_* + a PAT.
//
// Run: scala-cli run AdoImplement.java -- "12345"

import java.util.List;

import llm4zio.javaapi.*;

public class AdoImplement {
  public static void main(String[] args) {
    Llm4zioJava.flow(args, "", flow -> {
      final int id;
      try {
        id = Integer.parseInt(flow.userPrompt().trim());
      } catch (NumberFormatException e) {
        flow.fail("usage: scala-cli run AdoImplement.java -- \"<work item id>\"");
        return;
      }
      flow.withAdo(ado -> {
        var wi = flow.stage("Read work item " + id, () -> ado.readWorkItem(id));
        var spec = wi.acceptanceCriteria().isBlank() ? wi.title() + "\n\n" + wi.description()
            : wi.acceptanceCriteria();
        var branch = "wi-" + id;
        flow.stage("Branch", () -> flow.git().checkoutOrCreate(branch));

        var planPath = flow.workDir().resolve(".llm4zio/wi-" + id + ".md");
        var plan = flow.stage("Plan from spec", () -> flow.recoverOrCreatePlan(planPath,
            () -> Plans.withBrief(flow.planFrom(spec), spec)));

        var testGate = List.of("mvn", "-q", "test");
        var chat = flow.startChat("You implement one task at a time in the current repo;"
            + " the acceptance criteria are the contract.");
        flow.implementTaskLoop(planPath, plan, task -> {
          chat.ask(plan.taskPrompt(task));
          flow.reviewAndFixLoop(Reviewers.minimal(), chat, task.title(), () -> flow.git().diffAll(), testGate, 1);
          flow.git().commitAll(branch + ": " + task.title());
        });
        flow.stage("Verify", () -> {
          if (!flow.lint(testGate).isClean()) {
            flow.fail("acceptance criteria not met after the final task");
          }
        });

        flow.stage("Push branch", () -> flow.git().push("origin", branch));
        var base = flow.git().defaultBase();
        var diff = flow.git().diffVsBase(base);
        var summary = flow.stage("Summarise PR", () -> flow.summarisePr(diff, "Work item #" + id + ": " + wi.title()));
        var pr = flow.stage("Open PR", () -> ado.createPr("refs/heads/" + branch,
            "refs/heads/" + base.replaceFirst("^origin/", ""), summary.title(), summary.body()));
        flow.stage("Link PR to work item", () -> ado.linkPr(id, pr));
        flow.stage("Comment on card", () -> ado.comment(id, "PR opened: " + pr.webUrl()));
        flow.stage("Move to In Review", () -> ado.setState(id, "In Review"));
      });
    });
  }
}
