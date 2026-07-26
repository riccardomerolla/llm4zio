package llm4zio.flow

import java.nio.file.{ Files, Path }

import zio.*
import zio.json.ast.Json
import zio.test.*

object EquivSpec extends ZIOSpecDefault:

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-equiv-")).orDie)(d =>
      ZIO.attempt {
        Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
      }.orDie
    )

  private val transfer = EquivVector(
    schema = 1,
    program = "ACCTXFR",
    id = "fee-below-threshold-1",
    tier = Equiv.Tier.Generated,
    rules = List("2400-CALC-FEE"),
    inputs = Json.Obj("amount" -> Json.Str("500.00"), "fromCustomer" -> Json.Str("C1")),
    observations = List(
      Equiv.Observation.Record("output:TRANSOUT", Map("status" -> "POSTED", "fee" -> "2.50")),
      Equiv.Observation.DbMutation(
        "LEDGER",
        "insert",
        key = Map("ACCT_ID" -> "A1"),
        set = Map("AMOUNT" -> "-502.50"),
      ),
    ),
  )

  private val captured = transfer.copy(
    id = "mainframe-run-042",
    tier = Equiv.Tier.Captured,
    observations = List(Equiv.Observation.Record("output:TRANSOUT", Map("status" -> "POSTED", "fee" -> "2.50"))),
  )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Equiv")(
    test("VectorStore round-trips vectors through JSONL") {
      ZIO.scoped {
        for
          dir    <- tempDir
          path    = dir.resolve("vectors/ACCTXFR.jsonl")
          _      <- VectorStore.write(path, List(transfer, captured))
          loaded <- VectorStore.read(path)
        yield assertTrue(loaded == List(transfer, captured))
      }
    },
    test("the on-disk format is one JSON object per line with lowercase tier names — a public contract") {
      ZIO.scoped {
        for
          dir  <- tempDir
          path  = dir.resolve("ACCTXFR.jsonl")
          _    <- VectorStore.write(path, List(transfer, captured))
          text <- ZIO.attemptBlocking(Files.readString(path)).orDie
        yield
          val lines = text.strip.linesIterator.toList
          assertTrue(
            lines.size == 2,
            lines.head.contains("\"tier\":\"generated\""),
            lines(1).contains("\"tier\":\"captured\""),
          )
      }
    },
    test("read fails with a typed parse error on a malformed line") {
      ZIO.scoped {
        for
          dir    <- tempDir
          path    = dir.resolve("bad.jsonl")
          _      <- ZIO.attemptBlocking(Files.writeString(path, "not json\n")).orDie
          result <- VectorStore.read(path).either
        yield assertTrue(result.left.exists {
          case FlowError.PlanParse(_) => true
          case _                      => false
        })
      }
    },
    test("replayVector pipes the vector to the command's stdin and parses the observation array") {
      val script =
        """#!/bin/sh
          |cat > /dev/null
          |echo '[{"type":"record","kind":"output:TRANSOUT","fields":{"status":"POSTED","fee":"2.50"}}]'
          |""".stripMargin
      ZIO.scoped {
        for
          dir    <- tempDir
          _      <- ZIO.attemptBlocking {
                      val p = dir.resolve("replay.sh")
                      Files.writeString(p, script)
                      p.toFile.setExecutable(true)
                    }.orDie
          result <- Equiv.replayVector(List("./replay.sh"), dir, transfer)
        yield assertTrue(
          result == Equiv.Replayed.Observed(
            List(Equiv.Observation.Record("output:TRANSOUT", Map("status" -> "POSTED", "fee" -> "2.50")))
          )
        )
      }
    },
    test("replayVector reports a crashed replay as a typed value — one bad vector must not kill the run") {
      ZIO.scoped {
        for
          dir    <- tempDir
          result <- Equiv.replayVector(List("false"), dir, transfer)
        yield assertTrue(result match
          case Equiv.Replayed.Crashed(code, _) => code != 0
          case _                               => false)
      }
    },
    test("replayVector fails typed when the replay output is not an observation array — a broken contract") {
      val script = "#!/bin/sh\ncat > /dev/null\necho 'not json'\n"
      ZIO.scoped {
        for
          dir    <- tempDir
          _      <- ZIO.attemptBlocking {
                      val p = dir.resolve("replay.sh")
                      Files.writeString(p, script)
                      p.toFile.setExecutable(true)
                    }.orDie
          result <- Equiv.replayVector(List("./replay.sh"), dir, transfer).either
        yield assertTrue(result.left.exists {
          case FlowError.PlanParse(_) => true
          case _                      => false
        })
      }
    },
  ) @@ TestAspect.withLiveClock
