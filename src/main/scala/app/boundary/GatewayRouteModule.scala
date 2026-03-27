package app.boundary

import zio.*
import zio.http.*

import conversation.boundary.WebSocketController as ConversationWebSocketController
import gateway.boundary.{
  ChannelController as GatewayChannelController,
  TelegramController as GatewayTelegramController,
}
import mcp.McpService

trait GatewayRouteModule:
  def routes: Routes[Any, Response]

object GatewayRouteModule:
  val live
    : ZLayer[
      GatewayTelegramController &
        GatewayChannelController &
        ConversationWebSocketController &
        McpService,
      Nothing,
      GatewayRouteModule,
    ] =
    ZLayer {
      for
        telegram  <- ZIO.service[GatewayTelegramController]
        channels  <- ZIO.service[GatewayChannelController]
        websocket <- ZIO.service[ConversationWebSocketController]
        mcpSvc    <- ZIO.service[McpService]
      yield new GatewayRouteModule:
        override val routes: Routes[Any, Response] =
          telegram.routes ++ channels.routes ++ websocket.routes ++ mcpSvc.controller.routes
    }
