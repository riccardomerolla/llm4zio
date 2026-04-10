package cli

import java.nio.file.Paths

import zio.*
import zio.schema.Schema

import _root_.config.entity.{ CustomAgent, Setting, SettingValue, Workflow }
import conversation.entity.{ Conversation, ConversationEvent }
import io.github.riccardomerolla.zio.eclipsestore.config.{ EclipseStoreConfig, StorageTarget }
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.schema.{ SchemaBinaryCodec, TypedStoreLive }
import io.github.riccardomerolla.zio.eclipsestore.service.{ EclipseStoreService, LifecycleCommand }
import io.github.riccardomerolla.zio.eclipsestore.schema.TypedStore
import shared.store.{ ConfigStoreRef, DataStoreRef, DataStoreService, StoreConfig }
import taskrun.entity.{ TaskRun, TaskRunEvent }

private val cliDataStoreHandlers =
  SchemaBinaryCodec.handlers(Schema[String])
    ++ SchemaBinaryCodec.handlers(Schema[TaskRun])
    ++ SchemaBinaryCodec.handlers(Schema[TaskRunEvent])
    ++ SchemaBinaryCodec.handlers(Schema[Conversation])
    ++ SchemaBinaryCodec.handlers(Schema[ConversationEvent])

private val cliConfigStoreHandlers =
  SchemaBinaryCodec.handlers(Schema[String])
    ++ SchemaBinaryCodec.handlers(Schema[SettingValue])
    ++ SchemaBinaryCodec.handlers(Schema[Setting])
    ++ SchemaBinaryCodec.handlers(Schema[Workflow])
    ++ SchemaBinaryCodec.handlers(Schema[CustomAgent])

object CliStoreModule:

  /** CLI-local ConfigStoreService — mirrors ConfigStoreModule.ConfigStoreService from root. */
  trait ConfigStoreService extends TypedStore:
    def rawStore: EclipseStoreService


  // ── Data store ─────────────────────────────────────────────────────────────

  private val withDataShutdownCheckpoint: ZLayer[DataStoreRef, EclipseStoreError, DataStoreRef] =
    ZLayer.scoped {
      for
        ref <- ZIO.service[DataStoreRef]
        svc  = ref.raw
        _   <- ZIO.logInfo("CLI data store: loading persisted roots...") *>
                 svc.reloadRoots *>
                 ZIO.logInfo("CLI data store: roots loaded.")
        _   <- ZIO.addFinalizer(
                 ZIO.logInfo("CLI data store: performing shutdown checkpoint...") *>
                   svc.maintenance(LifecycleCommand.Checkpoint).ignoreLogged *>
                   ZIO.logInfo("CLI data store: shutdown checkpoint complete.")
               )
      yield ref
    }

  private val toDataStoreRef: ZLayer[EclipseStoreService, Nothing, DataStoreRef] =
    ZLayer.fromFunction(DataStoreRef.apply)

  private val baseDataStore: ZLayer[StoreConfig, EclipseStoreError, DataStoreRef] =
    ZLayer.fromZIO(
      ZIO.serviceWith[StoreConfig] { cfg =>
        EclipseStoreConfig(
          storageTarget = StorageTarget.FileSystem(Paths.get(cfg.dataStorePath)),
          autoCheckpointInterval = Some(java.time.Duration.ofSeconds(5L)),
          customTypeHandlers = cliDataStoreHandlers,
        )
      }
    ) >>> EclipseStoreService.live.fresh >>> toDataStoreRef >>> withDataShutdownCheckpoint

  private val toDataStoreService: ZLayer[DataStoreRef, Nothing, DataStoreService] =
    ZLayer.fromFunction { (ref: DataStoreRef) =>
      val esc = ref.raw
      val ts  = TypedStoreLive(esc)
      new DataStoreService:
        export ts.{ store, fetch, remove, fetchAll, streamAll, typedRoot, storePersist }
        override val rawStore: EclipseStoreService = esc
    }

  val dataStoreLive: ZLayer[StoreConfig, EclipseStoreError, DataStoreService] =
    baseDataStore >>> toDataStoreService

  // ── Config store ───────────────────────────────────────────────────────────

  private val withConfigShutdownCheckpoint: ZLayer[ConfigStoreRef, EclipseStoreError, ConfigStoreRef] =
    ZLayer.scoped {
      for
        ref <- ZIO.service[ConfigStoreRef]
        svc  = ref.raw
        _   <- ZIO.logInfo("CLI config store: loading persisted roots...") *>
                 svc.reloadRoots *>
                 ZIO.logInfo("CLI config store: roots loaded.")
        _   <- ZIO.addFinalizer(
                 ZIO.logInfo("CLI config store: performing shutdown checkpoint...") *>
                   svc.maintenance(LifecycleCommand.Checkpoint).ignoreLogged *>
                   ZIO.logInfo("CLI config store: shutdown checkpoint complete.")
               )
      yield ref
    }

  private val toConfigStoreRef: ZLayer[EclipseStoreService, Nothing, ConfigStoreRef] =
    ZLayer.fromFunction(ConfigStoreRef.apply)

  private val baseConfigStore: ZLayer[StoreConfig, EclipseStoreError, ConfigStoreRef] =
    ZLayer.fromZIO(
      ZIO.serviceWith[StoreConfig] { cfg =>
        EclipseStoreConfig(
          storageTarget = StorageTarget.FileSystem(Paths.get(cfg.configStorePath)),
          autoCheckpointInterval = Some(java.time.Duration.ofSeconds(5L)),
          customTypeHandlers = cliConfigStoreHandlers,
        )
      }
    ) >>> EclipseStoreService.live.fresh >>> toConfigStoreRef >>> withConfigShutdownCheckpoint

  private val toConfigStoreService: ZLayer[ConfigStoreRef, Nothing, ConfigStoreService] =
    ZLayer.fromFunction { (ref: ConfigStoreRef) =>
      val esc = ref.raw
      val ts  = TypedStoreLive(esc)
      new ConfigStoreService:
        export ts.{ store, fetch, remove, fetchAll, streamAll, typedRoot, storePersist }
        override val rawStore: EclipseStoreService = esc
    }

  val configStoreLive: ZLayer[StoreConfig, EclipseStoreError, ConfigStoreService] =
    baseConfigStore >>> toConfigStoreService
