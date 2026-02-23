package issues.entity

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import shared.ids.Ids
import shared.store.EventStore
import store.{ AgentAssignmentRow, AgentIssueRow, DataStoreModule, StoreConfig }

object IssueRepositoryESSpec extends ZIOSpecDefault:

  private type Env = DataStoreModule.DataStoreService & EventStore[Ids.IssueId, IssueEvent] & IssueRepository

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("issue-repo-es-spec")).orDie
    )(dir =>
      ZIO.attemptBlocking {
        if Files.exists(dir) then
          Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(path =>
            val _ = Files.deleteIfExists(path)
          )
      }.ignore
    )(use)

  private def layerFor(path: Path): ZLayer[Any, EclipseStoreError | GigaMapError, Env] =
    ZLayer.make[Env](
      ZLayer.succeed(StoreConfig(path.resolve("config").toString, path.resolve("data").toString)),
      DataStoreModule.live,
      IssueEventStoreES.live,
      IssueRepositoryES.live,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("IssueRepositoryESSpec")(
      test("append/replay/snapshot for issue events") {
        withTempDir { path =>
          val id     = Ids.IssueId("issue-1")
          val now    = Instant.parse("2026-02-23T13:00:00Z")
          val events = List[IssueEvent](
            IssueEvent.Created(id, "Bug", "Fix bug", "bug", "high", now),
            IssueEvent.Assigned(id, Ids.AgentId("agent-1"), now.plusSeconds(5), now.plusSeconds(5)),
            IssueEvent.Started(id, Ids.AgentId("agent-1"), now.plusSeconds(6), now.plusSeconds(6)),
            IssueEvent.Completed(id, Ids.AgentId("agent-1"), now.plusSeconds(10), "done", now.plusSeconds(10)),
          )
          (for
            repo <- ZIO.service[IssueRepository]
            _    <- ZIO.foreachDiscard(events)(repo.append)
            got  <- repo.get(id)
          yield assertTrue(got.state == IssueState.Completed(Ids.AgentId("agent-1"), now.plusSeconds(10), "done")))
            .provideLayer(layerFor(path))
        }
      },
      test("legacy AgentIssueRow migration") {
        withTempDir { path =>
          val now           = Instant.parse("2026-02-23T13:05:00Z")
          val row           = AgentIssueRow(
            id = "legacy-issue-1",
            runId = Some("run-1"),
            conversationId = Some("conv-1"),
            title = "Legacy",
            description = "legacy issue",
            issueType = "bug",
            tags = Some("legacy"),
            preferredAgent = None,
            contextPath = Some("src"),
            sourceFolder = Some("src/main"),
            priority = "medium",
            status = "failed",
            assignedAgent = Some("agent-x"),
            assignedAt = Some(now.plusSeconds(2)),
            completedAt = Some(now.plusSeconds(4)),
            errorMessage = Some("boom"),
            resultData = None,
            createdAt = now,
            updatedAt = now.plusSeconds(4),
          )
          val assignmentRow = AgentAssignmentRow(
            id = "legacy-assignment-1",
            issueId = "legacy-issue-1",
            agentName = "agent-x",
            status = "completed",
            assignedAt = now.plusSeconds(2),
            startedAt = Some(now.plusSeconds(3)),
            completedAt = Some(now.plusSeconds(4)),
            executionLog = Some("done"),
            result = Some("ok"),
          )
          (for
            ds   <- ZIO.service[DataStoreModule.DataStoreService]
            _    <- ds.store.store("issue:legacy-issue-1", row)
            _    <- ds.store.store("assignment:legacy-assignment-1", assignmentRow)
            _    <- IssueMigration.migrateLegacyRows
            repo <- ZIO.service[IssueRepository]
            got  <- repo.get(Ids.IssueId("legacy-issue-1"))
            v2   <- ds.store.fetch[String, Assignment]("assignment:v2:legacy-assignment-1")
          yield assertTrue(
            got.state == IssueState.Failed(Ids.AgentId("agent-x"), now.plusSeconds(4), "boom"),
            v2.exists(_.id == Ids.AssignmentId("legacy-assignment-1")),
          )).provideLayer(layerFor(path))
        }
      },
    ) @@ TestAspect.sequential
