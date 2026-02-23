package gateway.control

import java.time.Instant

import zio.*
import zio.json.*
import zio.stream.ZStream

import gateway.entity.NormalizedMessage

enum ChannelStatus derives JsonCodec:
  case Connected
  case Disconnected
  case NotConfigured
  case Error(message: String)

final case class ChannelRuntime(
  status: ChannelStatus = ChannelStatus.Disconnected,
  lastActivity: Option[Instant] = None,
  errorCount: Long = 0L,
  lastError: Option[String] = None,
) derives JsonCodec

trait ChannelRegistry:
  def register(channel: MessageChannel): UIO[Unit]
  def unregister(channelName: String): UIO[Unit]
  def get(channelName: String): IO[MessageChannelError, MessageChannel]
  def list: UIO[List[MessageChannel]]
  def getStatus(channelName: String): UIO[ChannelStatus]
  def getRuntime(channelName: String): UIO[ChannelRuntime]
  def listRuntime: UIO[Map[String, ChannelRuntime]]
  def markConnected(channelName: String): UIO[Unit]
  def markDisconnected(channelName: String): UIO[Unit]
  def markNotConfigured(channelName: String): UIO[Unit]
  def markActivity(channelName: String, timestamp: Instant): UIO[Unit]
  def markError(channelName: String, message: String): UIO[Unit]
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

  def getStatus(channelName: String): ZIO[ChannelRegistry, Nothing, ChannelStatus] =
    ZIO.serviceWithZIO[ChannelRegistry](_.getStatus(channelName))

  def getRuntime(channelName: String): ZIO[ChannelRegistry, Nothing, ChannelRuntime] =
    ZIO.serviceWithZIO[ChannelRegistry](_.getRuntime(channelName))

  def listRuntime: ZIO[ChannelRegistry, Nothing, Map[String, ChannelRuntime]] =
    ZIO.serviceWithZIO[ChannelRegistry](_.listRuntime)

  def markConnected(channelName: String): ZIO[ChannelRegistry, Nothing, Unit] =
    ZIO.serviceWithZIO[ChannelRegistry](_.markConnected(channelName))

  def markDisconnected(channelName: String): ZIO[ChannelRegistry, Nothing, Unit] =
    ZIO.serviceWithZIO[ChannelRegistry](_.markDisconnected(channelName))

  def markNotConfigured(channelName: String): ZIO[ChannelRegistry, Nothing, Unit] =
    ZIO.serviceWithZIO[ChannelRegistry](_.markNotConfigured(channelName))

  def markActivity(channelName: String, timestamp: Instant): ZIO[ChannelRegistry, Nothing, Unit] =
    ZIO.serviceWithZIO[ChannelRegistry](_.markActivity(channelName, timestamp))

  def markError(channelName: String, message: String): ZIO[ChannelRegistry, Nothing, Unit] =
    ZIO.serviceWithZIO[ChannelRegistry](_.markError(channelName, message))

  def publish(channelName: String, message: NormalizedMessage): ZIO[ChannelRegistry, MessageChannelError, Unit] =
    ZIO.serviceWithZIO[ChannelRegistry](_.publish(channelName, message))

  def inboundMerged: ZStream[ChannelRegistry, MessageChannelError, NormalizedMessage] =
    ZStream.serviceWithStream[ChannelRegistry](_.inboundMerged)

  val empty: ULayer[ChannelRegistry] =
    ZLayer.fromZIO {
      for
        channels <- Ref.Synchronized.make(Map.empty[String, MessageChannel])
        runtime  <- Ref.Synchronized.make(Map.empty[String, ChannelRuntime])
      yield ChannelRegistryLive(channels, runtime)
    }

final case class ChannelRegistryLive(
  channelsRef: Ref.Synchronized[Map[String, MessageChannel]],
  runtimeRef: Ref.Synchronized[Map[String, ChannelRuntime]],
) extends ChannelRegistry:

  override def register(channel: MessageChannel): UIO[Unit] =
    channelsRef.update(_ + (channel.name -> channel)) *>
      runtimeRef.update { current =>
        val existing = current.getOrElse(channel.name, ChannelRuntime())
        current.updated(channel.name, existing.copy(status = ChannelStatus.Disconnected))
      }.unit

  override def unregister(channelName: String): UIO[Unit] =
    channelsRef.update(_ - channelName) *>
      runtimeRef.update { current =>
        val existing = current.getOrElse(channelName, ChannelRuntime())
        current.updated(channelName, existing.copy(status = ChannelStatus.NotConfigured))
      }.unit

  override def get(channelName: String): IO[MessageChannelError, MessageChannel] =
    channelsRef.get.flatMap { channels =>
      channels.get(channelName) match
        case Some(channel) => ZIO.succeed(channel)
        case None          => ZIO.fail(MessageChannelError.ChannelNotFound(channelName))
    }

  override def list: UIO[List[MessageChannel]] =
    channelsRef.get.map(_.values.toList.sortBy(_.name))

  override def getStatus(channelName: String): UIO[ChannelStatus] =
    runtimeRef.get.map(_.get(channelName).map(_.status).getOrElse(ChannelStatus.NotConfigured))

  override def getRuntime(channelName: String): UIO[ChannelRuntime] =
    runtimeRef.get.map(_.getOrElse(channelName, ChannelRuntime(status = ChannelStatus.NotConfigured)))

  override def listRuntime: UIO[Map[String, ChannelRuntime]] =
    runtimeRef.get

  override def markConnected(channelName: String): UIO[Unit] =
    updateRuntime(channelName)(_.copy(status = ChannelStatus.Connected, lastError = None))

  override def markDisconnected(channelName: String): UIO[Unit] =
    updateRuntime(channelName)(_.copy(status = ChannelStatus.Disconnected))

  override def markNotConfigured(channelName: String): UIO[Unit] =
    updateRuntime(channelName)(_.copy(status = ChannelStatus.NotConfigured))

  override def markActivity(channelName: String, timestamp: Instant): UIO[Unit] =
    updateRuntime(channelName)(_.copy(
      lastActivity = Some(timestamp),
      status = ChannelStatus.Connected,
      lastError = None,
    ))

  override def markError(channelName: String, message: String): UIO[Unit] =
    updateRuntime(channelName)(runtime =>
      runtime.copy(
        status = ChannelStatus.Error(message),
        errorCount = runtime.errorCount + 1L,
        lastError = Some(message),
      )
    )

  override def publish(channelName: String, message: NormalizedMessage): IO[MessageChannelError, Unit] =
    get(channelName).flatMap(_.send(message)).tapBoth(
      err => markError(channelName, err.toString),
      _ => Clock.instant.flatMap(now => markActivity(channelName, now)),
    )

  override def inboundMerged: ZStream[Any, MessageChannelError, NormalizedMessage] =
    ZStream.fromZIO(list).flatMap { registered =>
      if registered.isEmpty then ZStream.empty
      else ZStream.mergeAllUnbounded()(registered.map(_.inbound)*)
    }

  private def updateRuntime(
    channelName: String
  )(
    update: ChannelRuntime => ChannelRuntime
  ): UIO[Unit] =
    runtimeRef.update { current =>
      val existing = current.getOrElse(channelName, ChannelRuntime())
      current.updated(channelName, update(existing))
    }.unit
