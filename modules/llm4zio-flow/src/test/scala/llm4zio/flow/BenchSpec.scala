package llm4zio.flow

import zio.*
import zio.json.*
import zio.test.*

import llm4zio.core.TokenUsage

object BenchSpec extends ZIOSpecDefault:

  private def usage(prompt: Int, completion: Int, cached: Option[Int] = None): TokenUsage =
    TokenUsage(prompt, completion, prompt + completion, cached)

  private val record = BenchRecord(
    schema = BenchRecord.CurrentSchema,
    runId = "20260716-120000-001",
    label = Some("bench-cobol"),
    startedAt = "2026-07-16T12:00:00Z",
    finishedAt = "2026-07-16T12:20:00Z",
    provider = "gemini",
    modelRequested = "gemini-2.5-pro",
    modelsServed = List("gemini-2.5-pro"),
    judge = BenchJudge("gemini", "gemini-2.5-pro", fixed = false),
    toolVersion = Some("0.47.0"),
    llm4zioVersion = "3.18.0",
    pack = "cobol-springboot",
    fixtureFingerprint = "abc123",
    machine = BenchMachine("host1", "Mac OS X 15.5", "aarch64", 10, 32.0, "21.0.2"),
    outcome = "completed",
    totalMs = 1_200_000,
    phases = List(
      BenchPhase(
        name = "Extract",
        ms = 600_000,
        tokens = BenchTokens(prompt = 100_000, completion = 20_000, cached = 5_000),
        costUsd = Some(0.325),
        counters = BenchCounters(flakyRetries = 2, autoResumes = 1),
        detail = Map("programs" -> "3", "skipped" -> "0"),
      )
    ),
    quality = Some(BenchQuality(
      buildPassed = Some(true),
      testsRun = Some(12),
      testsFailed = Some(0),
      coveragePct = Some(100.0),
      featureFiles = 3,
      scenarios = 14,
      specFiles = 3,
      programs = 3,
      javaLines = 900,
      gateCleared = Some(true),
      gateRounds = Some(1),
      judgeScores = List(BenchScore("spec:completeness", 2, 2, "all rules captured")),
    )),
  )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Bench")(
    test("BenchRecord round-trips through JSON") {
      val json = record.toJson
      assertTrue(json.fromJson[BenchRecord] == Right(record))
    },
    test(
      "observe attributes tokens to the OUTERMOST open stage — nested task stages must not swallow a phase's tokens"
    ) {
      // Regression: implementTaskLoop wraps each task in stage(task.title); with innermost attribution the whole
      // Implement phase recorded 0 tokens because everything was keyed under task titles the record never reads.
      val events = List(
        FlowEvent.StageStarted("Extract"),
        FlowEvent.TokensUsed("coder", Some("gemini-2.5-pro"), usage(1000, 200, Some(50))),
        FlowEvent.StageStarted("Inner task"),
        FlowEvent.TokensUsed("coder", Some("gemini-2.5-pro"), usage(10, 5)),
        FlowEvent.StageCompleted("Inner task"),
        FlowEvent.TokensUsed("reasoning", None, usage(100, 30)),
        FlowEvent.StageCompleted("Extract"),
        FlowEvent.TokensUsed("coder", Some("gemini-2.5-flash"), usage(7, 3)),
      )
      val obs    = events.foldLeft(BenchObservation())(Bench.observe)
      assertTrue(
        obs.openStages.isEmpty,
        obs.stageTokens("Extract") == BenchTokens(1110, 235, 50),
        obs.stageTokens("Inner task") == BenchTokens(0, 0, 0),
        obs.stageTokens(Bench.Unstaged) == BenchTokens(7, 3, 0),
        obs.modelsServed == Set("gemini-2.5-pro", "gemini-2.5-flash"),
      )
    },
    test("observe counts the robustness notices against the open stage") {
      val events = List(
        FlowEvent.StageStarted("Gate"),
        FlowEvent.Info("⟳ flaky structured (fresh retry) — retry 1/6: empty response from provider"),
        FlowEvent.Info("⟳ flaky structured (fresh retry) — retry 2/6: empty response from provider"),
        FlowEvent.Info("⟳ transient error (stream) — retry 1/3: connection reset"),
        FlowEvent.Info("↻ auto-resume after transient failure — re-entering from plan, attempt 1/2: x"),
        FlowEvent.Info("judge returned empty at cap 400000 chars — shrinking to 200000: empty response"),
        FlowEvent.Info("turn limit hit on legacy/cobol/ACCTXFR.cbl after its spec was written — keeping the work"),
        FlowEvent.Info("resume: specs/ACCTXFR.md exists — skipping (1/3)"), // not a robustness notice
        FlowEvent.StageCompleted("Gate"),
      )
      val obs    = events.foldLeft(BenchObservation())(Bench.observe)
      assertTrue(
        obs.stageCounters("Gate") == BenchCounters(
          flakyRetries = 2,
          transientRetries = 1,
          autoResumes = 1,
          turnLimitTrips = 1,
          shrinks = 1,
        ),
        obs.stageCounters("Gate").selfHealing == 5,
      )
    },
    test("phase assembly sums tokens, prices known models, and leaves cost None when nothing is priced") {
      val priced   = List(
        FlowEvent.StageStarted("Extract"),
        FlowEvent.TokensUsed("coder", Some("gemini-2.5-pro"), usage(1_000_000, 100_000)),
        FlowEvent.TokensUsed("coder", Some("not-in-pricelist"), usage(50, 50)),
        FlowEvent.StageCompleted("Extract"),
      ).foldLeft(BenchObservation())(Bench.observe)
      val unpriced = List(
        FlowEvent.StageStarted("Extract"),
        FlowEvent.TokensUsed("coder", Some("not-in-pricelist"), usage(50, 50)),
        FlowEvent.StageCompleted("Extract"),
      ).foldLeft(BenchObservation())(Bench.observe)

      val pricedPhase   = Bench.phase(priced, "Extract", ms = 1000, detail = Map("programs" -> "3"))
      val unpricedPhase = Bench.phase(unpriced, "Extract", ms = 1000)
      assertTrue(
        pricedPhase.tokens == BenchTokens(1_000_050, 100_050, 0),
        // 1M prompt @ $1.25 + 100k completion @ $10 = 1.25 + 1.0
        pricedPhase.costUsd.exists(c => math.abs(c - 2.25) < 0.0001),
        pricedPhase.detail == Map("programs" -> "3"),
        unpricedPhase.costUsd.isEmpty,
        Bench.phase(priced, "NoSuchStage", ms = 5).tokens == BenchTokens(0, 0, 0),
      )
    },
    test("tap.get drains in-flight events before answering, so snapshots never miss trailing tokens") {
      ZIO.scoped {
        for
          hub <- FlowEvents.hub()
          tap <- Bench.tap(hub)
          _   <- hub.publish(FlowEvent.StageStarted("Extract"))
          _   <- hub.publish(FlowEvent.TokensUsed("coder", Some("gemini-2.5-pro"), usage(100, 10)))
          _   <- hub.publish(FlowEvent.StageCompleted("Extract"))
          obs <- tap.get // no polling: get must not answer before everything published is folded
        yield assertTrue(
          obs.stageTokens("Extract") == BenchTokens(100, 10, 0),
          obs.openStages.isEmpty,
          obs.modelsServed == Set("gemini-2.5-pro"),
        )
      }
    } @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds),
    test("tap.reset drains then clears, isolating consecutive runs") {
      ZIO.scoped {
        for
          hub   <- FlowEvents.hub()
          tap   <- Bench.tap(hub)
          _     <- hub.publish(FlowEvent.StageStarted("Extract"))
          _     <- hub.publish(FlowEvent.TokensUsed("coder", Some("m1"), usage(100, 10)))
          _     <- tap.reset
          _     <- hub.publish(FlowEvent.StageStarted("Extract"))
          _     <- hub.publish(FlowEvent.TokensUsed("coder", Some("m2"), usage(7, 3)))
          fresh <- tap.get
        yield assertTrue(
          fresh.stageTokens("Extract") == BenchTokens(7, 3, 0),
          fresh.modelsServed == Set("m2"),
        )
      }
    } @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds),
    test("tap over a non-hub sink degrades to an empty observation") {
      ZIO.scoped {
        for
          tap <- Bench.tap(FlowEvents.noop)
          obs <- tap.get
        yield assertTrue(obs == BenchObservation())
      }
    },
  )
