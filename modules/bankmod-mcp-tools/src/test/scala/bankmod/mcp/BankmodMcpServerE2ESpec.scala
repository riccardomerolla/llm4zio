package bankmod.mcp

import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.TestAspect.*

import bankmod.graph.model.Refinements.*
import bankmod.graph.model.{ Schemas, * }

/** End-to-end test for the bankmod MCP server assembly.
  *
  * Boots the stateless routes on an OS-assigned port and drives it with the zio-http `Client`, exercising JSON-RPC over
  * HTTP: `initialize`, `resources/list`, `resources/templates/list`, `tools/list`, `prompts/list`, and two `tools/call`
  * flavors of `validateEvolution`.
  */
object BankmodMcpServerE2ESpec extends ZIOSpecDefault:

  // ── Fixture graph ────────────────────────────────────────────────────────

  private val sidA = ServiceId.from("svc-a").toOption.get
  private val sidB = ServiceId.from("svc-b").toOption.get
  private val pIn  = PortName.from("in").toOption.get
  private val pOut = PortName.from("out").toOption.get
  private val url  = UrlLike.from("https://example.com").toOption.get

  private val sla = Sla(
    LatencyMs.from(100).toOption.get,
    Percentage.from(99).toOption.get,
    BoundedRetries.from(3).toOption.get,
  )

  private val edgeAB =
    Edge(pOut, sidB, pIn, Protocol.Rest(url), Consistency.Strong, Ordering.TotalOrder)

  private val svcA = Service(
    sidA,
    Criticality.Tier1,
    Ownership.Platform,
    inbound = Set.empty,
    outbound = Set(edgeAB),
    Set.empty,
    Set.empty,
    sla,
  )

  private val svcB = Service(
    sidB,
    Criticality.Tier1,
    Ownership.Platform,
    inbound = Set(Port(pIn)),
    outbound = Set.empty,
    Set.empty,
    Set.empty,
    sla,
  )

  private val fixture: Graph = Graph(Map(sidA -> svcA, sidB -> svcB))
  private val fixtureJson    = Schemas.graphCodec.encodeToString(fixture)

  // A patch that introduces a cycle (svc-b -> svc-a) — must fail validation.
  private val cyclePatchJson =
    val edgeBA          =
      Edge(pOut, sidA, pIn, Protocol.Rest(url), Consistency.Strong, Ordering.TotalOrder)
    val svcAWithInbound = svcA.copy(inbound = Set(Port(pIn)))
    val svcBWithBack    = svcB.copy(outbound = Set(edgeBA))
    val cyclic          = Graph(Map(sidA -> svcAWithInbound, sidB -> svcBWithBack))
    Schemas.graphCodec.encodeToString(cyclic)

  // ── JSON-RPC helpers ─────────────────────────────────────────────────────

  private def rpc(id: Int, method: String, params: Option[Json.Obj] = None): String =
    val base = Json.Obj(
      "jsonrpc" -> Json.Str("2.0"),
      "id"      -> Json.Num(id),
      "method"  -> Json.Str(method),
    )
    val full = params.fold(base)(p => Json.Obj(base.fields ++ Chunk("params" -> p)))
    full.toJson

  private def toolsCall(id: Int, toolName: String, args: Json.Obj): String =
    rpc(id, "tools/call", Some(Json.Obj("name" -> Json.Str(toolName), "arguments" -> args)))

  private def post(port: Int, body: String): ZIO[Client, Throwable, Json.Obj] =
    for
      client <- ZIO.service[Client]
      resp   <- client.batched(
                  Request
                    .post(url = URL.decode(s"http://localhost:$port/mcp").toOption.get, body = Body.fromString(body))
                    .addHeaders(Headers(Header.ContentType(MediaType.application.json)))
                )
      text   <- resp.body.asString
      parsed <- ZIO.fromEither(text.fromJson[Json.Obj]).mapError(e => new RuntimeException(s"Bad JSON: $text ($e)"))
    yield parsed

  private def resultOf(rpcResponse: Json.Obj): Json.Obj =
    rpcResponse.get("result").flatMap(_.asObject).get

  // ── Server boot ──────────────────────────────────────────────────────────

  private val storeLayer: ZLayer[Any, Nothing, GraphStore] =
    ZLayer.succeed[Graph](fixture) >>> GraphStoreLive.layer

  private val installedServer: ZLayer[GraphStore & Server, Throwable, Int] =
    ZLayer.scoped {
      for
        storeInstance <- ZIO.service[GraphStore]
        routes         = BankmodMcpServer.build(storeInstance).statelessRoutes
        port          <- Server.install(routes)
      yield port
    }

  private val serverLayer: ZLayer[Any, Throwable, Int] =
    (storeLayer ++ Server.defaultWithPort(0)) >>> installedServer

  // ── Spec ─────────────────────────────────────────────────────────────────

  def spec: Spec[Any, Any] = suite("BankmodMcpServer E2E")(
    test("initialize → serverInfo, resources/list, templates/list, tools/list, prompts/list") {
      for
        port        <- ZIO.service[Int]
        // initialize (required handshake — stateless still parses it)
        initResp    <- post(
                         port,
                         rpc(
                           1,
                           "initialize",
                           Some(
                             Json.Obj(
                               "protocolVersion" -> Json.Str("2025-11-25"),
                               "capabilities"    -> Json.Obj(),
                               "clientInfo"      -> Json.Obj(
                                 "name"    -> Json.Str("bankmod-e2e"),
                                 "version" -> Json.Str("0.0.1"),
                               ),
                             )
                           ),
                         ),
                       )
        info         = resultOf(initResp)
                         .get("serverInfo")
                         .flatMap(_.asObject)
                         .get
        // resources/list
        resResp     <- post(port, rpc(2, "resources/list"))
        resources    = resultOf(resResp)
                         .get("resources")
                         .flatMap(_.asArray)
                         .getOrElse(Chunk.empty)
        // resources/templates/list
        tmplResp    <- post(port, rpc(3, "resources/templates/list"))
        templates    = resultOf(tmplResp)
                         .get("resourceTemplates")
                         .flatMap(_.asArray)
                         .getOrElse(Chunk.empty)
        // tools/list
        toolsResp   <- post(port, rpc(4, "tools/list"))
        tools        = resultOf(toolsResp)
                         .get("tools")
                         .flatMap(_.asArray)
                         .getOrElse(Chunk.empty)
        // prompts/list
        promptsResp <- post(port, rpc(5, "prompts/list"))
        prompts      = resultOf(promptsResp)
                         .get("prompts")
                         .flatMap(_.asArray)
                         .getOrElse(Chunk.empty)
      yield assertTrue(
        info.get("name").contains(Json.Str("bankmod")),
        info.get("version").contains(Json.Str("0.1.0")),
        resources.size == 1,
        templates.size == 4,
        tools.size == 6,
        prompts.size == 3,
      )
    },
    test("tools/call validateEvolution: cycle patch → accepted=false, CycleDetected") {
      for
        port      <- ZIO.service[Int]
        resp      <- post(
                       port,
                       toolsCall(
                         10,
                         "validateEvolution",
                         Json.Obj("patchJson" -> Json.Str(cyclePatchJson)),
                       ),
                     )
        result     = resultOf(resp)
        structured = result.get("structuredContent").flatMap(_.asObject).get
        accepted   = structured.get("accepted").flatMap(_.asBoolean).getOrElse(true)
        errors     = structured.get("errors").flatMap(_.asArray).getOrElse(Chunk.empty)
        firstKind  = errors.headOption
                       .flatMap(_.asObject)
                       .flatMap(_.get("kind"))
                       .flatMap(_.asString)
                       .getOrElse("")
      yield assertTrue(
        !accepted,
        errors.nonEmpty,
        firstKind == "CycleDetected",
      )
    },
    test("tools/call validateEvolution: fixture graph → accepted=true") {
      for
        port      <- ZIO.service[Int]
        resp      <- post(
                       port,
                       toolsCall(
                         11,
                         "validateEvolution",
                         Json.Obj("patchJson" -> Json.Str(fixtureJson)),
                       ),
                     )
        result     = resultOf(resp)
        structured = result.get("structuredContent").flatMap(_.asObject).get
        accepted   = structured.get("accepted").flatMap(_.asBoolean).getOrElse(false)
      yield assertTrue(accepted)
    },
    test("tools/call renderDiagram: scope=full format=mermaid returns graph LR body") {
      for
        port      <- ZIO.service[Int]
        resp      <- post(
                       port,
                       toolsCall(
                         12,
                         "renderDiagram",
                         Json.Obj(
                           "scope"  -> Json.Str("full"),
                           "format" -> Json.Str("mermaid"),
                         ),
                       ),
                     )
        result     = resultOf(resp)
        structured = result.get("structuredContent").flatMap(_.asObject).get
        body       = structured.get("body").flatMap(_.asString).getOrElse("")
      yield assertTrue(body.startsWith("graph LR"))
    },
    test("resources/read graph://full returns the fixture JSON") {
      for
        port    <- ZIO.service[Int]
        resp    <- post(
                     port,
                     rpc(
                       13,
                       "resources/read",
                       Some(Json.Obj("uri" -> Json.Str("graph://full"))),
                     ),
                   )
        result   = resultOf(resp)
        contents = result.get("contents").flatMap(_.asArray).getOrElse(Chunk.empty)
        text     = contents.headOption
                     .flatMap(_.asObject)
                     .flatMap(_.get("text"))
                     .flatMap(_.asString)
                     .getOrElse("")
      yield assertTrue(text == fixtureJson)
    },
  ).provideShared(serverLayer, Client.default) @@ withLiveClock @@ sequential
