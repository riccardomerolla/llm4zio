//> using dep "io.github.riccardomerolla::llm4zio-runner:3.18.0"
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
  * Extraction is PER PROGRAM, so it is resumable and bounded on real estates:
  *   - the pack's `programs:` regex (falling back to `sources:`) enumerates the spec-worthy
  *     files; each gets its own analyst turn, its own commit, and its own artifacts:
  *     `specs/<NAME>.md`, `features/<name>.feature`, `traceability/<NAME>.md`, `mapping/<NAME>.md`;
  *   - a rerun skips every program whose spec already exists (delete `specs/<NAME>.md` to
  *     re-extract just that program) — a flaky stream costs one program's turn, not the estate;
  *   - `traceability.md` and `mapping.md` are REGENERATED deterministically from the per-program
  *     fragments before every gate round — fix findings in the fragments, never in the indexes;
  *   - each analyst turn runs under a turn limit (`AnalystTurns`), so a confused headless agent
  *     cannot burn quota on no-op commands; if the limit trips after the spec was written the
  *     flow keeps the work and moves on.
  *
  * The gate is layered, and nothing auto-approves:
  *   - Layer 1 (deterministic, `SpecChecks`): every COBOL paragraph / JCL step found in the source
  *     must appear in the traceability matrix; the .feature files must be well-formed Gherkin;
  *     traceability.md and mapping.md must exist.
  *   - Layer 2 (LLM-as-a-Judge on `reasoning`): completeness / faithfulness / testability scored
  *     PER PROGRAM against the pack's rubrics — each judge call sees one program's source and its
  *     spec, so a production estate can't blow the model context. Full marks required. An empty
  *     judge response is retried at half, then quarter context before giving up (deterministic
  *     context overflows shrink instead of repeating).
  *   - The gate is RESUMABLE per program: every verdict persists in `gate/<NAME>.json`
  *     (`flow.ReviewCache`), fingerprinted over the source + spec + feature + rubric it judged.
  *     Unchanged content reuses its stored verdict with no LLM call — a crash, quota death, or
  *     auto-resume re-entry re-judges only what changed. Delete `gate/` to force a full re-judge.
  *   - `fixLoop` feeds failures back to the analyst up to MaxRounds — one bounded fix turn per
  *     sub-bar program (own commit) plus one residual turn for estate-wide findings; a still-dirty
  *     pack is committed as an explicit DRAFT and the flow HALTS for human triage.
  *   - Even a clean pack only gets an UNCHECKED `- [ ] Approved` marker: a human reviews
  *     `docs/modernization/README.md`, flips it, and runs modernize-seed.sc.
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
  * Judge context is bounded per program by LLM4ZIO_JUDGE_SOURCES_LIMIT (chars, default 400000).
  */

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import zio.{ IO, UIO, ZIO }

import llm4zio.core.LlmError
import llm4zio.eval.*
import llm4zio.flow.*
import llm4zio.runner.*

val ProModel     = "gemini-2.5-pro" // point these at whatever your `gemini` CLI offers
val MaxRounds    = 3
val ModDir       = "docs/modernization"
val AnalystTurns = 48               // per-program turn budget — bounds a wedged agent, generous for real work

val (coderCfg, reasoningCfg) =
  sys.env.get("LLM4ZIO_CODER").map(_.trim.toLowerCase).filter(_.nonEmpty) match
    case None | Some("gemini") =>
      (
        gemini.withModel(ProModel).withTurnLimit(AnalystTurns),
        gemini.withModel(ProModel).copy(readOnly = true),
      )
    case Some(_)               =>
      val agent = Connectors.coderFromEnv()
      (agent, agent.copy(readOnly = true))

def fileExists(path: Path): UIO[Boolean] =
  ZIO.attemptBlocking(Files.exists(path)).orDie

def dirExists(path: Path): UIO[Boolean] =
  ZIO.attemptBlocking(Files.isDirectory(path)).orDie

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

/** Concatenate every file under `root` whose relative path matches `regex`. */
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

/** Concatenate the spec pack the analyst wrote — the planner's input. */
def gatherSpecPack(modDir: Path): IO[FlowError, String] =
  gatherSources(modDir, """.*\.(md|feature)""")

/** Bound a judge/planner input: past the limit keep head + tail so both the entry points and the
  * trailing rules stay visible. Oversized prompts are gemini's deterministic-empty-response path.
  */
val JudgeSourcesLimit: Int =
  sys.env.get("LLM4ZIO_JUDGE_SOURCES_LIMIT").flatMap(_.toIntOption).getOrElse(400_000)

def capText(text: String, limit: Int): String =
  if text.length <= limit then text
  else
    val head = limit * 3 / 4
    s"${text.take(head)}\n\n… [truncated] …\n\n${text.takeRight(limit - head)}"

/** `cobol/ACCTXFR.cbl` → `ACCTXFR`: the program name that keys every per-program artifact. */
def programName(rel: String): String =
  val base = rel.substring(rel.lastIndexOf('/') + 1)
  val dot  = base.lastIndexOf('.')
  if dot > 0 then base.take(dot) else base

def programAsk(pack: Pack, rel: String, name: String): String =
  s"""Extract the behavioural spec for ONE source unit of this repository: $rel
     |
     |Write exactly these files (create directories as needed):
     |
     |- $ModDir/specs/$name.md — the behavioural spec for $rel.
     |${pack.prompt("spec").getOrElse("")}
     |
     |- $ModDir/features/${name.toLowerCase}.feature — BDD scenarios encoding that spec.
     |${pack.prompt("bdd").getOrElse("")}
     |
     |- $ModDir/traceability/$name.md — EVERY source unit of $rel (each COBOL paragraph, each JCL
     |  step) on its own line, mapped to the spec rules/scenarios that cover it: `<UNIT-NAME> — <refs>`.
     |  Unit names verbatim as they appear in the source.
     |
     |- $ModDir/mapping/$name.md — data & interface mapping for $rel: tables/record layouts → target
     |  entities; files/screens/queues → target service contracts.
     |
     |Read $rel and anything it references (copybooks, includes, called programs) for context, but
     |spec ONLY $rel and do not modify legacy sources. Write the four files, then stop.""".stripMargin

/** A turn-limit trip after the spec landed is the wedged-agent tail, not a failure — keep the work. */
def turnLimitRecovery(spec: Path, rel: String)(using ctx: FlowContext): PartialFunction[FlowError, IO[FlowError, Unit]] =
  case e @ FlowError.Llm(_, Some(_: LlmError.TurnLimitError)) =>
    ZIO.ifZIO(fileExists(spec))(
      ctx.events.publish(FlowEvent.Info(s"turn limit hit on $rel after its spec was written — keeping the work")),
      ZIO.fail(e),
    )

/** One analyst turn per program, skipping programs whose spec already exists and committing each one —
  * the resume unit is a single program, so a flaky stream or crash costs one turn, not the estate.
  */
def extractPrograms(pack: Pack, system: String, programs: List[String])(using ctx: FlowContext): IO[FlowError, Unit] =
  val modDir = workDir.resolve(ModDir)
  ZIO.foreachDiscard(programs.zipWithIndex) { (rel, i) =>
    val name     = programName(rel)
    val spec     = modDir.resolve("specs").resolve(s"$name.md")
    val progress = s"(${i + 1}/${programs.size})"
    ZIO.ifZIO(fileExists(spec))(
      ctx.events.publish(FlowEvent.Info(s"resume: specs/$name.md exists — skipping $rel $progress")),
      for
        _    <- ctx.events.publish(FlowEvent.Info(s"extracting $rel $progress"))
        chat <- Chat.start(coder, system = Some(system))
        _    <- chat.ask(programAsk(pack, rel, name)).unit.catchSome(turnLimitRecovery(spec, rel))
        _    <- git.commitAll(s"modernize(${pack.name}): spec $name").unit
      yield (),
    )
  }

/** Rebuild `traceability.md` and `mapping.md` from their per-program fragments. Deterministic and
  * idempotent — runs before every gate round, so fixes belong in the fragments, never the indexes.
  * When no fragments exist (e.g. a pack extracted by an older run) the existing indexes are kept.
  */
def rebuildIndexes(modDir: Path): IO[FlowError, Unit] =
  def rebuild(fragmentDir: Path, index: Path): IO[FlowError, Unit] =
    ZIO.whenZIO(dirExists(fragmentDir)) {
      gatherSources(fragmentDir, """.*\.md""").flatMap(text => ZIO.when(text.nonEmpty)(writeFile(index, text)))
    }.unit
  rebuild(modDir.resolve("traceability"), modDir.resolve("traceability.md")) *>
    rebuild(modDir.resolve("mapping"), modDir.resolve("mapping.md"))

/** Layer-1 existence check: an estate-wide gate needs both indexes present and non-blank. */
def requiredDocs(modDir: Path): IO[FlowError, ReviewResult] =
  for
    trace <- readFileOr(modDir.resolve("traceability.md"), "")
    map   <- readFileOr(modDir.resolve("mapping.md"), "")
  yield
    val issues = List(
      Option.when(trace.isBlank)(
        ReviewIssue(Severity.Critical, "missing traceability", s"no $ModDir/traceability/ fragments were written")
      ),
      Option.when(map.isBlank)(
        ReviewIssue(Severity.Critical, "missing mapping", s"no $ModDir/mapping/ fragments were written")
      ),
    ).flatten
    ReviewResult(issues, "docs")

/** Sub-bar judge dimensions as Critical review issues, titled with the program they belong to. */
def judgeIssues(scored: EvalResult, dims: List[Dimension], program: String): ReviewResult =
  val subBar = scored.scores.filter(s => s.score < dims.find(_.name == s.name).fold(2)(_.maxScore))
  ReviewResult(
    subBar.map(s => ReviewIssue(Severity.Critical, s"judge[$program]: ${s.name} scored ${s.score}", s.reasoning)),
    s"judge:$program",
  )

def isEmptyResponse(e: FlowError): Boolean = e match
  case FlowError.Llm(message, _) => message.contains("empty response")
  case _                         => false

/** Evaluate with a shrinking context ladder: an empty judge response that survives the in-run flaky
  * retries is usually DETERMINISTIC (context overflow) — repeating the same prompt cannot succeed,
  * so retry at half, then quarter context instead.
  */
def judgeWithShrink(judge: Evaluator[Sample], sample: Sample)(using ctx: FlowContext): IO[FlowError, EvalResult] =
  def capped(cap: Int): Sample =
    sample.copy(context = sample.context.map(capText(_, cap)), response = capText(sample.response, cap))
  def attempt(cap: Int, rest: List[Int]): IO[FlowError, EvalResult] =
    judge
      .evaluate(capped(cap))
      .mapError(e => FlowError.Llm(e.message, Some(e)))
      .catchSome {
        case e if isEmptyResponse(e) && rest.nonEmpty =>
          ctx.events.publish(
            FlowEvent.Info(s"judge returned empty at cap $cap chars — shrinking to ${rest.head}: ${e.message}")
          ) *> attempt(rest.head, rest.tail)
      }
  attempt(JudgeSourcesLimit, List(JudgeSourcesLimit / 2, JudgeSourcesLimit / 4))

/** Judge ONE program — resumably: its verdict persists in `gate/<NAME>.json`, fingerprinted over the
  * source, spec, feature, and rubric it judged. Unchanged content reuses the stored verdict with NO
  * LLM call, so a crash, quota death, or auto-resume re-entry re-judges only what actually changed.
  * Delete a `gate/<NAME>.json` (or the whole `gate/` dir) to force a re-judge.
  */
def judgeProgram(pack: Pack, judge: Evaluator[Sample], rel: String)(using ctx: FlowContext): IO[FlowError, (String, ReviewResult)] =
  val name   = programName(rel)
  val modDir = workDir.resolve(ModDir)
  for
    spec    <- readFileOr(modDir.resolve("specs").resolve(s"$name.md"), "")
    feature <- readFileOr(modDir.resolve("features").resolve(s"${name.toLowerCase}.feature"), "")
    source  <- readFileOr(workDir.resolve(rel), "")
    rubric   = pack.judgeDimensions.map(d => s"${d.name} (0..${d.maxScore}): ${d.rubric}").mkString("\n")
    result  <- ReviewCache.cached(
                 modDir.resolve("gate").resolve(s"$name.json"),
                 ReviewCache.fingerprint(source, spec, feature, rubric),
               ) {
                 ctx.events.publish(FlowEvent.Info(s"judging $name")) *>
                   judgeWithShrink(judge, Sample(response = s"$spec\n\n$feature", context = Some(source), query = Some(userPrompt)))
                     .map(judgeIssues(_, pack.judgeDimensions, name))
               }
  yield name -> result

/** One evaluation of the spec pack: indexes rebuilt, deterministic SpecChecks, then the per-program
  * judge over the verdict cache — every program is consulted every round, but only programs whose
  * files changed since their last verdict cost a judge call. An untouched dirty program keeps its
  * stored findings ("you didn't change the files, the findings stand") instead of a fresh roll of
  * the dice.
  */
def gateEvaluate(
  pack: Pack,
  judge: Evaluator[Sample],
  programs: List[String],
)(using ctx: FlowContext
): IO[FlowError, ReviewResult] =
  val modDir = workDir.resolve(ModDir)
  for
    _        <- rebuildIndexes(modDir)
    trace    <- readFileOr(modDir.resolve("traceability.md"), "")
    coverage <- SpecChecks.coverage(workDir, pack.coverage, trace)
    features <- SpecChecks.features(modDir.resolve("features"))
    docs     <- requiredDocs(modDir)
    judged   <- ZIO.foreach(programs)(rel => judgeProgram(pack, judge, rel))
  yield Reviewers.merge(coverage :: features :: docs :: judged.map(_._2))

/** `judge[ACCTXFR]: …` → `ACCTXFR` — the program a gate finding belongs to, when it names one. */
val JudgeIssueProgram = """judge\[([^\]]+)\]: .*""".r

def issueProgram(issue: ReviewIssue): Option[String] = issue.title match
  case JudgeIssueProgram(name) => Some(name)
  case _                       => None

def issueLines(issues: List[ReviewIssue]): String =
  issues.map(i => s"- [${i.severity}] ${i.title}: ${i.description}").mkString("\n")

def programFixAsk(name: String, rel: String, issues: List[ReviewIssue]): String =
  s"""The spec pack for ONE program did not clear its quality gate: $name (source: $rel).
     |Fix these findings by editing ONLY this program's files — $ModDir/specs/$name.md,
     |$ModDir/features/${name.toLowerCase}.feature, $ModDir/traceability/$name.md,
     |$ModDir/mapping/$name.md — against the source at $rel. Then stop:
     |${issueLines(issues)}""".stripMargin

def globalFixAsk(issues: List[ReviewIssue]): String =
  s"""The spec pack did not clear its estate-wide quality gate. Fix these findings by editing the
     |per-program files under $ModDir/ (specs/, features/, traceability/<PROGRAM>.md,
     |mapping/<PROGRAM>.md). $ModDir/traceability.md and $ModDir/mapping.md are REGENERATED from the
     |fragments — do not edit them directly. Fix the findings in place, then stop:
     |${issueLines(issues)}""".stripMargin

/** Fix turns mirror the judge: one bounded turn per sub-bar program (fresh chat, own commit — a crash
  * mid-round keeps the programs already fixed), plus a single residual turn for estate-wide findings
  * (coverage gaps, malformed features, missing indexes). Editing a program's files changes its
  * fingerprint, so exactly the programs touched here get re-judged next round.
  */
def fixOnce(pack: Pack, system: String, programs: List[String])(result: ReviewResult)(using ctx: FlowContext): IO[FlowError, Unit] =
  val recover: PartialFunction[FlowError, IO[FlowError, Unit]] =
    case FlowError.Llm(_, Some(_: LlmError.TurnLimitError)) =>
      ctx.events.publish(FlowEvent.Info("turn limit hit during a fix turn — re-evaluating what was written"))
  val relOf            = programs.map(rel => programName(rel) -> rel).toMap
  val (scoped, global) = result.issues.partition(i => issueProgram(i).exists(relOf.contains))
  val byProgram        = scoped.groupBy(i => issueProgram(i).getOrElse("")).toList.sortBy(_._1)
  def turn(ask: String, commitMsg: String): IO[FlowError, Unit] =
    for
      chat <- Chat.start(coder, system = Some(system))
      _    <- chat.ask(ask).unit.catchSome(recover)
      _    <- git.commitAll(commitMsg).unit
    yield ()
  for
    _ <- ZIO.foreachDiscard(byProgram) { (name, issues) =>
           ctx.events.publish(FlowEvent.Info(s"fixing $name — ${issues.size} finding(s)")) *>
             turn(programFixAsk(name, relOf(name), issues), s"modernize(${pack.name}): gate fixes $name")
         }
    _ <- ZIO.when(global.nonEmpty)(
           ctx.events.publish(FlowEvent.Info(s"fixing estate-wide findings — ${global.size}")) *>
             turn(globalFixAsk(global), s"modernize(${pack.name}): gate fixes (estate-wide)")
         )
  yield ()

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
     |- traceability.md — source-unit → spec coverage matrix (generated from traceability/)
     |- mapping.md — data & interface mapping (generated from mapping/)
     |- gate/ — cached per-program judge verdicts (delete to force re-judging)
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
    pack     <- stage("Pack")(Pack.load(packDir))
    _        <- stage("Branch")(git.checkoutOrCreate("modernize/spec-pack"))
    system    = List(
                  pack.prompt("analysis"),
                  pack.lessons.map(l => s"Lessons from previous modernization runs — apply them:\n$l"),
                ).flatten.mkString("\n\n")
    judge     = Judge.of(reasoning, pack.judgeDimensions)
    programs <- stage("Inventory")(
                  SpecChecks.matchingFiles(workDir, pack.programs.orElse(pack.sources).getOrElse(""".*"""))
                )
    _        <- ZIO.when(programs.isEmpty)(
                  fail(s"no source units matched the pack's programs/sources regex under $workDir")
                )
    _        <- stage("Extract")(extractPrograms(pack, system, programs))
    // Commit the (ungated) draft: extraction is the expensive step, and a gate failure or crash must
    // not cost it. The Gate/Commit stages amend the picture with the verdict afterwards.
    _        <- stage("Draft")(
                  rebuildIndexes(modDir) *> git.commitAll(s"modernize(${pack.name}): spec pack draft (ungated)").unit
                )
    result   <- stage("Gate")(fixLoop(gateEvaluate(pack, judge, programs), fixOnce(pack, system, programs), MaxRounds))
    _        <- ZIO.when(result.isClean)(stage("Plan") {
                  for
                    specText <- gatherSpecPack(modDir)
                    plan     <- Planner.from(
                                  reasoning,
                                  capText(specText, JudgeSourcesLimit),
                                  Planner.defaultInstructions + "\n\n" + pack.prompt("plan").getOrElse(""),
                                )
                    _        <- writeFile(modDir.resolve("plan.md"), plan.render)
                  yield ()
                })
    verdict   = if result.isClean then "PASSED — pending human approval" else s"DRAFT — ${result.issues.size} open issue(s)"
    _        <- stage("Commit")(
                  writeFile(modDir.resolve("README.md"), ApprovalGate.withDraftMarker(indexFor(pack, verdict))) *>
                    git.commitAll(s"modernize(${pack.name}): spec pack ($verdict)").unit
                )
    _        <- announceOrHalt(result)
  yield ModDir
