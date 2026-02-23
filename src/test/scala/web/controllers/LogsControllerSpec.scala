package web.controllers

import zio.*
import zio.http.*
import zio.test.*

import taskrun.boundary.LogsControllerLive

object LogsControllerSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LogsControllerSpec")(
    test("GET /logs renders live log viewer page") {
      val controller = LogsControllerLive()
      for
        response <- controller.routes.runZIO(Request.get("/logs"))
        body     <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("Live Logs"),
        body.contains("log-viewer"),
        body.contains("/ws/logs"),
      )
    },
    test("GET /logs propagates custom path into page") {
      val controller = LogsControllerLive()
      for
        response <- controller.routes.runZIO(Request.get(URL.decode("/logs?path=tmp/dev.log").toOption.get))
        body     <- response.body.asString
      yield assertTrue(body.contains("tmp/dev.log"))
    },
  )
