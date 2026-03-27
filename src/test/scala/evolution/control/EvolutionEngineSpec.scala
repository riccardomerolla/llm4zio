package evolution.control

import java.time.Instant

import zio.*
import zio.test.*

import _root_.config.entity.WorkflowDefinition
import daemon.entity.*
import db.{ ConfigRepository, CustomAgentRow, PersistenceError as DbPersistenceError, SettingRow, WorkflowRow }
import decision.control.DecisionInbox
import decision.entity.*
import evolution.entity.*
import governance.entity.*
import orchestration.control.{ WorkflowService, WorkflowServiceError }
import shared.errors.PersistenceError
import shared.ids.Ids.{ DecisionId, EvolutionProposalId, GovernancePolicyId, ProjectId }

object EvolutionEngineSpec extends ZIOSpecDefault:

  private val now       = Instant.parse("2026-03-26T13:00:00Z")
  private val projectId = ProjectId("project-1")

  final private class InMemoryProposalRepository(ref: Ref[Map[EvolutionProposalId, List[EvolutionProposalEvent]]])
    extends EvolutionProposalRepository:
    override def append(event: EvolutionProposalEvent): IO[PersistenceError, Unit] =
      ref.update(current => current.updated(event.proposalId, current.getOrElse(event.proposalId, Nil) :+ event))

    override def get(id: EvolutionProposalId): IO[PersistenceError, EvolutionProposal] =
      history(id).flatMap(events =>
        ZIO.fromEither(EvolutionProposal.fromEvents(events)).mapError(error =>
          PersistenceError.SerializationFailed(id.value, error)
        )
      )

    override def history(id: EvolutionProposalId): IO[PersistenceError, List[EvolutionProposalEvent]] =
      ref.get.flatMap(state =>
        ZIO.fromOption(state.get(id)).orElseFail(PersistenceError.NotFound("evolution_proposal", id.value))
      )

    override def list(filter: EvolutionProposalFilter): IO[PersistenceError, List[EvolutionProposal]] =
      ref.get.flatMap(state => ZIO.foreach(state.keys.toList)(get))

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
    override def escalate(id: DecisionId, reason: String): IO[PersistenceError, Decision]                 = ZIO.dieMessage("unused")
    override def get(id: DecisionId): IO[PersistenceError, Decision]                                      =
      ref.get.flatMap(state =>
        ZIO.fromOption(state.get(id)).orElseFail(PersistenceError.NotFound("decision", id.value))
      )
    override def list(filter: DecisionFilter): IO[PersistenceError, List[Decision]]                       = ref.get.map(_.values.toList)
    override def runMaintenance(now: Instant): IO[PersistenceError, List[Decision]]                       = ZIO.succeed(Nil)

  final private class StubGovernanceRepo(ref: Ref[Map[GovernancePolicyId, List[GovernancePolicyEvent]]])
    extends GovernancePolicyRepository:
    override def append(event: GovernancePolicyEvent): IO[PersistenceError, Unit]                  =
      ref.update(current => current.updated(event.policyId, current.getOrElse(event.policyId, Nil) :+ event))
    override def get(id: GovernancePolicyId): IO[PersistenceError, GovernancePolicy]               =
      ref.get.flatMap(state =>
        ZIO.fromOption(state.get(id)).orElseFail(PersistenceError.NotFound("governance_policy", id.value)).flatMap(
          events =>
            ZIO.fromEither(GovernancePolicy.fromEvents(events)).mapError(err =>
              PersistenceError.SerializationFailed(id.value, err)
            )
        )
      )
    override def getActiveByProject(projectId: ProjectId): IO[PersistenceError, GovernancePolicy]  =
      listByProject(projectId).flatMap(_.find(_.archivedAt.isEmpty) match
        case Some(value) => ZIO.succeed(value)
        case None        => ZIO.fail(PersistenceError.NotFound("governance_policy", projectId.value)))
    override def listByProject(projectId: ProjectId): IO[PersistenceError, List[GovernancePolicy]] =
      ref.get.flatMap(state => ZIO.foreach(state.keys.toList)(get).map(_.filter(_.projectId == projectId)))

  final private class StubWorkflowService(ref: Ref[Map[String, WorkflowDefinition]]) extends WorkflowService:
    override def createWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Long]          =
      ref.update(_.updated(workflow.name, workflow.copy(id = Some("1")))).as(1L)
    override def getWorkflow(id: Long): IO[WorkflowServiceError, Option[WorkflowDefinition]]           =
      ref.get.map(_.values.find(_.id.contains(id.toString)))
    override def getWorkflowByName(name: String): IO[WorkflowServiceError, Option[WorkflowDefinition]] =
      ref.get.map(_.get(name))
    override def listWorkflows: IO[WorkflowServiceError, List[WorkflowDefinition]]                     = ref.get.map(_.values.toList)
    override def updateWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Unit]          =
      ref.update(_.updated(workflow.name, workflow))
    override def deleteWorkflow(id: Long): IO[WorkflowServiceError, Unit]                              =
      ref.update(_.filterNot(_._2.id.contains(id.toString)))

  final private class StubDbConfigRepository(ref: Ref[Map[String, String]]) extends ConfigRepository:
    override def getAllSettings: IO[DbPersistenceError, List[SettingRow]]                           =
      ref.get.map(_.toList.map((k, v) => SettingRow(k, v, now)))
    override def getSetting(key: String): IO[DbPersistenceError, Option[SettingRow]]                =
      ref.get.map(_.get(key).map(SettingRow(key, _, now)))
    override def upsertSetting(key: String, value: String): IO[DbPersistenceError, Unit]            =
      ref.update(_.updated(key, value))
    override def deleteSetting(key: String): IO[DbPersistenceError, Unit]                           = ref.update(_ - key)
    override def deleteSettingsByPrefix(prefix: String): IO[DbPersistenceError, Unit]               =
      ref.update(_.filterNot(_._1.startsWith(prefix)))
    override def createWorkflow(workflow: WorkflowRow): IO[DbPersistenceError, Long]                = ZIO.succeed(1L)
    override def getWorkflow(id: Long): IO[DbPersistenceError, Option[WorkflowRow]]                 = ZIO.none
    override def getWorkflowByName(name: String): IO[DbPersistenceError, Option[WorkflowRow]]       = ZIO.none
    override def listWorkflows: IO[DbPersistenceError, List[WorkflowRow]]                           = ZIO.succeed(Nil)
    override def updateWorkflow(workflow: WorkflowRow): IO[DbPersistenceError, Unit]                = ZIO.unit
    override def deleteWorkflow(id: Long): IO[DbPersistenceError, Unit]                             = ZIO.unit
    override def createCustomAgent(agent: CustomAgentRow): IO[DbPersistenceError, Long]             = ZIO.succeed(1L)
    override def getCustomAgent(id: Long): IO[DbPersistenceError, Option[CustomAgentRow]]           = ZIO.none
    override def getCustomAgentByName(name: String): IO[DbPersistenceError, Option[CustomAgentRow]] = ZIO.none
    override def listCustomAgents: IO[DbPersistenceError, List[CustomAgentRow]]                     = ZIO.succeed(Nil)
    override def updateCustomAgent(agent: CustomAgentRow): IO[DbPersistenceError, Unit]             = ZIO.unit
    override def deleteCustomAgent(id: Long): IO[DbPersistenceError, Unit]                          = ZIO.unit

  final private class StubDaemonRepo(ref: Ref[Map[shared.ids.Ids.DaemonAgentSpecId, DaemonAgentSpec]])
    extends DaemonAgentSpecRepository:
    override def get(id: shared.ids.Ids.DaemonAgentSpecId): IO[PersistenceError, DaemonAgentSpec] =
      ref.get.flatMap(state =>
        ZIO.fromOption(state.get(id)).orElseFail(PersistenceError.NotFound("daemon_spec", id.value))
      )
    override def listByProject(projectId: ProjectId): IO[PersistenceError, List[DaemonAgentSpec]] =
      ref.get.map(_.values.filter(_.projectId == projectId).toList)
    override def listAll: IO[PersistenceError, List[DaemonAgentSpec]]                             = ref.get.map(_.values.toList)
    override def save(spec: DaemonAgentSpec): IO[PersistenceError, Unit]                          = ref.update(_.updated(spec.id, spec))
    override def delete(id: shared.ids.Ids.DaemonAgentSpecId): IO[PersistenceError, Unit]         = ref.update(_ - id)

  private def makeEngine =
    for
      proposalRef <- Ref.make(Map.empty[EvolutionProposalId, List[EvolutionProposalEvent]])
      decisionRef <- Ref.make(Map.empty[DecisionId, Decision])
      govId        = GovernancePolicyId("policy-1")
      govRef      <- Ref.make(
                       Map[GovernancePolicyId, List[GovernancePolicyEvent]](
                         govId -> List(
                           GovernancePolicyEvent.PolicyCreated(
                             policyId = govId,
                             projectId = projectId,
                             name = "default",
                             version = 1,
                             transitionRules = Nil,
                             daemonTriggers = Nil,
                             escalationRules = Nil,
                             completionCriteria = Nil,
                             isDefault = true,
                             occurredAt = now,
                           )
                         )
                       )
                     )
      workflowRef <- Ref.make(Map("workflow-a" -> WorkflowDefinition(
                       id = Some("9"),
                       name = "workflow-a",
                       steps = List("chat"),
                       isBuiltin = false,
                     )))
      configRef   <- Ref.make(Map.empty[String, String])
      daemonRef   <- Ref.make(Map.empty[shared.ids.Ids.DaemonAgentSpecId, DaemonAgentSpec])
    yield EvolutionEngineLive(
      repository = new InMemoryProposalRepository(proposalRef),
      decisionInbox = new StubDecisionInbox(decisionRef),
      governanceRepository = new StubGovernanceRepo(govRef),
      workflowService = new StubWorkflowService(workflowRef),
      configRepository = new StubDbConfigRepository(configRef),
      daemonRepository = new StubDaemonRepo(daemonRef),
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("EvolutionEngineSpec")(
      test("propose opens a decision-backed proposal") {
        val target = EvolutionTarget.WorkflowDefinitionTarget(
          projectId = projectId,
          workflow = WorkflowDefinition(name = "new-workflow", steps = List("chat", "test"), isBuiltin = false),
        )
        for
          engine   <- makeEngine
          proposal <- engine.propose(EvolutionProposalRequest(
                        projectId,
                        "New workflow",
                        "Need tests",
                        target,
                        "agent",
                        "proposal",
                      ))
        yield assertTrue(
          proposal.status == EvolutionProposalStatus.Proposed,
          proposal.decisionId.isDefined,
        )
      },
      test("approved governance proposals can be applied") {
        val target = EvolutionTarget.GovernancePolicyTarget(
          projectId = projectId,
          policyId = Some(GovernancePolicyId("policy-1")),
          name = "default",
          transitionRules = Nil,
          daemonTriggers = Nil,
          escalationRules = Nil,
          completionCriteria = List(GovernanceCompletionCriteria("feature", List(GovernanceGate.CodeReview))),
          isDefault = true,
        )
        for
          engine   <- makeEngine
          created  <- engine.propose(EvolutionProposalRequest(
                        projectId,
                        "Adjust governance",
                        "Need code review",
                        target,
                        "agent",
                        "proposal",
                      ))
          decision <- engine.get(created.id).map(_.decisionId.get)
          _        <- engine.asInstanceOf[EvolutionEngineLive].decisionInbox.resolve(
                        decision,
                        DecisionResolutionKind.Approved,
                        "human",
                        "ok",
                      ).orElseSucceed(
                        Decision(
                          id = decision,
                          title = "",
                          context = "",
                          action = DecisionAction.ManualEscalation,
                          source = DecisionSource(DecisionSourceKind.Manual, "", ""),
                          urgency = DecisionUrgency.Medium,
                          status = DecisionStatus.Pending,
                          deadlineAt = None,
                          createdAt = now,
                          updatedAt = now,
                        )
                      )
          _        <- engine.approve(created.id, "human", "approved")
          applied  <- engine.apply(created.id, "system", "applied")
        yield assertTrue(applied.status == EvolutionProposalStatus.Applied)
      },
      test("workflow proposals can be rolled back") {
        val target = EvolutionTarget.WorkflowDefinitionTarget(
          projectId = projectId,
          workflow = WorkflowDefinition(name = "workflow-a", steps = List("chat", "test"), isBuiltin = false),
        )
        for
          engine   <- makeEngine
          created  <- engine.propose(EvolutionProposalRequest(
                        projectId,
                        "Change workflow",
                        "Need tests",
                        target,
                        "agent",
                        "proposal",
                      ))
          decision <- engine.get(created.id).map(_.decisionId.get)
          _        <- engine.asInstanceOf[EvolutionEngineLive].decisionInbox.resolve(
                        decision,
                        DecisionResolutionKind.Approved,
                        "human",
                        "ok",
                      ).orElseSucceed(
                        Decision(
                          id = decision,
                          title = "",
                          context = "",
                          action = DecisionAction.ManualEscalation,
                          source = DecisionSource(DecisionSourceKind.Manual, "", ""),
                          urgency = DecisionUrgency.Medium,
                          status = DecisionStatus.Pending,
                          deadlineAt = None,
                          createdAt = now,
                          updatedAt = now,
                        )
                      )
          _        <- engine.approve(created.id, "human", "approved")
          _        <- engine.apply(created.id, "system", "applied")
          rolled   <- engine.rollback(created.id, "system", "rollback")
        yield assertTrue(rolled.status == EvolutionProposalStatus.RolledBack)
      },
      test("daemon proposals persist custom specs and enablement") {
        val spec   = DaemonAgentSpec(
          id = DaemonAgentSpec.idFor(projectId, "custom-checker"),
          daemonKey = "custom-checker",
          projectId = projectId,
          name = "Custom Checker",
          purpose = "Open recurring maintenance work",
          trigger = DaemonTriggerCondition.Scheduled(1.hour),
          workspaceIds = List("ws-1"),
          agentName = "code-agent",
          prompt = "Inspect the workspace",
          limits = DaemonExecutionLimits(),
          builtIn = false,
          governed = false,
        )
        val target = EvolutionTarget.DaemonAgentSpecTarget(spec = spec, enabled = true)
        for
          engine   <- makeEngine
          created  <- engine.propose(EvolutionProposalRequest(
                        projectId,
                        "Add daemon",
                        "Need automation",
                        target,
                        "agent",
                        "proposal",
                      ))
          decision <- engine.get(created.id).map(_.decisionId.get)
          _        <- engine.asInstanceOf[EvolutionEngineLive].decisionInbox.resolve(
                        decision,
                        DecisionResolutionKind.Approved,
                        "human",
                        "ok",
                      ).orElseSucceed(
                        Decision(
                          id = decision,
                          title = "",
                          context = "",
                          action = DecisionAction.ManualEscalation,
                          source = DecisionSource(DecisionSourceKind.Manual, "", ""),
                          urgency = DecisionUrgency.Medium,
                          status = DecisionStatus.Pending,
                          deadlineAt = None,
                          createdAt = now,
                          updatedAt = now,
                        )
                      )
          _        <- engine.approve(created.id, "human", "approved")
          applied  <- engine.apply(created.id, "system", "applied")
        yield assertTrue(applied.status == EvolutionProposalStatus.Applied)
      },
    )
