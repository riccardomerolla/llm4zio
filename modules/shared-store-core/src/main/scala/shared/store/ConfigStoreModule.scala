package shared.store

import java.nio.file.Paths

import zio.*
import zio.json.*
import zio.stream.ZStream

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.service.{ NativeLocal, ObjectStore, StorageOps }

object ConfigStoreModule:

  /** Config-store service provides JSON-codec-validated CRUD and key-prefix scanning. Used for settings, workflows, and
    * custom agents.
    */
  trait ConfigStoreService:
    def store[K, V](key: K, value: V)(using JsonEncoder[V]): IO[EclipseStoreError, Unit]
    def fetch[K, V](key: K)(using JsonDecoder[V]): IO[EclipseStoreError, Option[V]]
    def remove[K](key: K): IO[EclipseStoreError, Unit]
    def fetchAll[V](using JsonDecoder[V]): IO[EclipseStoreError, List[V]]
    def streamAll[V](using JsonDecoder[V]): ZStream[Any, EclipseStoreError, V]
    def streamKeys[K]: ZStream[Any, EclipseStoreError, K]
    def checkpoint: IO[EclipseStoreError, Unit]
    def fetchRawJson(key: String): IO[EclipseStoreError, Option[String]]

  val live: ZLayer[StoreConfig, EclipseStoreError, ConfigStoreService] =
    ZLayer.scoped {
      for
        cfg         <- ZIO.service[StoreConfig]
        snapshotPath = Paths.get(cfg.configStorePath).resolve("config-store.snapshot.json")
        _           <- ZIO.attemptBlocking(java.nio.file.Files.createDirectories(snapshotPath.getParent))
                         .mapError(e => EclipseStoreError.StorageError(s"mkdir ${snapshotPath.getParent}", Some(e)))
        _           <- ZIO.logInfo(s"Config store: initializing NativeLocal at $snapshotPath")
        env         <- NativeLocal.live[KVRoot](snapshotPath, KVRoot.configDescriptor).build
        objectStore  = env.get[ObjectStore[KVRoot]]
        ops          = env.get[StorageOps[KVRoot]]
        _           <- ops.scheduleCheckpoints(Schedule.fixed(5.seconds))
        _           <- ZIO.addFinalizer(
                         ZIO.logInfo("Config store: performing shutdown checkpoint...") *>
                           objectStore.checkpoint.ignoreLogged *>
                           ZIO.logInfo("Config store: shutdown checkpoint complete.")
                       )
        _           <- ZIO.logInfo("Config store: NativeLocal ready.")
      yield new ConfigStoreService:
        private val delegate = NativeLocalKVStore(objectStore)
        export delegate.{ store, fetch, remove, fetchAll, streamAll, streamKeys, checkpoint, fetchRawJson }
    }
