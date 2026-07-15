package llm4zio.flow

import zio.*
import zio.json.*

import llm4zio.core.TokenUsage

/** Token totals for one benchmark phase. */
final case class BenchTokens(prompt: Long = 0, completion: Long = 0, cached: Long = 0) derives JsonCodec:
  def total: Long                       = prompt + completion
  def +(that: BenchTokens): BenchTokens =
    BenchTokens(prompt + that.prompt, completion + that.completion, cached + that.cached)

object BenchTokens:
  def of(usage: TokenUsage): BenchTokens =
    BenchTokens(usage.prompt.toLong, usage.completion.toLong, usage.cached.fold(0L)(_.toLong))

/** Robustness counters for one phase — the "self-healing actions" the harness performed. */
final case class BenchCounters(
  flakyRetries: Int = 0,
  transientRetries: Int = 0,
  autoResumes: Int = 0,
  turnLimitTrips: Int = 0,
  shrinks: Int = 0,
) derives JsonCodec:
  // Turn-limit trips are deliberately excluded: they bound a wedged agent rather than recover from a provider fault.
  def selfHealing: Int = flakyRetries + transientRetries + autoResumes + shrinks

/** One benchmark phase: duration, tokens, estimated cost, robustness, and phase-specific detail. */
final case class BenchPhase(
  name: String,
  ms: Long,
  tokens: BenchTokens,
  costUsd: Option[Double],
  counters: BenchCounters,
  detail: Map[String, String] = Map.empty,
) derives JsonCodec

/** One judged dimension score, prefixed by what was judged (e.g. `spec:completeness`, `code:spec-compliance`). */
final case class BenchScore(name: String, score: Int, max: Int, reasoning: String = "") derives JsonCodec

/** The examiner that produced the run's judged scores. `fixed` = pinned via env, constant across runs. */
final case class BenchJudge(provider: String, model: String, fixed: Boolean) derives JsonCodec

/** Where the run happened — wall-clock comparability context. */
final case class BenchMachine(
  hostname: String,
  os: String,
  arch: String,
  cores: Int,
  memoryGb: Double,
  jvm: String,
) derives JsonCodec

/** The comparable quality block: deterministic measurements plus judged scores. */
final case class BenchQuality(
  buildPassed: Option[Boolean] = None,
  testsRun: Option[Int] = None,
  testsFailed: Option[Int] = None,
  coveragePct: Option[Double] = None,
  featureFiles: Int = 0,
  scenarios: Int = 0,
  malformedFeatures: Int = 0,
  specFiles: Int = 0,
  programs: Int = 0,
  javaFiles: Int = 0,
  javaLines: Long = 0,
  testFiles: Int = 0,
  testLines: Long = 0,
  deterministicFindings: Int = 0,
  judgeFindings: Int = 0,
  gateCleared: Option[Boolean] = None,
  gateRounds: Option[Int] = None,
  gateOpenIssues: Option[Int] = None,
  judgeScores: List[BenchScore] = Nil,
) derives JsonCodec

/** One benchmark run — one self-contained line in `bench-results.jsonl`. Lines from different machines merge into one
  * comparison as long as `pack` + `fixtureFingerprint` match (the aggregator refuses to compare different tasks).
  */
final case class BenchRecord(
  schema: Int,
  runId: String,
  label: Option[String],
  startedAt: String,
  finishedAt: String,
  provider: String,
  modelRequested: String,
  modelsServed: List[String],
  judge: BenchJudge,
  toolVersion: Option[String],
  llm4zioVersion: String,
  pack: String,
  fixtureFingerprint: String,
  machine: BenchMachine,
  outcome: String,
  totalMs: Long,
  phases: List[BenchPhase],
  quality: Option[BenchQuality],
) derives JsonCodec:
  def completed: Boolean           = outcome == "completed"
  def totalTokens: Long            = phases.map(_.tokens.total).sum
  def totalCostUsd: Option[Double] =
    val costs = phases.flatMap(_.costUsd)
    Option.when(costs.nonEmpty)(costs.sum)
  def selfHealing: Int             = phases.map(_.counters.selfHealing).sum

object BenchRecord:
  val CurrentSchema: Int = 1

/** What the live event tap has seen so far: tokens per (stage × model), robustness counters per stage, served models.
  * Pure state — [[Bench.observe]] folds one [[FlowEvent]] at a time, so the accumulator is testable without any
  * concurrency.
  */
final case class BenchObservation(
  openStages: List[String] = Nil,
  cells: Map[(String, String), BenchTokens] = Map.empty,
  counters: Map[String, BenchCounters] = Map.empty,
  modelsServed: Set[String] = Set.empty,
):
  def stageTokens(stage: String): BenchTokens     =
    cells.collect { case ((s, _), t) if s == stage => t }.foldLeft(BenchTokens())(_ + _)
  def stageCounters(stage: String): BenchCounters =
    counters.getOrElse(stage, BenchCounters())

object Bench:

  /** Bucket for tokens/notices observed outside any open stage. */
  val Unstaged: String = "(none)"

  /** Fold one flow event into the observation. Tokens and robustness notices are attributed to the innermost open
    * stage. The robustness counters key on the stable message prefixes the library and the modernize flows emit (`⟳
    * flaky…` / `⟳ transient…` from TransientRetry, `↻ auto-resume` from AutoResume, `turn limit hit` and `shrinking to`
    * from the flows) — if those ever feel fragile, promote them to structured events.
    */
  def observe(o: BenchObservation, event: FlowEvent): BenchObservation = event match
    case FlowEvent.StageStarted(s)             => o.copy(openStages = s :: o.openStages)
    case FlowEvent.StageCompleted(s)           => o.copy(openStages = dropFirst(o.openStages, s))
    case FlowEvent.StageFailed(s, _)           => o.copy(openStages = dropFirst(o.openStages, s))
    case FlowEvent.TokensUsed(_, model, usage) =>
      val key = (currentStage(o), model.getOrElse(""))
      o.copy(
        cells = o.cells.updated(key, o.cells.getOrElse(key, BenchTokens()) + BenchTokens.of(usage)),
        modelsServed = o.modelsServed ++ model,
      )
    case FlowEvent.Info(message)               =>
      classify(message).fold(o) { bump =>
        val stage = currentStage(o)
        o.copy(counters = o.counters.updated(stage, bump(o.stageCounters(stage))))
      }
    case _                                     => o

  /** Classify a robustness notice into the counter it bumps; None for ordinary progress messages. */
  private def classify(message: String): Option[BenchCounters => BenchCounters] =
    if message.startsWith("⟳ flaky") then Some(c => c.copy(flakyRetries = c.flakyRetries + 1))
    else if message.startsWith("⟳ transient") then Some(c => c.copy(transientRetries = c.transientRetries + 1))
    else if message.startsWith("↻ auto-resume") then Some(c => c.copy(autoResumes = c.autoResumes + 1))
    else if message.contains("turn limit hit") then Some(c => c.copy(turnLimitTrips = c.turnLimitTrips + 1))
    else if message.contains("shrinking to") then Some(c => c.copy(shrinks = c.shrinks + 1))
    else None

  private def currentStage(o: BenchObservation): String = o.openStages.headOption.getOrElse(Unstaged)

  private def dropFirst(stages: List[String], stage: String): List[String] = stages.indexOf(stage) match
    case -1 => stages
    case i  => stages.patch(i, Nil, 1)

  /** Assemble one phase from the observation: the stage's tokens, its counters, and a cost estimate summed over the
    * stage's priced models (None when nothing in the stage matched the [[PriceList]]).
    */
  def phase(o: BenchObservation, stage: String, ms: Long, detail: Map[String, String] = Map.empty): BenchPhase =
    val costs = o.cells.collect {
      case ((s, model), t) if s == stage =>
        PriceList.costUsd(model, TokenUsage(t.prompt.toInt, t.completion.toInt, t.total.toInt))
    }.flatten
    BenchPhase(
      name = stage,
      ms = ms,
      tokens = o.stageTokens(stage),
      costUsd = Option.when(costs.nonEmpty)(costs.sum),
      counters = o.stageCounters(stage),
      detail = detail,
    )

  /** A live observation over a flow's event sink. Consumption is asynchronous, so both accessors first DRAIN — wait
    * until everything published so far has been folded — before answering: a phase snapshot taken right after a stage
    * completes must include that stage's trailing token events, and a `reset` between benchmark runs must not leak one
    * run's tail into the next.
    */
  final class Tap private[Bench] (
    observation: Ref[BenchObservation],
    consumed: Ref[Long],
    baseline: Long,
    events: FlowEvents,
  ):
    /** The observation once everything published so far is folded in. */
    def get: UIO[BenchObservation] = drained *> observation.get

    /** Drain, then start a fresh observation — run isolation for looped benchmarks. */
    def reset: UIO[Unit] = drained *> observation.set(BenchObservation())

    private def drained: UIO[Unit] = events match
      case hub: FlowEvents.Hub =>
        def poll: UIO[Unit] =
          (hub.publishedCount <*> consumed.get).flatMap { (published, seen) =>
            if seen >= published - baseline then ZIO.unit else ZIO.sleep(5.millis) *> poll
          }
        poll
      case _                   => ZIO.unit

  /** Subscribe an observation fold to the flow's event sink. Real runs pass the context's [[FlowEvents.Hub]]; any other
    * sink (noop, collecting) degrades to a static empty observation, so callers never special-case tests.
    */
  def tap(events: FlowEvents): ZIO[Scope, Nothing, Tap] =
    for
      ref      <- Ref.make(BenchObservation())
      consumed <- Ref.make(0L)
      baseline <- events match
                    // Subscribe eagerly (in the caller's scope) BEFORE forking the drain: a stream-based
                    // subscription only attaches when the forked fiber first runs, silently dropping anything
                    // published before that. Events counted before this subscription never reach the queue, so
                    // they are the drain baseline.
                    case hub: FlowEvents.Hub =>
                      for
                        queue     <- hub.subscribe
                        published <- hub.publishedCount
                        queued    <- queue.size
                        _         <- queue.take
                                       .flatMap(e => ref.update(observe(_, e)) *> consumed.update(_ + 1))
                                       .forever
                                       .forkScoped
                      yield published - queued // pre-subscription events never reach this queue
                    case _                   => ZIO.succeed(0L)
    yield new Tap(ref, consumed, baseline, events)
