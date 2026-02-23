package shared.store

import java.nio.file.Paths

import zio.*
import zio.schema.Schema

import activity.entity.ActivityEvent
import conversation.entity.{ Conversation, ConversationEvent }
import io.github.riccardomerolla.zio.eclipsestore.config.{ EclipseStoreConfig, StorageTarget }
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.schema.{ SchemaBinaryCodec, TypedStore, TypedStoreLive }
import io.github.riccardomerolla.zio.eclipsestore.service.{ EclipseStoreService, LifecycleCommand }
import issues.entity.{ AgentIssue, Assignment, IssueEvent }
import taskrun.entity.{ TaskRun, TaskRunEvent }

private val dataStoreHandlers =
  SchemaBinaryCodec.handlers(Schema[String])
    ++ SchemaBinaryCodec.handlers(Schema[ActivityEvent])
    ++ SchemaBinaryCodec.handlers(Schema[TaskRun])
    ++ SchemaBinaryCodec.handlers(Schema[TaskRunEvent])
    ++ SchemaBinaryCodec.handlers(Schema[AgentIssue])
    ++ SchemaBinaryCodec.handlers(Schema[Assignment])
    ++ SchemaBinaryCodec.handlers(Schema[IssueEvent])
    ++ SchemaBinaryCodec.handlers(Schema[Conversation])
    ++ SchemaBinaryCodec.handlers(Schema[ConversationEvent])

object DataStoreModule:

  /** DataStoreService exposes a TypedStore for schema-validated CRUD and the raw EclipseStoreService for key-prefix
    * scanning (streamKeys). Mirrors ConfigStoreModule.ConfigStoreService for consistency.
    */
  trait DataStoreService:
    def store: TypedStore
    def rawStore: EclipseStoreService

  /** Shutdown-checkpoint finalizer layered on top of the data-store service. */
  private val withShutdownCheckpoint: ZLayer[DataStoreRef, EclipseStoreError, DataStoreRef] =
    ZLayer.scoped {
      for
        ref <- ZIO.service[DataStoreRef]
        svc  = ref.raw
        _   <- ZIO.logInfo("Data store: loading persisted roots...") *>
                 svc.reloadRoots *>
                 ZIO.logInfo("Data store: roots loaded.")
        _   <- ZIO.addFinalizer(
                 ZIO.logInfo("Data store: performing shutdown checkpoint...") *>
                   svc.maintenance(LifecycleCommand.Checkpoint).ignoreLogged *>
                   ZIO.logInfo("Data store: shutdown checkpoint complete.")
               )
      yield ref
    }

  private val toDataStoreRef: ZLayer[EclipseStoreService, Nothing, DataStoreRef] =
    ZLayer.fromFunction(DataStoreRef.apply)

  val baseStore: ZLayer[StoreConfig, EclipseStoreError, DataStoreRef] =
    ZLayer.fromZIO(
      ZIO.serviceWith[StoreConfig] { cfg =>
        EclipseStoreConfig(
          storageTarget = StorageTarget.FileSystem(Paths.get(cfg.dataStorePath)),
          autoCheckpointInterval = Some(java.time.Duration.ofSeconds(5L)),
          customTypeHandlers = dataStoreHandlers,
        )
      }
    ) >>> EclipseStoreService.live.fresh >>> toDataStoreRef >>> withShutdownCheckpoint

  val dataStore: ZLayer[DataStoreRef, Nothing, DataStoreService] =
    ZLayer.fromFunction((ref: DataStoreRef) =>
      val esc = ref.raw
      new DataStoreService:
        override val store: TypedStore             = TypedStoreLive(esc)
        override val rawStore: EclipseStoreService = esc
    )

  /** Simplified live layer — produces only DataStoreService (no memory/vector infrastructure). Memory/vector
    * infrastructure is now provided separately by MemoryStoreModule.
    */
  val live: ZLayer[StoreConfig, EclipseStoreError, DataStoreService] =
    baseStore >>> dataStore
