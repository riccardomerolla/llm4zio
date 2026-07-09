//> using dep "io.github.riccardomerolla:llm4zio-java:3.13.0"
//> using scala "3.8.3"
//> using jvm 21

// Azure DevOps spec drafting, authored in Java — the Java-surface counterpart of ado-spec.sc.
//
// Given a work-item id: read the card, draft a spec (numbered, testable acceptance criteria) over the reasoning
// connector, write it onto the work item's acceptance-criteria field, comment, and move the card to Spec Review.
// Config comes from the ADO pipeline env (SYSTEM_COLLECTIONURI, …) or LLM4ZIO_ADO_* + a PAT for local runs.
//
// Run: scala-cli run AdoSpec.java -- "12345"

import llm4zio.javaapi.*;

public class AdoSpec {
  static final String SPEC_INSTRUCTIONS = """
      You are a specification writer. Turn the work item below into a precise spec for this repo:
      context, goals, non-goals, and a numbered list of testable acceptance criteria
      (Given/When/Then). Explore the repo as needed. Plain Markdown, no task list.""";

  public static void main(String[] args) {
    Llm4zioJava.flow(args, "", flow -> {
      final int id;
      try {
        id = Integer.parseInt(flow.userPrompt().trim());
      } catch (NumberFormatException e) {
        flow.fail("usage: scala-cli run AdoSpec.java -- \"<work item id>\"");
        return;
      }
      flow.withAdo(ado -> {
        var wi = flow.stage("Read work item " + id, () -> ado.readWorkItem(id));
        var request = wi.title() + "\n\n" + wi.description();
        var spec = flow.stage("Draft spec", () -> flow.brief(request, SPEC_INSTRUCTIONS));
        flow.stage("Write acceptance criteria", () -> ado.setAcceptanceCriteria(id, spec));
        flow.stage("Comment on card", () -> ado.comment(id, "Spec drafted — please review the acceptance criteria."));
        flow.stage("Move to Spec Review", () -> ado.setState(id, "Spec Review"));
      });
    });
  }
}
