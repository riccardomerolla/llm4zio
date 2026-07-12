package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, StandardOpenOption }

import scala.util.hashing.MurmurHash3

import zio.json.*
import zio.{ Clock, IO, UIO, ZIO }

/** One roll-up of token usage attributed to a (stage, agent, model) triple within a single run. `costUsd` is the
  * [[PriceList]] estimate captured at run time (absent for unpriced/unknown models); token counts are exact.
  */
final case class CostCell(
  stage: String,
  agent: String,
  model: String,
  prompt: Long,
  completion: Long,
  cached: Option[Long],
  total: Long,
  costUsd: Option[Double],
) derives JsonCodec

/** One flow run's durable token/cost record — one JSON line in the `costs.jsonl` ledger. The console footer renders and
  * forgets; this is the structured, aggregatable trail: group by `label` (LLM4ZIO_RUN_LABEL) for a modernization
  * effort, by `repo` for a codebase, by `promptHash` for reruns of the same request. `runId` joins the run's trace file
  * (`trace-<runId>.jsonl`) while it is still retained.
  */
final case class CostRecord(
  runId: String,
  at: String,
  repo: String,
  label: Option[String],
  promptHead: String,
  promptHash: String,
  pricesAsOf: String,
  cells: List[CostCell],
  totalPrompt: Long,
  totalCompletion: Long,
  totalCostUsd: Option[Double],
) derives JsonCodec

object CostRecord:

  /** Build a record from raw parts: derives the one-line 120-char prompt head, the murmur prompt hash (the same hash
    * [[Plan.defaultPath]] uses, so ledger records join plan files), and the totals.
    */
  def from(
    runId: String,
    at: String,
    repo: String,
    label: Option[String],
    prompt: String,
    pricesAsOf: String,
    cells: List[CostCell],
  ): CostRecord =
    val costs = cells.flatMap(_.costUsd)
    CostRecord(
      runId = runId,
      at = at,
      repo = repo,
      label = label,
      promptHead = prompt.strip.replaceAll("\\s+", " ").take(120),
      promptHash = Integer.toHexString(MurmurHash3.stringHash(prompt)),
      pricesAsOf = pricesAsOf,
      cells = cells,
      totalPrompt = cells.map(_.prompt).sum,
      totalCompletion = cells.map(_.completion).sum,
      totalCostUsd = Option.when(costs.nonEmpty)(costs.sum),
    )

/** The append-only, plain-file cost ledger: `costs.jsonl` under the flow's bookkeeping directory
  * (`Workspace.llm4zioDir`), one [[CostRecord]] line per run, never pruned — traces are diagnostics, this is the
  * accounting record.
  */
object CostLedger:
  val FileName: String = "costs.jsonl"

  def path(dir: Path): Path = dir.resolve(FileName)

  /** Append one record (creating directories and the file on first use). */
  def append(dir: Path, record: CostRecord): IO[FlowError, Unit] =
    ZIO
      .attemptBlocking {
        Files.createDirectories(dir)
        Files.write(
          path(dir),
          s"${record.toJson}\n".getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND,
        )
        ()
      }
      .mapError(e => FlowError.Persistence(s"failed to append cost record to ${path(dir)}", Some(e)))

  /** Build this run's record from the tracker's accumulated cells and append it — the runner calls this once at flow
    * end. A run with no token usage (a deterministic flow like modernize-seed.sc) writes nothing. Best-effort by
    * design: a write failure logs a warning and never fails the flow — accounting must not break the work.
    */
  def write(
    dir: Path,
    tracker: CostTracker,
    runId: String,
    repo: String,
    label: Option[String],
    prompt: String,
  ): UIO[Unit] =
    tracker.cells
      .flatMap(cells =>
        ZIO.unless(cells.isEmpty)(
          Clock.instant.flatMap(now =>
            append(dir, CostRecord.from(runId, now.toString, repo, label, prompt, PriceList.asOf, cells))
          )
        )
      )
      .catchAll(e => ZIO.logWarning(s"cost ledger write failed: ${e.message}"))
      .unit

  /** All records in the ledger, in append order. Absent file ⇒ empty; malformed lines are skipped (the ledger must stay
    * readable even if one run wrote garbage).
    */
  def load(dir: Path): IO[FlowError, List[CostRecord]] =
    ZIO
      .attemptBlocking {
        val file = path(dir)
        if !Files.exists(file) then Nil
        else
          Files
            .readAllLines(file, StandardCharsets.UTF_8)
            .toArray(Array.empty[String])
            .toList
            .flatMap(line => line.fromJson[CostRecord].toOption)
      }
      .mapError(e => FlowError.Persistence(s"failed to read cost ledger at ${path(dir)}", Some(e)))

  /** Roll up identical (stage, agent, model) cells across records — the cross-run aggregation primitive. */
  def mergeCells(records: List[CostRecord]): List[CostCell] =
    records
      .flatMap(_.cells)
      .groupBy(c => (c.stage, c.agent, c.model))
      .toList
      .sortBy(_._1)
      .map {
        case ((stage, agent, model), cs) =>
          val cached = cs.flatMap(_.cached) match
            case Nil => None
            case xs  => Some(xs.sum)
          val costs  = cs.flatMap(_.costUsd)
          CostCell(
            stage = stage,
            agent = agent,
            model = model,
            prompt = cs.map(_.prompt).sum,
            completion = cs.map(_.completion).sum,
            cached = cached,
            total = cs.map(_.total).sum,
            costUsd = Option.when(costs.nonEmpty)(costs.sum),
          )
      }
