package llm4zio.mcp.transport

import java.io.{ InputStream, OutputStream }

import zio.*
import zio.json.*
import zio.stream.*

import llm4zio.mcp.jsonrpc.*
import llm4zio.mcp.server.{ McpError, McpTransport }

/** MCP transport over stdin/stdout — newline-delimited JSON-RPC framing.
  * All logging goes to stderr to avoid corrupting the protocol on stdout.
  */
class StdioTransport(in: InputStream, out: OutputStream) extends McpTransport:

  override def receive: ZStream[Any, McpError, JsonRpcRequest] =
    ZStream
      .fromInputStream(in)
      .mapError(e => McpError.TransportError(e.getMessage))
      .via((ZPipeline.utf8Decode >>> ZPipeline.splitLines).mapError(e => McpError.TransportError(e.getMessage)))
      .filter(_.trim.nonEmpty)
      .mapZIO { line =>
        ZIO
          .fromEither(line.fromJson[JsonRpcRequest])
          .mapError(e => McpError.ProtocolError(s"Failed to parse JSON-RPC request: $e"))
      }

  override def send(response: JsonRpcResponse): IO[McpError, Unit] =
    writeJson(response.toJson)

  override def notify(notification: JsonRpcNotification): IO[McpError, Unit] =
    writeJson(notification.toJson)

  private def writeJson(json: String): IO[McpError, Unit] =
    ZIO
      .attempt {
        val bytes = (json + "\n").getBytes("UTF-8")
        out.synchronized {
          out.write(bytes)
          out.flush()
        }
      }
      .mapError(e => McpError.TransportError(s"Write failed: ${e.getMessage}"))

object StdioTransport:
  def fromStreams(in: InputStream, out: OutputStream): StdioTransport =
    new StdioTransport(in, out)

  /** Live layer using System.in / System.out. */
  val live: ULayer[McpTransport] =
    ZLayer.succeed(new StdioTransport(java.lang.System.in, java.lang.System.out))
