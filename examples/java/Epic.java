//> using dep "io.github.riccardomerolla:llm4zio-java:4.1.0"
//> using scala "3.8.3"
//> using jvm 21

// Run an epic, authored in Java — the Java-surface counterpart of examples/epic.sc.
//
// A resumable multi-task workstream with the full review roster. .llm4zio/plan-<hash>.md holds the task list;
// a re-run resumes from the first incomplete task (each checkbox is committed as it lands). After each task the
// seven review lenses (Reviewers.all()) run and the coder fixes their findings. At the end the docs are updated
// and the plan file is cleaned up.
//
// Run: scala-cli run Epic.java -- "<a multi-task change request>"

import llm4zio.javaapi.*;

public class Epic {
  public static void main(String[] args) {
    Llm4zioJava.flow(
        args,
        "Persist tasks to a JSON file, add 'done <id>' and 'delete <id>' commands, and support priority levels",
        flow -> {
          var planPath = flow.defaultPlanPath();
          var plan = flow.stage("Acquire epic", () -> flow.recoverOrCreatePlan(planPath));
          flow.stage("Branch", () -> flow.git().checkoutOrCreate(plan.epicId()));
          var chat = flow.startChat("You implement one task at a time in the current repo.");
          flow.implementTaskLoop(planPath, plan, task -> {
            chat.ask(task.description());
            flow.reviewAndFixLoop(Reviewers.all(), chat, task.title(), () -> flow.git().diff());
            flow.git().commitAll(plan.epicId() + ": " + task.title());
          });
          flow.stage("Update documentation", () -> {
            chat.ask("All tasks are done. Update the project docs for the changes made — only what's affected.");
            flow.git().commitAll("docs: update for completed epic");
          });
          flow.stage("Clean up epic file", () -> flow.deletePlan(planPath));
        });
  }
}
