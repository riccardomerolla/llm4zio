package bankmod.mcp

import zio.*
import com.jamesward.ziohttp.mcp.*

import bankmod.mcp.prompts.BankmodPrompts
import bankmod.mcp.resources.GraphResources
import bankmod.mcp.tools.*

/** Assembles the bankmod MCP surface on top of `zio-http-mcp`.
  *
  * Tools use the `GraphStore` environment slot (wired by layer at boot). Resources close over
  * the concrete store value because the library's resource-read handler is fixed to `ZIO[Any, ToolError, _]`.
  * Prompts also run in `Any` and are pure transformations over argument maps.
  */
object BankmodMcpServer:

  given queryDepsErr: McpError[QueryDependenciesTool.Failure] with
    def message(e: QueryDependenciesTool.Failure): String = e.message

  given renderDiagramErr: McpError[RenderDiagramTool.Failure] with
    def message(e: RenderDiagramTool.Failure): String = e.message

  def build(store: GraphStore): McpServer[GraphStore] =
    McpServer("bankmod", "0.1.0")
      .tool(queryDependenciesTool)
      .tool(renderDiagramTool)
      .tool(validateEvolutionTool)
      .tool(proposeServiceTool)
      .tool(explainInvariantViolationTool)
      .tool(listInvariantsTool)
      .resource(graphFullResource(store))
      .resourceTemplate(graphServiceTemplate(store))
      .resourceTemplate(graphEdgeTemplate(store))
      .resourceTemplate(graphInvariantTemplate(store))
      .resourceTemplate(graphSliceTemplate(store))
      .prompt(addServicePrompt)
      .prompt(migrateEndpointPrompt)
      .prompt(introduceEventPrompt)

  // ── Tools ────────────────────────────────────────────────────────────────

  private val queryDependenciesTool: McpToolHandlerR[GraphStore] =
    McpTool("queryDependencies")
      .description(
        "BFS traversal of the services graph from a given service, out to a depth of 1..5."
      )
      .handle((in: QueryDependenciesInput) => QueryDependenciesTool.handle(in))

  private val renderDiagramTool: McpToolHandlerR[GraphStore] =
    McpTool("renderDiagram")
      .description(
        "Render the graph (full or a service neighborhood) in one of: mermaid, d2, structurizr, json."
      )
      .handle((in: RenderDiagramInput) => RenderDiagramTool.handle(in))

  private val validateEvolutionTool: McpToolHandlerR[GraphStore] =
    McpTool("validateEvolution")
      .description(
        "Decode a full Graph JSON and run the validator. Does NOT commit; use proposeService to commit."
      )
      .handle((in: ValidateEvolutionInput) => ValidateEvolutionTool.handle(in))

  private val proposeServiceTool: McpToolHandlerR[GraphStore] =
    McpTool("proposeService")
      .description(
        "Decode a full Graph JSON, validate, and atomically swap it into the store on success."
      )
      .handle((in: ProposeServiceInput) => ProposeServiceTool.handle(in))

  private val explainInvariantViolationTool: McpToolHandlerR[Any] =
    McpTool("explainInvariantViolation")
      .description(
        "Given an invariant-error kind tag, return a human-readable explanation and suggested fixes."
      )
      .handle((in: ExplainInvariantViolationInput) => ExplainInvariantViolationTool.handle(in))

  private val listInvariantsTool: McpToolHandlerR[Any] =
    McpTool("listInvariants")
      .description("List every invariant the validator enforces, with short descriptions.")
      .handle((in: ListInvariantsInput) => ListInvariantsTool.handle(in))

  // ── Resources ────────────────────────────────────────────────────────────

  private def handleUri(store: GraphStore, uri: String): ZIO[Any, ToolError, Chunk[ResourceContents]] =
    store.get.flatMap { g =>
      ZIO
        .fromEither(GraphResources.read(uri, g))
        .mapError(ToolError.apply)
        .map { rb =>
          Chunk.single(
            ResourceContents(
              uri = rb.uri,
              mimeType = Some(rb.mimeType),
              text = Some(rb.body),
              blob = None,
            )
          )
        }
    }

  private def graphFullResource(store: GraphStore): McpResourceHandler =
    McpResource("graph://full", "full")
      .description("The entire services graph as JSON.")
      .mimeType("application/json")
      .read(uri => handleUri(store, uri))

  private def graphServiceTemplate(store: GraphStore): McpResourceTemplateHandler =
    McpResourceTemplate("graph://service/{serviceId}", "service")
      .description("Single service JSON, looked up by id.")
      .mimeType("application/json")
      .read(uri => handleUri(store, uri))

  private def graphEdgeTemplate(store: GraphStore): McpResourceTemplateHandler =
    McpResourceTemplate("graph://edge/{fromService}/{toService}/{toPort}", "edge")
      .description("Single edge JSON, addressed by (from, to, toPort).")
      .mimeType("application/json")
      .read(uri => handleUri(store, uri))

  private def graphInvariantTemplate(store: GraphStore): McpResourceTemplateHandler =
    McpResourceTemplate("graph://invariant/{name}", "invariant")
      .description("Catalog entry for a named invariant.")
      .mimeType("application/json")
      .read(uri => handleUri(store, uri))

  private def graphSliceTemplate(store: GraphStore): McpResourceTemplateHandler =
    McpResourceTemplate("graph://slice/{serviceId}/{depth}", "slice")
      .description("N-hop dependency neighborhood around a service (depth 1..5).")
      .mimeType("application/json")
      .read(uri => handleUri(store, uri))

  // ── Prompts ──────────────────────────────────────────────────────────────

  private def convertRole(r: BankmodPrompts.Role): Role = r match
    case BankmodPrompts.Role.User      => Role.User
    case BankmodPrompts.Role.Assistant => Role.Assistant

  private def toLibMessages(msgs: List[BankmodPrompts.PromptMessage]): Chunk[PromptMessage] =
    Chunk.fromIterable(
      msgs.map(m => PromptMessage(convertRole(m.role), ToolContent.text(m.content)))
    )

  private def asPromptResult(p: BankmodPrompts.Prompt): PromptGetResult =
    PromptGetResult(description = Some(p.description), messages = toLibMessages(p.messages))

  private def requireArg(m: Map[String, String], key: String): IO[ToolError, String] =
    ZIO.fromOption(m.get(key)).orElseFail(ToolError(s"Missing required argument: $key"))

  private val addServicePrompt: McpPromptHandler =
    McpPrompt("addService")
      .description("Propose adding a new service to the bankmod graph.")
      .argument("serviceId", "Proposed service id (e.g. 'loan-service')", true)
      .argument("tier", "Criticality tier label (Tier1/Tier2/Tier3)", true)
      .argument("owner", "Owner label (Platform/Product/Shared)", true)
      .argument("reason", "Short rationale for adding the service", true)
      .get { args =>
        for
          sid    <- requireArg(args, "serviceId")
          tier   <- requireArg(args, "tier")
          owner  <- requireArg(args, "owner")
          reason <- requireArg(args, "reason")
        yield asPromptResult(
          BankmodPrompts.addService(BankmodPrompts.AddServiceParams(sid, tier, owner, reason))
        )
      }

  private val migrateEndpointPrompt: McpPromptHandler =
    McpPrompt("migrateEndpoint")
      .description("Migrate a service endpoint from one port to another.")
      .argument("serviceId", "Service whose port is being migrated", true)
      .argument("oldPort", "Old port name", true)
      .argument("newPort", "New port name", true)
      .argument("protocol", "Protocol (rest/grpc/event/graphql/soap)", true)
      .get { args =>
        for
          sid      <- requireArg(args, "serviceId")
          oldPort  <- requireArg(args, "oldPort")
          newPort  <- requireArg(args, "newPort")
          protocol <- requireArg(args, "protocol")
        yield asPromptResult(
          BankmodPrompts.migrateEndpoint(
            BankmodPrompts.MigrateEndpointParams(sid, oldPort, newPort, protocol)
          )
        )
      }

  private val introduceEventPrompt: McpPromptHandler =
    McpPrompt("introduceEvent")
      .description("Introduce or promote an event-driven edge between two services.")
      .argument("fromService", "Source service id", true)
      .argument("toService", "Target service id", true)
      .argument("topic", "Event topic name", true)
      .argument("reason", "Rationale", true)
      .get { args =>
        for
          fromSvc <- requireArg(args, "fromService")
          toSvc   <- requireArg(args, "toService")
          topic   <- requireArg(args, "topic")
          reason  <- requireArg(args, "reason")
        yield asPromptResult(
          BankmodPrompts.introduceEvent(
            BankmodPrompts.IntroduceEventParams(fromSvc, toSvc, topic, reason)
          )
        )
      }
