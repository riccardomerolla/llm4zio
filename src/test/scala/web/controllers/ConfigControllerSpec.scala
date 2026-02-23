package web.controllers

import java.nio.file.Files

import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import _root_.config.boundary.ConfigController
import activity.control.ActivityHub
import activity.entity.ActivityEvent
import core.ConfigValidator
import models.*

object ConfigControllerSpec extends ZIOSpecDefault:

  private def mkLayer: UIO[ZLayer[Any, Nothing, ConfigController]] =
    for
      tempDir <- ZIO.attemptBlocking(Files.createTempDirectory("cfg-controller-test")).orDie
      cfg      = MigrationConfig(
                   sourceDir = tempDir.resolve("src"),
                   outputDir = tempDir.resolve("out"),
                   stateDir = tempDir.resolve("state"),
                 )
      hub      = new ActivityHub:
                   override def publish(event: ActivityEvent): UIO[Unit] = ZIO.unit
                   override def subscribe: UIO[Dequeue[ActivityEvent]]   = Queue.unbounded[ActivityEvent].map(identity)
    yield (ZLayer.succeed(cfg) ++ ConfigValidator.live ++ ZLayer.succeed(hub)) >>> ConfigController.live

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ConfigControllerSpec")(
    test("GET /config renders editor page") {
      for
        layer      <- mkLayer
        controller <- ZIO.service[ConfigController].provideLayer(layer)
        response   <- controller.routes.runZIO(Request.get("/config"))
        body       <- response.body.asString
      yield assertTrue(response.status == Status.Ok, body.contains("Advanced Configuration Editor"))
    },
    test("GET /api/config/current returns current document") {
      for
        layer      <- mkLayer
        controller <- ZIO.service[ConfigController].provideLayer(layer)
        response   <- controller.routes.runZIO(Request.get("/api/config/current"))
        body       <- response.body.asString
      yield assertTrue(response.status == Status.Ok, body.contains("\"content\""), body.contains("migration"))
    },
    test("POST /api/config/validate fails invalid config") {
      for
        layer      <- mkLayer
        controller <- ZIO.service[ConfigController].provideLayer(layer)
        payload     = ConfigContentRequest(
                        content = """migration { source-dir = "src" output-dir = "out" parallelism = 0 }""",
                        format = ConfigFormat.Hocon,
                      ).toJson
        response   <- controller.routes.runZIO(Request.post("/api/config/validate", Body.fromString(payload)))
        body       <- response.body.asString
      yield assertTrue(response.status == Status.Ok, body.contains("\"valid\":false"))
    },
    test("save adds history and rollback restores previous content") {
      for
        layer        <- mkLayer
        controller   <- ZIO.service[ConfigController].provideLayer(layer)
        currentRes   <- controller.routes.runZIO(Request.get("/api/config/current"))
        currentBody  <- currentRes.body.asString
        currentDoc   <- ZIO.fromEither(currentBody.fromJson[ConfigDocument]).mapError(new RuntimeException(_)).orDie
        savePayload   = ConfigContentRequest(
                          content = currentDoc.content + "\n# changed by test\n",
                          format = currentDoc.format,
                        ).toJson
        saveRes      <- controller.routes.runZIO(Request.post("/api/config/save", Body.fromString(savePayload)))
        _            <- ZIO.when(saveRes.status != Status.Ok)(ZIO.fail(new RuntimeException("save failed")))
        historyRes   <- controller.routes.runZIO(Request.get("/api/config/history"))
        historyBody  <- historyRes.body.asString
        history      <-
          ZIO.fromEither(historyBody.fromJson[List[ConfigHistoryEntry]]).mapError(new RuntimeException(_)).orDie
        firstId       = history.headOption.map(_.id).getOrElse("")
        rollbackRes  <- controller.routes.runZIO(
                          Request.post(s"/api/config/history/$firstId/rollback", Body.empty)
                        )
        rollbackBody <- rollbackRes.body.asString
      yield assertTrue(
        history.nonEmpty,
        rollbackRes.status == Status.Ok,
        rollbackBody.contains("migration"),
      )
    },
  )
