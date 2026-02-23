package shared.web

import zio.*

trait StreamAbortRegistry:
  def register(conversationId: Long, cancel: UIO[Unit]): UIO[Unit]
  def abort(conversationId: Long): UIO[Boolean]
  def unregister(conversationId: Long): UIO[Unit]

object StreamAbortRegistry:

  val live: ULayer[StreamAbortRegistry] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[Long, UIO[Unit]]).map(StreamAbortRegistryLive.apply)
    )

final case class StreamAbortRegistryLive(
  ref: Ref[Map[Long, UIO[Unit]]]
) extends StreamAbortRegistry:

  override def register(conversationId: Long, cancel: UIO[Unit]): UIO[Unit] =
    ref.update(_ + (conversationId -> cancel))

  override def abort(conversationId: Long): UIO[Boolean] =
    ref.modify { current =>
      current.get(conversationId) match
        case Some(cancel) => (cancel.as(true), current - conversationId)
        case None         => (ZIO.succeed(false), current)
    }.flatten

  override def unregister(conversationId: Long): UIO[Unit] =
    ref.update(_ - conversationId)
