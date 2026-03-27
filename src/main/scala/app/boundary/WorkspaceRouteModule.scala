package app.boundary

import zio.*
import zio.http.*

import workspace.boundary.WorkspacesController

trait WorkspaceRouteModule:
  def routes: Routes[Any, Response]

object WorkspaceRouteModule:
  val live: ZLayer[WorkspacesController, Nothing, WorkspaceRouteModule] =
    ZLayer {
      for workspaces <- ZIO.service[WorkspacesController]
      yield new WorkspaceRouteModule:
        override val routes: Routes[Any, Response] = workspaces.routes
    }
