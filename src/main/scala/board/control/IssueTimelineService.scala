package board.control

import zio.*

import analysis.entity.{ AnalysisRepository, AnalysisType }
import board.entity.*
import board.entity.TimelineEntry.*
import conversation.entity.ChatRepository
import conversation.entity.api.ConversationEntry
import decision.control.DecisionInbox
import decision.entity.*
import issues.boundary.IssueControllerSupport
import issues.entity.*
import plan.entity.{ Plan, PlanRepository }
import shared.errors.PersistenceError
import shared.ids.Ids.*
import specification.entity.{ Specification, SpecificationRepository }
import workspace.entity.{ RunStatus, WorkspaceRepository, WorkspaceRun }

trait IssueTimelineService:
  def buildTimeline(workspaceId: String, issueId: BoardIssueId): IO[PersistenceError, IssueContext]

object IssueTimelineService:
  def buildTimeline(
    workspaceId: String,
    issueId: BoardIssueId,
  ): ZIO[IssueTimelineService, PersistenceError, IssueContext] =
    ZIO.serviceWithZIO[IssueTimelineService](_.buildTimeline(workspaceId, issueId))

  val live: ZLayer[
    IssueRepository & WorkspaceRepository & DecisionInbox & ChatRepository & AnalysisRepository &
      PlanRepository & SpecificationRepository,
    Nothing,
    IssueTimelineService,
  ] =
    ZLayer.fromFunction(IssueTimelineServiceLive.apply)

final case class IssueTimelineServiceLive(
  issueRepository: IssueRepository,
  workspaceRepository: WorkspaceRepository,
  decisionInbox: DecisionInbox,
  chatRepository: ChatRepository,
  analysisRepository: AnalysisRepository,
  planRepository: PlanRepository,
  specificationRepository: SpecificationRepository,
) extends IssueTimelineService:

  override def buildTimeline(workspaceId: String, issueId: BoardIssueId): IO[PersistenceError, IssueContext] =
    val agentIssueId = IssueId(issueId.value)

    for
      issueEvents     <- issueRepository.history(agentIssueId)
      runs            <- workspaceRepository
                           .listRunsByIssueRef(issueId.value)
                           .map(_.filter(_.workspaceId == workspaceId))
      decisions       <- decisionInbox.list(DecisionFilter(issueId = Some(agentIssueId), workspaceId = Some(workspaceId)))
      chatEntries     <- ZIO.foreach(runs)(loadRunChatEntries)
      analysisEntries <- loadAnalysisEntries(workspaceId, issueEvents, runs)
      timeline         = issueEvents.flatMap(mapIssueEvent) ++
                           runs.flatMap(mapRun) ++
                           decisions.flatMap(mapDecision) ++
                           chatEntries.flatten ++
                           analysisEntries
      plans           <- planRepository.list
      specs           <- specificationRepository.list
      linkedPlans      = plans
                           .filter(_.linkedIssueIds.exists(_.value == agentIssueId.value))
                           .map(toLinkedPlan)
      linkedSpecs      = specs
                           .filter(_.linkedIssueIds.exists(_.value == agentIssueId.value))
                           .map(toLinkedSpec)
    yield IssueContext(
      timeline = timeline.sortBy(_.occurredAt),
      linkedPlans = linkedPlans,
      linkedSpecs = linkedSpecs,
    )

  private def mapIssueEvent(event: IssueEvent): List[TimelineEntry] =
    event match
      case e: IssueEvent.Created        =>
        List(
          IssueCreated(
            issueId = BoardIssueId(e.issueId.value),
            title = e.title,
            description = e.description,
            priority = parseIssuePriority(e.priority),
            tags = Nil,
            occurredAt = e.occurredAt,
          )
        )
      case e: IssueEvent.MovedToTodo    =>
        List(MovedToTodo(BoardIssueId(e.issueId.value), e.occurredAt))
      case e: IssueEvent.Assigned       =>
        List(AgentAssigned(BoardIssueId(e.issueId.value), e.agent.value, e.occurredAt))
      case e: IssueEvent.MovedToRework  =>
        List(ReworkRequested(e.reason, "human", e.occurredAt))
      case e: IssueEvent.MergeSucceeded =>
        List(Merged(s"merge:${e.commitSha}", e.occurredAt))
      case e: IssueEvent.MarkedDone     =>
        List(IssueDone(e.result, e.occurredAt))
      case e: IssueEvent.RunFailed      =>
        List(IssueFailed(e.reason, e.occurredAt))
      case e: IssueEvent.Failed         =>
        List(IssueFailed(e.errorMessage, e.occurredAt))
      case _                            =>
        Nil

  private def mapRun(run: WorkspaceRun): List[TimelineEntry] =
    val started = RunStarted(
      runId = run.id,
      branchName = run.branchName,
      conversationId = run.conversationId,
      occurredAt = run.createdAt,
    )

    val terminalEntries =
      run.status match
        case RunStatus.Completed =>
          val durationSeconds = java.time.Duration.between(run.createdAt, run.updatedAt).getSeconds
          List(
            RunCompleted(run.id, "Agent completed work", durationSeconds, run.updatedAt),
            GitChanges(run.id, run.workspaceId, run.branchName, run.updatedAt),
          )
        case RunStatus.Failed    =>
          List(
            IssueFailed("Run failed", run.updatedAt),
            GitChanges(run.id, run.workspaceId, run.branchName, run.updatedAt),
          )
        case _                   =>
          Nil

    started :: terminalEntries

  private def mapDecision(decision: Decision): List[TimelineEntry] =
    val raised = DecisionRaised(
      decisionId = decision.id.value,
      title = decision.title,
      urgency = decision.urgency.toString,
      occurredAt = decision.createdAt,
    )

    val resolutionEntries = decision.resolution.toList.map { resolution =>
      ReviewAction(
        decisionId = decision.id.value,
        action = resolution.kind.toString,
        actor = resolution.actor,
        summary = resolution.summary,
        occurredAt = resolution.respondedAt,
      )
    }

    raised :: resolutionEntries

  private def loadRunChatEntries(run: WorkspaceRun): IO[PersistenceError, List[TimelineEntry]] =
    run.conversationId.toLongOption match
      case None                 => ZIO.succeed(Nil)
      case Some(conversationId) =>
        chatRepository.getMessages(conversationId).map(messages => mapChatMessages(run, messages))

  private def mapChatMessages(run: WorkspaceRun, messages: List[ConversationEntry]): List[TimelineEntry] =
    val sortedMessages = messages.sortBy(_.createdAt)
    sortedMessages match
      case Nil          => Nil
      case head :: tail =>
        val allMessages = head :: tail
        List(
          ChatMessages(
            runId = run.id,
            conversationId = run.conversationId,
            messages = allMessages.map(toChatMessageSummary),
            occurredAt = head.createdAt,
          )
        )

  private def toChatMessageSummary(message: ConversationEntry): ChatMessageSummary =
    ChatMessageSummary(
      role = message.senderType.toString.toLowerCase,
      contentPreview = message.content.take(200),
      fullContent = message.content,
      timestamp = message.createdAt,
    )

  private def loadAnalysisEntries(
    workspaceId: String,
    issueEvents: List[IssueEvent],
    runs: List[WorkspaceRun],
  ): IO[PersistenceError, List[TimelineEntry]] =
    // Try event-linked doc IDs first; fall back to querying the workspace directly
    // because analysis runs (WorkspaceAnalysisScheduler) don't emit AnalysisAttached events.
    val eventDocIds =
      issueEvents.collect { case e: IssueEvent.AnalysisAttached => e }.flatMap(_.analysisDocIds).distinct

    // Derive which AnalysisTypes this issue's runs cover (agent names like "analysis-code-review")
    val runAnalysisTypes = runs.flatMap(r => agentNameToAnalysisType(r.agentName)).distinct

    val loadDocs =
      if eventDocIds.nonEmpty then
        ZIO
          .foreach(eventDocIds)(docId => analysisRepository.get(docId).either.map(_.toOption))
          .map(_.flatten)
      else
        analysisRepository.listByWorkspace(workspaceId).map { allDocs =>
          if runAnalysisTypes.nonEmpty then
            // Only show docs matching this issue's analysis types
            allDocs.filter(doc => runAnalysisTypes.contains(doc.analysisType))
          else allDocs
        }

    for
      workspace    <- workspaceRepository.get(workspaceId)
      workspacePath = workspace.flatMap(ws => Option(ws.localPath).map(_.trim).filter(_.nonEmpty))
      docs         <- loadDocs
    yield docs.map { doc =>
      val title     = IssueControllerSupport.analysisTitle(doc.analysisType)
      val vscodeUrl = workspacePath.map(IssueControllerSupport.buildVscodeUrl(_, doc.filePath))
      AnalysisDocAttached(
        title = title,
        analysisType = title,
        content = doc.content,
        filePath = doc.filePath,
        vscodeUrl = vscodeUrl,
        occurredAt = doc.createdAt,
      )
    }

  private def agentNameToAnalysisType(agentName: String): Option[AnalysisType] =
    agentName match
      case "analysis-code-review"               => Some(AnalysisType.CodeReview)
      case "analysis-architecture"              => Some(AnalysisType.Architecture)
      case "analysis-security"                  => Some(AnalysisType.Security)
      case name if name.startsWith("analysis-") =>
        Some(AnalysisType.Custom(name.stripPrefix("analysis-")))
      case _                                    => None

  private def toLinkedPlan(plan: Plan): LinkedPlan =
    LinkedPlan(
      id = plan.id.value,
      summary = plan.summary,
      status = plan.status.toString,
      taskCount = plan.drafts.size,
      validationStatus = plan.validation.map(_.status.toString),
      specificationId = plan.specificationId.map(_.value),
      createdAt = plan.createdAt,
    )

  private def toLinkedSpec(spec: Specification): LinkedSpec =
    LinkedSpec(
      id = spec.id.value,
      title = spec.title,
      status = spec.status.toString,
      version = spec.version,
      author = spec.author.displayName,
      contentPreview = spec.content.take(200),
      reviewCommentCount = spec.reviewComments.size,
      createdAt = spec.createdAt,
    )

  private def parseIssuePriority(value: String): IssuePriority =
    value.trim.toLowerCase match
      case "critical" => IssuePriority.Critical
      case "high"     => IssuePriority.High
      case "low"      => IssuePriority.Low
      case _          => IssuePriority.Medium
