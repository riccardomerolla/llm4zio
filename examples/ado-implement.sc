//> using dep "io.github.riccardomerolla::llm4zio-runner:4.1.0"
//> using scala "3.8.3"
//> using jvm 21

/** Azure DevOps — Implement gate (second of two). Triggered when a work item, with a human-approved
  * spec, enters **Approved**.
  *
  * llm4zio reads the (human-edited) spec back from the work item's Acceptance Criteria field, branches
  * `llm4zio/wi-<id>`, commits the spec as the contract, then runs the same Spec→Tests→Implement→Verify
  * harness as `sdd.sc`: task 1 encodes the criteria as failing tests (gated RED), later tasks implement
  * until `mvn test` is green, with per-task review-and-fix rounds. Finally it pushes, opens a PR, links
  * the PR to the work item, comments the PR URL on the card, and moves the card to **In Review**. The
  * merge itself is gated by Azure Repos branch policies — the final human/CI gate.
  *
  * Run (dispatched by the poll pipeline, or locally):
  *   scala-cli run ado-implement.sc -- "<work-item-id>"
  * Requires `gemini` logged in, `mvn` on PATH (or set LLM4ZIO_TEST_CMD / LLM4ZIO_BUILD_CMD), ADO config
  * in the env, and a repo with branch policies on the target branch.
  */

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.{ IO, ZIO }

import llm4zio.flow.*
import llm4zio.runner.*

val ProModel   = "gemini-3-pro-preview"
val FlashModel = "gemini-2.5-flash"

val planInstructions =
  Planner.defaultInstructions +
    "\n\nThe request below is a SPEC with numbered acceptance criteria. The FIRST task must be" +
    "\nexactly: encode the acceptance criteria as tests (no production code). Every later task" +
    "\nimplements production code towards making those tests pass."

def writeIfAbsent(path: Path, content: String): IO[FlowError, Boolean] =
  ZIO
    .attemptBlocking {
      if Files.exists(path) then false
      else
        Option(path.getParent).foreach(Files.createDirectories(_))
        Files.write(path, content.getBytes(StandardCharsets.UTF_8))
        true
    }
    .mapError(e => FlowError.Persistence(s"failed to write $path", Some(e)))

// Seats default to all-gemini, per-role (Pro reasoning, Flash coder + reviewers). Set
// LLM4ZIO_CODER=claude|codex|gemini|pi to run the WHOLE flow — reasoning, coder, AND reviewers —
// on that one provider (its own default model); reasoning + reviewers stay read-only.
val (coderCfg, reasoningCfg, reviewerCfg) =
  sys.env.get("LLM4ZIO_CODER").map(_.trim.toLowerCase).filter(_.nonEmpty) match
    case None | Some("gemini") =>
      (
        gemini.withModel(FlashModel),
        gemini.withModel(ProModel).copy(readOnly = true),
        gemini.withModel(FlashModel).copy(readOnly = true),
      )
    case Some(_) =>
      val agent = Connectors.coderFromEnv()
      (agent, agent.copy(readOnly = true), agent.copy(readOnly = true))

flow(
  args,
  coder = coderCfg,
  reasoning = Some(reasoningCfg),
  reviewers = List(reviewerCfg),
):
  userPrompt.trim.toIntOption match
    case None     => fail("usage: scala-cli run ado-implement.sc -- \"<work-item-id>\"")
    case Some(id) =>
      Ado.withTool() { ado =>
        val branch    = s"llm4zio/wi-$id"
        val planPath  = workDir.resolve(s".llm4zio/wi-$id.md")
        val testCmd   = sys.env.getOrElse("LLM4ZIO_TEST_CMD", "mvn -q test")
        val buildCmd  = sys.env.getOrElse("LLM4ZIO_BUILD_CMD", "mvn -q test-compile")
        val testGate  = Reviewers.lintCommand(List("bash", "-c", testCmd), workDir)
        val buildGate = Reviewers.lintCommand(List("bash", "-c", buildCmd), workDir)
        val reviewSvc = reviewers.headOption.getOrElse(reasoning)

        for
          wi        <- stage(s"Read work item #$id")(ado.readWorkItem(id))
          spec       = if wi.acceptanceCriteria.nonEmpty then wi.acceptanceCriteria
                       else s"${wi.title}\n\n${wi.description}" // fall back to the card body if AC is empty
          _         <- stage("Branch")(git.checkoutOrCreate(branch))
          plan      <- stage("Plan from spec") {
                         PlanStore.recoverOrCreate(planPath) {
                           Planner.from(reasoning, spec, planInstructions).map(_.copy(brief = Some(spec)))
                         }
                       }
          _         <- stage("Commit spec") {
                         writeIfAbsent(workDir.resolve(s"specs/wi-$id.md"), spec).flatMap(wrote =>
                           ZIO.when(wrote)(git.commitAll(s"wi-$id: spec")).unit
                         )
                       }
          coderChat <- Chat.start(
                         coder,
                         system = Some(
                           "You implement one task at a time in the current repo. The committed spec is the contract;" +
                             " the tests encode it — make them pass without weakening them."
                         ),
                       )
          _         <- implementTaskLoop(planPath, plan) { task =>
                         val testsTask = plan.tasks.headOption.contains(task)
                         for
                           _ <- coderChat.ask(plan.taskPrompt(task))
                           _ <- reviewAndFixLoop(
                                  Reviewers.minimal,
                                  reviewSvc,
                                  coderChat,
                                  task.title,
                                  git.diffAll,
                                  lint = Some(if testsTask then buildGate else testGate),
                                  parallelism = 1, // gemini's free tier 429s under concurrent reviewers
                                )
                           _ <- ZIO.when(testsTask) {
                                  testGate.flatMap(r =>
                                    ZIO.when(r.isClean)(
                                      fail("the new tests pass before any implementation — they encode nothing")
                                    )
                                  )
                                }
                           _ <- git.commitAll(s"wi-$id: ${task.title}").unit
                         yield ()
                       }
          _         <- stage("Verify")(
                         testGate.flatMap(r =>
                           if r.isClean then ZIO.unit
                           else fail("acceptance criteria not met: tests are red after implementation")
                         )
                       )
          _         <- stage("Push branch")(git.push("origin", branch))
          base      <- git.defaultBase
          diff      <- git.diffVsBase(base)
          summary   <- stage("Summarise PR")(summarisePr(reasoning, diff, context = Some(s"Implements work item #$id: ${wi.title}")))
          pr        <- stage("Open PR")(ado.createPr(s"refs/heads/$branch", s"refs/heads/${base.stripPrefix("origin/")}", summary.title, summary.body))
          _         <- stage("Link PR to work item")(ado.linkPr(id, pr))
          _         <- stage("Comment on card")(ado.comment(id, s"PR opened: ${pr.webUrl}"))
          _         <- stage("Move to In Review")(ado.setState(id, "In Review"))
        yield ()
      }
