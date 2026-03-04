package llm4zio.mcp.transport

import java.util.UUID

import zio.*
import zio.stream.*

import llm4zio.mcp.jsonrpc.*
import llm4zio.mcp.server.{ McpError, McpTransport }

/** Per-session outbound queue entry: notification or response. */
type SessionOutbound = Either[JsonRpcNotification, JsonRpcResponse]

/** Registry of active SSE sessions, each with a bounded outbound queue. */
class SseSessionRegistry(state: Ref[Map[String, Queue[SessionOutbound]]]):

  def create: UIO[String] =
    for
      id    <- ZIO.succeed(UUID.randomUUID().toString)
      queue <- Queue.bounded[SessionOutbound](128)
      _     <- state.update(_ + (id -> queue))
    yield id

  def close(id: String): UIO[Unit] =
    state.modify(m => (m.get(id), m - id)).flatMap {
      case Some(q) => q.shutdown
      case None    => ZIO.unit
    }

  def exists(id: String): UIO[Boolean] =
    state.get.map(_.contains(id))

  def enqueue(id: String, msg: SessionOutbound): UIO[Unit] =
    state.get.flatMap(m => ZIO.foreachDiscard(m.get(id))(_.offer(msg).unit))

  /** Broadcast a message to all active sessions. */
  def broadcast(msg: SessionOutbound): UIO[Unit] =
    state.get.flatMap(m => ZIO.foreachDiscard(m.values)(_.offer(msg).unit))

  /** Non-blocking poll of the next item in a session queue (for testing). */
  def take(id: String): UIO[Option[SessionOutbound]] =
    state.get.flatMap { m =>
      m.get(id) match
        case Some(q) => q.poll
        case None    => ZIO.succeed(None)
    }

  /** Infinite stream of outbound messages for a session (used for SSE). */
  def stream(id: String): ZStream[Any, Nothing, SessionOutbound] =
    ZStream.unwrap(
      state.get.map { m =>
        m.get(id) match
          case Some(q) => ZStream.fromQueue(q)
          case None    => ZStream.empty
      }
    )

object SseSessionRegistry:
  def make: UIO[SseSessionRegistry] =
    Ref.make(Map.empty[String, Queue[SessionOutbound]]).map(new SseSessionRegistry(_))

// ---------------------------------------------------------------------------
// SseTransport
// ---------------------------------------------------------------------------

/** MCP transport over HTTP SSE.
  *
  * - Client connects via `GET /mcp/sse?sessionId=<id>` and receives a stream of SSE events.
  * - Client sends requests via `POST /mcp?sessionId=<id>` with JSON-RPC body.
  *
  * The gateway layer is responsible for mounting the HTTP routes and pushing
  * incoming requests into this transport's inbound queue via `accept()`.
  * Responses are sent per-session via `sendToSession()`. Notifications are broadcast.
  */
class SseTransport(
  val sessions: SseSessionRegistry,
  val apiKey: Option[String],
  inboundQ: Queue[JsonRpcRequest],
) extends McpTransport:

  override def receive: ZStream[Any, McpError, JsonRpcRequest] =
    ZStream.fromQueue(inboundQ)

  override def send(response: JsonRpcResponse): IO[McpError, Unit] =
    ZIO.unit // Per-session routing done via sendToSession

  override def notify(notification: JsonRpcNotification): IO[McpError, Unit] =
    sessions.broadcast(Left(notification))

  /** Called by the HTTP route handler to enqueue a response to a specific session. */
  def sendToSession(sessionId: String, response: JsonRpcResponse): UIO[Unit] =
    sessions.enqueue(sessionId, Right(response))

  /** Called by the HTTP route handler to push an inbound request. */
  def accept(request: JsonRpcRequest): UIO[Unit] =
    inboundQ.offer(request).unit

  /** True when the request carries a valid API key (or no key is required). */
  def validateKey(providedKey: Option[String]): Boolean =
    apiKey match
      case None         => true
      case Some(secret) => providedKey.contains(secret)

object SseTransport:
  def make(apiKey: Option[String]): UIO[SseTransport] =
    for
      sessions <- SseSessionRegistry.make
      inboundQ <- Queue.unbounded[JsonRpcRequest]
    yield new SseTransport(sessions, apiKey, inboundQ)

  val live: ZLayer[Any, Nothing, McpTransport] =
    ZLayer.fromZIO(make(apiKey = None))
