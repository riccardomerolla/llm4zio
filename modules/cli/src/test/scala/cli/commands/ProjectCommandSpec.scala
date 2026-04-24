package cli.commands

import java.time.Instant

import zio.*
import zio.test.*

import project.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.ProjectId

object ProjectCommandSpec extends ZIOSpecDefault:

  def makeProject(id: String, name: String): Project =
    Project(
      id = ProjectId(id),
      name = name,
      description = None,
      settings = ProjectSettings(),
      createdAt = Instant.EPOCH,
      updatedAt = Instant.EPOCH,
    )

  final class StubProjectRepo(projects: List[Project]) extends ProjectRepository:

    override def append(event: ProjectEvent): IO[PersistenceError, Unit] =
      ZIO.unit

    override def list: IO[PersistenceError, List[Project]] =
      ZIO.succeed(projects)

    override def get(id: ProjectId): IO[PersistenceError, Option[Project]] =
      ZIO.succeed(projects.find(_.id == id))

    override def delete(id: ProjectId): IO[PersistenceError, Unit] =
      ZIO.unit

  val sampleProjects: List[Project] = List(
    makeProject("proj-1", "Alpha Project"),
    makeProject("proj-2", "Beta Project"),
  )

  def spec = suite("ProjectCommand")(
    test("listProjects shows all projects with id and name") {
      val layer = ZLayer.succeed(StubProjectRepo(sampleProjects): ProjectRepository)
      for result <- ProjectCommand.listProjects.provide(layer)
      yield
        assertTrue(result.contains("proj-1")) &&
          assertTrue(result.contains("Alpha Project")) &&
          assertTrue(result.contains("proj-2")) &&
          assertTrue(result.contains("Beta Project"))
    },
    test("listProjects with no projects shows empty message") {
      val layer = ZLayer.succeed(StubProjectRepo(Nil): ProjectRepository)
      for result <- ProjectCommand.listProjects.provide(layer)
      yield assertTrue(result == "No projects configured.")
    },
  )
