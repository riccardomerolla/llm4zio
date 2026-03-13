package orchestration.control

import zio.*
import zio.json.*

import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import conversation.entity.api.{ ChatConversation, ConversationEntry, MessageType, SenderType }
import db.{ ChatRepository, PersistenceError, TaskRepository }
import issues.entity.api.{ AgentIssueView, IssuePriority, IssueStatus }
import issues.entity.{ IssueEvent, IssueRepository }
import llm4zio.core.{ LlmError, LlmService }
import shared.ids.Ids.{ AgentId, EventId, IssueId, TaskRunId }

trait IssueAssignmentOrchestrator:
  def assignIssue(issueId: String, agentName: String): IO[PersistenceError, AgentIssueView]
  def assignIssue(
    issueId: String,
    agentName: String,
    skipConversationBootstrap: Boolean,
  ): IO[PersistenceError, AgentIssueView] =
    assignIssue(issueId, agentName)

object IssueAssignmentOrchestrator:

  def assignIssue(issueId: String, agentName: String)
    : ZIO[IssueAssignmentOrchestrator, PersistenceError, AgentIssueView] =
    ZIO.serviceWithZIO[IssueAssignmentOrchestrator](_.assignIssue(issueId, agentName))

  def assignIssue(issueId: String, agentName: String, skipConversationBootstrap: Boolean)
    : ZIO[IssueAssignmentOrchestrator, PersistenceError, AgentIssueView] =
    ZIO.serviceWithZIO[IssueAssignmentOrchestrator](
      _.assignIssue(issueId, agentName, skipConversationBootstrap)
    )

  val live: ZLayer[
    ChatRepository & TaskRepository & LlmService & AgentConfigResolver & ActivityHub & IssueRepository,
    Nothing,
    IssueAssignmentOrchestrator,
  ] =
    ZLayer.scoped {
      for
        chatRepository      <- ZIO.service[ChatRepository]
        migrationRepository <- ZIO.service[TaskRepository]
        llmService          <- ZIO.service[LlmService]
        configResolver      <- ZIO.service[AgentConfigResolver]
        activityHub         <- ZIO.service[ActivityHub]
        issueRepository     <- ZIO.service[IssueRepository]
        queue               <- Queue.unbounded[AssignmentTask]
        service              =
          IssueAssignmentOrchestratorLive(
            chatRepository,
            migrationRepository,
            llmService,
            configResolver,
            activityHub,
            issueRepository,
            queue,
          )
        _                   <- service.processQueue.forever.forkScoped
      yield service
    }

final private case class AssignmentTask(
  issueId: String,
  agentName: String,
  conversationId: String,
)

final private case class IssueAssignmentOrchestratorLive(
  chatRepository: ChatRepository,
  migrationRepository: TaskRepository,
  llmService: LlmService,
  configResolver: AgentConfigResolver,
  activityHub: ActivityHub,
  issueRepository: IssueRepository,
  queue: Queue[AssignmentTask],
) extends IssueAssignmentOrchestrator:

  override def assignIssue(issueId: String, agentName: String): IO[PersistenceError, AgentIssueView] =
    assignIssue(issueId, agentName, skipConversationBootstrap = false)

  override def assignIssue(
    issueId: String,
    agentName: String,
    skipConversationBootstrap: Boolean,
  ): IO[PersistenceError, AgentIssueView] =
    for
      issue   <- issueRepository.get(IssueId(issueId)).mapError(mapRepoError)
      now     <- Clock.instant
      _       <- issueRepository
                   .append(
                     IssueEvent.Assigned(
                       issueId = IssueId(issueId),
                       agent = AgentId(agentName),
                       assignedAt = now,
                       occurredAt = now,
                     )
                   )
                   .mapError(mapRepoError)
      _       <- issueRepository
                   .append(
                     IssueEvent.Started(
                       issueId = IssueId(issueId),
                       agent = AgentId(agentName),
                       startedAt = now,
                       occurredAt = now,
                     )
                   )
                   .mapError(mapRepoError)
      _       <- if skipConversationBootstrap then ZIO.unit
                 else
                   for
                     convId <- ensureIssueConversation(issueId, issue)
                     _      <- queue.offer(AssignmentTask(issueId, agentName, convId))
                   yield ()
      _       <- activityHub.publish(
                   ActivityEvent(
                     id = EventId.generate,
                     eventType = ActivityEventType.AgentAssigned,
                     source = "issue-assignment",
                     runId = issue.runId.map(r => TaskRunId(r.value)),
                     agentName = Some(agentName),
                     summary = s"Agent '$agentName' assigned to issue #$issueId: ${issue.title}",
                     createdAt = now,
                   )
                 )
      updated <- issueRepository.get(IssueId(issueId)).mapError(mapRepoError)
    yield domainToView(updated)

  private def ensureIssueConversation(
    issueId: String,
    issue: issues.entity.AgentIssue,
  ): IO[PersistenceError, String] =
    issue.conversationId match
      case Some(cid) => ZIO.succeed(cid.value)
      case None      =>
        for
          now    <- Clock.instant
          convId <- chatRepository.createConversation(
                      ChatConversation(
                        runId = issue.runId.map(_.value),
                        title = s"Issue #$issueId: ${issue.title}",
                        description = Some("Auto-generated conversation from issue assignment"),
                        createdAt = now,
                        updatedAt = now,
                        createdBy = Some("system"),
                      )
                    )
        yield convId.toString

  private[orchestration] def processQueue: UIO[Unit] =
    queue.take.flatMap(processTask).catchAll(err => ZIO.logError(s"Issue assignment worker failed: $err"))

  private def processTask(task: AssignmentTask): IO[PersistenceError, Unit] =
    (for
      issue <- issueRepository.get(IssueId(task.issueId)).mapError(mapRepoError)
      _     <- sendIssueContextToAgent(issue, task.agentName, task.conversationId)
    yield ()).catchAll { err =>
      ZIO.logError(s"Issue assignment ${task.issueId} failed: $err")
    }

  private def sendIssueContextToAgent(
    issue: issues.entity.AgentIssue,
    agentName: String,
    conversationId: String,
  ): IO[PersistenceError, Unit] =
    for
      conversationKey <-
        ZIO
          .fromOption(conversationId.toLongOption)
          .orElseFail(PersistenceError.QueryFailed("issue", s"Invalid conversation id: $conversationId"))
      runMetadata     <- issue.runId match
                           case Some(runId) =>
                             runId.value.toLongOption match
                               case Some(parsedId) => migrationRepository.getRun(parsedId)
                               case None           => ZIO.none
                           case None        => ZIO.none
      customAgent     <- migrationRepository.getCustomAgentByName(agentName)
      prompt           = buildIssueAssignmentPrompt(issue, agentName, runMetadata, customAgent.map(_.systemPrompt))
      now             <- Clock.instant
      _               <- chatRepository.addMessage(
                           ConversationEntry(
                             conversationId = conversationId,
                             sender = "system",
                             senderType = SenderType.System,
                             content = prompt,
                             messageType = MessageType.Status,
                             createdAt = now,
                             updatedAt = now,
                           )
                         )
      llmResponse     <- llmService.execute(prompt).mapError(convertLlmError)
      now2            <- Clock.instant
      _               <- chatRepository.addMessage(
                           ConversationEntry(
                             conversationId = conversationId,
                             sender = "assistant",
                             senderType = SenderType.Assistant,
                             content = llmResponse.content,
                             messageType = MessageType.Text,
                             metadata = Some(llmResponse.metadata.toJson),
                             createdAt = now2,
                             updatedAt = now2,
                           )
                         )
      conv            <- chatRepository
                           .getConversation(conversationKey)
                           .someOrFail(PersistenceError.NotFound("conversation", conversationKey))
      _               <- chatRepository.updateConversation(conv.copy(updatedAt = now2))
    yield ()

  private def buildIssueAssignmentPrompt(
    issue: issues.entity.AgentIssue,
    agentName: String,
    run: Option[db.TaskRunRow],
    customSystemPrompt: Option[String],
  ): String =
    val runContext    = run match
      case Some(value) =>
        s"""Run metadata:
           |- runId: ${value.id}
           |- sourceDir: ${value.sourceDir}
           |- outputDir: ${value.outputDir}
           |- status: ${value.status}
           |- currentPhase: ${value.currentPhase.getOrElse("n/a")}
           |""".stripMargin
      case None        => "Run metadata: not linked"
    val systemContext = customSystemPrompt.map(_.trim).filter(_.nonEmpty) match
      case Some(prompt) =>
        s"""Custom agent system prompt (highest priority):
           |$prompt
           |
           |""".stripMargin
      case None         => ""
    s"""${systemContext}Issue assignment for agent: $agentName
       |
       |Issue title: ${issue.title}
       |Issue type: ${issue.issueType}
       |Priority: ${issue.priority}
       |Tags: ${if issue.tags.isEmpty then "none" else issue.tags.mkString(", ")}
       |Required capabilities: ${
        if issue.requiredCapabilities.isEmpty then "none" else issue.requiredCapabilities.mkString(", ")
      }
       |Context path: ${if issue.contextPath.isEmpty then "none" else issue.contextPath}
       |Source folder: ${if issue.sourceFolder.isEmpty then "none" else issue.sourceFolder}
       |
       |$runContext
       |
       |Markdown task:
       |${issue.description}
       |
       |Please execute this task and provide a concise implementation summary and next actions.
       |""".stripMargin

  private def mapRepoError(e: shared.errors.PersistenceError): PersistenceError =
    e match
      case shared.errors.PersistenceError.NotFound(entity, id)           =>
        PersistenceError.QueryFailed(entity, s"Not found: $id")
      case shared.errors.PersistenceError.QueryFailed(op, cause)         =>
        PersistenceError.QueryFailed(op, cause)
      case shared.errors.PersistenceError.SerializationFailed(entity, c) =>
        PersistenceError.QueryFailed(entity, c)
      case shared.errors.PersistenceError.StoreUnavailable(msg)          =>
        PersistenceError.QueryFailed("store", msg)

  private def domainToView(i: issues.entity.AgentIssue): AgentIssueView =
    import issues.entity.IssueState
    val (status, assignedAgent, assignedAt, completedAt, errorMessage) = i.state match
      case IssueState.Backlog(_)            => (IssueStatus.Backlog, None, None, None, None)
      case IssueState.Todo(at)              => (IssueStatus.Todo, None, Some(at), None, None)
      case IssueState.Open(_)               => (IssueStatus.Backlog, None, None, None, None)
      case IssueState.Assigned(agent, at)   => (IssueStatus.Todo, Some(agent.value), Some(at), None, None)
      case IssueState.InProgress(agent, at) => (IssueStatus.InProgress, Some(agent.value), Some(at), None, None)
      case IssueState.HumanReview(at)       => (IssueStatus.HumanReview, None, None, Some(at), None)
      case IssueState.Rework(at, msg)       => (IssueStatus.Rework, None, None, Some(at), Some(msg))
      case IssueState.Merging(at)           => (IssueStatus.Merging, None, None, Some(at), None)
      case IssueState.Done(at, _)           => (IssueStatus.Done, None, None, Some(at), None)
      case IssueState.Canceled(at, msg)     => (IssueStatus.Canceled, None, None, Some(at), Some(msg))
      case IssueState.Duplicated(at, msg)   => (IssueStatus.Duplicated, None, None, Some(at), Some(msg))
      case IssueState.Completed(_, at, _)   => (IssueStatus.Done, None, None, Some(at), None)
      case IssueState.Failed(_, at, msg)    => (IssueStatus.Rework, None, None, Some(at), Some(msg))
      case IssueState.Skipped(at, _)        => (IssueStatus.Canceled, None, None, Some(at), None)
    val priority                                                       = IssuePriority.values.find(_.toString.equalsIgnoreCase(i.priority)).getOrElse(IssuePriority.Medium)
    val createdAt                                                      = i.state match
      case IssueState.Backlog(at) => at
      case IssueState.Open(at)    => at
      case _                      => java.time.Instant.EPOCH
    AgentIssueView(
      id = Some(i.id.value),
      runId = i.runId.map(_.value),
      conversationId = i.conversationId.map(_.value),
      title = i.title,
      description = i.description,
      issueType = i.issueType,
      tags = if i.tags.isEmpty then None else Some(i.tags.mkString(",")),
      requiredCapabilities =
        if i.requiredCapabilities.isEmpty then None else Some(i.requiredCapabilities.mkString(",")),
      contextPath = Option(i.contextPath).filter(_.nonEmpty),
      sourceFolder = Option(i.sourceFolder).filter(_.nonEmpty),
      workspaceId = i.workspaceId,
      priority = priority,
      status = status,
      assignedAgent = assignedAgent,
      assignedAt = assignedAt,
      completedAt = completedAt,
      errorMessage = errorMessage,
      createdAt = createdAt,
      updatedAt = assignedAt.orElse(completedAt).getOrElse(createdAt),
    )

  private def convertLlmError(error: LlmError): PersistenceError =
    error match
      case LlmError.ProviderError(message, cause) =>
        PersistenceError.QueryFailed(
          "llm_service",
          s"Provider error: $message${cause.map(c => s" (${c.getMessage})").getOrElse("")}",
        )
      case LlmError.RateLimitError(retryAfter)    =>
        PersistenceError.QueryFailed(
          "llm_service",
          s"Rate limited${retryAfter.map(d => s", retry after ${d.toSeconds}s").getOrElse("")}",
        )
      case LlmError.AuthenticationError(message)  =>
        PersistenceError.QueryFailed("llm_service", s"Authentication failed: $message")
      case LlmError.InvalidRequestError(message)  =>
        PersistenceError.QueryFailed("llm_service", s"Invalid request: $message")
      case LlmError.TimeoutError(duration)        =>
        PersistenceError.QueryFailed("llm_service", s"Request timed out after ${duration.toSeconds}s")
      case LlmError.ParseError(message, raw)      =>
        PersistenceError.QueryFailed("llm_service", s"Parse error: $message\nRaw: ${raw.take(200)}")
      case LlmError.ToolError(toolName, message)  =>
        PersistenceError.QueryFailed("llm_service", s"Tool error ($toolName): $message")
      case LlmError.ConfigError(message)          =>
        PersistenceError.QueryFailed("llm_service", s"Configuration error: $message")
