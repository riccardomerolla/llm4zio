package cli.commands

import zio.*

import project.entity.ProjectRepository
import shared.errors.PersistenceError

object ProjectCommand:

  def listProjects: ZIO[ProjectRepository, PersistenceError, String] =
    ZIO.serviceWithZIO[ProjectRepository](_.list).map { projects =>
      if projects.isEmpty then "No projects configured."
      else
        projects.map { proj =>
          s"  ${proj.id.value}  ${proj.name}"
        }.mkString("\n")
    }
