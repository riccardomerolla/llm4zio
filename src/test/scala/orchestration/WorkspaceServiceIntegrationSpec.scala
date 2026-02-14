package orchestration

import java.nio.file.{ Files, Paths }

import zio.*
import zio.test.*
import zio.test.Assertion.*

import core.FileService
import models.*

object WorkspaceServiceIntegrationSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("WorkspaceService Integration")(
    test("workspace creation succeeds") {
      ZIO.scoped {
        for
          tempRoot <- ZIO.attemptBlocking(Files.createTempDirectory("ws-test")).orDie
          config    = MigrationConfig(
                        sourceDir = tempRoot.resolve("source"),
                        outputDir = tempRoot.resolve("output"),
                        stateDir = tempRoot.resolve(".state"),
                      )
          ws       <- WorkspaceService.create("test-run-1", config)
        yield assertTrue(
          ws.runId == "test-run-1" &&
          ws.configSnapshot.basePackage == config.basePackage
        )
      }
    },
    test("parallel workspace creation succeeds") {
      ZIO.scoped {
        for
          tempRoot <- ZIO.attemptBlocking(Files.createTempDirectory("ws-parallel")).orDie
          config    = MigrationConfig(
                        sourceDir = tempRoot.resolve("source"),
                        outputDir = tempRoot.resolve("output"),
                        stateDir = tempRoot.resolve(".state"),
                      )
          results  <- ZIO.foreachPar(1 to 5)(i =>
                        WorkspaceService.create(s"parallel-$i", config)
                      )
        yield assertTrue(
          results.length == 5 &&
          results.forall(ws => ws.runId.startsWith("parallel-"))
        )
      }
    },
  ).provide(
    FileService.live,
    ZLayer.succeed(
      MigrationConfig(
        sourceDir = Paths.get("./test-source"),
        outputDir = Paths.get("./test-output"),
        stateDir = Paths.get("./.test-state"),
      )
    ),
    WorkspaceService.live,
  )
