package board.control

import java.time.Instant

import zio.*
import zio.test.*

import analysis.entity.{ AnalysisDoc, AnalysisEvent, AnalysisRepository, AnalysisType }
import board.entity.TimelineEntry
import board.entity.TimelineEntry.*
import conversation.entity.api.{ ChatConversation, ConversationEntry, MessageType, SenderType, SessionContextLink }
import db.ChatRepository
import decision.control.DecisionInbox
import decision.entity.*
import issues.entity.*
import plan.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.*
import specification.entity.*
import workspace.entity.*

object IssueTimelineServiceSpec extends ZIOSpecDefault:

  private val workspaceId   = "ws-1"
  private val boardIssueId  = BoardIssueId("issue-1")
  private val agentIssueId  = IssueId("issue-1")
  private val createdAt     = Instant.parse("2026-04-01T10:00:00Z")
  private val assignedAt    = createdAt.plusSeconds(60)
  private val runStartedAt  = createdAt.plusSeconds(120)
  private val chatAt        = createdAt.plusSeconds(180)
  private val decisionAt    = createdAt.plusSeconds(240)
  private val resolvedAt    = createdAt.plusSeconds(300)
  private val runFinishedAt = createdAt.plusSeconds(420)
  private val doneAt        = createdAt.plusSeconds(480)

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("IssueTimelineService")(
      test("builds a chronological timeline from issue events, runs, decisions, and chat") {
        val issueEvents = List(
          IssueEvent.Created(
            issueId = agentIssueId,
            title = "Add login endpoint",
            description = "Implement POST /login",
            issueType = "task",
            priority = "high",
            occurredAt = createdAt,
          ),
          IssueEvent.Assigned(agentIssueId, AgentId("code-agent"), assignedAt, assignedAt),
          IssueEvent.MarkedDone(agentIssueId, doneAt, "Merged successfully", doneAt),
        )

        val run = WorkspaceRun(
          id = "run-1",
          workspaceId = workspaceId,
          parentRunId = None,
          issueRef = boardIssueId.value,
          agentName = "code-agent",
          prompt = "Implement the issue",
          conversationId = "101",
          worktreePath = "/tmp/ws-1",
          branchName = "agent/issue-1",
          status = RunStatus.Completed,
          attachedUsers = Set.empty,
          controllerUserId = None,
          createdAt = runStartedAt,
          updatedAt = runFinishedAt,
        )

        val decision = Decision(
          id = DecisionId("decision-1"),
          title = "Review implementation",
          context = "Human review required",
          action = DecisionAction.ReviewIssue,
          source = DecisionSource(
            kind = DecisionSourceKind.IssueReview,
            referenceId = boardIssueId.value,
            summary = "Review run output",
            workspaceId = Some(workspaceId),
            issueId = Some(agentIssueId),
          ),
          urgency = DecisionUrgency.High,
          status = DecisionStatus.Resolved,
          deadlineAt = None,
          resolution = Some(
            DecisionResolution(
              kind = DecisionResolutionKind.Approved,
              actor = "reviewer",
              summary = "Looks good",
              respondedAt = resolvedAt,
            )
          ),
          escalatedAt = None,
          expiredAt = None,
          createdAt = decisionAt,
          updatedAt = resolvedAt,
        )

        val messages = List(
          ConversationEntry(
            id = Some("m2"),
            conversationId = "101",
            sender = "assistant",
            senderType = SenderType.Assistant,
            content = "Implemented the endpoint",
            messageType = MessageType.Text,
            metadata = None,
            createdAt = chatAt.plusSeconds(10),
            updatedAt = chatAt.plusSeconds(10),
          ),
          ConversationEntry(
            id = Some("m1"),
            conversationId = "101",
            sender = "user",
            senderType = SenderType.User,
            content = "Please implement the endpoint",
            messageType = MessageType.Text,
            metadata = None,
            createdAt = chatAt,
            updatedAt = chatAt,
          ),
        )

        val service = IssueTimelineServiceLive(
          issueRepository = StubIssueRepository(issueEvents),
          workspaceRepository = StubWorkspaceRepository(List(run)),
          decisionInbox = StubDecisionInbox(List(decision)),
          chatRepository = StubChatRepository(Map(101L -> messages)),
          analysisRepository = EmptyAnalysisRepository,
          planRepository = StubPlanRepository(Nil),
          specificationRepository = StubSpecRepository(Nil),
        )

        for context <- service.buildTimeline(workspaceId, boardIssueId)
        yield
          val entries = context.timeline
          assertTrue(
            entries.map(_.occurredAt) == entries.map(_.occurredAt).sorted,
            entries.collectFirst { case entry: IssueCreated => entry }.exists(
              _.priority == board.entity.IssuePriority.High
            ),
            entries.exists {
              case AgentAssigned(_, "code-agent", _) => true
              case _                                 => false
            },
            entries.exists {
              case RunStarted("run-1", "agent/issue-1", "101", `runStartedAt`) => true
              case _                                                           => false
            },
            entries.exists {
              case DecisionRaised("decision-1", "Review implementation", "High", `decisionAt`) => true
              case _                                                                           => false
            },
            entries.exists {
              case ReviewAction("decision-1", "Approved", "reviewer", "Looks good", `resolvedAt`) => true
              case _                                                                              => false
            },
            entries.exists {
              case RunCompleted("run-1", "Agent completed work", 300L, `runFinishedAt`) => true
              case _                                                                    => false
            },
            entries.exists {
              case GitChanges("run-1", `workspaceId`, "agent/issue-1", `runFinishedAt`) => true
              case _                                                                    => false
            },
            entries.exists {
              case IssueDone("Merged successfully", `doneAt`) => true
              case _                                          => false
            },
            entries.exists {
              case ChatMessages("run-1", "101", chatMessages, `chatAt`) =>
                chatMessages.map(_.role) == List("user", "assistant")
              case _                                                    => false
            },
            context.linkedPlans.isEmpty,
            context.linkedSpecs.isEmpty,
          )
      },
      test("ignores runs from other workspaces and non-numeric conversation ids") {
        val service = IssueTimelineServiceLive(
          issueRepository = StubIssueRepository(
            List(
              IssueEvent.Created(
                issueId = agentIssueId,
                title = "Task",
                description = "Task description",
                issueType = "task",
                priority = "medium",
                occurredAt = createdAt,
              )
            )
          ),
          workspaceRepository = StubWorkspaceRepository(
            List(
              WorkspaceRun(
                id = "run-1",
                workspaceId = "ws-2",
                parentRunId = None,
                issueRef = boardIssueId.value,
                agentName = "agent",
                prompt = "ignored",
                conversationId = "999",
                worktreePath = "/tmp/ws-2",
                branchName = "agent/other",
                status = RunStatus.Pending,
                attachedUsers = Set.empty,
                controllerUserId = None,
                createdAt = createdAt.plusSeconds(10),
                updatedAt = createdAt.plusSeconds(10),
              ),
              WorkspaceRun(
                id = "run-2",
                workspaceId = workspaceId,
                parentRunId = None,
                issueRef = boardIssueId.value,
                agentName = "agent",
                prompt = "kept",
                conversationId = "not-a-number",
                worktreePath = "/tmp/ws-1",
                branchName = "agent/current",
                status = RunStatus.Pending,
                attachedUsers = Set.empty,
                controllerUserId = None,
                createdAt = createdAt.plusSeconds(20),
                updatedAt = createdAt.plusSeconds(20),
              ),
            )
          ),
          decisionInbox = StubDecisionInbox(Nil),
          chatRepository = StubChatRepository(Map.empty),
          analysisRepository = EmptyAnalysisRepository,
          planRepository = StubPlanRepository(Nil),
          specificationRepository = StubSpecRepository(Nil),
        )

        for context <- service.buildTimeline(workspaceId, boardIssueId)
        yield
          val entries = context.timeline
          assertTrue(
            entries.count {
              case _: RunStarted => true
              case _             => false
            } == 1,
            !entries.exists {
              case ChatMessages(_, _, _, _) => true
              case _                        => false
            },
          )
      },
    )

  final private case class StubIssueRepository(events: List[IssueEvent]) extends IssueRepository:
    override def append(event: IssueEvent): IO[PersistenceError, Unit]             = ZIO.unit
    override def get(id: IssueId): IO[PersistenceError, AgentIssue]                =
      ZIO.fail(PersistenceError.NotFound("issue", id.value))
    override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]]      = ZIO.succeed(events)
    override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] = ZIO.succeed(Nil)
    override def delete(id: IssueId): IO[PersistenceError, Unit]                   = ZIO.unit

  final private case class StubWorkspaceRepository(runs: List[WorkspaceRun]) extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit]                      = ZIO.unit
    override def list: IO[PersistenceError, List[Workspace]]                                    = ZIO.succeed(Nil)
    override def listByProject(projectId: ProjectId): IO[PersistenceError, List[Workspace]]     = ZIO.succeed(Nil)
    override def get(id: String): IO[PersistenceError, Option[Workspace]]                       = ZIO.none
    override def delete(id: String): IO[PersistenceError, Unit]                                 = ZIO.unit
    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]                = ZIO.unit
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]        =
      ZIO.succeed(runs.filter(_.workspaceId == workspaceId))
    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] =
      ZIO.succeed(runs.filter(_.issueRef.trim.stripPrefix("#") == issueRef.trim.stripPrefix("#")))
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]                 =
      ZIO.succeed(runs.find(_.id == id))

  final private case class StubDecisionInbox(decisions: List[Decision]) extends DecisionInbox:
    override def openIssueReviewDecision(issue: AgentIssue): IO[PersistenceError, Decision] =
      unsupported("openIssueReviewDecision")
    override def resolve(id: DecisionId, resolutionKind: DecisionResolutionKind, actor: String, summary: String)
      : IO[PersistenceError, Decision] = unsupported("resolve")
    override def syncOpenIssueReviewDecision(
      issueId: IssueId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Option[Decision]] = ZIO.none
    override def resolveOpenIssueReviewDecision(
      issueId: IssueId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Option[Decision]] = ZIO.none
    override def escalate(id: DecisionId, reason: String): IO[PersistenceError, Decision]   = unsupported("escalate")
    override def get(id: DecisionId): IO[PersistenceError, Decision]                        = unsupported("get")
    override def list(filter: DecisionFilter): IO[PersistenceError, List[Decision]]         =
      ZIO.succeed(
        decisions.filter { decision =>
          filter.workspaceId.forall(Some(_) == decision.source.workspaceId) &&
          filter.issueId.forall(Some(_) == decision.source.issueId)
        }
      )
    override def runMaintenance(now: Instant): IO[PersistenceError, List[Decision]]         = ZIO.succeed(Nil)

    private def unsupported(operation: String): IO[PersistenceError, Decision] =
      ZIO.fail(PersistenceError.QueryFailed(operation, "unused"))

  final private case class StubChatRepository(messagesByConversation: Map[Long, List[ConversationEntry]])
    extends ChatRepository:
    override def createConversation(conversation: ChatConversation): IO[PersistenceError, Long]                        = ZIO.succeed(1L)
    override def getConversation(id: Long): IO[PersistenceError, Option[ChatConversation]]                             = ZIO.none
    override def listConversations(offset: Int, limit: Int): IO[PersistenceError, List[ChatConversation]]              =
      ZIO.succeed(Nil)
    override def getConversationsByChannel(channelName: String): IO[PersistenceError, List[ChatConversation]]          =
      ZIO.succeed(Nil)
    override def listConversationsByRun(runId: Long): IO[PersistenceError, List[ChatConversation]]                     = ZIO.succeed(Nil)
    override def updateConversation(conversation: ChatConversation): IO[PersistenceError, Unit]                        = ZIO.unit
    override def deleteConversation(id: Long): IO[PersistenceError, Unit]                                              = ZIO.unit
    override def addMessage(message: ConversationEntry): IO[PersistenceError, Long]                                    = ZIO.succeed(1L)
    override def getMessages(conversationId: Long): IO[PersistenceError, List[ConversationEntry]]                      =
      ZIO.succeed(messagesByConversation.getOrElse(conversationId, Nil))
    override def getMessagesSince(conversationId: Long, since: Instant): IO[PersistenceError, List[ConversationEntry]] =
      getMessages(conversationId).map(_.filter(message => !message.createdAt.isBefore(since)))
    override def upsertSessionContext(
      channelName: String,
      sessionKey: String,
      contextJson: String,
      updatedAt: Instant,
    ): IO[PersistenceError, Unit] = ZIO.unit
    override def getSessionContext(channelName: String, sessionKey: String): IO[PersistenceError, Option[String]]      =
      ZIO.none
    override def getSessionContextByConversation(conversationId: Long)
      : IO[PersistenceError, Option[SessionContextLink]] =
      ZIO.none
    override def getSessionContextByTaskRunId(taskRunId: Long): IO[PersistenceError, Option[SessionContextLink]]       =
      ZIO.none
    override def listSessionContexts: IO[PersistenceError, List[SessionContextLink]]                                   = ZIO.succeed(Nil)
    override def deleteSessionContext(channelName: String, sessionKey: String): IO[PersistenceError, Unit]             = ZIO.unit

  private object EmptyAnalysisRepository extends AnalysisRepository:
    override def append(event: AnalysisEvent): IO[PersistenceError, Unit]                        = ZIO.unit
    override def get(id: AnalysisDocId): IO[PersistenceError, AnalysisDoc]                       =
      ZIO.fail(PersistenceError.NotFound("analysis_doc", id.value))
    override def listByWorkspace(workspaceId: String): IO[PersistenceError, List[AnalysisDoc]]   = ZIO.succeed(Nil)
    override def listByType(analysisType: AnalysisType): IO[PersistenceError, List[AnalysisDoc]] = ZIO.succeed(Nil)

  final private case class StubPlanRepository(plans: List[Plan]) extends PlanRepository:
    override def append(event: PlanEvent): IO[PersistenceError, Unit]       = ZIO.unit
    override def get(id: PlanId): IO[PersistenceError, Plan]                =
      ZIO.fail(PersistenceError.NotFound("plan", id.value))
    override def history(id: PlanId): IO[PersistenceError, List[PlanEvent]] = ZIO.succeed(Nil)
    override def list: IO[PersistenceError, List[Plan]]                     = ZIO.succeed(plans)

  final private case class StubSpecRepository(specs: List[Specification]) extends SpecificationRepository:
    override def append(event: SpecificationEvent): IO[PersistenceError, Unit]                                        = ZIO.unit
    override def get(id: SpecificationId): IO[PersistenceError, Specification]                                        =
      ZIO.fail(PersistenceError.NotFound("specification", id.value))
    override def history(id: SpecificationId): IO[PersistenceError, List[SpecificationEvent]]                         = ZIO.succeed(Nil)
    override def list: IO[PersistenceError, List[Specification]]                                                      = ZIO.succeed(specs)
    override def diff(id: SpecificationId, fromVersion: Int, toVersion: Int): IO[PersistenceError, SpecificationDiff] =
      ZIO.succeed(SpecificationDiff(fromVersion, toVersion, "", ""))
