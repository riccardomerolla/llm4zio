package bankmod.mcp.resources

import java.nio.charset.StandardCharsets

import zio.*
import zio.schema.codec.{ BinaryCodec, JsonCodec as ZioJsonCodec }
import zio.schema.{ DeriveSchema, Schema }

import bankmod.graph.model.*
import bankmod.mcp.GraphStore
import bankmod.mcp.tools.*

/** Pure URI-to-content lookup for bankmod graph resources. The zio-http-mcp `McpResource` / `McpResourceTemplate`
  * wrappers are applied at server assembly time — this object is the single source of truth for resource semantics so
  * it can be tested in isolation.
  */
object GraphResources:

  /** Result of a resource read. `uri` is echoed back so server adapters don't have to track it. */
  final case class ResourceBody(uri: String, mimeType: String, body: String)
  object ResourceBody:
    given Schema[ResourceBody] = DeriveSchema.gen

  /** Flat string-only view of an Edge — keeps zio-blocks-schema out of the resources layer. */
  final case class EdgeView(
    fromService: String,
    fromPort: String,
    toService: String,
    toPort: String,
    protocol: String,
    consistency: String,
    ordering: String,
  )
  object EdgeView:
    given Schema[EdgeView]           = DeriveSchema.gen
    val codec: BinaryCodec[EdgeView] = ZioJsonCodec.schemaBasedBinaryCodec[EdgeView]

  val uriTemplates: List[String] = List(
    "graph://full",
    "graph://service/{serviceId}",
    "graph://edge/{fromService}/{toService}/{toPort}",
    "graph://invariant/{name}",
    "graph://slice/{serviceId}/{depth}",
  )

  private val MimeJson = "application/json"

  private val invariantCodec: BinaryCodec[ListInvariantsTool.InvariantEntry] =
    ZioJsonCodec.schemaBasedBinaryCodec[ListInvariantsTool.InvariantEntry]

  private val sliceCodec: BinaryCodec[QueryDependenciesTool.Output] =
    ZioJsonCodec.schemaBasedBinaryCodec[QueryDependenciesTool.Output]

  def read(uri: String, graph: Graph): Either[String, ResourceBody] =
    uri match
      case "graph://full" =>
        Right(ResourceBody(uri, MimeJson, Schemas.graphCodec.encodeToString(graph)))

      case s"graph://service/$idRaw" =>
        for
          sid <- ServiceId.from(idRaw)
          svc <- graph.services.get(sid).toRight(s"Unknown service: $idRaw")
        yield ResourceBody(uri, MimeJson, Schemas.schemaService.jsonCodec.encodeToString(svc))

      case s"graph://edge/$fromRaw/$toRaw/$portRaw" =>
        for
          fromId <- ServiceId.from(fromRaw)
          svc    <- graph.services.get(fromId).toRight(s"Unknown service: $fromRaw")
          edge   <- svc.outbound
                      .find(e => e.toService.value == toRaw && e.toPort.value == portRaw)
                      .toRight(s"Edge not found: $fromRaw -> $toRaw on port $portRaw")
        yield
          val view = EdgeView(
            fromService = fromRaw,
            fromPort = edge.fromPort.value,
            toService = edge.toService.value,
            toPort = edge.toPort.value,
            protocol = protocolLabel(edge.protocol),
            consistency = edge.consistency.toString,
            ordering = edge.ordering.toString,
          )
          ResourceBody(uri, MimeJson, encodeAsString(EdgeView.codec, view))

      case s"graph://invariant/$name" =>
        ListInvariantsTool.catalog
          .find(_.kind == name)
          .map(entry => ResourceBody(uri, MimeJson, encodeAsString(invariantCodec, entry)))
          .toRight(s"Unknown invariant: $name")

      case s"graph://slice/$idRaw/$depthRaw" =>
        for
          depth <- depthRaw.toIntOption.toRight(s"depth must be an integer, got '$depthRaw'")
          out   <- QueryDependenciesTool
                     .run(QueryDependenciesInput(idRaw, depth), graph)
                     .left
                     .map(_.message)
        yield ResourceBody(uri, MimeJson, encodeAsString(sliceCodec, out))

      case other =>
        Left(s"Unknown URI: $other")

  def handle(uri: String): ZIO[GraphStore, String, ResourceBody] =
    ZIO.serviceWithZIO[GraphStore](_.get).flatMap(g => ZIO.fromEither(read(uri, g)))

  private def encodeAsString[A](codec: BinaryCodec[A], value: A): String =
    new String(codec.encode(value).toArray, StandardCharsets.UTF_8)

  private def protocolLabel(p: Protocol): String = p match
    case _: Protocol.Rest    => "Rest"
    case _: Protocol.Grpc    => "Grpc"
    case _: Protocol.Event   => "Event"
    case _: Protocol.Graphql => "Graphql"
    case _: Protocol.Soap    => "Soap"
