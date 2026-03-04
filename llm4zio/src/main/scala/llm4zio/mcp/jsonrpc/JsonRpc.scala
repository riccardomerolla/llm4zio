package llm4zio.mcp.jsonrpc

import zio.*
import zio.json.*
import zio.json.ast.Json

/** JSON-RPC 2.0 request id — either a String or a Long number. */
enum RequestId:
  case Str(value: String)
  case Num(value: Long)

object RequestId:
  given JsonDecoder[RequestId] = JsonDecoder[Json].mapOrFail {
    case Json.Str(s)  => Right(RequestId.Str(s))
    case Json.Num(n)  => Right(RequestId.Num(BigDecimal(n).toLongExact))
    case other        => Left(s"RequestId must be a string or number, got: $other")
  }
  given JsonEncoder[RequestId] = JsonEncoder[Json].contramap {
    case RequestId.Str(s) => Json.Str(s)
    case RequestId.Num(n) => Json.Num(BigDecimal(n))
  }

/** A JSON-RPC 2.0 request or notification.
  * When `id` is None this is a notification (no response expected).
  */
case class JsonRpcRequest(
  jsonrpc: String = "2.0",
  id: Option[RequestId] = None,
  method: String,
  params: Option[Json] = None,
) derives JsonCodec

/** Standard JSON-RPC 2.0 error object. */
case class JsonRpcError(
  code: Int,
  message: String,
  data: Option[Json] = None,
) derives JsonCodec

object JsonRpcError:
  def parseError(msg: String): JsonRpcError      = JsonRpcError(-32700, s"Parse error: $msg")
  def invalidRequest(msg: String): JsonRpcError  = JsonRpcError(-32600, s"Invalid request: $msg")
  def methodNotFound(method: String): JsonRpcError =
    JsonRpcError(-32601, s"Method not found: $method")
  def invalidParams(msg: String): JsonRpcError   = JsonRpcError(-32602, s"Invalid params: $msg")
  def internalError(msg: String): JsonRpcError   = JsonRpcError(-32603, s"Internal error: $msg")

/** A JSON-RPC 2.0 response. Exactly one of `result` or `error` is set. */
case class JsonRpcResponse(
  jsonrpc: String = "2.0",
  id: RequestId,
  result: Option[Json] = None,
  error: Option[JsonRpcError] = None,
) derives JsonCodec

object JsonRpcResponse:
  def success(id: RequestId, result: Json): JsonRpcResponse =
    JsonRpcResponse(id = id, result = Some(result))
  def error(id: RequestId, err: JsonRpcError): JsonRpcResponse =
    JsonRpcResponse(id = id, error = Some(err))

/** A JSON-RPC 2.0 notification — no id, no response expected. */
case class JsonRpcNotification(
  jsonrpc: String = "2.0",
  method: String,
  params: Option[Json] = None,
) derives JsonCodec

/** Handles incoming JSON-RPC requests and returns responses. */
trait JsonRpcHandler:
  def handle(request: JsonRpcRequest): UIO[JsonRpcResponse]

object JsonRpcHandler:
  /** Build a handler from a partial function; falls back to method-not-found. */
  def of(pf: PartialFunction[JsonRpcRequest, UIO[JsonRpcResponse]]): JsonRpcHandler =
    request =>
      pf.lift(request).getOrElse(
        ZIO.succeed(
          JsonRpcResponse.error(
            request.id.getOrElse(RequestId.Num(0L)),
            JsonRpcError.methodNotFound(request.method),
          )
        )
      )

  val empty: JsonRpcHandler =
    of(PartialFunction.empty)
