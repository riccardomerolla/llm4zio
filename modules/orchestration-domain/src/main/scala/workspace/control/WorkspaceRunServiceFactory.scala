package workspace.control

import zio.*

import workspace.entity.WorkspaceRunService as WorkspaceRunServiceTrait

object WorkspaceRunServiceFactory:

  val live: ZLayer[WorkspaceRunService.LiveDeps, Nothing, WorkspaceRunServiceTrait] =
    ZLayer.scoped {
      WorkspaceRunService
        .liveWithAgent(CliAgentRunner.runProcessStreaming)
        .build
        .map(_.get[WorkspaceRunServiceTrait])
    }
