package shared.store

import java.nio.file.Paths

import zio.*
import zio.schema.Schema

import _root_.config.entity.{ CustomAgent, Setting, SettingValue, Workflow }
import io.github.riccardomerolla.zio.eclipsestore.config.{ EclipseStoreConfig, StorageTarget }
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.schema.{ SchemaBinaryCodec, TypedStore, TypedStoreLive }
import io.github.riccardomerolla.zio.eclipsestore.service.{ EclipseStoreService, LifecycleCommand }

private val configStoreHandlers =
  SchemaBinaryCodec.handlers(Schema[String])
    ++ SchemaBinaryCodec.handlers(Schema[SettingValue])
    ++ SchemaBinaryCodec.handlers(Schema[Setting])
    ++ SchemaBinaryCodec.handlers(Schema[Workflow])
    ++ SchemaBinaryCodec.handlers(Schema[CustomAgent])

object ConfigStoreModule:

  /** Config-store service exposes a TypedStore for schema-validated CRUD and the raw EclipseStoreService for key-prefix
    * scanning (streamKeys). Used for settings, workflows, and custom agents.
    */
  trait ConfigStoreService:
    def store: TypedStore
    def rawStore: EclipseStoreService

  private val withShutdownCheckpoint: ZLayer[ConfigStoreRef, EclipseStoreError, ConfigStoreRef] =
    ZLayer.scoped {
      for
        ref <- ZIO.service[ConfigStoreRef]
        svc  = ref.raw
        _   <- ZIO.logInfo("Config store: loading persisted roots...") *>
                 svc.reloadRoots *>
                 ZIO.logInfo("Config store: roots loaded.")
        _   <- ZIO.addFinalizer(
                 ZIO.logInfo("Config store: performing shutdown checkpoint...") *>
                   svc.maintenance(LifecycleCommand.Checkpoint).ignoreLogged *>
                   ZIO.logInfo("Config store: shutdown checkpoint complete.")
               )
      yield ref
    }

  private val toConfigStoreRef: ZLayer[EclipseStoreService, Nothing, ConfigStoreRef] =
    ZLayer.fromFunction(ConfigStoreRef.apply)

  val baseStore: ZLayer[StoreConfig, EclipseStoreError, ConfigStoreRef] =
    ZLayer.fromZIO(
      ZIO.serviceWith[StoreConfig] { cfg =>
        EclipseStoreConfig(
          storageTarget = StorageTarget.FileSystem(Paths.get(cfg.configStorePath)),
          autoCheckpointInterval = Some(java.time.Duration.ofSeconds(5L)),
          customTypeHandlers = configStoreHandlers,
        )
      }
    ) >>> EclipseStoreService.live.fresh >>> toConfigStoreRef >>> withShutdownCheckpoint

  val configStore: ZLayer[ConfigStoreRef, Nothing, ConfigStoreService] =
    ZLayer.fromFunction((ref: ConfigStoreRef) =>
      val esc = ref.raw
      new ConfigStoreService:
        override val store: TypedStore             = TypedStoreLive(esc)
        override val rawStore: EclipseStoreService = esc
    )

  val live: ZLayer[StoreConfig, EclipseStoreError, ConfigStoreService] =
    baseStore >>> configStore
