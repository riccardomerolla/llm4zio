package cli

import zio.*

import _root_.config.entity.ConfigRepository
import activity.entity.ActivityRepository
import agent.entity.{ AgentEvent, AgentEventStoreES, AgentRepository, AgentRepositoryES }
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import project.entity.ProjectRepository
import CliStoreModule.ConfigStoreService
import shared.ids.Ids.AgentId
import shared.store.{ DataStoreService, EventStore, StoreConfig }
import workspace.entity.WorkspaceRepository

object CliDI:

  type StandaloneEnv =
    StoreConfig & DataStoreService & ConfigStoreService & ConfigRepository & WorkspaceRepository & ProjectRepository &
      ActivityRepository & AgentRepository

  def standaloneLayers(storeConfig: StoreConfig): ZLayer[Any, EclipseStoreError, StandaloneEnv] =
    val storeConfigLayer: ZLayer[Any, Nothing, StoreConfig] = ZLayer.succeed(storeConfig)

    val dataStoreLayer: ZLayer[Any, EclipseStoreError, DataStoreService] =
      storeConfigLayer >>> CliStoreModule.dataStoreLive

    val configStoreLayer: ZLayer[Any, EclipseStoreError, ConfigStoreService] =
      storeConfigLayer >>> CliStoreModule.configStoreLive

    val configRepoLayer: ZLayer[Any, EclipseStoreError, ConfigRepository] =
      configStoreLayer >>> CliConfigRepository.live

    val workspaceRepoLayer: ZLayer[Any, EclipseStoreError, WorkspaceRepository] =
      dataStoreLayer >>> WorkspaceRepository.live

    val projectRepoLayer: ZLayer[Any, EclipseStoreError, ProjectRepository] =
      dataStoreLayer >>> ProjectRepository.live

    val activityRepoLayer: ZLayer[Any, EclipseStoreError, ActivityRepository] =
      dataStoreLayer >>> ActivityRepository.live

    val agentEventStoreLayer: ZLayer[Any, EclipseStoreError, EventStore[AgentId, AgentEvent]] =
      dataStoreLayer >>> AgentEventStoreES.live

    val agentRepoLayer: ZLayer[Any, EclipseStoreError, AgentRepository] =
      (agentEventStoreLayer ++ dataStoreLayer) >>> AgentRepositoryES.live

    storeConfigLayer ++
      dataStoreLayer ++
      configStoreLayer ++
      configRepoLayer ++
      workspaceRepoLayer ++
      projectRepoLayer ++
      activityRepoLayer ++
      agentRepoLayer
