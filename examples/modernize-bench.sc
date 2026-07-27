//> using dep "io.github.riccardomerolla::llm4zio-runner:4.1.0"
//> using scala "3.8.3"
//> using jvm 21

/** Benchmark harness: the SAME modernization task (legacy-bank COBOL → Spring Boot) under one
  * tool/LLM per invocation, with every metric recorded to `bench-results.jsonl` — one
  * self-contained JSON line per run, aggregated later by bench-report.sc into a markdown
  * comparison. This is NOT the product pipeline: there is no human approval gate, and internal
  * quality gates never stop the run — they are measured, not obeyed.
  *
  *   - One invocation = one benchmark run (LLM4ZIO_BENCH_RUNS=N loops N runs in fresh subdirs).
  *   - The whole task runs in a fresh temp directory: `run-N/legacy` (fixture copy),
  *     `run-N/docs/modernization` (spec pack), `run-N/target` (scaffolded service).
  *   - Phases: Estate → Extract → Gate → Plan → Seed → Implement → Verify → Score → Record.
  *     A phase failure records `outcome: failed-<phase>` and jumps to Record — partial metrics
  *     are kept, the line is always written.
  *   - Internal loops (extraction gate, implement test-gate fixes) run all-in-provider: the
  *     tool's ability to satisfy its own judge is part of what is benchmarked. The FINAL quality
  *     scores default to the same provider (self-graded, marked non-comparable) unless
  *     LLM4ZIO_BENCH_JUDGE pins one fixed examiner for all runs.
  *
  * Tool selection and models (requested AND served are recorded):
  *   LLM4ZIO_CODER=gemini|claude|codex|pi|grok|cursor|opencode   (default gemini)
  *   defaults: gemini-2.5-pro / claude-opus-4-8 / gpt-5.5 / grok-4.5 — override with LLM4ZIO_BENCH_MODEL.
  *   Token honesty: cursor's stream reports no usage and opencode's carries no model id, so those seats
  *   show unpriced/absent tokens rather than estimates.
  *   LLM4ZIO_BENCH_JUDGE=provider[:model]   fixed examiner (e.g. gemini:gemini-2.5-pro)
  *   LLM4ZIO_BENCH_RUNS=N                   runs per invocation (default 1)
  *   LLM4ZIO_BENCH_PHASE_TIMEOUT=minutes    wall-clock bound per phase (default 60)
  *   LLM4ZIO_BENCH_DIR=<dir>                pin the bench root; point at a previous run's root to
  *                                          RESUME it — completed programs, cached gate verdicts,
  *                                          the plan, the seeded target, and completed implement
  *                                          tasks are skipped; the record is flagged `resumed ♻`
  *                                          (incremental time/tokens). Force redo by deleting the
  *                                          artifact (specs/<NAME>.md, gate/, plan.md,
  *                                          run-N/.bench-recorded).
  *   LLM4ZIO_BENCH_FIXTURE=<dir>            estate to convert (default fixtures/legacy-bank)
  *   LLM4ZIO_PACK=<dir>                     modernization pack (default packs/cobol-springboot)
  *   LLM4ZIO_RUN_LABEL=<key>                correlates runs across machines/providers
  *
  * Run:   scala-cli run modernize-bench.sc            (from the examples/ directory)
  * Then:  scala-cli run bench-report.sc -- bench-results.jsonl --project 500
  */

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, StandardOpenOption }

import scala.jdk.CollectionConverters.*

import zio.json.*
import zio.process.Command
import zio.{ Clock, Duration, IO, Ref, UIO, ZIO }

import llm4zio.core.{ CliConnectorConfig, LlmError }
import llm4zio.eval.*
import llm4zio.flow.*
import llm4zio.runner.*

val Llm4zioVersion = "4.1.0"
val MaxGateRounds  = 3
val ImplFixRounds  = 2
val AnalystTurns   = 48
val ReasonerTurns  = 16 // gemini judge/planner seat: a wedged plan-mode session gets cut, not ground

/** Wall-clock bound per phase (minutes): a provider that can neither finish nor fail — endless flaky
  * retries, "my job is done" text loops — records `failed-<phase>` instead of hanging the benchmark.
  */
val PhaseTimeout: Duration =
  Duration.fromSeconds(sys.env.get("LLM4ZIO_BENCH_PHASE_TIMEOUT").flatMap(_.toLongOption).getOrElse(60L) * 60)

// ── seats ─────────────────────────────────────────────────────────────────────────────────────

val Provider: String = sys.env.get("LLM4ZIO_CODER").map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("gemini")

def defaultModel(provider: String): Option[String] = provider match
  case "claude" => Some("claude-opus-4-8")
  case "codex"  => Some("gpt-5.5")
  case "gemini" => Some("gemini-2.5-pro")
  case "grok"   => Some("grok-4.5")
  case _        => None // cursor/opencode/pi run their user-configured default model

def presetFor(provider: String): CliConnectorConfig = provider match
  case "claude"   => claude
  case "codex"    => codex
  case "pi"       => pi
  case "grok"     => grok
  case "cursor"   => cursor
  case "opencode" => opencode
  case _          => gemini

def cfgFor(provider: String, model: Option[String]): CliConnectorConfig =
  val base   = presetFor(provider)
  val pinned = model.fold(base)(m => base.withModel(m))
  if provider == "gemini" then pinned.withTurnLimit(AnalystTurns) else pinned

val CoderModel: Option[String] = sys.env.get("LLM4ZIO_BENCH_MODEL").filter(_.nonEmpty).orElse(defaultModel(Provider))
val coderCfg                   = cfgFor(Provider, CoderModel)
val reasoningCfg               =
  coderCfg.copy(readOnly = true, turnLimit = if Provider == "gemini" then Some(ReasonerTurns) else None)

/** LLM4ZIO_BENCH_JUDGE=provider[:model] — the fixed examiner; absent = self-graded final scores. */
val FixedJudge: Option[(String, String)] =
  sys.env.get("LLM4ZIO_BENCH_JUDGE").map(_.trim).filter(_.nonEmpty).map { spec =>
    spec.split(":", 2) match
      case Array(p, m) => (p.toLowerCase, m)
      case _           => (spec.toLowerCase, defaultModel(spec.toLowerCase).getOrElse(""))
  }

val judgeIdentity: BenchJudge =
  FixedJudge match
    case Some((p, m)) => BenchJudge(p, m, fixed = true)
    case None         => BenchJudge(Provider, CoderModel.getOrElse("(tool default)"), fixed = false)

val examinerCfg: List[llm4zio.core.ConnectorConfig] =
  FixedJudge.map((p, m) => cfgFor(p, Some(m).filter(_.nonEmpty)).copy(readOnly = true, turnLimit = None)).toList

// ── plain-file helpers ────────────────────────────────────────────────────────────────────────

def fileExists(path: Path): UIO[Boolean] = ZIO.attemptBlocking(Files.exists(path)).orDie
def dirExists(path: Path): UIO[Boolean]  = ZIO.attemptBlocking(Files.isDirectory(path)).orDie

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

def appendLine(path: Path, line: String): IO[FlowError, Unit] =
  ZIO
    .attemptBlocking {
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.write(path, s"$line\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      ()
    }
    .mapError(e => FlowError.Persistence(s"failed to append to $path", Some(e)))

def copyTree(src: Path, dst: Path, skip: Set[String] = Set(".git", "target", "node_modules")): IO[FlowError, Int] =
  ZIO
    .attemptBlocking {
      val stream = Files.walk(src)
      val files  =
        try stream.iterator().asScala.filter(Files.isRegularFile(_)).toList
        finally stream.close()
      val kept   = files.filterNot(f => src.relativize(f).iterator().asScala.exists(p => skip(p.toString)))
      kept.foreach { f =>
        val to = dst.resolve(src.relativize(f).toString)
        Option(to.getParent).foreach(Files.createDirectories(_))
        Files.copy(f, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      }
      kept.size
    }
    .mapError(e => FlowError.Persistence(s"failed to copy $src -> $dst", Some(e)))

def copyFile(src: Path, dst: Path): IO[FlowError, Boolean] =
  ZIO
    .attemptBlocking {
      if !Files.exists(src) then false
      else
        Option(dst.getParent).foreach(Files.createDirectories(_))
        Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        true
    }
    .mapError(e => FlowError.Persistence(s"failed to copy $src -> $dst", Some(e)))

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

val JudgeSourcesLimit: Int =
  sys.env.get("LLM4ZIO_JUDGE_SOURCES_LIMIT").flatMap(_.toIntOption).getOrElse(400_000)

def capText(text: String, limit: Int): String =
  if text.length <= limit then text
  else
    val head = limit * 3 / 4
    s"${text.take(head)}\n\n… [truncated] …\n\n${text.takeRight(limit - head)}"

def programName(rel: String): String =
  val base = rel.substring(rel.lastIndexOf('/') + 1)
  val dot  = base.lastIndexOf('.')
  if dot > 0 then base.take(dot) else base

// ── extraction (per program, prefixed into the run's subdir) ─────────────────────────────────

def programAsk(pack: Pack, rootRel: String, rel: String, name: String): String =
  val modDir = s"$rootRel/docs/modernization"
  s"""Extract the behavioural spec for ONE source unit of this repository: $rootRel/legacy/$rel
     |
     |Write exactly these files (create directories as needed):
     |
     |- $modDir/specs/$name.md — the behavioural spec.
     |${pack.prompt("spec").getOrElse("")}
     |
     |- $modDir/features/${name.toLowerCase}.feature — BDD scenarios encoding that spec.
     |${pack.prompt("bdd").getOrElse("")}
     |
     |- $modDir/traceability/$name.md — EVERY source unit (each COBOL paragraph, each JCL step) on
     |  its own line, mapped to the spec rules/scenarios covering it: `<UNIT-NAME> — <refs>`.
     |
     |- $modDir/mapping/$name.md — data & interface mapping: record layouts → entities,
     |  files/screens → service contracts.
     |
     |Read anything the unit references for context, but spec ONLY it and do not modify legacy
     |sources. Write the four files, then stop.""".stripMargin

def turnLimitRecovery(marker: Path, what: String)(using ctx: FlowContext): PartialFunction[FlowError, IO[FlowError, Unit]] =
  case e @ FlowError.Llm(_, Some(_: LlmError.TurnLimitError)) =>
    ZIO.ifZIO(fileExists(marker))(
      ctx.events.publish(FlowEvent.Info(s"turn limit hit on $what after its output was written — keeping the work")),
      ZIO.fail(e),
    )

def extractPrograms(pack: Pack, system: String, rootRel: String, modDir: Path, programs: List[String])(using ctx: FlowContext): IO[FlowError, Unit] =
  ZIO.foreachDiscard(programs.zipWithIndex) { (rel, i) =>
    val name = programName(rel)
    val spec = modDir.resolve("specs").resolve(s"$name.md")
    ZIO.ifZIO(fileExists(spec))(
      ctx.events.publish(FlowEvent.Info(s"resume: specs/$name.md exists — skipping $rel (${i + 1}/${programs.size})")),
      for
        _    <- ctx.events.publish(FlowEvent.Info(s"extracting $rel (${i + 1}/${programs.size})"))
        chat <- Chat.start(coder, system = Some(system))
        _    <- chat.ask(programAsk(pack, rootRel, rel, name)).unit.catchSome(turnLimitRecovery(spec, rel))
        _    <- git.commitAll(s"bench(${pack.name}): spec $name").unit
      yield (),
    )
  }

def rebuildIndexes(modDir: Path): IO[FlowError, Unit] =
  def rebuild(fragmentDir: Path, index: Path): IO[FlowError, Unit] =
    ZIO.whenZIO(dirExists(fragmentDir)) {
      gatherSources(fragmentDir, """.*\.md""").flatMap(text => ZIO.when(text.nonEmpty)(writeFile(index, text)))
    }.unit
  rebuild(modDir.resolve("traceability"), modDir.resolve("traceability.md")) *>
    rebuild(modDir.resolve("mapping"), modDir.resolve("mapping.md"))

def requiredDocs(modDir: Path): IO[FlowError, ReviewResult] =
  for
    trace <- readFileOr(modDir.resolve("traceability.md"), "")
    map   <- readFileOr(modDir.resolve("mapping.md"), "")
  yield
    val issues = List(
      Option.when(trace.isBlank)(ReviewIssue(Severity.Critical, "missing traceability", "no traceability fragments")),
      Option.when(map.isBlank)(ReviewIssue(Severity.Critical, "missing mapping", "no mapping fragments")),
    ).flatten
    ReviewResult(issues, "docs")

def judgeIssues(scored: EvalResult, dims: List[Dimension], program: String): ReviewResult =
  val subBar = scored.scores.filter(s => s.score < dims.find(_.name == s.name).fold(2)(_.maxScore))
  ReviewResult(
    subBar.map(s => ReviewIssue(Severity.Critical, s"judge[$program]: ${s.name} scored ${s.score}", s.reasoning)),
    s"judge:$program",
  )

def isEmptyResponse(e: FlowError): Boolean = e match
  case FlowError.Llm(message, _) => message.contains("empty response")
  case _                         => false

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

def judgeProgram(pack: Pack, judge: Evaluator[Sample], legacyDir: Path, modDir: Path, rel: String)(using ctx: FlowContext): IO[FlowError, (String, ReviewResult)] =
  val name = programName(rel)
  for
    spec    <- readFileOr(modDir.resolve("specs").resolve(s"$name.md"), "")
    feature <- readFileOr(modDir.resolve("features").resolve(s"${name.toLowerCase}.feature"), "")
    source  <- readFileOr(legacyDir.resolve(rel), "")
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

def gateEvaluate(pack: Pack, judge: Evaluator[Sample], legacyDir: Path, modDir: Path, programs: List[String])(using ctx: FlowContext): IO[FlowError, ReviewResult] =
  for
    _        <- rebuildIndexes(modDir)
    trace    <- readFileOr(modDir.resolve("traceability.md"), "")
    coverage <- SpecChecks.coverage(legacyDir, pack.coverage, trace)
    features <- SpecChecks.features(modDir.resolve("features"))
    docs     <- requiredDocs(modDir)
    judged   <- ZIO.foreach(programs)(rel => judgeProgram(pack, judge, legacyDir, modDir, rel))
  yield Reviewers.merge(coverage :: features :: docs :: judged.map(_._2))

val JudgeIssueProgram = """judge\[([^\]]+)\]: .*""".r

def issueProgram(issue: ReviewIssue): Option[String] = issue.title match
  case JudgeIssueProgram(name) => Some(name)
  case _                       => None

def issueLines(issues: List[ReviewIssue]): String =
  issues.map(i => s"- [${i.severity}] ${i.title}: ${i.description}").mkString("\n")

def gateFixOnce(pack: Pack, system: String, rootRel: String, programs: List[String], rounds: Ref[Int])(result: ReviewResult)(using ctx: FlowContext): IO[FlowError, Unit] =
  val modDir           = s"$rootRel/docs/modernization"
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
  def programAskFix(name: String, issues: List[ReviewIssue]): String =
    s"""The spec pack for ONE program did not clear its quality gate: $name (source:
       |$rootRel/legacy/${relOf(name)}). Fix these findings by editing ONLY this program's files —
       |$modDir/specs/$name.md, $modDir/features/${name.toLowerCase}.feature,
       |$modDir/traceability/$name.md, $modDir/mapping/$name.md. Then stop:
       |${issueLines(issues)}""".stripMargin
  def globalAskFix(issues: List[ReviewIssue]): String =
    s"""The spec pack did not clear its estate-wide quality gate. Fix these findings by editing the
       |per-program files under $modDir/ (traceability.md and mapping.md are REGENERATED from the
       |fragments — do not edit them directly). Then stop:
       |${issueLines(issues)}""".stripMargin
  for
    _ <- rounds.update(_ + 1)
    _ <- ZIO.foreachDiscard(byProgram)((name, issues) => turn(programAskFix(name, issues), s"bench: gate fixes $name"))
    _ <- ZIO.when(global.nonEmpty)(turn(globalAskFix(global), "bench: gate fixes (estate-wide)"))
  yield ()

// ── quality measurement ───────────────────────────────────────────────────────────────────────

def countFiles(root: Path, regex: String): UIO[(Int, Long)] =
  ZIO.attemptBlocking {
    if !Files.isDirectory(root) then (0, 0L)
    else
      val stream = Files.walk(root)
      val files  =
        try stream.iterator().asScala.filter(Files.isRegularFile(_)).toList
        finally stream.close()
      val kept   = files.filter(f => root.relativize(f).toString.replace('\\', '/').matches(regex))
      (kept.size, kept.map(f => Files.readAllLines(f).size.toLong).sum)
  }.orElseSucceed((0, 0L))

def surefireCounts(targetDir: Path): UIO[Option[(Int, Int)]] =
  ZIO.attemptBlocking {
    val reports = targetDir.resolve("target").resolve("surefire-reports")
    if !Files.isDirectory(reports) then None
    else
      val stream = Files.list(reports)
      val xmls   =
        try stream.iterator().asScala.filter(_.getFileName.toString.endsWith(".xml")).toList
        finally stream.close()
      val attr   = (text: String, name: String) => s"""$name="(\\d+)"""".r.findFirstMatchIn(text).map(_.group(1).toInt)
      val counts = xmls.map { f =>
        val text = Files.readString(f)
        (attr(text, "tests").getOrElse(0), attr(text, "failures").getOrElse(0) + attr(text, "errors").getOrElse(0))
      }
      Option.when(counts.nonEmpty)((counts.map(_._1).sum, counts.map(_._2).sum))
  }.orElseSucceed(None)

def coveragePct(pack: Pack, legacyDir: Path, modDir: Path): IO[FlowError, Option[Double]] =
  for
    units <- SpecChecks.coverageUnits(legacyDir, pack.coverage)
    trace <- readFileOr(modDir.resolve("traceability.md"), "")
  yield
    val all = units.values.flatten.toList
    Option.when(all.nonEmpty)(all.count(trace.contains).toDouble / all.size * 100)

def scenarioCounts(modDir: Path): UIO[(Int, Int)] =
  ZIO.attemptBlocking {
    val dir = modDir.resolve("features")
    if !Files.isDirectory(dir) then (0, 0)
    else
      val stream = Files.walk(dir)
      val files  =
        try stream.iterator().asScala.filter(_.getFileName.toString.endsWith(".feature")).toList
        finally stream.close()
      val scenarios = files.map { f =>
        Files.readAllLines(f).asScala.count(l => l.trim.startsWith("Scenario:") || l.trim.startsWith("Scenario Outline:"))
      }.sum
      (files.size, scenarios)
  }.orElseSucceed((0, 0))

val complianceDims = List(
  Dimension(
    "spec-compliance",
    "Does the implementation satisfy every rule in the specs — exact values, validation order, error paths?",
  ),
  Dimension(
    "scenario-coverage",
    "Is every BDD scenario in the feature files exercised by an acceptance test in the implementation?",
  ),
)

/** Final examiner scores: per-program spec quality on the pack rubric, plus code compliance — always fresh
  * (never cached): these are the run's comparable output, produced by `judgeIdentity`.
  */
def finalScores(pack: Pack, examiner: llm4zio.core.LlmService, legacyDir: Path, modDir: Path, targetDir: Path, programs: List[String])(using ctx: FlowContext): IO[FlowError, List[BenchScore]] =
  val specJudge = Judge.of(examiner, pack.judgeDimensions)
  val codeJudge = Judge.of(examiner, complianceDims)
  for
    perProgram <- ZIO.foreach(programs) { rel =>
                    val name = programName(rel)
                    for
                      spec    <- readFileOr(modDir.resolve("specs").resolve(s"$name.md"), "")
                      feature <- readFileOr(modDir.resolve("features").resolve(s"${name.toLowerCase}.feature"), "")
                      source  <- readFileOr(legacyDir.resolve(rel), "")
                      scored  <- judgeWithShrink(specJudge, Sample(response = s"$spec\n\n$feature", context = Some(source), query = Some(userPrompt)))
                    yield scored
                  }
    specScores  = pack.judgeDimensions.map { d =>
                    val perDim = perProgram.flatMap(_.scores.filter(_.name == d.name))
                    BenchScore(s"spec:${d.name}", perDim.map(_.score).sum, d.maxScore * programs.size)
                  }
    code       <- gatherSources(targetDir.resolve("src"), """.*\.(java|kt|scala)""")
    specs      <- gatherSources(modDir, """.*\.(md|feature)""")
    codeScored <- judgeWithShrink(codeJudge, Sample(response = code, context = Some(specs), query = Some(userPrompt)))
    codeScores  = codeScored.scores.map(s => BenchScore(s"code:${s.name}", s.score, 2, s.reasoning.take(200)))
  yield specScores ++ codeScores

// ── machine / provenance ──────────────────────────────────────────────────────────────────────

def machineInfo: BenchMachine =
  val hostname = try java.net.InetAddress.getLocalHost.getHostName
  catch case _: Throwable => "unknown"
  val memoryGb =
    try
      java.lang.management.ManagementFactory.getOperatingSystemMXBean match
        case os: com.sun.management.OperatingSystemMXBean => os.getTotalMemorySize / 1e9
        case _                                            => Runtime.getRuntime.maxMemory / 1e9
    catch case _: Throwable => 0.0
  BenchMachine(
    hostname = hostname,
    os = s"${sys.props.getOrElse("os.name", "?")} ${sys.props.getOrElse("os.version", "")}".trim,
    arch = sys.props.getOrElse("os.arch", "?"),
    cores = Runtime.getRuntime.availableProcessors,
    memoryGb = math.round(memoryGb * 10) / 10.0,
    jvm = sys.props.getOrElse("java.version", "?"),
  )

def toolVersion(provider: String): UIO[Option[String]] =
  val bin = provider match
    case "claude"   => "claude"
    case "codex"    => "codex"
    case "pi"       => "pi"
    case "grok"     => "grok"
    case "cursor"   => "cursor-agent"
    case "opencode" => "opencode"
    case _          => "gemini"
  Command(bin, "--version").string
    .map(out => Some(out.trim.linesIterator.nextOption.getOrElse("").take(60)).filter(_.nonEmpty))
    .catchAll(_ => ZIO.none)

// ── the bench root (created before the flow so the context roots git/coder inside it) ─────────
//
// Fresh temp dir by default (pristine benchmark semantics). LLM4ZIO_BENCH_DIR pins the root instead:
// point it at a previous run's directory to RESUME after a crash/timeout — completed programs, cached
// gate verdicts, the plan, the seeded target, and completed implement tasks are all skipped; the run
// continues from wherever it died, and its record is flagged `resumed` (incremental time/tokens).

val benchRoot: Path =
  sys.env.get("LLM4ZIO_BENCH_DIR").map(_.trim).filter(_.nonEmpty) match
    case Some(dir) => Files.createDirectories(Path.of(dir).toAbsolutePath.normalize)
    case None      => Files.createTempDirectory("llm4zio-bench-").toAbsolutePath.normalize

flow(
  Array("--repo", benchRoot.toString) ++ args,
  coder = coderCfg,
  reasoning = Some(reasoningCfg),
  reviewers = examinerCfg,
  defaultPrompt = Some("Benchmark: convert the legacy estate into a Spring Boot service"),
):
  val packDir    = workspace.resolve(sys.env.getOrElse("LLM4ZIO_PACK", "packs/cobol-springboot"))
  val fixtureDir = workspace.resolve(sys.env.getOrElse("LLM4ZIO_BENCH_FIXTURE", "fixtures/legacy-bank"))
  val runs       = sys.env.get("LLM4ZIO_BENCH_RUNS").flatMap(_.toIntOption).filter(_ > 0).getOrElse(1)
  val resultsOut = workspace.resolve("bench-results.jsonl")
  val examiner   = reviewers.headOption.getOrElse(reasoning)
  val events     = summon[FlowEvents]

  def benchPhase(name: String, tap: Bench.Tap, phases: Ref[List[BenchPhase]], failed: Ref[Option[String]])(
    eff: IO[FlowError, Map[String, String]]
  ): UIO[Unit] =
    ZIO.unlessZIO(failed.get.map(_.isDefined)) {
      val bounded = eff.timeoutFail(
        FlowError.Aborted(s"phase timed out after ${PhaseTimeout.toMinutes}m (LLM4ZIO_BENCH_PHASE_TIMEOUT)")
      )(PhaseTimeout)
      stage(name)(bounded).either.timed.flatMap { (elapsed, res) =>
        val detail = res.toOption.getOrElse(Map.empty) ++ res.left.toOption.map(e => "error" -> e.message.take(300))
        for
          o <- tap.get // drains in-flight events, so the phase's trailing tokens are counted
          _ <- phases.update(_ :+ Bench.phase(o, name, elapsed.toMillis, detail))
          _ <- res.fold(
                 e =>
                   failed.set(Some(name)) *>
                     events.publish(FlowEvent.Info(s"phase $name failed — skipping to Record: ${e.message.take(200)}")),
                 _ => ZIO.unit,
               )
        yield ()
      }
    }.unit

  def oneRun(tap: Bench.Tap, i: Int): IO[FlowError, Unit] =
    val rootRel   = s"run-$i"
    val root      = workDir.resolve(rootRel)
    val legacyDir = root.resolve("legacy")
    val modDir    = root.resolve("docs").resolve("modernization")
    val targetDir = root.resolve("target")
    for
      _           <- tap.reset
      resumedRun  <- dirExists(root)
      startedAt   <- Clock.instant
      runId       <- FlowTrace.runId.map(id => s"$id-r$i")
      phasesRef   <- Ref.make(List.empty[BenchPhase])
      failedRef   <- Ref.make(Option.empty[String])
      pack        <- Pack.load(packDir)
      fingerprint <- gatherSources(fixtureDir, pack.sources.getOrElse(""".*""")).map(ReviewCache.fingerprint(_))
      programsRef <- Ref.make(List.empty[String])
      gateRef     <- Ref.make(Option.empty[ReviewResult])
      roundsRef   <- Ref.make(0)
      qualityRef  <- Ref.make(Option.empty[BenchQuality])
      analystSys   = List(
                       pack.prompt("analysis"),
                       Some(s"All paths are relative to the repository root; the legacy estate is under $rootRel/legacy " +
                         s"and every artifact you write goes under $rootRel/docs/modernization."),
                     ).flatten.mkString("\n\n")
      implementSys = List(
                       pack.prompt("implement"),
                       Some(s"The service under construction lives at $rootRel/target — work ONLY there. Specs and " +
                         s"features are already seeded inside it."),
                     ).flatten.mkString("\n\n")

      _ <- benchPhase("Estate", tap, phasesRef, failedRef) {
             ZIO.ifZIO(dirExists(legacyDir))(
               // Resuming: same task only — a different estate under the same root would corrupt the comparison.
               for
                 existing <- gatherSources(legacyDir, pack.sources.getOrElse(""".*""")).map(ReviewCache.fingerprint(_))
                 _        <- ZIO.when(existing != fingerprint)(ZIO.fail(FlowError.Aborted(
                               s"$rootRel/legacy holds a DIFFERENT estate than the fixture — refusing to resume into it"
                             )))
                 _        <- events.publish(FlowEvent.Info(s"resume: estate already in place at $rootRel/legacy"))
               yield Map("resumed" -> "true"),
               for
                 n <- copyTree(fixtureDir, legacyDir)
                 _ <- git.commitAll(s"bench: baseline estate ($n files)").unit
               yield Map("files" -> n.toString),
             )
           }
      _ <- benchPhase("Extract", tap, phasesRef, failedRef) {
             for
               programs <- SpecChecks.matchingFiles(legacyDir, pack.programs.orElse(pack.sources).getOrElse(""".*"""))
               _        <- ZIO.when(programs.isEmpty)(ZIO.fail(FlowError.Aborted("no programs matched the pack regex")))
               _        <- programsRef.set(programs)
               _        <- extractPrograms(pack, analystSys, rootRel, modDir, programs)
             yield Map("programs" -> programs.size.toString)
           }
      _ <- benchPhase("Gate", tap, phasesRef, failedRef) {
             for
               programs <- programsRef.get
               result   <- fixLoop(
                             gateEvaluate(pack, Judge.of(reasoning, pack.judgeDimensions), legacyDir, modDir, programs),
                             gateFixOnce(pack, analystSys, rootRel, programs, roundsRef),
                             MaxGateRounds,
                           )
               _        <- gateRef.set(Some(result))
               _        <- git.commitAll("bench: spec pack (gated)").unit
             yield Map("cleared" -> result.isClean.toString, "openIssues" -> result.issues.size.toString)
           }
      _ <- benchPhase("Plan", tap, phasesRef, failedRef) {
             ZIO.ifZIO(fileExists(modDir.resolve("plan.md")))(
               events.publish(FlowEvent.Info("resume: plan.md exists — skipping planning (delete it to re-plan)"))
                 .as(Map("resumed" -> "true")),
               for
                 specText <- gatherSources(modDir, """.*\.(md|feature)""")
                 plan     <- Planner.from(
                               reasoning,
                               capText(specText, JudgeSourcesLimit),
                               Planner.defaultInstructions + "\n\n" + pack.prompt("plan").getOrElse(""),
                             )
                 _        <- writeFile(modDir.resolve("plan.md"), plan.render)
               yield Map("tasks" -> plan.tasks.size.toString),
             )
           }
      _ <- benchPhase("Seed", tap, phasesRef, failedRef) {
             // The seeded plan is the implement loop's progress ledger — re-copying the draft over it would
             // UNCHECK completed tasks and redo them. A present target plan means "already seeded": skip.
             ZIO.ifZIO(fileExists(targetDir.resolve("docs/modernization/plan.md")))(
               events.publish(FlowEvent.Info("resume: target already seeded — skipping"))
                 .as(Map("resumed" -> "true")),
               {
                 val scaffold = pack.scaffold.map(rel => pack.dir.resolve(rel).normalize)
                 for
                   _  <- ZIO.foreachDiscard(scaffold.toList)(copyTree(_, targetDir).unit)
                   n1 <- copyTree(modDir.resolve("specs"), targetDir.resolve(pack.specsDir))
                   n2 <- copyTree(modDir.resolve("features"), targetDir.resolve(pack.featuresDir))
                   _  <- ZIO.foreachDiscard(List("traceability.md", "mapping.md"))(f =>
                           copyFile(modDir.resolve(f), targetDir.resolve(pack.specsDir).resolve(f)).unit
                         )
                   _  <- copyFile(modDir.resolve("plan.md"), targetDir.resolve("docs/modernization/plan.md"))
                   _  <- git.commitAll("bench: seed target").unit
                 yield Map("files" -> (n1 + n2).toString)
               },
             )
           }
      _ <- benchPhase("Implement", tap, phasesRef, failedRef) {
             val planFile = targetDir.resolve("docs/modernization/plan.md")
             val testGate = pack.gate("test").fold(ZIO.succeed(ReviewResult(Nil)))(Reviewers.lintCommand(_, targetDir))
             def fixAsk(r: ReviewResult): String =
               s"""The gate command failed for the service under $rootRel/target. Fix it there, then stop:
                  |${issueLines(r.issues)}""".stripMargin
             def implementTask(plan: Plan)(task: Task): IO[FlowError, Unit] =
               for
                 chat <- Chat.start(coder, system = Some(implementSys))
                 _    <- chat.ask(plan.taskPrompt(task)).unit
                           .catchSome(turnLimitRecovery(targetDir.resolve("src"), task.title))
                 _    <- fixLoop(
                           testGate,
                           r => Chat.start(coder, system = Some(implementSys)).flatMap(_.ask(fixAsk(r)).unit),
                           ImplFixRounds,
                         )
                 _    <- git.commitAll(s"bench: ${task.title}").unit
               yield ()
             for
               plan  <- PlanStore.load(planFile).someOrFail(FlowError.Aborted(s"no plan at $planFile"))
               _     <- implementTaskLoop(planFile, plan)(implementTask(plan))
               after <- PlanStore.load(planFile)
               // Task completion is persisted into the SEEDED plan (the loop's source of truth); sync it back to
               // the draft under docs/modernization so both copies agree when a human inspects the run dir.
               _     <- copyFile(planFile, modDir.resolve("plan.md"))
             yield Map("tasks" -> plan.tasks.size.toString, "completed" -> after.fold("0")(_.tasks.count(_.completed).toString))
           }
      _ <- benchPhase("Verify", tap, phasesRef, failedRef) {
             val verGate = pack.gate("verify").orElse(pack.gate("test"))
                             .fold(ZIO.succeed(ReviewResult(Nil)))(Reviewers.lintCommand(_, targetDir))
             for
               result <- verGate
               tests  <- surefireCounts(targetDir)
               _      <- qualityRef.update(q =>
                           Some(q.getOrElse(BenchQuality()).copy(
                             buildPassed = Some(result.isClean),
                             testsRun = tests.map(_._1),
                             testsFailed = tests.map(_._2),
                           ))
                         )
             yield Map("passed" -> result.isClean.toString)
           }
      _ <- benchPhase("Score", tap, phasesRef, failedRef) {
             for
               programs        <- programsRef.get
               gate            <- gateRef.get
               rounds          <- roundsRef.get
               cov             <- coveragePct(pack, legacyDir, modDir)
               (feats, scens)  <- scenarioCounts(modDir)
               malformed       <- SpecChecks.features(modDir.resolve("features")).map(_.issues.size).catchAll(_ => ZIO.succeed(0))
               (specN, _)      <- countFiles(modDir.resolve("specs"), """.*\.md""")
               (mainN, mainL)  <- countFiles(targetDir.resolve("src/main"), """.*\.java""")
               (testN, testL)  <- countFiles(targetDir.resolve("src/test"), """.*\.java""")
               scores          <- finalScores(pack, examiner, legacyDir, modDir, targetDir, programs)
               judgeFound       = gate.fold(0)(_.issues.count(i => issueProgram(i).isDefined))
               deterministic    = gate.fold(0)(_.issues.size) - judgeFound
               _               <- qualityRef.update(q =>
                                    Some(q.getOrElse(BenchQuality()).copy(
                                      coveragePct = cov,
                                      featureFiles = feats,
                                      scenarios = scens,
                                      malformedFeatures = malformed,
                                      specFiles = specN,
                                      programs = programs.size,
                                      javaFiles = mainN,
                                      javaLines = mainL,
                                      testFiles = testN,
                                      testLines = testL,
                                      deterministicFindings = deterministic,
                                      judgeFindings = judgeFound,
                                      gateCleared = gate.map(_.isClean),
                                      gateRounds = Some(rounds + 1),
                                      gateOpenIssues = gate.map(_.issues.size),
                                      judgeScores = scores,
                                    ))
                                  )
             yield Map("scores" -> scores.size.toString)
           }

      // Record ALWAYS runs — a failed run is a data point, not a lost one.
      _ <- stage("Record") {
             for
               finishedAt <- Clock.instant
               phases     <- phasesRef.get
               failed     <- failedRef.get
               obs        <- tap.get
               quality    <- qualityRef.get
               tool       <- toolVersion(Provider)
               record      = BenchRecord(
                               schema = BenchRecord.CurrentSchema,
                               runId = runId,
                               label = sys.env.get("LLM4ZIO_RUN_LABEL").filter(_.nonEmpty),
                               startedAt = startedAt.toString,
                               finishedAt = finishedAt.toString,
                               provider = Provider,
                               modelRequested = CoderModel.getOrElse("(tool default)"),
                               modelsServed = obs.modelsServed.toList.sorted,
                               judge = judgeIdentity,
                               toolVersion = tool,
                               llm4zioVersion = Llm4zioVersion,
                               pack = pack.name,
                               fixtureFingerprint = fingerprint,
                               machine = machineInfo,
                               outcome = failed.fold("completed")(p => s"failed-$p"),
                               resumed = resumedRun,
                               totalMs = java.time.Duration.between(startedAt, finishedAt).toMillis,
                               phases = phases,
                               quality = quality,
                             )
               _          <- appendLine(resultsOut, record.toJson)
               _          <- writeFile(root.resolve(".bench-recorded"), record.runId)
               _          <- events.publish(FlowEvent.Info(s"bench record appended to $resultsOut (${record.outcome})"))
               _          <- ZIO.when(failed.isDefined)(events.publish(FlowEvent.Info(
                               s"run $i did not complete — resume it with LLM4ZIO_BENCH_DIR=$workDir " +
                                 s"(finished work is skipped; delete $rootRel/.bench-recorded first)"
                             )))
             yield ()
           }
    yield ()

  def runOrSkip(tap: Bench.Tap, i: Int): IO[FlowError, Unit] =
    // A run whose record already landed must not append a duplicate row on resume.
    ZIO.ifZIO(fileExists(workDir.resolve(s"run-$i").resolve(".bench-recorded")))(
      events.publish(FlowEvent.Info(s"run-$i already recorded — skipping (delete run-$i/.bench-recorded to redo)")),
      oneRun(tap, i),
    )

  ZIO.scoped {
    for
      tap    <- Bench.tap(events)
      _      <- events.publish(FlowEvent.Info(s"bench root: $workDir — resume later with LLM4ZIO_BENCH_DIR=$workDir"))
      _      <- stage("Repo")(git.init *> git.config("user.email", "bench@llm4zio") *> git.config("user.name", "llm4zio-bench"))
      _      <- ZIO.foreachDiscard(1 to runs)(i => runOrSkip(tap, i))
    yield s"bench-results.jsonl (${runs} run(s), provider=$Provider)"
  }
