package bankmod.mcp

import zio.*
import zio.stream.ZStream

import bankmod.graph.model.Graph
import bankmod.graph.validate.InvariantError

/** In-memory holder for the current [[Graph]] served by the bankmod MCP surface.
  *
  * `update` takes a pure function from the current snapshot to either accumulated invariant errors or a new graph. A
  * successful update atomically swaps the stored graph AND publishes the new value on a subscription hub. A failed
  * update leaves both the stored graph and the hub untouched and surfaces the errors on the failure channel.
  *
  * `updates` is a ZStream that emits every graph successfully written via `update`. Subscribers see only future
  * updates — the initial seed is delivered through `get`.
  */
trait GraphStore:
  def get: UIO[Graph]
  def update(
    f: Graph => Either[NonEmptyChunk[InvariantError], Graph]
  ): IO[NonEmptyChunk[InvariantError], Graph]
  def updates: ZStream[Any, Nothing, Graph]
