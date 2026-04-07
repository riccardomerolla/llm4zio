package integration

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import activity.control.ActivityHub
import activity.entity.ActivityEvent
import _root_.config.entity.{ ConfigRepository, CustomAgentRow, SettingRow, WorkflowRow }
import decision.control.DecisionInboxLive
import decision.entity.*
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import issues.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ DecisionId, IssueId }
import shared.store.{ DataStoreModule, DataStoreService, EventStore, StoreConfig }

/** Integration test: `DecisionInbox.runMaintenance` with real `DecisionRepositoryES`.
  *
  * Verifies that expired and escalated decisions are correctly written and readable after reload.
  */
object DecisionInboxMaintenanceIntegrationSpec extends ZIOSpecDefault:

  private val now          = Instant.parse("2026-03-26T11:00:00Z")
  private val shortTimeout = 5L // 5 seconds

  // ─── Stub side-effect dependencies ───────────────────────────────────────

  private object NoOpActivityHub extends ActivityHub:
    override def publish(event: ActivityEvent): UIO[Unit] = ZIO.unit
    override def subscribe: UIO[Dequeue[ActivityEvent]]   =
      Queue.bounded[ActivityEvent](1).map(q => q: Dequeue[ActivityEvent])

  private object NoOpIssueRepository extends IssueRepository:
    override def append(event: IssueEvent): IO[PersistenceError, Unit]             = ZIO.unit
    override def get(id: IssueId): IO[PersistenceError, AgentIssue]                =
      ZIO.fail(PersistenceError.NotFound("issue", id.value))
    override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]]      = ZIO.succeed(Nil)
    override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] = ZIO.succeed(Nil)
    override def delete(id: IssueId): IO[PersistenceError, Unit]                   = ZIO.unit

  /** Short timeout so maintenance test can expire decisions without waiting. */
  private def configRepoWithTimeout(timeoutSeconds: Long): ConfigRepository =
    new ConfigRepository:
      private val settings                                                                            = Map("decisions.timeoutSeconds.default" -> timeoutSeconds.toString)
      override def getAllSettings: IO[PersistenceError, List[SettingRow]]                           =
        ZIO.succeed(settings.toList.map((k, v) => SettingRow(k, v, now)))
      override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]                =
        ZIO.succeed(settings.get(key).map(SettingRow(key, _, now)))
      override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]            = ZIO.unit
      override def deleteSetting(key: String): IO[PersistenceError, Unit]                           = ZIO.unit
      override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit]               = ZIO.unit
      override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]                = ZIO.succeed(1L)
      override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]                 = ZIO.none
      override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]]       = ZIO.none
      override def listWorkflows: IO[PersistenceError, List[WorkflowRow]]                           = ZIO.succeed(Nil)
      override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit]                = ZIO.unit
      override def deleteWorkflow(id: Long): IO[PersistenceError, Unit]                             = ZIO.unit
      override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long]             = ZIO.succeed(1L)
      override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]]           = ZIO.none
      override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] = ZIO.none
      override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]]                     = ZIO.succeed(Nil)
      override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit]             = ZIO.unit
      override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit]                          = ZIO.unit

  // ─── ES layer helpers ────────────────────────────────────────────────────

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("decision-inbox-it-spec")).orDie
    )(dir =>
      ZIO.attemptBlocking {
        if Files.exists(dir) then
          Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach { p =>
            val _ = Files.deleteIfExists(p)
          }
      }.ignore
    )(use)

  private type EsEnv =
    DataStoreService & EventStore[DecisionId, DecisionEvent] & DecisionRepository

  private def esLayer(path: Path): ZLayer[Any, EclipseStoreError | GigaMapError, EsEnv] =
    ZLayer.make[EsEnv](
      ZLayer.succeed(StoreConfig(path.resolve("config").toString, path.resolve("data").toString)),
      DataStoreModule.live,
      DecisionEventStoreES.live,
      DecisionRepositoryES.live,
    )

  private def makeInbox(repo: DecisionRepository, timeoutSeconds: Long = shortTimeout): DecisionInboxLive =
    DecisionInboxLive(
      decisionRepository = repo,
      issueRepository = NoOpIssueRepository,
      activityHub = NoOpActivityHub,
      configRepository = configRepoWithTimeout(timeoutSeconds),
    )

  // ─── Tests ────────────────────────────────────────────────────────────────

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DecisionInboxMaintenanceIntegrationSpec")(
      test("openManualDecision creates a Pending decision persisted in ES") {
        withTempDir { path =>
          (for
            repo    <- ZIO.service[DecisionRepository]
            inbox    = makeInbox(repo)
            created <- inbox.openManualDecision(
                         title = "Approve architecture change",
                         context = "Needs sign-off",
                         referenceId = "arch-review-1",
                         summary = "Review required before merge",
                       )
            fetched <- repo.get(created.id)
          yield assertTrue(
            fetched.status == DecisionStatus.Pending,
            fetched.title == "Approve architecture change",
          )).provideLayer(esLayer(path))
        }
      },
      test("runMaintenance expires and escalates a past-deadline decision") {
        withTempDir { path =>
          (for
            repo    <- ZIO.service[DecisionRepository]
            inbox    = makeInbox(repo, timeoutSeconds = shortTimeout)
            created <- inbox.openManualDecision(
                         title = "Expiry test decision",
                         context = "Will expire",
                         referenceId = "ref-expire",
                         summary = "Check expiry",
                       )
            // Run maintenance at a time past the deadline
            updated <- inbox.runMaintenance(now.plusSeconds(shortTimeout + 60))
            fetched <- repo.get(created.id)
          yield assertTrue(
            updated.exists(_.id == created.id),
            fetched.status == DecisionStatus.Escalated,
          )).provideLayer(esLayer(path))
        }
      },
      test("runMaintenance does not expire a resolved decision") {
        withTempDir { path =>
          (for
            repo    <- ZIO.service[DecisionRepository]
            inbox    = makeInbox(repo, timeoutSeconds = shortTimeout)
            created <- inbox.openManualDecision(
                         title = "Resolved decision",
                         context = "Already done",
                         referenceId = "ref-resolved",
                         summary = "No expiry needed",
                       )
            _       <- inbox.resolve(created.id, DecisionResolutionKind.Approved, "admin", "Looks good")
            updated <- inbox.runMaintenance(now.plusSeconds(shortTimeout + 60))
            fetched <- repo.get(created.id)
          yield assertTrue(
            !updated.exists(_.id == created.id),
            fetched.status == DecisionStatus.Resolved,
          )).provideLayer(esLayer(path))
        }
      },
      test("list returns only Pending decisions after maintenance expires one") {
        withTempDir { path =>
          (for
            repo    <- ZIO.service[DecisionRepository]
            inbox    = makeInbox(repo, timeoutSeconds = shortTimeout)
            // One to expire, one to resolve before maintenance
            d1      <- inbox.openManualDecision("Will expire", "ctx", "ref-1", "summary")
            d2      <- inbox.openManualDecision("Stays resolved", "ctx", "ref-2", "summary")
            _       <- inbox.resolve(d2.id, DecisionResolutionKind.Approved, "admin", "OK")
            _       <- inbox.runMaintenance(now.plusSeconds(shortTimeout + 60))
            pending <- inbox.list(DecisionFilter(statuses = Set(DecisionStatus.Pending)))
          yield assertTrue(pending.isEmpty))
            .provideLayer(esLayer(path))
        }
      },
    ) @@ sequential
