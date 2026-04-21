package bankmod.app

import java.nio.file.Paths

import zio.*
import zio.http.*

import bankmod.graph.model.Graph
import bankmod.graph.render.SampleGraph
import bankmod.mcp.{ BankmodMcpServer, GraphStore, GraphStoreLive }
import com.jamesward.ziohttp.mcp.McpServer

/** Boots the bankmod MCP server on port 8090 (default), seeded with the canonical 8-service sample.
  *
  * If the env var `BANKMOD_GRAPH_FILE` points at a readable JSON file, [[ModelWatcher]] is started against that path:
  * every successful edit to the file is decoded, validated, and hot-swapped into the `GraphStore`, which in turn pushes
  * `notifications/resources/updated` to subscribed MCP clients.
  */
object BankmodMain extends ZIOAppDefault:

  private val Port         = 8090
  private val GraphFileEnv = "BANKMOD_GRAPH_FILE"

  def run: ZIO[Any, Throwable, Unit] =
    val program =
      for
        store     <- ZIO.service[GraphStore]
        watchPath <- ZIO.attempt(sys.env.get(GraphFileEnv).map(Paths.get(_)))
        _         <- ZIO.foreachDiscard(watchPath) { p =>
                       Console.printLine(s"bankmod: watching $p for live edits") *>
                         ModelWatcher.watch(p).forkScoped
                     }
        routes     = BankmodMcpServer.build(store).routes
        _         <- Console.printLine(s"bankmod v0.0.1 — MCP endpoint: http://localhost:$Port/mcp")
        _         <- Server.serve(routes)
      yield ()

    ZIO.scoped(program).provide(
      ZLayer.succeed[Graph](SampleGraph.sample),
      GraphStoreLive.layer,
      McpServer.State.default,
      Server.defaultWithPort(Port),
    )
