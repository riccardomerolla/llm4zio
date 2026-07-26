package llm4zio.flow

import java.nio.file.{ Files, Path }

import zio.*
import zio.test.*

object ProcSpec extends ZIOSpecDefault:

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-proc-")).orDie)(d =>
      ZIO.attempt {
        Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
      }.orDie
    )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Proc")(
    test("runWithInput feeds the input to the command's stdin and captures stdout") {
      ZIO.scoped {
        for
          dir    <- tempDir
          result <- Proc.runWithInput("cat", Nil, dir, input = "{\"program\":\"ACCTXFR\"}\n")
        yield assertTrue(result.ok, result.stdout == "{\"program\":\"ACCTXFR\"}")
      }
    },
    test("runWithInput tolerates a non-zero exit like run — the caller decides") {
      ZIO.scoped {
        for
          dir    <- tempDir
          result <- Proc.runWithInput("false", Nil, dir, input = "ignored")
        yield assertTrue(!result.ok, result.exitCode != 0)
      }
    },
  ) @@ TestAspect.withLiveClock
