package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.*
import zio.test.*

object SpecChecksSpec extends ZIOSpecDefault:

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-speccheck-")).orDie)(d =>
      ZIO.attempt {
        Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
      }.orDie
    )

  private def write(dir: Path, name: String, content: String): UIO[Path] =
    ZIO.attemptBlocking {
      val path = dir.resolve(name)
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.write(path, content.getBytes(StandardCharsets.UTF_8))
      path
    }.orDie

  private val paragraphRule =
    CoverageRule("cobol-paragraph", """.*\.cbl""", """^ {7}(\d{4}-[A-Z0-9-]+)\.""")

  private val cobol =
    """       IDENTIFICATION DIVISION.
      |       PROCEDURE DIVISION.
      |       2100-VALIDATE-XFER.
      |           PERFORM 2300-CHECK-FUNDS.
      |       2300-CHECK-FUNDS.
      |           CONTINUE.
      |""".stripMargin

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("SpecChecks")(
    test("coverage is clean when every source unit appears in the traceability text") {
      ZIO.scoped {
        for
          dir    <- tempDir
          _      <- write(dir, "src/ACCTXFR.cbl", cobol)
          result <- SpecChecks.coverage(
                      dir,
                      List(paragraphRule),
                      "2100-VALIDATE-XFER: spec §1\n2300-CHECK-FUNDS: spec §2\n",
                    )
        yield assertTrue(result.isClean)
      }
    },
    test("coverage reports each unit missing from the traceability text as a Critical issue") {
      ZIO.scoped {
        for
          dir    <- tempDir
          _      <- write(dir, "src/ACCTXFR.cbl", cobol)
          result <- SpecChecks.coverage(dir, List(paragraphRule), "2100-VALIDATE-XFER: spec §1\n")
        yield assertTrue(
          result.issues.map(_.severity).forall(_ == Severity.Critical),
          result.issues.map(_.title) == List("uncovered cobol-paragraph: 2300-CHECK-FUNDS"),
        )
      }
    },
    test("coverage only scans files matching the rule's file scope") {
      ZIO.scoped {
        for
          dir    <- tempDir
          _      <- write(dir, "src/ACCTXFR.cbl", cobol)
          _      <- write(dir, "notes/scratch.txt", "       9999-NOT-COBOL.\n")
          result <- SpecChecks.coverage(
                      dir,
                      List(paragraphRule),
                      "2100-VALIDATE-XFER ... 2300-CHECK-FUNDS ...",
                    )
        yield assertTrue(result.isClean)
      }
    },
    test("features is clean for well-formed Gherkin files") {
      val feature =
        """Feature: Account transfer fees
          |
          |  Scenario: Flat fee below the threshold
          |    Given a transfer of 500.00 between accounts of different customers
          |    When the batch posts the transfer
          |    Then a fee of 2.50 is charged
          |""".stripMargin
      ZIO.scoped {
        for
          dir    <- tempDir
          _      <- write(dir, "features/fees.feature", feature)
          result <- SpecChecks.features(dir.resolve("features"))
        yield assertTrue(result.isClean)
      }
    },
    test("features flags a missing Feature header, a scenario-less file, and a step-less scenario") {
      ZIO.scoped {
        for
          dir    <- tempDir
          _      <- write(dir, "features/headless.feature", "Scenario: no header\n  Given something\n")
          _      <- write(dir, "features/empty.feature", "Feature: nothing here\n")
          _      <- write(dir, "features/steps.feature", "Feature: f\n\n  Scenario: hollow\n")
          result <- SpecChecks.features(dir.resolve("features"))
        yield assertTrue(
          result.issues.size == 3,
          result.issues.forall(_.severity == Severity.Critical),
          result.issues.flatMap(_.file).sorted == List("empty.feature", "headless.feature", "steps.feature"),
        )
      }
    },
    test("features fails the gate when no .feature files exist at all") {
      ZIO.scoped {
        for
          dir    <- tempDir
          result <- SpecChecks.features(dir.resolve("features"))
        yield assertTrue(!result.isClean, result.issues.size == 1)
      }
    },
  )
