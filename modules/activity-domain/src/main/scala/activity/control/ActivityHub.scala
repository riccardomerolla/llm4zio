package activity.control

import zio.*

import activity.entity.{ ActivityEvent, ActivityRepository }

trait ActivityHub:
  def publish(event: ActivityEvent): UIO[Unit]
  def subscribe: UIO[Dequeue[ActivityEvent]]

object ActivityHub:

  def publish(event: ActivityEvent): ZIO[ActivityHub, Nothing, Unit] =
    ZIO.serviceWithZIO[ActivityHub](_.publish(event))

  def subscribe: ZIO[ActivityHub, Nothing, Dequeue[ActivityEvent]] =
    ZIO.serviceWithZIO[ActivityHub](_.subscribe)

  val live: ZLayer[ActivityRepository, Nothing, ActivityHub] =
    ZLayer.fromZIO {
      for
        repository  <- ZIO.service[ActivityRepository]
        subscribers <- Ref.make(Set.empty[Queue[ActivityEvent]])
      yield ActivityHubLive(repository, subscribers)
    }

final case class ActivityHubLive(
  repository: ActivityRepository,
  subscribers: Ref[Set[Queue[ActivityEvent]]],
) extends ActivityHub:

  override def publish(event: ActivityEvent): UIO[Unit] =
    repository
      .createEvent(event)
      .catchAll(err => ZIO.logWarning(s"Failed to persist activity event: $err"))
      .unit *>
      subscribers.get.flatMap { queues =>
        ZIO.foreachDiscard(queues)(_.offer(event).unit)
      }

  override def subscribe: UIO[Dequeue[ActivityEvent]] =
    for
      queue <- Queue.bounded[ActivityEvent](64)
      _     <- subscribers.update(_ + queue)
    yield queue
