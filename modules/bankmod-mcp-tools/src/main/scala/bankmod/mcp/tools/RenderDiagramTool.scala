package bankmod.mcp.tools

import zio.*
import zio.schema.{ DeriveSchema, Schema }

import bankmod.graph.model.*
import bankmod.graph.render.*
import bankmod.mcp.GraphStore

object RenderDiagramTool:

  final case class Output(format: String, scope: String, body: String)
  object Output:
    given Schema[Output] = DeriveSchema.gen

  final case class Failure(message: String)
  object Failure:
    given Schema[Failure] = DeriveSchema.gen

  /** Pure render: decodes scope + format, slices the graph, delegates to the right interpreter. */
  def run(input: RenderDiagramInput, graph: Graph): Either[Failure, Output] =
    for
      sliced      <- sliceByScope(input.scope, graph)
      (scope, g0)  = sliced
      interpreter <- pickInterpreter(input.format)
      body         = interpreter.render(g0)
    yield Output(format = input.format.toLowerCase, scope = scope, body = body)

  def handle(input: RenderDiagramInput): ZIO[GraphStore, Failure, Output] =
    ZIO.serviceWithZIO[GraphStore](_.get).flatMap { graph =>
      ZIO.fromEither(run(input, graph))
    }

  private def sliceByScope(scope: String, graph: Graph): Either[Failure, (String, Graph)] =
    scope match
      case "full"                        =>
        Right("full" -> graph)
      case s if s.startsWith("service:") =>
        val idRaw = s.stripPrefix("service:")
        for
          sid <- ServiceId.from(idRaw).left.map(Failure.apply)
          _   <- Either.cond(graph.services.contains(sid), (), Failure(s"Unknown service: $idRaw"))
        yield
          val root      = graph.services(sid)
          val neighbors = root.outbound.map(_.toService).flatMap(graph.services.get)
          val sliceMap  = (neighbors.toSeq :+ root).map(svc => svc.id -> svc).toMap
          (s"service:$idRaw", Graph(sliceMap))
      case other                         =>
        Left(Failure(s"Unknown scope: $other (expected 'full' or 'service:<id>')"))

  private def pickInterpreter(format: String): Either[Failure, GraphInterpreter[String]] =
    format.toLowerCase match
      case "mermaid"     => Right(MermaidInterpreter.interpreter)
      case "d2"          => Right(D2Interpreter.interpreter)
      case "structurizr" => Right(StructurizrInterpreter.interpreter)
      case "json"        => Right(JsonSchemaInterpreter.interpreter)
      case other         => Left(Failure(s"Unknown format: $other"))
