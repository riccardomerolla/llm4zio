package knowledge.entity

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import shared.ids.Ids.{ DecisionLogId, IssueId }
import shared.store.{ DataStoreModule, DataStoreService, EventStore, StoreConfig }

object DecisionLogRepositoryESSpec extends ZIOSpecDefault:

  private type Env =
    DataStoreService & EventStore[DecisionLogId, DecisionLogEvent] & DecisionLogRepository

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("decision-log-repo-es-spec")).orDie
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
      DecisionLogEventStoreES.live,
      DecisionLogRepositoryES.live,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DecisionLogRepositoryESSpec")(
      test("append get history and list preserve structured decision data") {
        withTempDir { path =>
          val logId = DecisionLogId("decision-log-1")
          val now   = Instant.parse("2026-03-26T12:20:00Z")
          (for
            repo <- ZIO.service[DecisionLogRepository]
            _    <- repo.append(
                      DecisionLogEvent.Created(
                        decisionLogId = logId,
                        title = "Persist decision logs",
                        context = "Need ADR-style records",
                        decisionTaken = "Add DecisionLog aggregate",
                        rationale = "Keeps history queryable",
                        consequences = List("Adds repository layer"),
                        decisionDate = now,
                        decisionMaker = DecisionMaker(DecisionMakerKind.Agent, "planner"),
                        workspaceId = Some("ws-1"),
                        issueIds = List(IssueId("issue-1")),
                        occurredAt = now,
                      )
                    )
            _    <- repo.append(
                      DecisionLogEvent.Revised(
                        decisionLogId = logId,
                        version = 2,
                        title = "Persist structured decision logs",
                        context = "Need ADR-style records with revisions",
                        decisionTaken = "Add DecisionLog aggregate and snapshot repository",
                        rationale = "Keeps history queryable and retrieval fast",
                        consequences = List("Adds repository layer"),
                        decisionDate = now.plusSeconds(30),
                        decisionMaker = DecisionMaker(DecisionMakerKind.Agent, "planner"),
                        workspaceId = Some("ws-1"),
                        issueIds = List(IssueId("issue-1")),
                        runId = Some("run-1"),
                        occurredAt = now.plusSeconds(30),
                      )
                    )
            got  <- repo.get(logId)
            all  <- repo.list(DecisionLogFilter(workspaceId = Some("ws-1")))
            hist <- repo.history(logId)
          yield assertTrue(
            got.version == 2,
            got.runId.contains("run-1"),
            all.map(_.id) == List(logId),
            hist.size == 2,
          )).provideLayer(layerFor(path))
        }
      }
    ) @@ TestAspect.sequential
