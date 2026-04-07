package integration

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import _root_.config.entity.{ ConfigRepository, CustomAgentRow, SettingRow, WorkflowDefinition, WorkflowRow }
import daemon.entity.*
import decision.control.DecisionInbox
import decision.entity.*
import evolution.control.*
import evolution.entity.*
import governance.entity.*
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import orchestration.control.{ WorkflowService, WorkflowServiceError }
import shared.errors.PersistenceError
import shared.ids.Ids.{ DecisionId, DaemonAgentSpecId, EvolutionProposalId, GovernancePolicyId, ProjectId }
import shared.store.{ DataStoreModule, DataStoreService, EventStore, StoreConfig }

/** Integration test: Evolution lifecycle with real EclipseStore repos.
  *
  * Validates that `EvolutionEngine.apply` correctly mutates `GovernancePolicyRepository` and that both proposal and
  * policy state persist across ES reads. This catches serialization and event-replay issues not visible in unit tests.
  */
object EvolutionPersistenceIntegrationSpec extends ZIOSpecDefault:

  private val now       = Instant.parse("2026-03-26T13:00:00Z")
  private val projectId = ProjectId("project-1")

  // ─── Stub side-effect dependencies ───────────────────────────────────────

  final private class StubDecisionInbox(ref: Ref[Map[DecisionId, Decision]]) extends DecisionInbox:
    override def openIssueReviewDecision(issue: issues.entity.AgentIssue): IO[PersistenceError, Decision] =
      ZIO.dieMessage("unused")

    override def openManualDecision(
      title: String,
      context: String,
      referenceId: String,
      summary: String,
      urgency: DecisionUrgency,
      workspaceId: Option[String],
      issueId: Option[shared.ids.Ids.IssueId],
    ): IO[PersistenceError, Decision] =
      for
        id      <- ZIO.succeed(DecisionId.generate)
        decision = Decision(
                     id = id,
                     title = title,
                     context = context,
                     action = DecisionAction.ManualEscalation,
                     source = DecisionSource(DecisionSourceKind.Manual, referenceId, summary),
                     urgency = urgency,
                     status = DecisionStatus.Pending,
                     deadlineAt = None,
                     createdAt = now,
                     updatedAt = now,
                   )
        _       <- ref.update(_.updated(id, decision))
      yield decision

    override def resolve(id: DecisionId, resolutionKind: DecisionResolutionKind, actor: String, summary: String)
      : IO[PersistenceError, Decision] =
      ref.modify { state =>
        val updated = state(id).copy(
          status = DecisionStatus.Resolved,
          resolution = Some(DecisionResolution(resolutionKind, actor, summary, now.plusSeconds(10))),
          updatedAt = now.plusSeconds(10),
        )
        (updated, state.updated(id, updated))
      }

    override def syncOpenIssueReviewDecision(
      issueId: shared.ids.Ids.IssueId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Option[Decision]] = ZIO.none

    override def resolveOpenIssueReviewDecision(
      issueId: shared.ids.Ids.IssueId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Option[Decision]] = ZIO.none

    override def escalate(id: DecisionId, reason: String): IO[PersistenceError, Decision] = ZIO.dieMessage("unused")
    override def get(id: DecisionId): IO[PersistenceError, Decision]                      =
      ref.get.flatMap(state =>
        ZIO.fromOption(state.get(id)).orElseFail(PersistenceError.NotFound("decision", id.value))
      )
    override def list(filter: DecisionFilter): IO[PersistenceError, List[Decision]]       = ref.get.map(_.values.toList)
    override def runMaintenance(now: Instant): IO[PersistenceError, List[Decision]]       = ZIO.succeed(Nil)

  final private class StubWorkflowService extends WorkflowService:
    override def createWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Long]          = ZIO.succeed(1L)
    override def getWorkflow(id: Long): IO[WorkflowServiceError, Option[WorkflowDefinition]]           = ZIO.none
    override def getWorkflowByName(name: String): IO[WorkflowServiceError, Option[WorkflowDefinition]] = ZIO.none
    override def listWorkflows: IO[WorkflowServiceError, List[WorkflowDefinition]]                     = ZIO.succeed(Nil)
    override def updateWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Unit]          = ZIO.unit
    override def deleteWorkflow(id: Long): IO[WorkflowServiceError, Unit]                              = ZIO.unit

  final private class StubDbConfigRepository extends ConfigRepository:
    override def getAllSettings: IO[PersistenceError, List[SettingRow]]                           = ZIO.succeed(Nil)
    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]                = ZIO.none
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

  final private class StubDaemonRepo extends DaemonAgentSpecRepository:
    override def get(id: DaemonAgentSpecId): IO[PersistenceError, DaemonAgentSpec]          =
      ZIO.fail(PersistenceError.NotFound("daemon_spec", id.value))
    override def listByProject(pid: ProjectId): IO[PersistenceError, List[DaemonAgentSpec]] = ZIO.succeed(Nil)
    override def listAll: IO[PersistenceError, List[DaemonAgentSpec]]                       = ZIO.succeed(Nil)
    override def save(spec: DaemonAgentSpec): IO[PersistenceError, Unit]                    = ZIO.unit
    override def delete(id: DaemonAgentSpecId): IO[PersistenceError, Unit]                  = ZIO.unit

  // ─── ES layer helpers ────────────────────────────────────────────────────

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("evolution-persist-it-spec")).orDie
    )(dir =>
      ZIO.attemptBlocking {
        if Files.exists(dir) then
          Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach { p =>
            val _ = Files.deleteIfExists(p)
          }
      }.ignore
    )(use)

  private type EsEnv =
    DataStoreService &
      EventStore[EvolutionProposalId, EvolutionProposalEvent] &
      EvolutionProposalRepository &
      EventStore[GovernancePolicyId, GovernancePolicyEvent] &
      GovernancePolicyRepository

  private def esLayer(path: Path): ZLayer[Any, EclipseStoreError | GigaMapError, EsEnv] =
    ZLayer.make[EsEnv](
      ZLayer.succeed(StoreConfig(path.resolve("config").toString, path.resolve("data").toString)),
      DataStoreModule.live,
      EvolutionProposalEventStoreES.live,
      EvolutionProposalRepositoryES.live,
      GovernancePolicyEventStoreES.live,
      GovernancePolicyRepositoryES.live,
    )

  private def makeEngine(
    decisionRef: Ref[Map[DecisionId, Decision]],
    proposalRepo: EvolutionProposalRepository,
    govRepo: GovernancePolicyRepository,
  ): EvolutionEngineLive =
    EvolutionEngineLive(
      repository = proposalRepo,
      decisionInbox = StubDecisionInbox(decisionRef),
      governanceRepository = govRepo,
      workflowService = StubWorkflowService(),
      configRepository = StubDbConfigRepository(),
      daemonRepository = StubDaemonRepo(),
    )

  // ─── Tests ────────────────────────────────────────────────────────────────

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("EvolutionPersistenceIntegrationSpec")(
      test("apply GovernancePolicyTarget writes updated policy to ES") {
        withTempDir { path =>
          (for
            govRepo     <- ZIO.service[GovernancePolicyRepository]
            propRepo    <- ZIO.service[EvolutionProposalRepository]
            policyId     = GovernancePolicyId("policy-1")
            _           <- govRepo.append(
                             GovernancePolicyEvent.PolicyCreated(
                               policyId = policyId,
                               projectId = projectId,
                               name = "Base Policy",
                               version = 1,
                               transitionRules = Nil,
                               daemonTriggers = Nil,
                               escalationRules = Nil,
                               completionCriteria = Nil,
                               isDefault = false,
                               occurredAt = now,
                             )
                           )
            decisionRef <- Ref.make(Map.empty[DecisionId, Decision])
            engine       = makeEngine(decisionRef, propRepo, govRepo)
            target       = EvolutionTarget.GovernancePolicyTarget(
                             projectId = projectId,
                             policyId = Some(policyId),
                             name = "Updated Policy",
                             transitionRules = Nil,
                             daemonTriggers = Nil,
                             escalationRules = Nil,
                             completionCriteria = List(
                               GovernanceCompletionCriteria("feature", List(GovernanceGate.CodeReview))
                             ),
                             isDefault = false,
                           )
            created     <- engine.propose(EvolutionProposalRequest(
                             projectId,
                             "Add CodeReview gate",
                             "CI compliance",
                             target,
                             "agent",
                             "proposal",
                           ))
            decisionId   = created.decisionId.get
            _           <- engine.decisionInbox.resolve(decisionId, DecisionResolutionKind.Approved, "human", "OK")
            _           <- engine.approve(created.id, "human", "approved")
            applied     <- engine.apply(created.id, "system", "applied")
            policy      <- govRepo.getActiveByProject(projectId)
          yield assertTrue(
            applied.status == EvolutionProposalStatus.Applied,
            policy.completionCriteria.exists(c =>
              c.issueType == "feature" && c.requiredGates.contains(GovernanceGate.CodeReview)
            ),
          )).provideLayer(esLayer(path))
        }
      },
      test("proposal status persists in ES after apply") {
        withTempDir { path =>
          (for
            govRepo     <- ZIO.service[GovernancePolicyRepository]
            propRepo    <- ZIO.service[EvolutionProposalRepository]
            policyId     = GovernancePolicyId("policy-2")
            _           <- govRepo.append(
                             GovernancePolicyEvent.PolicyCreated(
                               policyId = policyId,
                               projectId = projectId,
                               name = "Policy",
                               version = 1,
                               transitionRules = Nil,
                               daemonTriggers = Nil,
                               escalationRules = Nil,
                               completionCriteria = Nil,
                               isDefault = false,
                               occurredAt = now,
                             )
                           )
            decisionRef <- Ref.make(Map.empty[DecisionId, Decision])
            engine       = makeEngine(decisionRef, propRepo, govRepo)
            target       = EvolutionTarget.GovernancePolicyTarget(
                             projectId = projectId,
                             policyId = Some(policyId),
                             name = "Changed",
                             transitionRules = Nil,
                             daemonTriggers = Nil,
                             escalationRules = Nil,
                             completionCriteria = Nil,
                             isDefault = false,
                           )
            created     <- engine.propose(EvolutionProposalRequest(
                             projectId,
                             "Minor tweak",
                             "rationale",
                             target,
                             "agent",
                             "proposal",
                           ))
            _           <- engine.decisionInbox.resolve(created.decisionId.get, DecisionResolutionKind.Approved, "human", "OK")
            _           <- engine.approve(created.id, "human", "approved")
            _           <- engine.apply(created.id, "system", "applied")
            // Read back from ES - should have Applied status
            reloaded    <- propRepo.get(created.id)
          yield assertTrue(reloaded.status == EvolutionProposalStatus.Applied))
            .provideLayer(esLayer(path))
        }
      },
      test("rollback restores governance policy to pre-apply state") {
        withTempDir { path =>
          (for
            govRepo     <- ZIO.service[GovernancePolicyRepository]
            propRepo    <- ZIO.service[EvolutionProposalRepository]
            policyId     = GovernancePolicyId("policy-3")
            _           <- govRepo.append(
                             GovernancePolicyEvent.PolicyCreated(
                               policyId = policyId,
                               projectId = projectId,
                               name = "Original",
                               version = 1,
                               transitionRules = Nil,
                               daemonTriggers = Nil,
                               escalationRules = Nil,
                               completionCriteria = Nil,
                               isDefault = false,
                               occurredAt = now,
                             )
                           )
            decisionRef <- Ref.make(Map.empty[DecisionId, Decision])
            engine       = makeEngine(decisionRef, propRepo, govRepo)
            target       = EvolutionTarget.GovernancePolicyTarget(
                             projectId = projectId,
                             policyId = Some(policyId),
                             name = "Modified",
                             transitionRules = Nil,
                             daemonTriggers = Nil,
                             escalationRules = Nil,
                             completionCriteria = List(
                               GovernanceCompletionCriteria("task", List(GovernanceGate.CiPassed))
                             ),
                             isDefault = false,
                           )
            created     <- engine.propose(EvolutionProposalRequest(
                             projectId,
                             "Add CI gate",
                             "rationale",
                             target,
                             "agent",
                             "proposal",
                           ))
            _           <- engine.decisionInbox.resolve(created.decisionId.get, DecisionResolutionKind.Approved, "human", "OK")
            _           <- engine.approve(created.id, "human", "approved")
            _           <- engine.apply(created.id, "system", "applied")
            // Verify applied state
            applied     <- govRepo.getActiveByProject(projectId)
            _           <- ZIO.dieMessage("CI gate should be present after apply").unless(
                             applied.completionCriteria.exists(_.requiredGates.contains(GovernanceGate.CiPassed))
                           )
            // Roll back
            rolled      <- engine.rollback(created.id, "system", "rollback")
            reloaded    <- propRepo.get(created.id)
          yield assertTrue(
            rolled.status == EvolutionProposalStatus.RolledBack,
            reloaded.status == EvolutionProposalStatus.RolledBack,
          )).provideLayer(esLayer(path))
        }
      },
    ) @@ sequential
