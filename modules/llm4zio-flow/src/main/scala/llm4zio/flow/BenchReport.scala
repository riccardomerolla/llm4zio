package llm4zio.flow

import java.nio.file.{ Files, Path }
import java.util.Locale

import scala.jdk.CollectionConverters.*

import zio.json.*
import zio.{ UIO, ZIO }

/** Pure aggregation + markdown rendering over [[BenchRecord]] lines — the deterministic (no-LLM) engine behind
  * `bench-report.sc`. Records are sectioned by (pack, fixture fingerprint) so different tasks are never compared;
  * within a section providers are compared column-wise, best/worst crowned per metric direction, and judged scores are
  * crowned only when every run had the same examiner.
  */
object BenchReport:

  /** All parseable records under `paths`: a `.jsonl` file contributes its well-formed lines, a directory contributes
    * every `*.jsonl` inside it, and missing/unreadable paths contribute nothing — collection must never fail.
    */
  def load(paths: List[Path]): UIO[List[BenchRecord]] =
    ZIO
      .foreach(paths) { path =>
        ZIO.attemptBlocking {
          val files =
            if Files.isDirectory(path) then
              val stream = Files.list(path)
              try stream.iterator().asScala.filter(_.getFileName.toString.endsWith(".jsonl")).toList
              finally stream.close()
            else if Files.isRegularFile(path) then List(path)
            else Nil
          files.flatMap(f =>
            Files.readAllLines(f).asScala.toList.flatMap(_.fromJson[BenchRecord].toOption)
          )
        }.orElseSucceed(Nil)
      }
      .map(_.flatten.sortBy(r => (r.startedAt, r.runId)))

  /** Render the full markdown comparison. `projectPrograms` adds the linear scale-projection section. */
  def render(records: List[BenchRecord], projectPrograms: Option[Int] = None): String =
    records
      .groupBy(r => (r.pack, r.fixtureFingerprint))
      .toList
      .sortBy(_._1)
      .map((key, recs) => section(key._1, key._2, recs, projectPrograms))
      .mkString("\n\n")

  private[flow] def median(values: List[Double]): Double =
    val sorted = values.sorted
    val n      = sorted.size
    if n % 2 == 1 then sorted(n / 2) else (sorted(n / 2 - 1) + sorted(n / 2)) / 2.0

  // ── formatting (Locale.ROOT throughout: report bytes must not depend on the host locale) ──────────────

  private def f(pattern: String, v: Double): String = String.format(Locale.ROOT, pattern, v)

  private def fmtMs(ms: Double): String =
    if ms < 120_000 then f("%.1fs", ms / 1000.0)
    else if ms < 10_800_000 then f("%.1fm", ms / 60_000.0) // under 3 hours, minutes read best
    else f("%.1fh", ms / 3_600_000.0)

  private def fmtTokens(v: Double): String =
    if v >= 1_000_000 then f("%.1fM", v / 1_000_000)
    else if v >= 1_000 then f("%.1fk", v / 1_000)
    else f("%.0f", v)

  private def fmtInt(v: Double): String   = f("%.0f", v)
  private def fmtPct(v: Double): String   = f("%.1f%%", v)
  private def fmtUsd(v: Double): String   = f("%.2f", v)
  private def fmtYesNo(v: Double): String =
    if v >= 0.999 then "yes" else if v <= 0.001 then "no" else fmtPct(v * 100)

  // ── metric catalog ────────────────────────────────────────────────────────────────────────────────────

  private enum Direction:
    case Lower, Higher, Neutral

  final private case class Row(
    name: String,
    direction: Direction,
    fmt: Double => String,
    of: BenchRecord => Option[Double],
    // Gap deltas usually share the cell format, but yes/no cells need a count ("+1", not "+yes").
    gapFmt: Option[Double => String] = None,
  ):
    def fmtGap: Double => String = gapFmt.getOrElse(fmt)

  private def quality[A](r: BenchRecord)(get: BenchQuality => Option[A]): Option[A] = r.quality.flatMap(get)

  private def judgePct(r: BenchRecord): Option[Double] =
    quality(r)(q => Option.when(q.judgeScores.nonEmpty)(q.judgeScores)).map(scores =>
      scores.map(_.score).sum.toDouble / scores.map(_.max).sum.max(1) * 100
    )

  private def headlineRows(judgesComparable: Boolean): List[Row] = List(
    Row("Total time", Direction.Lower, fmtMs, r => Some(r.totalMs.toDouble)),
    Row("Total tokens", Direction.Lower, fmtTokens, r => Some(r.totalTokens.toDouble)),
    Row("Est. cost (USD)", Direction.Neutral, fmtUsd, _.totalCostUsd),
    Row("Self-healing actions", Direction.Lower, fmtInt, r => Some(r.selfHealing.toDouble)),
    Row(
      "Gate cleared",
      Direction.Higher,
      fmtYesNo,
      quality(_)(_.gateCleared.map(if _ then 1.0 else 0.0)),
      gapFmt = Some(fmtInt),
    ),
    Row("Gate rounds", Direction.Lower, fmtInt, quality(_)(_.gateRounds.map(_.toDouble))),
    Row(
      "Build passed",
      Direction.Higher,
      fmtYesNo,
      quality(_)(_.buildPassed.map(if _ then 1.0 else 0.0)),
      gapFmt = Some(fmtInt),
    ),
    Row(
      "Tests passed",
      Direction.Higher,
      fmtInt,
      quality(_)(q => q.testsRun.map(run => (run - q.testsFailed.getOrElse(0)).toDouble)),
    ),
    Row("Coverage %", Direction.Higher, fmtPct, quality(_)(_.coveragePct)),
    Row("Judge score", if judgesComparable then Direction.Higher else Direction.Neutral, fmtPct, judgePct),
  )

  private def phaseRows(phase: String): List[Row] =
    def of(r: BenchRecord)(get: BenchPhase => Option[Double]): Option[Double] =
      r.phases.find(_.name == phase).flatMap(get)
    List(
      Row("Duration", Direction.Lower, fmtMs, of(_)(p => Some(p.ms.toDouble))),
      Row("Tokens", Direction.Lower, fmtTokens, of(_)(p => Some(p.tokens.total.toDouble))),
      Row("Est. cost (USD)", Direction.Neutral, fmtUsd, of(_)(_.costUsd)),
      Row("Self-healing actions", Direction.Lower, fmtInt, of(_)(p => Some(p.counters.selfHealing.toDouble))),
    )

  private def robustnessRows: List[Row] =
    def sum(get: BenchCounters => Int)(r: BenchRecord): Option[Double] =
      Some(r.phases.map(p => get(p.counters)).sum.toDouble)
    List(
      Row("Flaky retries", Direction.Lower, fmtInt, sum(_.flakyRetries)),
      Row("Transient retries", Direction.Lower, fmtInt, sum(_.transientRetries)),
      Row("Auto-resumes", Direction.Lower, fmtInt, sum(_.autoResumes)),
      Row("Turn-limit trips", Direction.Lower, fmtInt, sum(_.turnLimitTrips)),
      Row("Empty-response shrinks", Direction.Lower, fmtInt, sum(_.shrinks)),
      Row("Self-healing total", Direction.Lower, fmtInt, r => Some(r.selfHealing.toDouble)),
    )

  private def qualityRows: List[Row] = List(
    Row("Scenarios", Direction.Neutral, fmtInt, quality(_)(q => Some(q.scenarios.toDouble))),
    Row("Malformed features", Direction.Neutral, fmtInt, quality(_)(q => Some(q.malformedFeatures.toDouble))),
    Row("Spec files", Direction.Neutral, fmtInt, quality(_)(q => Some(q.specFiles.toDouble))),
    Row("Java LOC", Direction.Neutral, fmtInt, quality(_)(q => Some(q.javaLines.toDouble))),
    Row("Test LOC", Direction.Neutral, fmtInt, quality(_)(q => Some(q.testLines.toDouble))),
    Row("Deterministic findings", Direction.Neutral, fmtInt, quality(_)(q => Some(q.deterministicFindings.toDouble))),
    Row("Judge findings", Direction.Neutral, fmtInt, quality(_)(q => Some(q.judgeFindings.toDouble))),
  )

  // ── rendering ─────────────────────────────────────────────────────────────────────────────────────────

  private def section(pack: String, fingerprint: String, records: List[BenchRecord], project: Option[Int]): String =
    val providers        = records.map(_.provider).distinct.sorted
    val byProvider       = records.groupBy(_.provider)
    val judgesComparable = records.map(r => (r.judge.provider, r.judge.model)).distinct.size == 1
    val judgeLine        =
      if judgesComparable then
        val j = records.head.judge
        s"Judge: ${j.provider}/${j.model} — same examiner for all runs"
      else "Judges: self-graded — scores NOT cross-comparable"
    val runsLine         = providers.map(p => s"$p ×${byProvider(p).size}").mkString(", ")
    val phases           = records.flatMap(_.phases.map(_.name)).distinct

    val parts    = List.newBuilder[String]
    parts += s"# Benchmark — $pack @ ${fingerprint.take(8)}"
    parts += s"_Runs: $runsLine · ${judgeLine}_"
    parts += "## Headline"
    parts += table(outcomeRow(providers, byProvider) :: Nil, headlineRows(judgesComparable), providers, byProvider)
    parts += "## Phases"
    phases.foreach { phase =>
      parts += s"### $phase"
      parts += table(Nil, phaseRows(phase), providers, byProvider)
    }
    parts += "## Quality"
    parts += table(Nil, qualityRows, providers, byProvider)
    parts += "## Robustness"
    parts += table(Nil, robustnessRows, providers, byProvider)
    val failures = records.filterNot(_.completed)
    if failures.nonEmpty then
      parts += "## Failures"
      parts += failures.map(r => s"- ${r.provider} ${r.runId}: ${r.outcome}").mkString("\n")
    project.foreach(n => parts += projection(n, providers, byProvider))
    parts += machinesFootnote(records)
    parts.result().mkString("\n\n")

  /** Outcome is a text row (never crowned): the outcome string, or `x/y completed` across repeated runs. */
  private def outcomeRow(providers: List[String], byProvider: Map[String, List[BenchRecord]]): String =
    val cells = providers.map { p =>
      val runs = byProvider(p)
      if runs.size == 1 then runs.head.outcome
      else s"${runs.count(_.completed)}/${runs.size} completed"
    }
    s"| Outcome | ${cells.mkString(" | ")} |  |"

  private def table(
    extraRows: List[String],
    rows: List[Row],
    providers: List[String],
    byProvider: Map[String, List[BenchRecord]],
  ): String =
    val header    = s"| Metric | ${providers.mkString(" | ")} | Gap (best→worst) |"
    val separator = s"| --- |${providers.map(_ => " --- |").mkString} --- |"
    val body      = rows.map(renderRow(_, providers, byProvider))
    (header :: separator :: extraRows ::: body).mkString("\n")

  private def renderRow(row: Row, providers: List[String], byProvider: Map[String, List[BenchRecord]]): String =
    val stats                                             = providers.map { p =>
      val values = byProvider(p).flatMap(row.of)
      Option.when(values.nonEmpty)((median(values), values.min, values.max))
    }
    val medians                                           = stats.flatten.map(_._1)
    val crowned                                           =
      row.direction != Direction.Neutral && medians.distinct.size > 1 && stats.count(_.isDefined) > 1
    lazy val best                                         =
      if row.direction == Direction.Lower then medians.min else medians.max
    lazy val worst                                        =
      if row.direction == Direction.Lower then medians.max else medians.min
    def cell(s: Option[(Double, Double, Double)]): String = s match
      case None                  => "–"
      case Some((med, min, max)) =>
        val base = if min != max then s"${row.fmt(med)} (${row.fmt(min)}–${row.fmt(max)})" else row.fmt(med)
        if !crowned then base
        else if med == best then s"**$base ✅**"
        else if med == worst then s"$base ⚠️"
        else base
    val gap                                               =
      if !crowned then ""
      else
        val delta = math.abs(worst - best)
        val pct   = if best != 0 then s" (+${f("%.1f", delta / best * 100)}%)" else ""
        s"+${row.fmtGap(delta)}$pct"
    s"| ${row.name} | ${stats.map(cell).mkString(" | ")} | $gap |"

  private def projection(programs: Int, providers: List[String], byProvider: Map[String, List[BenchRecord]]): String =
    def perProgram(of: BenchRecord => Option[Double])(r: BenchRecord): Option[Double] =
      for
        v <- of(r)
        n <- r.quality.map(_.programs) if n > 0
      yield v / n
    def scaled(of: BenchRecord => Option[Double])(r: BenchRecord): Option[Double]     =
      perProgram(of)(r).map(_ * programs)
    val totalTokens: BenchRecord => Option[Double]                                    = r => Some(r.totalTokens.toDouble)
    val totalMs: BenchRecord => Option[Double]                                        = r => Some(r.totalMs.toDouble)
    val rows                                                                          = List(
      Row("Tokens / program", Direction.Neutral, fmtTokens, perProgram(totalTokens)),
      Row("Time / program", Direction.Neutral, fmtMs, perProgram(totalMs)),
      Row("Est. cost / program (USD)", Direction.Neutral, fmtUsd, perProgram(_.totalCostUsd)),
      Row("Projected tokens", Direction.Neutral, fmtTokens, scaled(totalTokens)),
      Row("Projected time", Direction.Neutral, fmtMs, scaled(totalMs)),
      Row("Projected est. cost (USD)", Direction.Neutral, fmtUsd, scaled(_.totalCostUsd)),
    )
    s"## Projection @ $programs programs\n\n" +
      table(Nil, rows, providers, byProvider) +
      "\n\n_Linear extrapolation from the fixture — assumes no context growth, no quota ceilings, serial execution._"

  private def machinesFootnote(records: List[BenchRecord]): String =
    val machines = records.map(_.machine).distinctBy(_.hostname).map { m =>
      s"${m.hostname} — ${m.os} ${m.arch}, ${m.cores} cores, ${f("%.1f", m.memoryGb)} GB, JVM ${m.jvm}"
    }
    s"_Machines: ${machines.mkString("; ")}_"
