package app.boundary

import zio.*
import zio.http.*
import zio.test.*

object WebServerSpec extends ZIOSpecDefault:

  override def spec: Spec[Any, Any] =
    suite("WebServer")(
      test("serves WebJar resources under /webjars") {
        for
          response <- request("/webjars/htmx.org/2.0.7/dist/htmx.min.js")
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("htmx"),
        )
      },
      test("serves application static resources under /static") {
        for
          response <- request("/static/client/components/ab-icon-button.js")
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("customElements.define"),
        )
      },
    ).provide(WebServerSpec.routeModules, WebServer.live)

  private def request(path: String): ZIO[WebServer, Response, Response] =
    ZIO.serviceWithZIO[WebServer](server => ZIO.scoped(server.routes.runZIO(Request.get(URL(Path.decode(path))))))

  private val routeModules: ULayer[AdeRouteModule & CoreRouteModule & GatewayRouteModule & ConfigRouteModule &
    WorkspaceRouteModule] =
    ZLayer.make[AdeRouteModule & CoreRouteModule & GatewayRouteModule & ConfigRouteModule & WorkspaceRouteModule](
      ZLayer.succeed(new AdeRouteModule:
        override val routes: Routes[Any, Response] = Routes.empty),
      ZLayer.succeed(new CoreRouteModule:
        override val routes: Routes[Any, Response] = Routes.empty),
      ZLayer.succeed(new GatewayRouteModule:
        override val routes: Routes[Any, Response] = Routes.empty),
      ZLayer.succeed(new ConfigRouteModule:
        override val routes: Routes[Any, Response] = Routes.empty),
      ZLayer.succeed(new WorkspaceRouteModule:
        override val routes: Routes[Any, Response] = Routes.empty),
    )
