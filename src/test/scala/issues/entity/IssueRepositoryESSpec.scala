package issues.entity

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import shared.ids.Ids
import shared.store.{ DataStoreModule, EventStore, StoreConfig }

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
      test("workspace link and unlink events update projection") {
        withTempDir { path =>
          val id  = Ids.IssueId("issue-2")
          val now = Instant.parse("2026-03-02T09:00:00Z")
          (for
            repo <- ZIO.service[IssueRepository]
            _    <- repo.append(IssueEvent.Created(id, "Task", "Do work", "task", "medium", now))
            _    <- repo.append(IssueEvent.WorkspaceLinked(id, "ws-1", now.plusSeconds(1)))
            mid  <- repo.get(id)
            _    <- repo.append(IssueEvent.WorkspaceUnlinked(id, now.plusSeconds(2)))
            end  <- repo.get(id)
          yield assertTrue(
            mid.workspaceId.contains("ws-1"),
            end.workspaceId.isEmpty,
          )).provideLayer(layerFor(path))
        }
      },
      test("reopened event moves issue back to Open") {
        withTempDir { path =>
          val id  = Ids.IssueId("issue-3")
          val now = Instant.parse("2026-03-02T09:10:00Z")
          (for
            repo <- ZIO.service[IssueRepository]
            _    <- repo.append(IssueEvent.Created(id, "Task", "Do work", "task", "medium", now))
            _    <- repo.append(IssueEvent.Completed(
                      id,
                      Ids.AgentId("agent-1"),
                      now.plusSeconds(5),
                      "done",
                      now.plusSeconds(5),
                    ))
            _    <- repo.append(IssueEvent.Reopened(id, now.plusSeconds(8), now.plusSeconds(8)))
            got  <- repo.get(id)
          yield assertTrue(
            got.state == IssueState.Open(now.plusSeconds(8))
          )).provideLayer(layerFor(path))
        }
      },
      test("tags updated event updates projection tags") {
        withTempDir { path =>
          val id  = Ids.IssueId("issue-4")
          val now = Instant.parse("2026-03-02T09:20:00Z")
          (for
            repo <- ZIO.service[IssueRepository]
            _    <- repo.append(IssueEvent.Created(id, "Task", "Do work", "task", "medium", now))
            _    <- repo.append(IssueEvent.TagsUpdated(id, List("bug", "backend"), now.plusSeconds(1)))
            got  <- repo.get(id)
          yield assertTrue(
            got.tags == List("bug", "backend")
          )).provideLayer(layerFor(path))
        }
      },
      test("created event stores required capabilities") {
        withTempDir { path =>
          val id  = Ids.IssueId("issue-5")
          val now = Instant.parse("2026-03-02T09:30:00Z")
          (for
            repo <- ZIO.service[IssueRepository]
            _    <- repo.append(
                      IssueEvent.Created(
                        issueId = id,
                        title = "Task",
                        description = "Needs capability matching",
                        issueType = "task",
                        priority = "medium",
                        occurredAt = now,
                        requiredCapabilities = List("scala", "testing"),
                      )
                    )
            got  <- repo.get(id)
          yield assertTrue(
            got.requiredCapabilities == List("scala", "testing")
          )).provideLayer(layerFor(path))
        }
      },
      test("external reference events update issue projection") {
        withTempDir { path =>
          val id  = Ids.IssueId("issue-6")
          val now = Instant.parse("2026-03-02T09:40:00Z")
          (for
            repo <- ZIO.service[IssueRepository]
            _    <- repo.append(IssueEvent.Created(id, "Task", "Do work", "task", "medium", now))
            _    <- repo.append(
                      IssueEvent.ExternalRefLinked(
                        issueId = id,
                        externalRef = "GH:owner/repo#42",
                        externalUrl = Some("https://github.com/owner/repo/issues/42"),
                        occurredAt = now.plusSeconds(1),
                      )
                    )
            _    <- repo.append(
                      IssueEvent.ExternalRefSynced(
                        issueId = id,
                        updatedFields = Map("title" -> "Task updated from tracker"),
                        occurredAt = now.plusSeconds(2),
                      )
                    )
            got  <- repo.get(id)
          yield assertTrue(
            got.externalRef.contains("GH:owner/repo#42"),
            got.externalUrl.contains("https://github.com/owner/repo/issues/42"),
            got.title == "Task updated from tracker",
          )).provideLayer(layerFor(path))
        }
      },
    ) @@ TestAspect.sequential
