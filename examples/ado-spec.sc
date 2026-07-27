//> using dep "io.github.riccardomerolla::llm4zio-runner:4.1.0"
//> using scala "3.8.3"
//> using jvm 21

/** Azure DevOps — Spec gate (first of two). Triggered when a work item enters **Refine**.
  *
  * llm4zio reads the card as the change request, drafts a spec (numbered, testable acceptance
  * criteria) with the gemini reasoner, writes it into the work item's **Acceptance Criteria**
  * field (`Microsoft.VSTS.Common.AcceptanceCriteria`) — where a human can review and edit it in
  * Azure Boards — leaves a discussion comment, and moves the card to **Spec Review**. No branch,
  * no code: the human gates the spec before any implementation (see `ado-implement.sc`).
  *
  * Run (dispatched by the poll pipeline, or locally):
  *   scala-cli run ado-spec.sc -- "<work-item-id>"
  * Requires `gemini` logged in and ADO config in the env (SYSTEM_COLLECTIONURI / SYSTEM_TEAMPROJECT /
  * BUILD_REPOSITORY_NAME / SYSTEM_ACCESSTOKEN in a pipeline; LLM4ZIO_ADO_* + a PAT locally).
  */

import zio.{ IO, ZIO }

import llm4zio.flow.*
import llm4zio.runner.*

val ProModel   = "gemini-3-pro-preview"
val FlashModel = "gemini-2.5-flash"

val specInstructions =
  """You are a specification writer. Turn the change request into a precise spec for this repo:
    |context, goals, non-goals, and a numbered list of testable acceptance criteria
    |(Given/When/Then). Explore the repo as needed. Plain Markdown, no task list.""".stripMargin

// Seats default to all-gemini (Pro reasoning, Flash coder). Set LLM4ZIO_CODER=claude|codex|gemini|pi
// to run the WHOLE flow on that one provider (its own default model); reasoning stays read-only.
val (coderCfg, reasoningCfg) =
  sys.env.get("LLM4ZIO_CODER").map(_.trim.toLowerCase).filter(_.nonEmpty) match
    case None | Some("gemini") =>
      (gemini.withModel(FlashModel), gemini.withModel(ProModel).copy(readOnly = true))
    case Some(_) =>
      val agent = Connectors.coderFromEnv()
      (agent, agent.copy(readOnly = true))

flow(
  args,
  coder = coderCfg, // unused here (no code edits), but keeps the flow on one provider
  reasoning = Some(reasoningCfg),
):
  userPrompt.trim.toIntOption match
    case None     => fail("usage: scala-cli run ado-spec.sc -- \"<work-item-id>\"")
    case Some(id) =>
      Ado.withTool() { ado =>
        for
          wi   <- stage(s"Read work item #$id")(ado.readWorkItem(id))
          req   = s"${wi.title}\n\n${wi.description}"
          spec <- stage("Draft spec")(Planner.brief(reasoning, req, specInstructions))
          _    <- stage("Write Acceptance Criteria")(ado.setAcceptanceCriteria(id, spec))
          _    <- stage("Comment on card")(
                    ado.comment(id, "Spec drafted by llm4zio — review the Acceptance Criteria, then move the card to Approved to implement.")
                  )
          _    <- stage("Move to Spec Review")(ado.setState(id, "Spec Review"))
        yield ()
      }
