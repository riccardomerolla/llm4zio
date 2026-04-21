package bankmod.mcp.tools

import zio.*
import zio.schema.{DeriveSchema, Schema}

import bankmod.graph.model.*
import bankmod.mcp.GraphStore

object QueryDependenciesTool:

  /** One (from → to) hop discovered during BFS. */
  final case class Hop(
    from: String,
    to: String,
    port: String,
    protocol: String,
    hopDepth: Int,
  )
  object Hop:
    given Schema[Hop] = DeriveSchema.gen

  final case class Output(
    root: String,
    neighborhood: List[Hop],
  )
  object Output:
    given Schema[Output] = DeriveSchema.gen

  final case class Failure(message: String)
  object Failure:
    given Schema[Failure] = DeriveSchema.gen

  /** Pure BFS on an in-memory Graph. Validates input, bounds depth to [1, 5]. */
  def run(input: QueryDependenciesInput, graph: Graph): Either[Failure, Output] =
    for
      _ <- Either.cond(
             input.depth >= 1 && input.depth <= 5,
             (),
             Failure(s"depth must be in [1,5], got ${input.depth}"),
           )
      rootId <- ServiceId.from(input.serviceId).left.map(Failure.apply)
      _ <- Either.cond(
             graph.services.contains(rootId),
             (),
             Failure(s"Unknown service: ${input.serviceId}"),
           )
    yield
      val visited = scala.collection.mutable.Set[(String, String, String)]()
      val hops    = scala.collection.mutable.ListBuffer[Hop]()
      val queue   = scala.collection.mutable.Queue[(ServiceId, Int)]((rootId, 0))
      while queue.nonEmpty do
        val (current, d) = queue.dequeue()
        if d < input.depth then
          graph.services.get(current).foreach { svc =>
            svc.outbound.foreach { edge =>
              val key = (current.value, edge.toService.value, edge.toPort.value)
              if !visited.contains(key) then
                val _ = visited.add(key)
                hops.addOne(
                  Hop(
                    from = current.value,
                    to = edge.toService.value,
                    port = edge.toPort.value,
                    protocol = protocolLabel(edge.protocol),
                    hopDepth = d + 1,
                  )
                )
                queue.enqueue((edge.toService, d + 1))
            }
          }
      Output(rootId.value, hops.toList)

  /** Effectful wrapper: reads the current graph from the store, runs BFS. */
  def handle(input: QueryDependenciesInput): ZIO[GraphStore, Failure, Output] =
    ZIO.serviceWithZIO[GraphStore](_.get).flatMap { graph =>
      ZIO.fromEither(run(input, graph))
    }

  private def protocolLabel(p: Protocol): String = p match
    case _: Protocol.Rest    => "rest"
    case _: Protocol.Grpc    => "grpc"
    case _: Protocol.Event   => "event"
    case _: Protocol.Graphql => "graphql"
    case _: Protocol.Soap    => "soap"
