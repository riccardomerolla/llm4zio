package shared.store

import java.nio.file.Paths

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.service.{ NativeLocal, ObjectStore, StorageOps }

object DataStoreModule:

  val live: ZLayer[StoreConfig, EclipseStoreError, DataStoreService] =
    ZLayer.scoped {
      for
        cfg         <- ZIO.service[StoreConfig]
        snapshotPath = Paths.get(cfg.dataStorePath).resolve("data-store.snapshot.json")
        _           <- ZIO.attemptBlocking(java.nio.file.Files.createDirectories(snapshotPath.getParent))
                         .mapError(e => EclipseStoreError.StorageError(s"mkdir ${snapshotPath.getParent}", Some(e)))
        _           <- ZIO.logInfo(s"Data store: initializing NativeLocal at $snapshotPath")
        env         <- NativeLocal.live[KVRoot](snapshotPath, KVRoot.dataDescriptor).build
        objectStore  = env.get[ObjectStore[KVRoot]]
        ops          = env.get[StorageOps[KVRoot]]
        _           <- ops.scheduleCheckpoints(Schedule.fixed(5.seconds))
        _           <- ZIO.addFinalizer(
                         ZIO.logInfo("Data store: performing shutdown checkpoint...") *>
                           objectStore.checkpoint.ignoreLogged *>
                           ZIO.logInfo("Data store: shutdown checkpoint complete.")
                       )
        _           <- ZIO.logInfo("Data store: NativeLocal ready.")
      yield NativeLocalKVStore(objectStore)
    }
