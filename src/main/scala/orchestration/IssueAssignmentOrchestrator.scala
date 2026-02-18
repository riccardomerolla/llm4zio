package orchestration

import java.time.Instant

import zio.*
import zio.json.*

import db.{ ChatRepository, PersistenceError, TaskRepository }
import llm4zio.core.{ LlmError, LlmService }
import models.*
import web.ActivityHub

trait IssueAssignmentOrchestrator:
  def assignIssue(issueId: Long, agentName: String): IO[PersistenceError, AgentIssue]

object IssueAssignmentOrchestrator:

  def assignIssue(issueId: Long, agentName: String): ZIO[IssueAssignmentOrchestrator, PersistenceError, AgentIssue] =
    ZIO.serviceWithZIO[IssueAssignmentOrchestrator](_.assignIssue(issueId, agentName))

  val live
    : ZLayer[
      ChatRepository & TaskRepository & LlmService & AgentConfigResolver & ActivityHub,
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
        queue               <- Queue.unbounded[AssignmentTask]
        service              =
          IssueAssignmentOrchestratorLive(
            chatRepository,
            migrationRepository,
            llmService,
            configResolver,
            activityHub,
            queue,
          )
        _                   <- service.processQueue.forever.forkScoped
      yield service
    }

final private case class AssignmentTask(
  assignmentId: Long,
  issueId: Long,
  agentName: String,
)

final private case class IssueAssignmentOrchestratorLive(
  chatRepository: ChatRepository,
  migrationRepository: TaskRepository,
  llmService: LlmService,
  configResolver: AgentConfigResolver,
  activityHub: ActivityHub,
  queue: Queue[AssignmentTask],
) extends IssueAssignmentOrchestrator:

  override def assignIssue(issueId: Long, agentName: String): IO[PersistenceError, AgentIssue] =
    for
      issue         <- chatRepository.getIssue(issueId).someOrFail(PersistenceError.NotFound("issue", issueId))
      assignments   <- chatRepository.listAssignmentsByIssue(issueId)
      existingActive =
        assignments.find(assignment =>
          assignment.agentName.equalsIgnoreCase(agentName) &&
          (assignment.status.equalsIgnoreCase("pending") || assignment.status.equalsIgnoreCase("processing"))
        )
      result        <- existingActive match
                         case Some(_) =>
                           ensureIssueConversation(issue, agentName)
                         case None    =>
                           for
                             now          <- Clock.instant
                             _            <- chatRepository.assignIssueToAgent(issueId, agentName)
                             conversation <- ensureIssueConversation(issue, agentName)
                             assignmentId <- chatRepository.createAssignment(
                                               AgentAssignment(
                                                 issueId = issueId,
                                                 agentName = agentName,
                                                 status = "pending",
                                                 assignedAt = now,
                                               )
                                             )
                             _            <- queue.offer(AssignmentTask(assignmentId, issueId, agentName))
                             _            <- activityHub.publish(
                                               ActivityEvent(
                                                 eventType = ActivityEventType.AgentAssigned,
                                                 source = "issue-assignment",
                                                 runId = issue.runId,
                                                 agentName = Some(agentName),
                                                 summary =
                                                   s"Agent '$agentName' assigned to issue #$issueId: ${issue.title}",
                                                 createdAt = now,
                                               )
                                             )
                           yield conversation
    yield result

  private[orchestration] def processQueue: UIO[Unit] =
    queue.take.flatMap(processTask).catchAll(err => ZIO.logError(s"Issue assignment worker failed: $err"))

  private def processTask(task: AssignmentTask): IO[PersistenceError, Unit] =
    (for
      issue      <- chatRepository
                      .getIssue(task.issueId)
                      .someOrFail(PersistenceError.NotFound("issue", task.issueId))
      now        <- Clock.instant
      assignment <- chatRepository
                      .getAssignment(task.assignmentId)
                      .someOrFail(PersistenceError.NotFound("agent_assignment", task.assignmentId))
      _          <- chatRepository.updateAssignment(
                      assignment.copy(
                        status = "processing",
                        startedAt = Some(now),
                      )
                    )
      _          <- sendIssueContextToAgent(issue, task.agentName)
      doneAt     <- Clock.instant
      latest     <- chatRepository
                      .getAssignment(task.assignmentId)
                      .someOrFail(PersistenceError.NotFound("agent_assignment", task.assignmentId))
      _          <- chatRepository.updateAssignment(
                      latest.copy(
                        status = "completed",
                        completedAt = Some(doneAt),
                      )
                    )
    yield ()).catchAll { err =>
      for
        failedAt <- Clock.instant
        maybe    <- chatRepository.getAssignment(task.assignmentId)
        _        <- ZIO.foreachDiscard(maybe) { assignment =>
                      chatRepository.updateAssignment(
                        assignment.copy(
                          status = "failed",
                          completedAt = Some(failedAt),
                          executionLog = Some(err.toString),
                        )
                      )
                    }
        _        <- ZIO.logError(s"Issue assignment ${task.assignmentId} failed: $err")
      yield ()
    }

  private def sendIssueContextToAgent(issue: AgentIssue, agentName: String): IO[PersistenceError, Unit] =
    for
      conversationId <- ZIO
                          .fromOption(issue.conversationId)
                          .orElseFail(PersistenceError.QueryFailed("issue", "Issue is missing linked conversation"))
      runMetadata    <- issue.runId match
                          case Some(runId) => migrationRepository.getRun(runId)
                          case None        => ZIO.none
      customAgent    <- migrationRepository.getCustomAgentByName(agentName)
      prompt          = buildIssueAssignmentPrompt(issue, agentName, runMetadata, customAgent.map(_.systemPrompt))
      now            <- Clock.instant
      _              <- chatRepository.addMessage(
                          ConversationMessage(
                            conversationId = conversationId,
                            sender = "system",
                            senderType = SenderType.System,
                            content = prompt,
                            messageType = MessageType.Status,
                            createdAt = now,
                            updatedAt = now,
                          )
                        )
      llmResponse    <- llmService.execute(prompt).mapError(convertLlmError)
      now2           <- Clock.instant
      _              <- chatRepository.addMessage(
                          ConversationMessage(
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
      conv           <- chatRepository
                          .getConversation(conversationId)
                          .someOrFail(PersistenceError.NotFound("conversation", conversationId))
      _              <- chatRepository.updateConversation(conv.copy(updatedAt = now2))
    yield ()

  private def ensureIssueConversation(issue: AgentIssue, agentName: String): IO[PersistenceError, AgentIssue] =
    issue.conversationId match
      case Some(_) =>
        chatRepository
          .getIssue(issue.id.getOrElse(0L))
          .someOrFail(PersistenceError.NotFound("issue", issue.id.getOrElse(0L)))
      case None    =>
        for
          issueId <- ZIO
                       .fromOption(issue.id)
                       .orElseFail(PersistenceError.QueryFailed("issue", "Issue ID missing during assignment"))
          now     <- Clock.instant
          convId  <- chatRepository.createConversation(
                       ChatConversation(
                         runId = issue.runId,
                         title = s"Issue #$issueId: ${issue.title}",
                         description = Some("Auto-generated conversation from issue assignment"),
                         createdAt = now,
                         updatedAt = now,
                         createdBy = Some("system"),
                       )
                     )
          _       <- chatRepository.updateIssue(
                       issue.copy(
                         conversationId = Some(convId),
                         assignedAgent = Some(agentName),
                         assignedAt = Some(now),
                         status = IssueStatus.Assigned,
                         updatedAt = now,
                       )
                     )
          updated <- chatRepository
                       .getIssue(issueId)
                       .someOrFail(PersistenceError.NotFound("issue", issueId))
        yield updated

  private def buildIssueAssignmentPrompt(
    issue: AgentIssue,
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
       |Tags: ${issue.tags.getOrElse("none")}
       |Preferred agent: ${issue.preferredAgent.getOrElse("none")}
       |Context path: ${issue.contextPath.getOrElse("none")}
       |Source folder: ${issue.sourceFolder.getOrElse("none")}
       |
       |$runContext
       |
       |Markdown task:
       |${issue.description}
       |
       |Please execute this task and provide a concise implementation summary and next actions.
       |""".stripMargin

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
