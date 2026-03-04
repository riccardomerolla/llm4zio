package mcp

import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import llm4zio.mcp.jsonrpc.{ JsonRpcRequest, RequestId }
import llm4zio.mcp.transport.SseTransport

object McpControllerSpec extends ZIOSpecDefault:

  def spec = suite("McpController")(
    suite("POST /mcp")(
      test("returns 401 when API key is required but not provided") {
        for
          transport  <- SseTransport.make(apiKey = Some("secret"))
          registry    = transport.sessions
          controller  = McpController(transport)
          request     = Request.post(URL.decode("/mcp?sessionId=s1").toOption.get, Body.fromString("{}"))
          response   <- controller.routes.runZIO(request)
        yield assertTrue(response.status == Status.Unauthorized)
      },
      test("returns 400 when sessionId is missing") {
        for
          transport  <- SseTransport.make(apiKey = None)
          controller  = McpController(transport)
          body        = JsonRpcRequest(id = Some(RequestId.Num(1L)), method = "ping").toJson
          request     = Request.post(URL.decode("/mcp").toOption.get, Body.fromString(body))
          response   <- controller.routes.runZIO(request)
        yield assertTrue(response.status == Status.BadRequest)
      },
      test("returns 404 when session does not exist") {
        for
          transport  <- SseTransport.make(apiKey = None)
          controller  = McpController(transport)
          body        = JsonRpcRequest(id = Some(RequestId.Num(1L)), method = "ping").toJson
          request     = Request.post(
                          URL.decode("/mcp?sessionId=nonexistent").toOption.get,
                          Body.fromString(body),
                        )
          response   <- controller.routes.runZIO(request)
        yield assertTrue(response.status == Status.NotFound)
      },
      test("accepts request for existing session and returns 202") {
        for
          transport  <- SseTransport.make(apiKey = None)
          sessionId  <- transport.sessions.create
          controller  = McpController(transport)
          body        = JsonRpcRequest(id = Some(RequestId.Num(1L)), method = "ping").toJson
          request     = Request.post(
                          URL.decode(s"/mcp?sessionId=$sessionId").toOption.get,
                          Body.fromString(body),
                        )
          response   <- controller.routes.runZIO(request)
        yield assertTrue(response.status == Status.Accepted)
      },
    ),
    suite("GET /mcp/sse")(
      test("returns 401 when API key is required but not provided") {
        for
          transport  <- SseTransport.make(apiKey = Some("secret"))
          controller  = McpController(transport)
          request     = Request.get(URL.decode("/mcp/sse").toOption.get)
          response   <- controller.routes.runZIO(request)
        yield assertTrue(response.status == Status.Unauthorized)
      },
      test("returns 200 with text/event-stream content type for valid request") {
        for
          transport  <- SseTransport.make(apiKey = None)
          controller  = McpController(transport)
          request     = Request.get(URL.decode("/mcp/sse").toOption.get)
          response   <- controller.routes.runZIO(request)
        yield assertTrue(
          response.status == Status.Ok,
          response.headers.get(Header.ContentType).exists(_.mediaType == MediaType.text.`event-stream`),
        )
      },
    ),
  )
