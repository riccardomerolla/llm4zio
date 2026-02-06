package integration

import java.nio.file.{ Files, Path }

import zio.*
import zio.logging.backend.SLF4J
import zio.test.*

import core.FileService
import prompts.PromptHelpers

object PromptHelpersIntegrationSpec extends ZIOSpecDefault:

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>> testEnvironment

  private val sampleFile = Path.of("cobol-source/samples/CUSTOMER-INQUIRY.cbl")

  def spec: Spec[Any, Any] = suite("PromptHelpersIntegrationSpec")(
    test("chunks divisions from CUSTOMER-INQUIRY sample") {
      for
        _       <- ensureSampleFile
        content <- FileService.readFile(sampleFile).provide(FileService.live)
        chunks   = PromptHelpers.chunkByDivision(content)
      yield assertTrue(
        chunks.contains("IDENTIFICATION"),
        chunks.contains("ENVIRONMENT"),
        chunks.contains("DATA"),
        chunks.contains("PROCEDURE"),
        chunks.get("PROCEDURE").exists(_.contains("SEARCH-CUSTOMER")),
      )
    }
  ) @@ TestAspect.sequential

  private def ensureSampleFile: Task[Unit] =
    ZIO
      .attemptBlocking(Files.isRegularFile(sampleFile))
      .flatMap { exists =>
        ZIO.fail(new RuntimeException(s"Missing COBOL sample at $sampleFile")).unless(exists)
      }
      .unit
