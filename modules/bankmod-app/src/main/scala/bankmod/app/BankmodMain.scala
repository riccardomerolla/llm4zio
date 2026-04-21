package bankmod.app

import zio.*
import zio.http.*

import com.jamesward.ziohttp.mcp.McpServer

import bankmod.graph.model.Graph
import bankmod.graph.render.SampleGraph
import bankmod.mcp.{ BankmodMcpServer, GraphStore, GraphStoreLive }

/** Boots the bankmod MCP server on port 8090 (default), seeded with the canonical 8-service sample. */
object BankmodMain extends ZIOAppDefault:

  private val Port = 8090

  def run: ZIO[Any, Throwable, Unit] =
    val program =
      for
        store <- ZIO.service[GraphStore]
        routes = BankmodMcpServer.build(store).routes
        _     <- Console.printLine(s"bankmod v0.0.1 — MCP endpoint: http://localhost:$Port/mcp")
        _     <- Server.serve(routes)
      yield ()

    program.provide(
      ZLayer.succeed[Graph](SampleGraph.sample),
      GraphStoreLive.layer,
      McpServer.State.default,
      Server.defaultWithPort(Port),
    )
