package shared.store

import java.nio.file.Paths

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.config.{ EclipseStoreConfig, StorageTarget }
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.config.{ GigaMapDefinition, GigaMapIndex }
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.service.GigaMap
import io.github.riccardomerolla.zio.eclipsestore.gigamap.vector.VectorIndexService
import io.github.riccardomerolla.zio.eclipsestore.service.{ EclipseStoreService, LifecycleCommand }
import memory.entity.MemoryEntry

/** Dedicated module for memory/vector infrastructure, separated from data store persistence. Uses its own
  * EclipseStoreService instance at a `memory-store` subdirectory.
  */
object MemoryStoreModule:

  trait MemoryEntriesStore:
    def map: GigaMap[MemoryId, MemoryEntry]

  def memoryEntriesMap: URIO[MemoryEntriesStore, GigaMap[MemoryId, MemoryEntry]] =
    ZIO.serviceWith[MemoryEntriesStore](_.map)

  private def derivedMemoryStorePath(dataStorePath: String): String =
    Paths.get(dataStorePath).resolve("memory-store").toString

  private val memoryEntriesDefinition = GigaMapDefinition[MemoryId, MemoryEntry](
    name = "memoryEntries",
    indexes = Chunk(
      GigaMapIndex.single("userId", _.userId.value),
      GigaMapIndex.single("kind", _.kind.value),
    ),
  )

  private val withShutdownCheckpoint: ZLayer[EclipseStoreService, EclipseStoreError, EclipseStoreService] =
    ZLayer.scoped {
      for
        svc <- ZIO.service[EclipseStoreService]
        _   <- ZIO.logInfo("Memory store: loading persisted roots...") *>
                 svc.reloadRoots *>
                 ZIO.logInfo("Memory store: roots loaded.")
        _   <- ZIO.addFinalizer(
                 ZIO.logInfo("Memory store: performing shutdown checkpoint...") *>
                   svc.maintenance(LifecycleCommand.Checkpoint).ignoreLogged *>
                   ZIO.logInfo("Memory store: shutdown checkpoint complete.")
               )
      yield svc
    }

  val baseStore: ZLayer[StoreConfig, EclipseStoreError, EclipseStoreService] =
    ZLayer.fromZIO(
      ZIO.serviceWith[StoreConfig] { cfg =>
        EclipseStoreConfig(
          storageTarget = StorageTarget.FileSystem(Paths.get(derivedMemoryStorePath(cfg.dataStorePath))),
          autoCheckpointInterval = Some(java.time.Duration.ofSeconds(5L)),
        )
      }
    ) >>> EclipseStoreService.live.fresh >>> withShutdownCheckpoint

  val memoryEntries: ZLayer[EclipseStoreService, GigaMapError, MemoryEntriesStore] =
    GigaMap.make(memoryEntriesDefinition) >>> ZLayer.fromFunction((gm: GigaMap[MemoryId, MemoryEntry]) =>
      new MemoryEntriesStore:
        override val map: GigaMap[MemoryId, MemoryEntry] = gm
    )

  val live: ZLayer[StoreConfig, EclipseStoreError | GigaMapError, MemoryEntriesStore & VectorIndexService] =
    baseStore >>> (memoryEntries ++ VectorIndexService.live)
