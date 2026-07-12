package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, StandardOpenOption }

import zio.*
import zio.test.*

object CostLedgerSpec extends ZIOSpecDefault:

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-costs-")).orDie)(d =>
      ZIO.attempt {
        Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
      }.orDie
    )

  private def cell(stage: String, agent: String = "coder", model: String = "m", prompt: Long = 100): CostCell =
    CostCell(stage, agent, model, prompt, 10, None, prompt + 10, Some(0.01))

  private def record(runId: String, label: Option[String] = None): CostRecord =
    CostRecord.from(
      runId = runId,
      at = "2026-07-12T10:00:00Z",
      repo = "meridian-legacy-abc123",
      label = label,
      prompt = "Extract the complete behavioural spec pack\nfor this legacy estate",
      pricesAsOf = PriceList.asOf,
      cells = List(cell("Extract"), cell("Gate", agent = "reasoning", prompt = 50)),
    )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("CostLedger")(
    test("from computes totals, a one-line 120-char prompt head, and a stable prompt hash") {
      val r     = record("run-1")
      val again = record("run-1")
      yieldAssert(r, again)
    },
    test("append then load round-trips records in order") {
      ZIO.scoped {
        for
          dir  <- tempDir
          _    <- CostLedger.append(dir, record("run-1"))
          _    <- CostLedger.append(dir, record("run-2", label = Some("meridian-acctxfr")))
          back <- CostLedger.load(dir)
        yield assertTrue(
          back.map(_.runId) == List("run-1", "run-2"),
          back(1).label.contains("meridian-acctxfr"),
          back(0).cells.size == 2,
        )
      }
    },
    test("load returns Nil for an absent ledger and skips malformed lines") {
      ZIO.scoped {
        for
          dir    <- tempDir
          absent <- CostLedger.load(dir)
          _      <- CostLedger.append(dir, record("run-1"))
          _      <- ZIO.attemptBlocking {
                      Files.write(
                        CostLedger.path(dir),
                        "{not json}\n".getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.APPEND,
                      )
                    }.orDie
          _      <- CostLedger.append(dir, record("run-2"))
          back   <- CostLedger.load(dir)
        yield assertTrue(absent.isEmpty, back.map(_.runId) == List("run-1", "run-2"))
      }
    },
    test("write appends the tracker's cells as one record, and writes nothing for a token-less run") {
      ZIO.scoped {
        for
          dir     <- tempDir
          tracker <- CostTracker.make
          _       <- tracker.record(FlowEvent.StageStarted("Gate"))
          _       <- tracker.record(
                       FlowEvent.TokensUsed("reasoning", Some("gemini-2.5-pro"), llm4zio.core.TokenUsage(100, 10, 110))
                     )
          _       <- CostLedger.write(dir, tracker, "run-1", "meridian", Some("acctxfr"), "Extract the spec pack")
          silent  <- CostTracker.make
          _       <- CostLedger.write(dir, silent, "run-2", "meridian", None, "seed only, no LLM calls")
          back    <- CostLedger.load(dir)
        yield assertTrue(
          back.map(_.runId) == List("run-1"),
          back.head.label.contains("acctxfr"),
          back.head.cells.exists(c => c.stage == "Gate" && c.agent == "reasoning" && c.prompt == 100L),
          back.head.pricesAsOf == PriceList.asOf,
        )
      }
    },
    test("mergeCells rolls up identical (stage, agent, model) cells across records") {
      val merged = CostLedger.mergeCells(List(record("run-1"), record("run-2")))
      val ext    = merged.find(_.stage == "Extract")
      yieldMerge(merged, ext)
    },
  )

  private def yieldAssert(r: CostRecord, again: CostRecord) =
    assertTrue(
      r.totalPrompt == 150L,
      r.totalCompletion == 20L,
      r.totalCostUsd.exists(c => math.abs(c - 0.02) < 0.0001),
      r.promptHead.length <= 120,
      !r.promptHead.contains("\n"),
      r.promptHash == again.promptHash,
      r.pricesAsOf.nonEmpty,
    )

  private def yieldMerge(merged: List[CostCell], ext: Option[CostCell]) =
    assertTrue(
      merged.size == 2,
      ext.exists(c => c.prompt == 200L && c.costUsd.exists(v => math.abs(v - 0.02) < 0.0001)),
    )
