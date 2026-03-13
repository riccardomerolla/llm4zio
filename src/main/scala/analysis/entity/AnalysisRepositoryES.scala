package analysis.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.AnalysisDocId
import shared.store.{ DataStoreModule, EventStore }

final case class AnalysisRepositoryES(
  eventStore: EventStore[AnalysisDocId, AnalysisEvent],
  dataStore: DataStoreModule.DataStoreService,
) extends AnalysisRepository:

  private def snapshotKey(id: AnalysisDocId): String =
    s"snapshot:analysis:${id.value}"

  private def snapshotPrefix: String =
    "snapshot:analysis:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  private def rebuildSnapshot(id: AnalysisDocId): IO[PersistenceError, Option[AnalysisDoc]] =
    for
      events <- eventStore.events(id)
      docOpt <- ZIO
                  .fromEither(AnalysisDoc.fromEvents(events))
                  .mapError(msg => PersistenceError.SerializationFailed(s"analysis:${id.value}", msg))
      _      <- docOpt match
                  case Some(doc) =>
                    dataStore
                      .store(snapshotKey(id), doc.toJson)
                      .mapError(storeErr("storeAnalysisSnapshot"))
                  case None      =>
                    dataStore.remove[String](snapshotKey(id)).mapError(storeErr("removeAnalysisSnapshot"))
    yield docOpt

  override def append(event: AnalysisEvent): IO[PersistenceError, Unit] =
    for
      _ <- eventStore.append(event.docId, event)
      _ <- rebuildSnapshot(event.docId)
    yield ()

  override def get(id: AnalysisDocId): IO[PersistenceError, AnalysisDoc] =
    fetchSnapshot(id).flatMap {
      case Some(doc) => ZIO.succeed(doc)
      case None      =>
        eventStore.events(id).flatMap {
          case Nil => ZIO.fail(PersistenceError.NotFound("analysis_doc", id.value))
          case _   =>
            rebuildSnapshot(id).flatMap {
              case Some(doc) => ZIO.succeed(doc)
              case None      => ZIO.fail(PersistenceError.NotFound("analysis_doc", id.value))
            }
        }
    }

  override def listByWorkspace(workspaceId: String): IO[PersistenceError, List[AnalysisDoc]] =
    listAll.map(_.filter(_.workspaceId == workspaceId.trim))

  override def listByType(analysisType: AnalysisType): IO[PersistenceError, List[AnalysisDoc]] =
    listAll.map(_.filter(_.analysisType == analysisType))

  private def fetchSnapshot(id: AnalysisDocId): IO[PersistenceError, Option[AnalysisDoc]] =
    dataStore
      .fetch[String, String](snapshotKey(id))
      .mapError(storeErr("fetchAnalysisSnapshot"))
      .flatMap {
        case None       => ZIO.succeed(None)
        case Some(json) =>
          ZIO
            .fromEither(json.fromJson[AnalysisDoc])
            .mapBoth(
              err => PersistenceError.SerializationFailed(s"analysis:${id.value}", err),
              Some(_),
            )
      }

  private def listAll: IO[PersistenceError, List[AnalysisDoc]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(snapshotPrefix))
      .runCollect
      .mapError(storeErr("listAnalysisDocs"))
      .flatMap(keys =>
        ZIO.foreach(keys.toList) { key =>
          dataStore
            .fetch[String, String](key)
            .mapError(storeErr("listAnalysisDocs"))
            .flatMap {
              case None       => ZIO.succeed(None)
              case Some(json) =>
                ZIO
                  .fromEither(json.fromJson[AnalysisDoc])
                  .mapBoth(err => PersistenceError.SerializationFailed(key, err), Some(_))
            }
        }
      )
      .map(_.flatten.sortBy(_.createdAt))

object AnalysisRepositoryES:
  val live
    : ZLayer[EventStore[AnalysisDocId, AnalysisEvent] & DataStoreModule.DataStoreService, Nothing, AnalysisRepository] =
    ZLayer.fromZIO {
      for
        eventStore <- ZIO.service[EventStore[AnalysisDocId, AnalysisEvent]]
        dataStore  <- ZIO.service[DataStoreModule.DataStoreService]
      yield AnalysisRepositoryES(eventStore, dataStore)
    }
