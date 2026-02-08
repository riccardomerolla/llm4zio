package di

import java.nio.file.Files

import zio.*
import zio.http.*
import zio.test.*

import db.*
import models.*
import orchestration.MigrationOrchestrator
import web.WebServer

object ApplicationDISpec extends ZIOSpecDefault:

  private def makeConfig(stateDir: java.nio.file.Path): MigrationConfig =
    MigrationConfig(
      sourceDir = stateDir.resolve("source"),
      outputDir = stateDir.resolve("output"),
      stateDir = stateDir,
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ApplicationDISpec")(
    test("commonLayers builds and provides expected services") {
      ZIO.scoped {
        for
          stateDir <- ZIO.attemptBlocking(Files.createTempDirectory("app-di-common"))
          dbPath    = stateDir.resolve("migration.db")
          cfg       = makeConfig(stateDir)
          settings <- ZIO
                        .serviceWithZIO[MigrationRepository](_.getAllSettings)
                        .provideLayer(ApplicationDI.commonLayers(cfg, dbPath))
                        .mapError(err => new RuntimeException(err.toString))
                        .orDie
        yield assertTrue(settings.isEmpty)
      }
    },
    test("orchestratorLayer builds and handles listRuns") {
      ZIO.scoped {
        for
          stateDir <- ZIO.attemptBlocking(Files.createTempDirectory("app-di-orchestrator"))
          dbPath    = stateDir.resolve("migration.db")
          cfg       = makeConfig(stateDir)
          runs     <- MigrationOrchestrator
                        .listRuns(page = 1, pageSize = 10)
                        .provideLayer(ApplicationDI.orchestratorLayer(cfg, dbPath))
                        .mapError(err => new RuntimeException(err.toString))
                        .orDie
        yield assertTrue(runs.isEmpty)
      }
    },
    test("webServerLayer builds and serves settings route") {
      ZIO.scoped {
        for
          stateDir <- ZIO.attemptBlocking(Files.createTempDirectory("app-di-web"))
          dbPath    = stateDir.resolve("migration.db")
          cfg       = makeConfig(stateDir)
          env      <- ApplicationDI.webServerLayer(cfg, dbPath).build
          response <- env.get[WebServer].routes.runZIO(Request.get(URL.decode("/settings").toOption.get))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("Settings"),
        )
      }
    },
  )
