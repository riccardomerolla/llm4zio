package conversation.entity

import zio.*

import shared.errors.PersistenceError
import shared.ids.Ids.{ ConversationId, TaskRunId }

final case class ConversationFilter(
  runId: Option[TaskRunId] = None,
  channel: Option[ChannelInfo] = None,
  includeClosed: Boolean = true,
  offset: Int = 0,
  limit: Int = 100,
)

trait ConversationRepository:
  def append(event: ConversationEvent): IO[PersistenceError, Unit]
  def get(id: ConversationId): IO[PersistenceError, Conversation]
  def list(filter: ConversationFilter): IO[PersistenceError, List[Conversation]]

object ConversationRepository:
  def append(event: ConversationEvent): ZIO[ConversationRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConversationRepository](_.append(event))

  def get(id: ConversationId): ZIO[ConversationRepository, PersistenceError, Conversation] =
    ZIO.serviceWithZIO[ConversationRepository](_.get(id))

  def list(filter: ConversationFilter): ZIO[ConversationRepository, PersistenceError, List[Conversation]] =
    ZIO.serviceWithZIO[ConversationRepository](_.list(filter))
