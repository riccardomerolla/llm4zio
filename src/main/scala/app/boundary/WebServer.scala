package app.boundary

import zio.*
import zio.http.*

trait WebServer:
  def routes: Routes[Any, Response]

object WebServer:

  val live: ZLayer[
    AdeRouteModule & CoreRouteModule & GatewayRouteModule & ConfigRouteModule & WorkspaceRouteModule,
    Nothing,
    WebServer,
  ] = ZLayer {
    for
      adeRoutes       <- ZIO.service[AdeRouteModule]
      coreRoutes      <- ZIO.service[CoreRouteModule]
      gatewayRoutes   <- ZIO.service[GatewayRouteModule]
      configRoutes    <- ZIO.service[ConfigRouteModule]
      workspaceRoutes <- ZIO.service[WorkspaceRouteModule]
      staticRoutes     = Routes.serveResources(Path.empty / "static")
      devCatalogRoutes = Routes(
                           Method.GET / "components" -> handler {
                             Response
                               .text(shared.web.ComponentsCatalogView.page())
                               .contentType(MediaType.text.html)
                           }
                         )
    yield new WebServer {
      override val routes: Routes[Any, Response] =
        (coreRoutes.routes ++
          configRoutes.routes ++
          gatewayRoutes.routes ++
          workspaceRoutes.routes ++
          adeRoutes.routes ++
          devCatalogRoutes ++
          staticRoutes) @@ shared.web.RequestLoggingMiddleware.live
    }
  }
  private val defaultShutdownTimeout = java.time.Duration.ofSeconds(3L)

  def start(port: Int): ZIO[WebServer, Throwable, Nothing] =
    start(host = "0.0.0.0", port = port)

  def start(host: String, port: Int): ZIO[WebServer, Throwable, Nothing] =
    val config =
      Server.Config.default
        .binding(host, port)
        .gracefulShutdownTimeout(defaultShutdownTimeout)

    ZIO.serviceWithZIO[WebServer](server => Server.serve(server.routes).provide(Server.defaultWith(_ => config)))
