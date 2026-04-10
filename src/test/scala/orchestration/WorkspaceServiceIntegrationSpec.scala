package orchestration

import java.nio.file.{ Files, Paths }
import java.util.UUID

import zio.*
import zio.test.*
import zio.test.Assertion.*

import _root_.config.entity.MigrationConfig
import orchestration.control.WorkspaceService
import shared.services.FileService

object WorkspaceServiceIntegrationSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("WorkspaceService Integration")(
    test("workspace creation succeeds") {
      ZIO.scoped {
        for
          tempRoot <- ZIO.attemptBlocking(Files.createTempDirectory("ws-test")).orDie
          runId     = s"test-run-${UUID.randomUUID().toString.take(8)}"
          config    = MigrationConfig(
                        sourceDir = tempRoot.resolve("source"),
                        outputDir = tempRoot.resolve("output"),
                        stateDir = tempRoot.resolve(".state"),
                      )
          ws       <- WorkspaceService.create(runId, config)
        yield assertTrue(
          ws.runId == runId &&
          ws.configSnapshot.basePackage == config.basePackage
        )
      }
    },
    test("parallel workspace creation succeeds") {
      ZIO.scoped {
        for
          tempRoot <- ZIO.attemptBlocking(Files.createTempDirectory("ws-parallel")).orDie
          prefix    = s"parallel-${UUID.randomUUID().toString.take(8)}"
          config    = MigrationConfig(
                        sourceDir = tempRoot.resolve("source"),
                        outputDir = tempRoot.resolve("output"),
                        stateDir = tempRoot.resolve(".state"),
                      )
          results  <- ZIO.foreachPar(1 to 5)(i =>
                        WorkspaceService.create(s"$prefix-$i", config)
                      )
        yield assertTrue(
          results.length == 5 &&
          results.forall(ws => ws.runId.startsWith(prefix))
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
