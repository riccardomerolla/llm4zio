package agent.entity

import zio.*
import zio.json.*

import shared.errors.PersistenceError
import shared.ids.Ids.AgentId
import shared.store.{ DataStoreService, EventStore }

trait AgentRepository:
  def append(event: AgentEvent): IO[PersistenceError, Unit]
  def get(id: AgentId): IO[PersistenceError, Agent]
  def list(includeDeleted: Boolean = false): IO[PersistenceError, List[Agent]]
  def findByName(name: String): IO[PersistenceError, Option[Agent]]

object AgentRepository:
  def append(event: AgentEvent): ZIO[AgentRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[AgentRepository](_.append(event))

  def get(id: AgentId): ZIO[AgentRepository, PersistenceError, Agent] =
    ZIO.serviceWithZIO[AgentRepository](_.get(id))

  def list(includeDeleted: Boolean = false): ZIO[AgentRepository, PersistenceError, List[Agent]] =
    ZIO.serviceWithZIO[AgentRepository](_.list(includeDeleted))

  def findByName(name: String): ZIO[AgentRepository, PersistenceError, Option[Agent]] =
    ZIO.serviceWithZIO[AgentRepository](_.findByName(name))

final case class AgentRepositoryES(
  eventStore: EventStore[AgentId, AgentEvent],
  dataStore: DataStoreService,
) extends AgentRepository:

  private def snapshotKey(id: AgentId): String = s"snapshot:agent:${id.value}"
  private val snapshotPrefix                   = "snapshot:agent:"

  private def rebuildSnapshot(id: AgentId): IO[PersistenceError, Agent] =
    for
      events <- eventStore.events(id)
      agent  <- ZIO
                  .fromEither(Agent.fromEvents(events))
                  .mapError(msg => PersistenceError.SerializationFailed(s"agent:${id.value}", msg))
      _      <- dataStore
                  .store(snapshotKey(id), agent.toJson)
                  .mapError(err => PersistenceError.QueryFailed("storeAgentSnapshot", err.toString))
    yield agent

  override def append(event: AgentEvent): IO[PersistenceError, Unit] =
    eventStore.append(event.agentId, event) *> rebuildSnapshot(event.agentId).unit

  override def get(id: AgentId): IO[PersistenceError, Agent] =
    dataStore
      .fetch[String, String](snapshotKey(id))
      .mapError(err => PersistenceError.QueryFailed("getAgentSnapshot", err.toString))
      .flatMap {
        case Some(json) =>
          ZIO.fromEither(json.fromJson[Agent]).mapError(err =>
            PersistenceError.SerializationFailed(s"agent:${id.value}", err)
          )
        case None       =>
          eventStore.events(id).flatMap {
            case Nil => ZIO.fail(PersistenceError.NotFound("agent", id.value))
            case _   => rebuildSnapshot(id)
          }
      }

  override def list(includeDeleted: Boolean = false): IO[PersistenceError, List[Agent]] =
    dataStore.streamKeys[String]
      .filter(_.startsWith(snapshotPrefix))
      .runCollect
      .mapError(err => PersistenceError.QueryFailed("listAgents", err.toString))
      .flatMap(keys =>
        ZIO.foreach(keys.toList) { key =>
          dataStore
            .fetch[String, String](key)
            .mapError(err => PersistenceError.QueryFailed("listAgents", err.toString))
            .flatMap {
              case Some(json) =>
                ZIO
                  .fromEither(json.fromJson[Agent])
                  .mapError(err => PersistenceError.SerializationFailed(key, err))
                  .map(Some(_))
              case None       => ZIO.succeed(None)
            }
        }
      )
      .map(_.flatten)
      .map((agents: List[Agent]) => agents.filter(agent => includeDeleted || agent.deletedAt.isEmpty))
      .map((agents: List[Agent]) => agents.sortBy(_.name.toLowerCase))

  override def findByName(name: String): IO[PersistenceError, Option[Agent]] =
    list(includeDeleted = true).map(_.find(_.name.equalsIgnoreCase(name.trim)))

object AgentRepositoryES:
  val live: ZLayer[EventStore[AgentId, AgentEvent] & DataStoreService, Nothing, AgentRepository] =
    ZLayer.fromZIO {
      for
        eventStore <- ZIO.service[EventStore[AgentId, AgentEvent]]
        dataStore  <- ZIO.service[DataStoreService]
      yield AgentRepositoryES(eventStore, dataStore)
    }
