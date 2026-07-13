//> using dep "io.github.riccardomerolla::llm4zio-runner:3.14.0"
//> using scala "3.8.3"
//> using jvm 21

/** Legacy-modernization phase 1 of 4: reverse-engineer the legacy estate into a judged spec pack.
  *
  *   modernize-extract.sc → (human approves) → modernize-seed.sc → modernize-implement.sc → modernize-review.sc
  *
  * Runs ROOTED AT THE LEGACY REPO (`--repo <legacy>`): a coder-as-analyst explores the COBOL/JCL
  * (or JSP, or ACE — whatever the pack says) in place and writes the spec pack under
  * `docs/modernization/`: behavioural specs, BDD features, a traceability matrix, and a data/
  * interface mapping. Everything estate-specific — prompts, judge rubrics, coverage regexes —
  * comes from a modernization PACK (`flow.Pack`), a plain versioned directory.
  *
  * The gate is layered, and nothing auto-approves:
  *   - Layer 1 (deterministic, `SpecChecks`): every COBOL paragraph / JCL step found in the source
  *     must appear in the traceability matrix; the .feature files must be well-formed Gherkin.
  *   - Layer 2 (LLM-as-a-Judge on `reasoning`): completeness / faithfulness / testability scored
  *     against the pack's rubrics, full marks required.
  *   - `fixLoop` feeds failures back to the analyst up to MaxRounds; a still-dirty pack is
  *     committed as an explicit DRAFT and the flow HALTS for human triage.
  *   - Even a clean pack only gets an UNCHECKED `- [ ] Approved` marker: a human reviews
  *     `docs/modernization/README.md`, flips it, and runs modernize-seed.sc.
  *
  * Resumable: the draft spec pack is committed BEFORE the gate, and a rerun that finds an
  * existing `docs/modernization/specs/` skips the (expensive) extraction and goes straight to
  * the gate — delete the directory to force a fresh extraction. On large estates cap the
  * judge's source context with LLM4ZIO_JUDGE_SOURCES_LIMIT (chars, default 400000; the flow
  * reports when it truncates).
  *
  * Pack:  LLM4ZIO_PACK=<dir> (default packs/cobol-springboot, resolved against the launch dir).
  * Run:   scala-cli run modernize-extract.sc -- --repo ~/estates/meridian-legacy
  *
  * Seats (all-gemini defaults; LLM4ZIO_CODER=claude|codex|gemini|pi swaps the whole flow):
  * analyst + reasoning + judge on the Pro model — extraction quality is the product here.
  *
  * Quota: if gemini exhausts the model's quota the flow fails fast with the reset time
  * (set LLM4ZIO_USAGE_WAIT=24h to wait it out and auto-resume, or point ProModel at a model
  * with remaining quota). A WARN is logged if the CLI serves a different model than requested.
  */

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import zio.{ IO, ZIO }

import llm4zio.eval.*
import llm4zio.flow.*
import llm4zio.runner.*

val ProModel  = "gemini-2.5-pro"   // point these at whatever your `gemini` CLI offers
val MaxRounds = 3
val ModDir    = "docs/modernization"

val (coderCfg, reasoningCfg) =
  sys.env.get("LLM4ZIO_CODER").map(_.trim.toLowerCase).filter(_.nonEmpty) match
    case None | Some("gemini") =>
      (gemini.withModel(ProModel), gemini.withModel(ProModel).copy(readOnly = true))
    case Some(_)               =>
      val agent = Connectors.coderFromEnv()
      (agent, agent.copy(readOnly = true))

def readFileOr(path: Path, fallback: String): IO[FlowError, String] =
  ZIO
    .attemptBlocking(if Files.exists(path) then Files.readString(path) else fallback)
    .mapError(e => FlowError.Persistence(s"failed to read $path", Some(e)))

def writeFile(path: Path, content: String): IO[FlowError, Unit] =
  ZIO
    .attemptBlocking {
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.write(path, content.getBytes(StandardCharsets.UTF_8))
      ()
    }
    .mapError(e => FlowError.Persistence(s"failed to write $path", Some(e)))

/** Concatenate every file under `root` whose relative path matches `regex` — the judge's ground truth. */
def gatherSources(root: Path, regex: String): IO[FlowError, String] =
  ZIO
    .attemptBlocking {
      val stream = Files.walk(root)
      val files  =
        try stream.iterator().asScala.filter(Files.isRegularFile(_)).toList
        finally stream.close()
      files
        .filter(f => root.relativize(f).toString.replace('\\', '/').matches(regex))
        .sortBy(_.toString)
        .map(f => s"===== ${root.relativize(f)} =====\n${Files.readString(f)}")
        .mkString("\n\n")
    }
    .mapError(e => FlowError.Persistence(s"failed to gather sources under $root", Some(e)))

/** Concatenate the spec pack the analyst wrote — the judge's response-under-test. */
def gatherSpecPack(modDir: Path): IO[FlowError, String] =
  gatherSources(modDir, """.*\.(md|feature)""")

/** Bound the legacy-source context handed to the judge: a production estate can exceed the model's context and
  * come back as an empty response. Head + tail keeps both the entry points and the trailing programs visible.
  */
val JudgeSourcesLimit: Int =
  sys.env.get("LLM4ZIO_JUDGE_SOURCES_LIMIT").flatMap(_.toIntOption).getOrElse(400_000)

def capForJudge(sources: String)(using ctx: FlowContext): zio.UIO[String] =
  if sources.length <= JudgeSourcesLimit then ZIO.succeed(sources)
  else
    val head = JudgeSourcesLimit * 3 / 4
    val tail = JudgeSourcesLimit - head
    ctx.events
      .publish(FlowEvent.Info(
        s"judge source context truncated: ${sources.length} -> $JudgeSourcesLimit chars " +
          "(raise LLM4ZIO_JUDGE_SOURCES_LIMIT or narrow the pack's 'sources:' regex)"
      ))
      .as(s"${sources.take(head)}\n\n… [truncated for the judge] …\n\n${sources.takeRight(tail)}")

/** True when a previous run already produced spec documents — rerun goes straight to the gate. */
def specPackExists(modDir: Path): zio.UIO[Boolean] =
  ZIO.attemptBlocking {
    val specs = modDir.resolve("specs")
    Files.isDirectory(specs) && {
      val stream = Files.list(specs)
      try stream.iterator().asScala.exists(_.getFileName.toString.endsWith(".md"))
      finally stream.close()
    }
  }.orDie

def extractionAsk(pack: Pack): String =
  s"""Reverse-engineer this repository into a spec pack under $ModDir/ (create it):
     |
     |- $ModDir/specs/<PROGRAM>.md — one behavioural spec per program.
     |${pack.prompt("spec").getOrElse("")}
     |
     |- $ModDir/features/<program>.feature — BDD scenarios encoding the specs.
     |${pack.prompt("bdd").getOrElse("")}
     |
     |- $ModDir/traceability.md — the completeness contract: EVERY source unit (each COBOL
     |  paragraph, each JCL step) on its own line, mapped to the spec rules/scenarios that
     |  cover it: `<UNIT-NAME> — <refs>`. Unit names verbatim as they appear in the source.
     |
     |- $ModDir/mapping.md — data & interface mapping: tables/record layouts → target
     |  entities; files/screens/queues → target service contracts.
     |
     |Write the files, then stop.""".stripMargin

/** Sub-bar judge dimensions as Critical review issues, so scores merge with the deterministic checks. */
def judgeIssues(scored: EvalResult, dims: List[Dimension]): ReviewResult =
  val subBar = scored.scores.filter(s => s.score < dims.find(_.name == s.name).fold(2)(_.maxScore))
  ReviewResult(
    subBar.map(s => ReviewIssue(Severity.Critical, s"judge: ${s.name} scored ${s.score}", s.reasoning)),
    "judge",
  )

/** One evaluation of the spec pack: deterministic SpecChecks + the pack-rubric judge, merged. */
def gateEvaluate(pack: Pack, judge: Evaluator[Sample])(using ctx: FlowContext): IO[FlowError, ReviewResult] =
  val modDir = workDir.resolve(ModDir)
  for
    trace    <- readFileOr(modDir.resolve("traceability.md"), "")
    coverage <- SpecChecks.coverage(workDir, pack.coverage, trace)
    features <- SpecChecks.features(modDir.resolve("features"))
    specText <- gatherSpecPack(modDir)
    sources  <- gatherSources(workDir, pack.sources.getOrElse(""".*""")).flatMap(capForJudge)
    scored   <- judge
                  .evaluate(Sample(response = specText, context = Some(sources), query = Some(userPrompt)))
                  .mapError(e => FlowError.Llm(e.message, Some(e)))
  yield Reviewers.merge(List(coverage, features, judgeIssues(scored, pack.judgeDimensions)))

def fixAsk(result: ReviewResult): String =
  val lines = result.issues.map(i => s"- [${i.severity}] ${i.title}: ${i.description}").mkString("\n")
  s"""The spec pack did not clear its quality gate. Fix these findings in place under $ModDir/,
     |then stop:
     |$lines""".stripMargin

/** Clean pack → tell the human where to approve; dirty pack → halt with the committed draft. */
def announceOrHalt(result: ReviewResult)(using ctx: FlowContext): IO[FlowError, Unit] =
  if result.isClean then
    ctx.events.publish(
      FlowEvent.Info(
        s"spec pack ready — review $ModDir/README.md, set '${ApprovalGate.ApprovedMarker}', then run modernize-seed.sc"
      )
    )
  else
    fail(
      s"extraction gate not cleared after $MaxRounds round(s) — spec pack committed as draft:\n" +
        result.issues.map(i => s"- ${i.title}").mkString("\n")
    )

def indexFor(pack: Pack, verdict: String): String =
  s"""# Modernization spec pack — ${pack.name}
     |
     |Extracted by modernize-extract.sc. Gate verdict: $verdict.
     |
     |- specs/ — behavioural specs, one per program
     |- features/ — BDD acceptance scenarios
     |- traceability.md — source-unit → spec coverage matrix
     |- mapping.md — data & interface mapping
     |- plan.md — proposed implementation tasks (parsed by modernize-seed.sc)
     |
     |Review everything, then flip the marker below and run modernize-seed.sc.""".stripMargin

flow(
  args,
  coder = coderCfg,
  reasoning = Some(reasoningCfg),
  defaultPrompt = Some("Extract the complete behavioural spec pack for this legacy estate"),
):
  val packDir = workspace.resolve(sys.env.getOrElse("LLM4ZIO_PACK", "packs/cobol-springboot"))
  val modDir  = workDir.resolve(ModDir)

  for
    pack    <- stage("Pack")(Pack.load(packDir))
    _       <- stage("Branch")(git.checkoutOrCreate("modernize/spec-pack"))
    system   = List(
                 pack.prompt("analysis"),
                 pack.lessons.map(l => s"Lessons from previous modernization runs — apply them:\n$l"),
               ).flatten.mkString("\n\n")
    analyst <- Chat.start(coder, system = Some(system))
    judge    = Judge.of(reasoning, pack.judgeDimensions)
    resumed <- specPackExists(modDir)
    _       <- stage("Extract") {
                 if resumed then
                   summon[FlowEvents].publish(FlowEvent.Info(
                     s"resuming: spec pack found under $ModDir — skipping extraction (delete it to re-extract)"
                   ))
                 else analyst.ask(extractionAsk(pack)).unit
               }
    // Commit the (ungated) draft immediately: extraction is the expensive step, and a gate failure or
    // crash must not cost it. The Gate/Commit stages amend the picture with the verdict afterwards.
    _       <- stage("Draft")(git.commitAll(s"modernize(${pack.name}): spec pack draft (ungated)").unit)
    result  <- stage("Gate")(fixLoop(gateEvaluate(pack, judge), r => analyst.ask(fixAsk(r)).unit, MaxRounds))
    _       <- ZIO.when(result.isClean)(stage("Plan") {
                 for
                   specText <- gatherSpecPack(modDir)
                   plan     <- Planner.from(reasoning, specText, Planner.defaultInstructions + "\n\n" + pack.prompt("plan").getOrElse(""))
                   _        <- writeFile(modDir.resolve("plan.md"), plan.render)
                 yield ()
               })
    verdict  = if result.isClean then "PASSED — pending human approval" else s"DRAFT — ${result.issues.size} open issue(s)"
    _       <- stage("Commit")(
                 writeFile(modDir.resolve("README.md"), ApprovalGate.withDraftMarker(indexFor(pack, verdict))) *>
                   git.commitAll(s"modernize(${pack.name}): spec pack ($verdict)").unit
               )
    _       <- announceOrHalt(result)
  yield ModDir
