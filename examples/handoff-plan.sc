//> using dep "io.github.riccardomerolla::llm4zio-runner:3.14.0"
//> using scala "3.8.3"
//> using jvm 21

/** Phase 1 of a human-gated, two-invocation hand-off: requirements → spec + plan → STOP.
  *
  *   handoff-plan.sc  →  (human reviews + approves the spec)  →  handoff-build.sc
  *
  * This script turns a business requirement into a reviewable spec and a resumable plan, then
  * exits without writing a line of production code. The spec is written with an UNCHECKED approval
  * marker (`- [ ] Approved`); a human reviews `specs/<epicId>.md`, edits as needed, and flips it to
  * `- [x] Approved`. Phase 2 (`handoff-build.sc`) refuses to implement until that box is checked.
  *
  * Run BOTH phases from OUTSIDE the target repo, with the same `--repo` and `--prompt-file`, so the
  * coder never sees this Scala script and both phases resolve the same plan:
  *
  *   cd ~/llm4zio-control
  *   scala-cli run handoff-plan.sc  -- --repo ~/projects/todo --prompt-file requirement.md
  *   # …review specs/<epicId>.md, change "- [ ] Approved" to "- [x] Approved"…
  *   scala-cli run handoff-build.sc -- --repo ~/projects/todo --prompt-file requirement.md
  *
  * The spec and plan live in the control directory (the workspace), not the target repo. Requires a
  * reasoning agent logged in (claude by default; LLM4ZIO_CODER=codex|gemini to swap).
  *
  * Seed a starter:  examples/seed.sh handoff
  */

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.{ IO, ZIO }

import llm4zio.flow.*
import llm4zio.runner.*

val specInstructions =
  """You are a specification writer. Turn the change request into a precise spec for this repo:
    |context, goals, non-goals, and a numbered list of testable acceptance criteria (Given/When/Then)
    |in the project's domain vocabulary, each with at least one concrete, named example. Explore the
    |repo as needed. Plain Markdown, no task list — the plan is derived from this spec.""".stripMargin

def writeFile(path: Path, content: String): IO[FlowError, Unit] =
  ZIO
    .attemptBlocking {
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.write(path, content.getBytes(StandardCharsets.UTF_8))
      ()
    }
    .mapError(e => FlowError.Persistence(s"failed to write $path", Some(e)))

flow(
  args,
  defaultPrompt = Some(
    "Add due dates: 'add <text> --due YYYY-MM-DD', mark overdue items in 'list', and a 'due' command showing items due today"
  ),
):
  for
    spec     <- stage("Specify")(Planner.brief(reasoning, userPrompt, specInstructions))
    plan     <- PlanStore.recoverOrCreate(planPath(userPrompt)) {
                  Planner.from(reasoning, spec).map(_.copy(brief = Some(spec)))
                }
    specFile  = workspace.resolve(s"specs/${plan.epicId}.md")
    _        <- writeFile(specFile, ApprovalGate.withDraftMarker(spec))
    _        <- summon[FlowEvents].publish(FlowEvent.Info(
                  s"spec + plan ready — review $specFile, set '${ApprovalGate.ApprovedMarker}', then run handoff-build.sc"
                ))
  yield specFile.toString
