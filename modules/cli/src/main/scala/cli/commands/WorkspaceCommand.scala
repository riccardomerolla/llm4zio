package cli.commands

import zio.*

import shared.errors.PersistenceError
import workspace.entity.WorkspaceRepository

object WorkspaceCommand:

  def listWorkspaces: ZIO[WorkspaceRepository, PersistenceError, String] =
    ZIO.serviceWithZIO[WorkspaceRepository](_.list).map { workspaces =>
      if workspaces.isEmpty then "No workspaces configured."
      else
        workspaces.map { ws =>
          s"  ${ws.id}  ${ws.name}  ${ws.localPath}"
        }.mkString("\n")
    }
