package cli.commands

import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import zio.*
import zio.test.*

import agent.entity.{ Agent, AgentEvent, AgentRepository }
import board.control.{ BoardRepositoryFS, IssueMarkdownParser }
import board.entity.BoardRepository
import shared.errors.PersistenceError
import shared.ids.Ids.AgentId
import shared.services.FileService
import workspace.control.GitService
import workspace.entity.{ Workspace, WorkspaceEvent, WorkspaceRepository, WorkspaceRun, WorkspaceRunEvent }
import shared.ids.Ids.ProjectId

object ScaffoldCommandSpec extends ZIOSpecDefault:

  // ── In-memory repos ────────────────────────────────────────────────────

  final class InMemoryWorkspaceRepo(ref: Ref[List[WorkspaceEvent]]) extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit] =
      ref.update(_ :+ event)

    def events: UIO[List[WorkspaceEvent]] = ref.get

    override def list: IO[PersistenceError, List[Workspace]]                              = ZIO.succeed(Nil)
    override def listByProject(projectId: ProjectId): IO[PersistenceError, List[Workspace]] = ZIO.succeed(Nil)
    override def get(id: String): IO[PersistenceError, Option[Workspace]]                 = ZIO.succeed(None)
    override def delete(id: String): IO[PersistenceError, Unit]                           = ZIO.unit
    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]          = ZIO.unit
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]  = ZIO.succeed(Nil)
    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] =
      ZIO.succeed(Nil)
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]] = ZIO.succeed(None)

  final class InMemoryAgentRepo(ref: Ref[List[AgentEvent]]) extends AgentRepository:
    override def append(event: AgentEvent): IO[PersistenceError, Unit] = ref.update(_ :+ event)

    def events: UIO[List[AgentEvent]] = ref.get

    override def get(id: AgentId): IO[PersistenceError, Agent] =
      ZIO.fail(PersistenceError.NotFound("agent", id.value))
    override def list(includeDeleted: Boolean = false): IO[PersistenceError, List[Agent]] = ZIO.succeed(Nil)
    override def findByName(name: String): IO[PersistenceError, Option[Agent]]            = ZIO.succeed(None)

  private val boardLayer: ZLayer[Any, Nothing, BoardRepository] =
    GitService.live ++ IssueMarkdownParser.live >>> BoardRepositoryFS.live

  private def withTempDir[R, E, A](f: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.scoped {
      ZIO.acquireRelease(
        ZIO.attempt(Files.createTempDirectory("scaffold-spec-")).orDie
      ) { path =>
        ZIO
          .attempt {
            def delete(p: Path): Unit =
              if Files.isDirectory(p) then
                val s = Files.list(p)
                try s.iterator.asScala.foreach(delete)
                finally s.close()
              Files.deleteIfExists(p): Unit
            delete(path)
          }
          .ignore
      }.flatMap(f)
    }

  def spec = suite("ScaffoldCommand")(
    test("initWorkspace creates filesystem layout and appends Created event") {
      withTempDir { dir =>
        val target = dir.resolve("ws-alpha")
        for
          wsRef  <- Ref.make[List[WorkspaceEvent]](Nil)
          wsRepo  = InMemoryWorkspaceRepo(wsRef)
          result <- ScaffoldCommand
                      .initWorkspace(target.toString, Some("Alpha Project"), Some("docs"))
                      .provide(FileService.live, boardLayer, ZLayer.succeed[WorkspaceRepository](wsRepo))
          events <- wsRepo.events
          boardDir   = Files.isDirectory(target.resolve(".board"))
          metaDir    = Files.isDirectory(target.resolve(".llm4zio"))
          policyFile = Files.isRegularFile(target.resolve(".llm4zio/governance-policy.json"))
          agentsKeep = Files.isRegularFile(target.resolve("agents/.gitkeep"))
          evalsFile  = Files.isRegularFile(target.resolve("evals/example.jsonl"))
          readmeFile = Files.isRegularFile(target.resolve("README.md"))
          matchedEvent = events.headOption.exists {
                           case c: WorkspaceEvent.Created =>
                             c.workspaceId == "alpha-project" &&
                             c.name == "Alpha Project" &&
                             c.description.contains("docs")
                           case _ => false
                         }
        yield
          assertTrue(result.contains("Initialized workspace 'Alpha Project'")) &&
          assertTrue(boardDir) &&
          assertTrue(metaDir) &&
          assertTrue(policyFile) &&
          assertTrue(agentsKeep) &&
          assertTrue(evalsFile) &&
          assertTrue(readmeFile) &&
          assertTrue(events.size == 1) &&
          assertTrue(matchedEvent)
      }
    },
    test("initAgent writes .agent.json and appends Created event") {
      withTempDir { dir =>
        // Pre-create the workspace agents/ dir so initAgent can land files in it.
        val agentsDir = dir.resolve("agents")
        Files.createDirectories(agentsDir)
        for
          agentRef <- Ref.make[List[AgentEvent]](Nil)
          agentRepo = InMemoryAgentRepo(agentRef)
          result   <- ScaffoldCommand
                        .initAgent(dir.toString, "Researcher Bot", Some("claude-opus-4-7"), "claude", Some("docs"))
                        .provide(FileService.live, ZLayer.succeed[AgentRepository](agentRepo))
          events   <- agentRepo.events
          file      = agentsDir.resolve("researcher-bot.agent.json")
          fileExists = Files.isRegularFile(file)
          content   = new String(Files.readAllBytes(file))
          matchedEvent = events.headOption.exists {
                           case c: AgentEvent.Created =>
                             c.agent.name == "researcher-bot" &&
                             c.agent.cliTool == "claude" &&
                             c.agent.defaultModel.contains("claude-opus-4-7")
                           case _ => false
                         }
        yield
          assertTrue(result.contains("Created agent 'researcher-bot'")) &&
          assertTrue(fileExists) &&
          assertTrue(content.contains("\"name\"")) &&
          assertTrue(content.contains("researcher-bot")) &&
          assertTrue(content.contains("claude-opus-4-7")) &&
          assertTrue(events.size == 1) &&
          assertTrue(matchedEvent)
      }
    },
    test("slug normalizes names") {
      assertTrue(ScaffoldCommand.slug("Hello World!") == "hello-world") &&
      assertTrue(ScaffoldCommand.slug("  Foo  Bar  ") == "foo-bar") &&
      assertTrue(ScaffoldCommand.slug("weird___name") == "weird-name")
    },
  )
