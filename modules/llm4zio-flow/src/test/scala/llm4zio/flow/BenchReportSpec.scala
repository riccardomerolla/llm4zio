package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.*
import zio.json.*
import zio.test.*

object BenchReportSpec extends ZIOSpecDefault:

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-benchreport-")).orDie)(d =>
      ZIO.attempt {
        Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
      }.orDie
    )

  private def rec(
    provider: String,
    runId: String,
    totalMs: Long,
    tokens: Long,
    fingerprint: String = "fp-1",
    outcome: String = "completed",
    judge: BenchJudge = BenchJudge("gemini", "gemini-2.5-pro", fixed = true),
    coveragePct: Double = 100.0,
    gateCleared: Boolean = true,
    judgeScore: Int = 2,
    programs: Int = 3,
    hostname: String = "host1",
    completionTokens: Long = 0,
    cachedTokens: Long = 0,
  ): BenchRecord =
    BenchRecord(
      schema = BenchRecord.CurrentSchema,
      runId = runId,
      label = Some("bench"),
      startedAt = "2026-07-16T12:00:00Z",
      finishedAt = "2026-07-16T12:20:00Z",
      provider = provider,
      modelRequested = s"$provider-model",
      modelsServed = List(s"$provider-model"),
      judge = judge,
      toolVersion = Some("1.0.0"),
      llm4zioVersion = "3.18.0",
      pack = "cobol-springboot",
      fixtureFingerprint = fingerprint,
      machine = BenchMachine(hostname, "Mac OS X 15.5", "aarch64", 10, 32.0, "21"),
      outcome = outcome,
      totalMs = totalMs,
      phases = List(
        BenchPhase(
          "Extract",
          totalMs,
          BenchTokens(prompt = tokens, completion = completionTokens, cached = cachedTokens),
          costUsd = Some(1.0),
          counters = BenchCounters(flakyRetries = 1),
        )
      ),
      quality = Some(BenchQuality(
        buildPassed = Some(outcome == "completed"),
        testsRun = Some(10),
        testsFailed = Some(0),
        coveragePct = Some(coveragePct),
        scenarios = 14,
        programs = programs,
        gateCleared = Some(gateCleared),
        gateRounds = Some(1),
        judgeScores = List(BenchScore("spec:completeness", judgeScore, 2)),
      )),
    )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("BenchReport")(
    test("median: odd, even, and single-element inputs") {
      assertTrue(
        BenchReport.median(List(3.0, 1.0, 2.0)) == 2.0,
        BenchReport.median(List(4.0, 1.0, 2.0, 3.0)) == 2.5,
        BenchReport.median(List(7.0)) == 7.0,
      )
    },
    test("load reads jsonl files and dirs, skipping malformed lines and missing paths") {
      ZIO.scoped {
        for
          dir     <- tempDir
          good     = rec("gemini", "r1", 8_000, 1_000_000)
          _       <- ZIO.attemptBlocking {
                       Files.write(
                         dir.resolve("a.jsonl"),
                         s"${good.toJson}\nnot json {{{\n${rec("claude", "r2", 12_000, 1_500_000).toJson}\n"
                           .getBytes(StandardCharsets.UTF_8),
                       )
                     }.orDie
          records <- BenchReport.load(List(dir, dir.resolve("missing.jsonl")))
        yield assertTrue(records.size == 2, records.map(_.provider).toSet == Set("gemini", "claude"))
      }
    },
    test("headline crowns best and worst with the gap, respecting metric direction") {
      val md = BenchReport.render(List(
        rec("gemini", "r1", totalMs = 8_000, tokens = 1_000_000),
        rec("claude", "r2", totalMs = 12_000, tokens = 1_500_000),
      ))
      assertTrue(
        // providers are columns in sorted order: claude, gemini
        md.contains("| Total time | 12.0s ⚠️ | **8.0s ✅** | +4.0s (+50.0%) |"),
        md.contains("| Total tokens | 1.5M ⚠️ | **1.0M ✅** | +500.0k (+50.0%) |"),
      )
    },
    test("neutral metrics are displayed but never crowned") {
      val md         = BenchReport.render(List(
        rec("gemini", "r1", 8_000, 1_000_000),
        rec("claude", "r2", 12_000, 1_500_000),
      ))
      val estCostRow = md.linesIterator.find(_.startsWith("| Est. cost")).getOrElse("")
      assertTrue(estCostRow.nonEmpty, !estCostRow.contains("✅"), !estCostRow.contains("⚠️"))
    },
    test("judge scores are crowned only when every run had the same examiner") {
      val fixed      = BenchJudge("gemini", "gemini-2.5-pro", fixed = true)
      val comparable = BenchReport.render(List(
        rec("gemini", "r1", 8_000, 1_000_000, judge = fixed, judgeScore = 2),
        rec("claude", "r2", 12_000, 1_500_000, judge = fixed, judgeScore = 1),
      ))
      val selfGraded = BenchReport.render(List(
        rec("gemini", "r1", 8_000, 1_000_000, judge = BenchJudge("gemini", "g", fixed = false), judgeScore = 2),
        rec("claude", "r2", 12_000, 1_500_000, judge = BenchJudge("claude", "c", fixed = false), judgeScore = 1),
      ))
      val judgeRowOf = (md: String) => md.linesIterator.find(_.startsWith("| Judge score")).getOrElse("")
      assertTrue(
        comparable.contains("same examiner"),
        judgeRowOf(comparable).contains("✅"),
        selfGraded.contains("NOT cross-comparable"),
        !judgeRowOf(selfGraded).contains("✅"),
      )
    },
    test("different fixture fingerprints are sectioned, never merged") {
      val md       = BenchReport.render(List(
        rec("gemini", "r1", 8_000, 1_000_000, fingerprint = "fp-1"),
        rec("claude", "r2", 12_000, 1_500_000, fingerprint = "fp-2"),
      ))
      val sections = md.linesIterator.count(_.startsWith("# Benchmark"))
      assertTrue(sections == 2, !md.contains("✅")) // one provider per section → nothing to crown
    },
    test("N>1 shows the median with min–max spread") {
      val md = BenchReport.render(List(
        rec("gemini", "r1", 8_000, 1_000_000),
        rec("gemini", "r2", 12_000, 1_000_000),
        rec("gemini", "r3", 10_000, 1_000_000),
      ))
      assertTrue(md.contains("10.0s (8.0s–12.0s)"))
    },
    test("failures are listed with their failing phase") {
      val md = BenchReport.render(List(
        rec("gemini", "r1", 8_000, 1_000_000),
        rec("claude", "r2", 5_000, 200_000, outcome = "failed-implement"),
      ))
      assertTrue(md.contains("## Failures"), md.contains("claude"), md.contains("failed-implement"))
    },
    test("projection extrapolates per-program economics to the target estate size") {
      val md = BenchReport.render(
        List(rec("gemini", "r1", totalMs = 9_000, tokens = 1_200_000, programs = 3)),
        projectPrograms = Some(500),
      )
      assertTrue(
        md.contains("## Projection @ 500 programs"),
        // 1.2M / 3 = 400k tokens per program; × 500 = 200M
        md.contains("400.0k"),
        md.contains("200.0M"),
        // 9s / 3 = 3s per program; × 500 = 1500s = 25m
        md.contains("25.0m"),
        md.contains("Linear extrapolation"),
      )
    },
    test("token accounting: totals include cache reads, output tokens are crowned, cached reads never are") {
      // Claude reports prompt NET of cache reads (prompt 45, cached 1M) while codex reports the full prompt —
      // a prompt+completion "total" makes claude look 100× cheaper on tokens than it was. Totals must include
      // cached; output tokens are the only cross-CLI-comparable figure, so they get their own crowned row.
      val claudeish = rec("claude", "r1", 8_000, tokens = 45_000, completionTokens = 55_000, cachedTokens = 1_000_000)
      val codexish  = rec("codex", "r2", 12_000, tokens = 1_300_000, completionTokens = 100_000)
      val md        = BenchReport.render(List(claudeish, codexish))
      val cachedRow = md.linesIterator.find(_.startsWith("| Cached reads")).getOrElse("")
      assertTrue(
        // 45k + 55k + 1M = 1.1M vs 1.3M + 100k = 1.4M — same order of magnitude, not 100× apart
        md.contains("| Total tokens | **1.1M ✅** | 1.4M ⚠️ |"),
        md.contains("| Output tokens | **55.0k ✅** | 100.0k ⚠️ |"),
        cachedRow.contains("1.0M"),
        !cachedRow.contains("✅"),
      )
    },
    test("boolean gaps render as counts and long durations as hours") {
      val passed = rec("gemini", "r1", totalMs = 14_400_000, tokens = 1_000_000)
      val failed = rec("claude", "r2", totalMs = 12_000, tokens = 1_000_000, outcome = "failed-implement")
      val md     = BenchReport.render(List(passed, failed))
      assertTrue(
        md.contains("| Build passed | no ⚠️ | **yes ✅** | +1 (+100.0%) |"),
        md.contains("4.0h"),
      )
    },
    test("missing phase costs are estimated at report time from recorded tokens × the requested model") {
      // Iteration-1 rows recorded costUsd: None (unpriceable at run time) but DID record tokens and the
      // requested model — the report can and should do that math itself, flagged as a report-time estimate.
      val base     = rec("codex", "r1", 8_000, tokens = 0)
      val unpriced = base.copy(
        modelRequested = "gpt-5.5",
        phases = List(
          // 1M in @ $5 + 100k out @ $30 = $8.00 — costUsd absent, tokens present
          BenchPhase("Extract", 8_000, BenchTokens(prompt = 1_000_000, completion = 100_000), None, BenchCounters()),
          // no tokens at all → still unpriceable, must stay "–", not a fake 0.00
          BenchPhase("Implement", 1_000, BenchTokens(), None, BenchCounters()),
        ),
      )
      val stored   = rec("claude", "r2", 9_000, tokens = 500) // costUsd Some(1.0) stored at run time wins
      val md       = BenchReport.render(List(unpriced, stored))
      val headline = md.linesIterator.find(_.startsWith("| Est. cost")).getOrElse("")
      val implRow  = md.linesIterator.toList.dropWhile(!_.startsWith("### Implement")).find(_.startsWith(
        "| Est. cost"
      )).getOrElse("")
      assertTrue(
        headline.contains("8.00*"),
        headline.contains("1.00"),
        implRow.contains("–"),
        md.contains("estimated at report time"),
      )
    },
    test("resumed runs are marked in the outcome row and footnoted as not time/token-comparable") {
      val fresh   = rec("gemini", "r1", 8_000, 1_000_000)
      val resumed = rec("claude", "r2", 3_000, 200_000).copy(resumed = true)
      val md      = BenchReport.render(List(fresh, resumed))
      val outcome = md.linesIterator.find(_.startsWith("| Outcome")).getOrElse("")
      assertTrue(
        outcome.contains("completed ♻"),
        md.contains("♻ resumed run"),
        // a fresh-only report carries no resume footnote
        !BenchReport.render(List(fresh)).contains("♻"),
      )
    },
    test("machines are footnoted") {
      val md = BenchReport.render(List(
        rec("gemini", "r1", 8_000, 1_000_000, hostname = "mac-a"),
        rec("claude", "r2", 12_000, 1_500_000, hostname = "mac-b"),
      ))
      assertTrue(md.contains("mac-a"), md.contains("mac-b"), md.contains("Machines:"))
    },
  )
