package integration

import java.nio.file.{ Files, Path }

import zio.*
import zio.logging.backend.SLF4J
import zio.test.*

import agents.CobolDiscoveryAgent
import core.FileService
import models.{ FileType, MigrationConfig }

object CobolDiscoveryIntegrationSpec extends ZIOSpecDefault:

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>> testEnvironment

  private val samplesDir    = Path.of("cobol-source/samples")
  private val expectedNames = Set(
    "CUSTOMER-DATA.cpy",
    "CUSTOMER-DISPLAY.cbl",
    "CUSTOMER-INQUIRY.cbl",
    "ERROR-CODES.cpy",
    "FORMAT-BALANCE.cbl",
    "TEST-PROGRAM.cbl",
  )

  def spec: Spec[Any, Any] = suite("CobolDiscoveryIntegrationSpec")(
    test("discovers sample COBOL programs and copybooks") {
      for
        _            <- ensureSamplesDir
        inventory    <-
          CobolDiscoveryAgent
            .discover(samplesDir)
            .provide(
              FileService.live,
              ZLayer.succeed(MigrationConfig(sourceDir = samplesDir, outputDir = Path.of("target/it-output"))),
              CobolDiscoveryAgent.live,
            )
        names         = inventory.files.map(_.name).toSet
        programCount  = inventory.files.count(_.fileType == FileType.Program)
        copybookCount = inventory.files.count(_.fileType == FileType.Copybook)
      yield assertTrue(
        inventory.summary.totalFiles == expectedNames.size,
        programCount == 4,
        copybookCount == 2,
        names == expectedNames,
      )
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  private def ensureSamplesDir: Task[Unit] =
    ZIO
      .attemptBlocking(Files.isDirectory(samplesDir))
      .flatMap { isDir =>
        ZIO.fail(new RuntimeException(s"Missing COBOL samples at $samplesDir")).unless(isDir)
      }
      .unit
