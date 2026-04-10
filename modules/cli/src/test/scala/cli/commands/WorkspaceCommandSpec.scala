package cli.commands

import java.time.Instant

import zio.*
import zio.test.*
import zio.test.Assertion.*

import shared.errors.PersistenceError
import shared.ids.Ids.ProjectId
import workspace.entity.*

object WorkspaceCommandSpec extends ZIOSpecDefault:

  def makeWorkspace(id: String, name: String, localPath: String): Workspace =
    Workspace(
      id = id,
      projectId = ProjectId("proj-1"),
      name = name,
      localPath = localPath,
      defaultAgent = None,
      description = None,
      enabled = true,
      runMode = RunMode.Host,
      cliTool = "claude",
      createdAt = Instant.EPOCH,
      updatedAt = Instant.EPOCH,
    )

  final class StubWorkspaceRepo(workspaces: List[Workspace]) extends WorkspaceRepository:

    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit] =
      ZIO.unit

    override def list: IO[PersistenceError, List[Workspace]] =
      ZIO.succeed(workspaces)

    override def listByProject(projectId: ProjectId): IO[PersistenceError, List[Workspace]] =
      ZIO.succeed(workspaces.filter(_.projectId == projectId))

    override def get(id: String): IO[PersistenceError, Option[Workspace]] =
      ZIO.succeed(workspaces.find(_.id == id))

    override def delete(id: String): IO[PersistenceError, Unit] =
      ZIO.unit

    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit] =
      ZIO.unit

    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]] =
      ZIO.succeed(Nil)

    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] =
      ZIO.succeed(Nil)

    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]] =
      ZIO.succeed(None)

  val sampleWorkspaces: List[Workspace] = List(
    makeWorkspace("ws-1", "Alpha", "/home/user/alpha"),
    makeWorkspace("ws-2", "Beta", "/home/user/beta"),
  )

  def spec = suite("WorkspaceCommand")(
    test("listWorkspaces shows all workspaces with id, name, localPath") {
      val layer = ZLayer.succeed(StubWorkspaceRepo(sampleWorkspaces): WorkspaceRepository)
      for result <- WorkspaceCommand.listWorkspaces.provide(layer)
      yield
        assertTrue(result.contains("ws-1")) &&
          assertTrue(result.contains("Alpha")) &&
          assertTrue(result.contains("/home/user/alpha")) &&
          assertTrue(result.contains("ws-2")) &&
          assertTrue(result.contains("Beta")) &&
          assertTrue(result.contains("/home/user/beta"))
    },
    test("listWorkspaces with no workspaces shows empty message") {
      val layer = ZLayer.succeed(StubWorkspaceRepo(Nil): WorkspaceRepository)
      for result <- WorkspaceCommand.listWorkspaces.provide(layer)
      yield assertTrue(result == "No workspaces configured.")
    },
  )
