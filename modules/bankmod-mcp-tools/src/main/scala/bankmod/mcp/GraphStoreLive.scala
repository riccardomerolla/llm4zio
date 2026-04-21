package bankmod.mcp

import zio.*
import zio.stream.ZStream

import bankmod.graph.model.Graph
import bankmod.graph.validate.InvariantError

/** In-memory [[GraphStore]] backed by a `Ref.Synchronized` + sliding `Hub`.
  *
  * We use `Ref.Synchronized.modifyZIO` so the `read → validate → publish → write` sequence is linearised: even with
  * multiple concurrent callers of `update`, each user-supplied `f` observes a consistent snapshot and cannot race
  * another writer between validation and commit. For the single-writer-via-MCP MVP this is overkill, but it costs
  * nothing and keeps the semantics trivially correct if we ever expose a second writer.
  *
  * The hub is `sliding(16)` so a slow subscriber cannot back-pressure writers; an overwhelmed consumer simply drops the
  * oldest buffered update.
  */
object GraphStoreLive:

  val layer: ZLayer[Graph, Nothing, GraphStore] = ZLayer.fromZIO:
    for
      seed <- ZIO.service[Graph]
      ref  <- Ref.Synchronized.make(seed)
      hub  <- Hub.sliding[Graph](16)
    yield new GraphStore:
      def get: UIO[Graph] = ref.get

      def update(
        f: Graph => Either[NonEmptyChunk[InvariantError], Graph]
      ): IO[NonEmptyChunk[InvariantError], Graph] =
        ref.modifyZIO { current =>
          f(current) match
            case Left(errs)  => ZIO.fail(errs)
            case Right(next) => hub.publish(next).as((next, next))
        }

      def updates: ZStream[Any, Nothing, Graph] = ZStream.fromHub(hub)
