package gateway

import zio.*
import zio.stream.ZStream

import gateway.models.NormalizedMessage

trait ChannelRegistry:
  def register(channel: MessageChannel): UIO[Unit]
  def unregister(channelName: String): UIO[Unit]
  def get(channelName: String): IO[MessageChannelError, MessageChannel]
  def list: UIO[List[MessageChannel]]
  def publish(channelName: String, message: NormalizedMessage): IO[MessageChannelError, Unit]
  def inboundMerged: ZStream[Any, MessageChannelError, NormalizedMessage]

object ChannelRegistry:
  def register(channel: MessageChannel): ZIO[ChannelRegistry, Nothing, Unit] =
    ZIO.serviceWithZIO[ChannelRegistry](_.register(channel))

  def unregister(channelName: String): ZIO[ChannelRegistry, Nothing, Unit] =
    ZIO.serviceWithZIO[ChannelRegistry](_.unregister(channelName))

  def get(channelName: String): ZIO[ChannelRegistry, MessageChannelError, MessageChannel] =
    ZIO.serviceWithZIO[ChannelRegistry](_.get(channelName))

  def list: ZIO[ChannelRegistry, Nothing, List[MessageChannel]] =
    ZIO.serviceWithZIO[ChannelRegistry](_.list)

  def publish(channelName: String, message: NormalizedMessage): ZIO[ChannelRegistry, MessageChannelError, Unit] =
    ZIO.serviceWithZIO[ChannelRegistry](_.publish(channelName, message))

  def inboundMerged: ZStream[ChannelRegistry, MessageChannelError, NormalizedMessage] =
    ZStream.serviceWithStream[ChannelRegistry](_.inboundMerged)

  val empty: ULayer[ChannelRegistry] =
    ZLayer.fromZIO(Ref.Synchronized.make(Map.empty[String, MessageChannel]).map(ChannelRegistryLive.apply))

final case class ChannelRegistryLive(
  channelsRef: Ref.Synchronized[Map[String, MessageChannel]]
) extends ChannelRegistry:

  override def register(channel: MessageChannel): UIO[Unit] =
    channelsRef.update(_ + (channel.name -> channel)).unit

  override def unregister(channelName: String): UIO[Unit] =
    channelsRef.update(_ - channelName).unit

  override def get(channelName: String): IO[MessageChannelError, MessageChannel] =
    channelsRef.get.flatMap { channels =>
      channels.get(channelName) match
        case Some(channel) => ZIO.succeed(channel)
        case None          => ZIO.fail(MessageChannelError.ChannelNotFound(channelName))
    }

  override def list: UIO[List[MessageChannel]] =
    channelsRef.get.map(_.values.toList.sortBy(_.name))

  override def publish(channelName: String, message: NormalizedMessage): IO[MessageChannelError, Unit] =
    get(channelName).flatMap(_.send(message))

  override def inboundMerged: ZStream[Any, MessageChannelError, NormalizedMessage] =
    ZStream.fromZIO(list).flatMap { registered =>
      if registered.isEmpty then ZStream.empty
      else ZStream.mergeAllUnbounded()(registered.map(_.inbound)*)
    }
