package store

import java.nio.file.Paths
import java.time.Instant

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.config.{ EclipseStoreConfig, StorageTarget }
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.config.{ GigaMapDefinition, GigaMapIndex }
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.service.GigaMap
import io.github.riccardomerolla.zio.eclipsestore.service.{ EclipseStoreService, LifecycleCommand }

given configStoreInstantCodec: JsonCodec[Instant] = JsonCodec[String].transform(
  str => Instant.parse(str),
  instant => instant.toString,
)

final case class WorkflowRow(
  id: String,
  name: String,
  description: Option[String],
  stepsJson: String,
  isBuiltin: Boolean,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec

final case class CustomAgentRow(
  id: String,
  name: String,
  displayName: String,
  description: Option[String],
  systemPrompt: String,
  tagsJson: Option[String],
  enabled: Boolean,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec

object ConfigStoreModule:
  trait ConfigStoreService:
    def store: EclipseStoreService

  trait SettingsStore:
    def map: GigaMap[String, String]

  trait WorkflowsStore:
    def map: GigaMap[WorkflowId, WorkflowRow]

  trait CustomAgentsStore:
    def map: GigaMap[AgentId, CustomAgentRow]

  def settingsMap: URIO[SettingsStore, GigaMap[String, String]] =
    ZIO.serviceWith[SettingsStore](_.map)

  def workflowsMap: URIO[WorkflowsStore, GigaMap[WorkflowId, WorkflowRow]] =
    ZIO.serviceWith[WorkflowsStore](_.map)

  def customAgentsMap: URIO[CustomAgentsStore, GigaMap[AgentId, CustomAgentRow]] =
    ZIO.serviceWith[CustomAgentsStore](_.map)

  private val settingsMapDef: GigaMapDefinition[String, String] =
    GigaMapDefinition(name = "settings")

  private val workflowsMapDef: GigaMapDefinition[WorkflowId, WorkflowRow] =
    GigaMapDefinition(
      name = "workflows",
      indexes = Chunk(GigaMapIndex.single("name", _.name)),
    )

  private val customAgentsMapDef: GigaMapDefinition[AgentId, CustomAgentRow] =
    GigaMapDefinition(
      name = "customAgents",
      indexes = Chunk(
        GigaMapIndex.single("name", _.name),
        GigaMapIndex.single("enabled", _.enabled.toString),
      ),
    )

  /** Passthrough layer that adds a shutdown-checkpoint finalizer on top of `EclipseStoreService`. */
  private val withShutdownCheckpoint: ZLayer[EclipseStoreService, Nothing, EclipseStoreService] =
    ZLayer.scoped {
      for
        svc <- ZIO.service[EclipseStoreService]
        _   <- ZIO.addFinalizer(
                 ZIO.logInfo("Config store: performing shutdown checkpoint...") *>
                   svc.maintenance(LifecycleCommand.Checkpoint).ignoreLogged *>
                   ZIO.logInfo("Config store: shutdown checkpoint complete.")
               )
      yield svc
    }

  val baseStore: ZLayer[StoreConfig, EclipseStoreError, EclipseStoreService] =
    ZLayer.fromZIO(
      ZIO.serviceWith[StoreConfig] { storeConfig =>
        EclipseStoreConfig(
          storageTarget = StorageTarget.FileSystem(Paths.get(storeConfig.configStorePath)),
          // Aggressive checkpoint: flush to disk every 5 seconds for config store
          // Configuration data (workflows, settings, agents) should persist reliably
          autoCheckpointInterval = Some(java.time.Duration.ofSeconds(5L)),
        )
      }
    ) >>> EclipseStoreService.live >>> withShutdownCheckpoint

  val configStore: ZLayer[EclipseStoreService, Nothing, ConfigStoreService] =
    ZLayer.fromFunction((svc: EclipseStoreService) =>
      new ConfigStoreService:
        override val store: EclipseStoreService = svc
    )

  val settings: ZLayer[EclipseStoreService, GigaMapError, SettingsStore] =
    GigaMap.make(settingsMapDef) >>> ZLayer.fromFunction((gm: GigaMap[String, String]) =>
      new SettingsStore:
        override val map: GigaMap[String, String] = gm
    )

  val workflows: ZLayer[EclipseStoreService, GigaMapError, WorkflowsStore] =
    GigaMap.make(workflowsMapDef) >>> ZLayer.fromFunction((gm: GigaMap[WorkflowId, WorkflowRow]) =>
      new WorkflowsStore:
        override val map: GigaMap[WorkflowId, WorkflowRow] = gm
    )

  val customAgents: ZLayer[EclipseStoreService, GigaMapError, CustomAgentsStore] =
    GigaMap.make(customAgentsMapDef) >>> ZLayer.fromFunction((gm: GigaMap[AgentId, CustomAgentRow]) =>
      new CustomAgentsStore:
        override val map: GigaMap[AgentId, CustomAgentRow] = gm
    )

  private val mapsLive: ZLayer[
    EclipseStoreService,
    GigaMapError,
    ConfigStoreService & SettingsStore & WorkflowsStore & CustomAgentsStore,
  ] =
    ZLayer.makeSome[EclipseStoreService, ConfigStoreService & SettingsStore & WorkflowsStore & CustomAgentsStore](
      configStore,
      settings,
      workflows,
      customAgents,
    )

  val live: ZLayer[
    StoreConfig,
    EclipseStoreError | GigaMapError,
    ConfigStoreService & SettingsStore & WorkflowsStore & CustomAgentsStore,
  ] =
    baseStore >>> mapsLive
