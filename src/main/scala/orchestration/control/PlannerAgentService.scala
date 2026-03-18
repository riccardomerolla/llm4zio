package orchestration.control

import java.time.Instant

import zio.*
import zio.json.*
import zio.json.ast.Json

import _root_.config.SettingsApplier
import _root_.config.entity.AIProviderConfig
import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import app.ApplicationDI
import conversation.entity.api.{ ChatConversation, ConversationEntry, MessageType, SenderType }
import db.{ ChatRepository, ConfigRepository, PersistenceError }
import issues.entity.{ AgentIssue as DomainIssue, IssueEvent, IssueRepository }
import llm4zio.core.{ LlmConfig, LlmError, LlmProvider, LlmService }
import llm4zio.providers.{ GeminiCliExecutionContext, GeminiCliExecutor, HttpClient }
import llm4zio.tools.JsonSchema
import shared.ids.Ids.{ EventId, IssueId }
import workspace.entity.WorkspaceRepository

enum PlannerAgentError:
  case ConversationNotFound(conversationId: Long)
  case EmptyConversation(conversationId: Long)
  case PreviewNotFound(conversationId: Long)
  case IssueDraftInvalid(details: String)
  case PersistenceFailure(operation: String, details: String)
  case LlmFailure(details: String)

  def message: String =
    this match
      case ConversationNotFound(conversationId)   => s"Planner conversation not found: $conversationId"
      case EmptyConversation(conversationId)      => s"Planner conversation $conversationId has no user messages yet"
      case PreviewNotFound(conversationId)        => s"No planner preview exists for conversation $conversationId"
      case IssueDraftInvalid(details)             => details
      case PersistenceFailure(operation, details) =>
        s"$operation failed: $details"
      case LlmFailure(details)                    => s"Planner agent failed: $details"

final case class PlannerIssueDraft(
  draftId: String,
  title: String,
  description: String,
  issueType: String = "task",
  priority: String = "medium",
  estimate: Option[String] = None,
  requiredCapabilities: List[String] = Nil,
  dependencyDraftIds: List[String] = Nil,
  acceptanceCriteria: String = "",
  promptTemplate: String = "",
  kaizenSkills: List[String] = Nil,
  proofOfWorkRequirements: List[String] = Nil,
  included: Boolean = true,
) derives JsonCodec

final case class PlannerPlanPreview(
  summary: String,
  issues: List[PlannerIssueDraft],
) derives JsonCodec

final case class PlannerPreviewState(
  conversationId: Long,
  workspaceId: Option[String],
  preview: PlannerPlanPreview,
  confirmedIssueIds: Option[List[IssueId]] = None,
  isGenerating: Boolean = false,
  lastError: Option[String] = None,
)

final case class PlannerSessionStart(
  conversationId: Long,
  warning: Option[String] = None,
)

final case class PlannerConfirmation(
  conversationId: Long,
  issueIds: List[IssueId],
)

enum PlannerInitialStatus:
  case Backlog, Todo

trait PlannerAgentService:
  def startSession(
    initialRequest: String,
    workspaceId: Option[String],
    createdBy: Option[String] = None,
  ): IO[PlannerAgentError, PlannerSessionStart]
  def regeneratePreview(conversationId: Long): IO[PlannerAgentError, PlannerPreviewState]
  def appendUserMessage(conversationId: Long, content: String): IO[PlannerAgentError, PlannerPreviewState]
  def getPreview(conversationId: Long): IO[PlannerAgentError, PlannerPreviewState]
  def updatePreview(conversationId: Long, preview: PlannerPlanPreview): IO[PlannerAgentError, PlannerPreviewState]
  def addBlankIssue(conversationId: Long): IO[PlannerAgentError, PlannerPreviewState]
  def removeIssue(conversationId: Long, draftId: String): IO[PlannerAgentError, PlannerPreviewState]
  def confirmPlan(conversationId: Long): IO[PlannerAgentError, PlannerConfirmation]

object PlannerAgentService:
  def startSession(
    initialRequest: String,
    workspaceId: Option[String],
    createdBy: Option[String] = None,
  ): ZIO[PlannerAgentService, PlannerAgentError, PlannerSessionStart] =
    ZIO.serviceWithZIO[PlannerAgentService](_.startSession(initialRequest, workspaceId, createdBy))

  def regeneratePreview(conversationId: Long): ZIO[PlannerAgentService, PlannerAgentError, PlannerPreviewState] =
    ZIO.serviceWithZIO[PlannerAgentService](_.regeneratePreview(conversationId))

  def appendUserMessage(
    conversationId: Long,
    content: String,
  ): ZIO[PlannerAgentService, PlannerAgentError, PlannerPreviewState] =
    ZIO.serviceWithZIO[PlannerAgentService](_.appendUserMessage(conversationId, content))

  def getPreview(conversationId: Long): ZIO[PlannerAgentService, PlannerAgentError, PlannerPreviewState] =
    ZIO.serviceWithZIO[PlannerAgentService](_.getPreview(conversationId))

  def updatePreview(
    conversationId: Long,
    preview: PlannerPlanPreview,
  ): ZIO[PlannerAgentService, PlannerAgentError, PlannerPreviewState] =
    ZIO.serviceWithZIO[PlannerAgentService](_.updatePreview(conversationId, preview))

  def addBlankIssue(conversationId: Long): ZIO[PlannerAgentService, PlannerAgentError, PlannerPreviewState] =
    ZIO.serviceWithZIO[PlannerAgentService](_.addBlankIssue(conversationId))

  def removeIssue(
    conversationId: Long,
    draftId: String,
  ): ZIO[PlannerAgentService, PlannerAgentError, PlannerPreviewState] =
    ZIO.serviceWithZIO[PlannerAgentService](_.removeIssue(conversationId, draftId))

  def confirmPlan(conversationId: Long): ZIO[PlannerAgentService, PlannerAgentError, PlannerConfirmation] =
    ZIO.serviceWithZIO[PlannerAgentService](_.confirmPlan(conversationId))

  val live
    : ZLayer[
      ChatRepository & IssueRepository & ConfigRepository & ActivityHub & AgentConfigResolver &
        HttpClient & GeminiCliExecutor & WorkspaceRepository & AIProviderConfig,
      Nothing,
      PlannerAgentService,
    ] =
    ZLayer.fromZIO {
      for
        chatRepository   <- ZIO.service[ChatRepository]
        issueRepository  <- ZIO.service[IssueRepository]
        configRepository <- ZIO.service[ConfigRepository]
        activityHub      <- ZIO.service[ActivityHub]
        configResolver   <- ZIO.service[AgentConfigResolver]
        httpClient       <- ZIO.service[HttpClient]
        cliExecutor      <- ZIO.service[GeminiCliExecutor]
        workspaceRepo    <- ZIO.service[WorkspaceRepository]
        startupAiConfig  <- ZIO.service[AIProviderConfig]
        previewState     <- Ref.Synchronized.make(Map.empty[Long, PlannerPreviewState])
      yield PlannerAgentServiceLive(
        chatRepository = chatRepository,
        issueRepository = issueRepository,
        configRepository = configRepository,
        activityHub = activityHub,
        configResolver = configResolver,
        httpClient = httpClient,
        cliExecutor = cliExecutor,
        workspaceRepository = workspaceRepo,
        startupAiConfig = startupAiConfig,
        previewState = previewState,
      )
    }

final private case class PlannerStructuredResponse(
  summary: String,
  issues: List[PlannerIssueDraft],
)

final private case class PlannerWorkspaceContext(
  workspaceId: String,
  name: String,
  localPath: Option[String],
  description: Option[String],
)

final case class PlannerAgentServiceLive(
  chatRepository: ChatRepository,
  issueRepository: IssueRepository,
  configRepository: ConfigRepository,
  activityHub: ActivityHub,
  configResolver: AgentConfigResolver,
  httpClient: HttpClient,
  cliExecutor: GeminiCliExecutor,
  workspaceRepository: WorkspaceRepository,
  startupAiConfig: AIProviderConfig,
  previewState: Ref.Synchronized[Map[Long, PlannerPreviewState]],
) extends PlannerAgentService:

  private val plannerAgentName = "task-planner"

  override def startSession(
    initialRequest: String,
    workspaceId: Option[String],
    createdBy: Option[String] = None,
  ): IO[PlannerAgentError, PlannerSessionStart] =
    for
      now            <- Clock.instant
      conversationId <- chatRepository
                          .createConversation(
                            ChatConversation(
                              title = plannerTitle(initialRequest),
                              description = Some(plannerDescription(workspaceId)),
                              createdAt = now,
                              updatedAt = now,
                              createdBy = createdBy,
                            )
                          )
                          .mapError(mapPersistence("create_planner_conversation"))
      _              <- appendChatMessage(
                          conversationId = conversationId,
                          sender = "user",
                          senderType = SenderType.User,
                          content = initialRequest,
                          messageType = MessageType.Text,
                          createdAt = now,
                        )
      _              <- markPreviewGenerating(conversationId, workspaceId, Some(initialRequest))
      _              <- generatePreviewInBackground(conversationId).forkDaemon
    yield PlannerSessionStart(conversationId)

  override def regeneratePreview(conversationId: Long): IO[PlannerAgentError, PlannerPreviewState] =
    for
      conversation <- getConversation(conversationId)
      state        <- markPreviewGenerating(conversationId, workspaceFromConversation(conversation))
      _            <- generatePreviewInBackground(conversationId).forkDaemon
    yield state

  override def appendUserMessage(conversationId: Long, content: String): IO[PlannerAgentError, PlannerPreviewState] =
    for
      now <- Clock.instant
      _   <- getConversation(conversationId)
      _   <- appendChatMessage(
               conversationId = conversationId,
               sender = "user",
               senderType = SenderType.User,
               content = content,
               messageType = MessageType.Text,
               createdAt = now,
             )
      out <- regeneratePreview(conversationId)
    yield out

  override def getPreview(conversationId: Long): IO[PlannerAgentError, PlannerPreviewState] =
    previewState.get.flatMap { current =>
      current.get(conversationId) match
        case Some(value) => ZIO.succeed(value)
        case None        => ZIO.fail(PlannerAgentError.PreviewNotFound(conversationId))
    }

  override def updatePreview(
    conversationId: Long,
    preview: PlannerPlanPreview,
  ): IO[PlannerAgentError, PlannerPreviewState] =
    for
      existing   <- getPreview(conversationId)
      normalized <- ZIO.fromEither(normalizePreview(preview)).mapError(PlannerAgentError.IssueDraftInvalid.apply)
      updated    <- previewState.modifyZIO { current =>
                      val next = existing.copy(preview = normalized)
                      ZIO.succeed(next -> current.updated(conversationId, next))
                    }
    yield updated

  override def addBlankIssue(conversationId: Long): IO[PlannerAgentError, PlannerPreviewState] =
    for
      existing <- getPreview(conversationId)
      next      = existing.copy(
                    preview = existing.preview.copy(
                      issues = existing.preview.issues :+ blankIssue(existing.preview.issues.length + 1)
                    ),
                    confirmedIssueIds = None,
                  )
      updated  <- previewState.modify(current => next -> current.updated(conversationId, next))
    yield updated

  override def removeIssue(conversationId: Long, draftId: String): IO[PlannerAgentError, PlannerPreviewState] =
    for
      existing   <- getPreview(conversationId)
      trimmed     = draftId.trim
      nextPreview = existing.preview.copy(
                      issues = existing.preview.issues
                        .filterNot(_.draftId == trimmed)
                        .map(issue => issue.copy(dependencyDraftIds = issue.dependencyDraftIds.filterNot(_ == trimmed)))
                    )
      next        = existing.copy(preview = nextPreview, confirmedIssueIds = None)
      updated    <- previewState.modify(current => next -> current.updated(conversationId, next))
    yield updated

  override def confirmPlan(conversationId: Long): IO[PlannerAgentError, PlannerConfirmation] =
    for
      existing <- getPreview(conversationId)
      result   <- existing.confirmedIssueIds match
                    case Some(issueIds) =>
                      ZIO.succeed(PlannerConfirmation(conversationId, issueIds))
                    case None           =>
                      createIssuesFromPreview(existing)
    yield result

  private def getConversation(conversationId: Long): IO[PlannerAgentError, ChatConversation] =
    chatRepository
      .getConversation(conversationId)
      .mapError(mapPersistence("planner_get_conversation"))
      .flatMap {
        case Some(value) => ZIO.succeed(value)
        case None        => ZIO.fail(PlannerAgentError.ConversationNotFound(conversationId))
      }

  private def appendChatMessage(
    conversationId: Long,
    sender: String,
    senderType: SenderType,
    content: String,
    messageType: MessageType,
    createdAt: Instant,
  ): IO[PlannerAgentError, Unit] =
    chatRepository
      .addMessage(
        ConversationEntry(
          conversationId = conversationId.toString,
          sender = sender,
          senderType = senderType,
          content = content,
          messageType = messageType,
          createdAt = createdAt,
          updatedAt = createdAt,
        )
      )
      .unit
      .mapError(mapPersistence("planner_add_message"))

  private def buildTranscript(
    conversationId: Long,
    messages: List[ConversationEntry],
  ): IO[PlannerAgentError, String] =
    val relevant = messages.filter(_.senderType == SenderType.User).map(_.content.trim).filter(_.nonEmpty)
    if relevant.isEmpty then ZIO.fail(PlannerAgentError.EmptyConversation(conversationId))
    else
      ZIO.succeed(
        messages
          .map(entry => s"${entry.senderType.toString.toUpperCase}: ${entry.content.trim}")
          .mkString("\n\n")
      )

  private def executePlannerPrompt(
    transcript: String,
    workspaceContext: Option[PlannerWorkspaceContext],
  ): IO[PlannerAgentError, PlannerPlanPreview] =
    executeStructuredWithPreferredAgent[Json](
      plannerPrompt(transcript, workspaceContext),
      plannerResponseSchema,
      workspaceContext,
    )
      .flatMap(responseJson =>
        ZIO
          .fromEither(normalizePlannerResponse(responseJson))
          .mapError(details =>
            PlannerAgentError.LlmFailure(
              s"Parse error: $details\n\nRaw response:\n```json\n${responseJson.toJsonPretty}\n```"
            )
          )
      )
      .map(response => PlannerPlanPreview(summary = response.summary, issues = response.issues))

  private def savePreviewState(
    conversationId: Long,
    workspaceId: Option[String],
    preview: PlannerPlanPreview,
    preserveConfirmation: Boolean,
    isGenerating: Boolean = false,
    lastError: Option[String] = None,
  ): UIO[PlannerPreviewState] =
    previewState.modify { current =>
      val existing     = current.get(conversationId)
      val confirmedIds = if preserveConfirmation then existing.flatMap(_.confirmedIssueIds) else None
      val next         = PlannerPreviewState(
        conversationId = conversationId,
        workspaceId = workspaceId.orElse(existing.flatMap(_.workspaceId)),
        preview = preview,
        confirmedIssueIds = confirmedIds,
        isGenerating = isGenerating,
        lastError = lastError,
      )
      next -> current.updated(conversationId, next)
    }

  private def markPreviewGenerating(
    conversationId: Long,
    workspaceId: Option[String],
    initialRequest: Option[String] = None,
  ): UIO[PlannerPreviewState] =
    previewState.modify { current =>
      val existing     = current.get(conversationId)
      val confirmedIds = existing.flatMap(_.confirmedIssueIds)
      val preview      = existing.map(_.preview).getOrElse(
        PlannerPlanPreview(
          summary = initialRequest
            .map(_.trim)
            .filter(_.nonEmpty)
            .map(request => s"Planner is generating a preview for: $request")
            .getOrElse("Planner is generating a preview."),
          issues = Nil,
        )
      )
      val next         = PlannerPreviewState(
        conversationId = conversationId,
        workspaceId = workspaceId.orElse(existing.flatMap(_.workspaceId)),
        preview = preview,
        confirmedIssueIds = confirmedIds,
        isGenerating = true,
        lastError = None,
      )
      next -> current.updated(conversationId, next)
    }

  private def createIssuesFromPreview(
    state: PlannerPreviewState
  ): IO[PlannerAgentError, PlannerConfirmation] =
    for
      now             <- Clock.instant
      initialStatus   <- loadInitialStatus
      _               <- ZIO.fail(PlannerAgentError.IssueDraftInvalid("Planner preview is still generating"))
                           .when(state.isGenerating)
      orderedDrafts    = state.preview.issues.filter(_.included)
      _               <- ZIO.fail(PlannerAgentError.IssueDraftInvalid("Planner preview does not contain any issue drafts"))
                           .when(orderedDrafts.isEmpty)
      issueIdsByDraft <- ZIO.foreach(orderedDrafts) { draft =>
                           val issueId   = IssueId.generate
                           val created   = IssueEvent.Created(
                             issueId = issueId,
                             title = draft.title.trim,
                             description = draft.description.trim,
                             issueType = normalizedIssueType(draft.issueType),
                             priority = normalizedPriority(draft.priority),
                             occurredAt = now,
                             requiredCapabilities = sanitizeList(draft.requiredCapabilities),
                           )
                           val skillTags = sanitizeList(draft.kaizenSkills).map(skill => s"skill:$skill")
                           for
                             _ <- issueRepository.append(created).mapError(mapIssuePersistence("planner_create_issue"))
                             _ <- ZIO.when(skillTags.nonEmpty) {
                                    issueRepository
                                      .append(IssueEvent.TagsUpdated(issueId, skillTags, now))
                                      .mapError(mapIssuePersistence("planner_tags"))
                                  }
                             _ <- ZIO.when(draft.promptTemplate.trim.nonEmpty) {
                                    issueRepository
                                      .append(IssueEvent.PromptTemplateUpdated(issueId, draft.promptTemplate.trim, now))
                                      .mapError(mapIssuePersistence("planner_prompt_template"))
                                  }
                             _ <- ZIO.when(draft.acceptanceCriteria.trim.nonEmpty) {
                                    issueRepository
                                      .append(IssueEvent.AcceptanceCriteriaUpdated(
                                        issueId,
                                        draft.acceptanceCriteria.trim,
                                        now,
                                      ))
                                      .mapError(mapIssuePersistence("planner_acceptance"))
                                  }
                             _ <- draft.estimate.fold[IO[PlannerAgentError, Unit]](ZIO.unit) { estimate =>
                                    issueRepository
                                      .append(IssueEvent.EstimateUpdated(issueId, estimate, now))
                                      .mapError(mapIssuePersistence("planner_estimate"))
                                  }
                             _ <- ZIO.when(draft.kaizenSkills.nonEmpty) {
                                    issueRepository
                                      .append(
                                        IssueEvent.KaizenSkillUpdated(
                                          issueId,
                                          sanitizeList(draft.kaizenSkills).mkString(", "),
                                          now,
                                        )
                                      )
                                      .mapError(mapIssuePersistence("planner_kaizen"))
                                  }
                             _ <- ZIO.when(draft.proofOfWorkRequirements.nonEmpty) {
                                    issueRepository
                                      .append(
                                        IssueEvent.ProofOfWorkRequirementsUpdated(
                                          issueId,
                                          sanitizeList(draft.proofOfWorkRequirements),
                                          now,
                                        )
                                      )
                                      .mapError(mapIssuePersistence("planner_proof_of_work"))
                                  }
                             _ <- ZIO.when(initialStatus == PlannerInitialStatus.Todo) {
                                    issueRepository
                                      .append(IssueEvent.MovedToTodo(issueId, movedAt = now, occurredAt = now))
                                      .mapError(mapIssuePersistence("planner_move_todo"))
                                  }
                             _ <- state.workspaceId.fold[IO[PlannerAgentError, Unit]](ZIO.unit) { workspaceId =>
                                    issueRepository
                                      .append(IssueEvent.WorkspaceLinked(issueId, workspaceId, now))
                                      .mapError(mapIssuePersistence("planner_workspace_link"))
                                  }
                           yield draft.draftId -> issueId
                         }.map(_.toMap)
      _               <- ZIO.foreachDiscard(orderedDrafts) { draft =>
                           val issueId = issueIdsByDraft(draft.draftId)
                           ZIO.foreachDiscard(sanitizeList(draft.dependencyDraftIds)) { dependencyDraftId =>
                             issueIdsByDraft.get(dependencyDraftId) match
                               case Some(blockerId) =>
                                 issueRepository
                                   .append(IssueEvent.DependencyLinked(issueId, blockerId, now))
                                   .mapError(mapIssuePersistence("planner_dependency_link"))
                               case None            =>
                                 ZIO.unit
                           }
                         }
      confirmation     =
        PlannerConfirmation(state.conversationId, orderedDrafts.flatMap(draft => issueIdsByDraft.get(draft.draftId)))
      _               <- publishPlannerBatchActivity(state, confirmation.issueIds, initialStatus, now)
      _               <- previewState.update(_.updated(
                           state.conversationId,
                           state.copy(confirmedIssueIds = Some(confirmation.issueIds)),
                         ))
    yield confirmation

  private def normalizePreview(preview: PlannerPlanPreview): Either[String, PlannerPlanPreview] =
    val normalizedIssuesEither =
      preview.issues.zipWithIndex.foldLeft[Either[String, List[PlannerIssueDraft]]](Right(Nil)) {
        case (acc, (draft, index)) =>
          acc.flatMap { issues =>
            val draftId         = Option(draft.draftId).map(_.trim).filter(_.nonEmpty).getOrElse(s"issue-${index + 1}")
            val rawEstimate     = draft.estimate.map(_.trim).filter(_.nonEmpty)
            val normalizedValue = normalizedEstimate(rawEstimate)
            if rawEstimate.isDefined && normalizedValue.isEmpty then
              Left(s"Planner issue estimate must be one of: ${DomainIssue.ValidEstimates.toList.sorted.mkString(", ")}")
            else
              Right(
                issues :+ draft.copy(
                  draftId = draftId,
                  title = draft.title.trim,
                  description = draft.description.trim,
                  issueType = normalizedIssueType(draft.issueType),
                  priority = normalizedPriority(draft.priority),
                  estimate = normalizedValue,
                  requiredCapabilities = sanitizeList(draft.requiredCapabilities),
                  dependencyDraftIds = sanitizeList(draft.dependencyDraftIds).filterNot(_ == draftId),
                  acceptanceCriteria = draft.acceptanceCriteria.trim,
                  promptTemplate = draft.promptTemplate.trim,
                  kaizenSkills = sanitizeList(draft.kaizenSkills),
                  proofOfWorkRequirements = sanitizeList(draft.proofOfWorkRequirements),
                  included = draft.included,
                )
              )
          }
      }
    normalizedIssuesEither.flatMap { normalizedIssues =>
      val duplicateIds =
        normalizedIssues.groupBy(_.draftId).collect { case (id, values) if values.size > 1 => id }.toList
      if duplicateIds.nonEmpty then Left(s"Planner issue draft ids must be unique: ${duplicateIds.mkString(", ")}")
      else if normalizedIssues.exists(_.title.isEmpty) then Left("Planner issue titles cannot be empty")
      else if normalizedIssues.exists(_.description.isEmpty) then Left("Planner issue descriptions cannot be empty")
      else
        Right(
          PlannerPlanPreview(
            summary = preview.summary.trim,
            issues = normalizedIssues,
          )
        )
    }

  private def blankIssue(index: Int): PlannerIssueDraft =
    PlannerIssueDraft(
      draftId = s"issue-$index",
      title = s"Planned Issue $index",
      description = "",
      estimate = None,
      acceptanceCriteria = "",
      promptTemplate = "",
      proofOfWorkRequirements = Nil,
      included = true,
    )

  private def appendPlannerWarningMessage(
    conversationId: Long,
    errorMessage: String,
    createdAt: Instant,
  ): UIO[Unit] =
    appendChatMessage(
      conversationId = conversationId,
      sender = "planner",
      senderType = SenderType.Assistant,
      content = s"Planner preview generation failed. You can retry or continue refining the request.\n\n$errorMessage",
      messageType = MessageType.Text,
      createdAt = createdAt,
    ).unit.catchAll(_ => ZIO.unit)

  private def generatePreviewInBackground(conversationId: Long): UIO[Unit] =
    computePreview(conversationId)
      .unit
      .catchAll(handlePreviewFailure(conversationId, _))

  private def computePreview(conversationId: Long): IO[PlannerAgentError, PlannerPreviewState] =
    for
      conversation <- getConversation(conversationId)
      messages     <- chatRepository.getMessages(conversationId).mapError(mapPersistence("planner_messages"))
      transcript   <- buildTranscript(conversationId, messages)
      workspaceCtx <- loadWorkspaceContext(workspaceFromConversation(conversation))
      preview      <- executePlannerPrompt(transcript, workspaceCtx)
      normalized   <- ZIO.fromEither(normalizePreview(preview)).mapError(PlannerAgentError.IssueDraftInvalid.apply)
      now          <- Clock.instant
      _            <- appendChatMessage(
                        conversationId = conversationId,
                        sender = plannerAgentName,
                        senderType = SenderType.Assistant,
                        content = previewMarkdown(normalized),
                        messageType = MessageType.Text,
                        createdAt = now,
                      )
      state        <- savePreviewState(
                        conversationId = conversationId,
                        workspaceId = workspaceFromConversation(conversation),
                        preview = normalized,
                        preserveConfirmation = false,
                        isGenerating = false,
                        lastError = None,
                      )
    yield state

  private def handlePreviewFailure(
    conversationId: Long,
    error: PlannerAgentError,
  ): UIO[Unit] =
    for
      existing <- previewState.get.map(_.get(conversationId))
      now      <- Clock.instant
      _        <- appendPlannerWarningMessage(conversationId, error.message, now)
      _        <- savePreviewState(
                    conversationId = conversationId,
                    workspaceId = existing.flatMap(_.workspaceId),
                    preview = existing.map(_.preview).getOrElse(
                      PlannerPlanPreview("Planner preview generation failed.", Nil)
                    ),
                    preserveConfirmation = true,
                    isGenerating = false,
                    lastError = Some(error.message),
                  )
    yield ()

  private def plannerTitle(initialRequest: String): String =
    val raw = initialRequest.trim.replaceAll("\\s+", " ")
    if raw.length <= 48 then s"Planner: $raw"
    else s"Planner: ${raw.take(45)}..."

  private def plannerDescription(workspaceId: Option[String]): String =
    workspaceId.fold("planner-session")(id => s"planner-session|workspace:$id")

  private def workspaceFromConversation(conversation: ChatConversation): Option[String] =
    conversation.description
      .flatMap(_.split("\\|").toList.collectFirst {
        case value if value.startsWith("workspace:") => value.stripPrefix("workspace:").trim
      })
      .filter(_.nonEmpty)

  private def previewMarkdown(preview: PlannerPlanPreview): String =
    val body = preview.issues.map { draft =>
      val dependencies = if draft.dependencyDraftIds.isEmpty then "none" else draft.dependencyDraftIds.mkString(", ")
      val capabilities =
        if draft.requiredCapabilities.isEmpty then "none" else draft.requiredCapabilities.mkString(", ")
      val skills       = if draft.kaizenSkills.isEmpty then "none" else draft.kaizenSkills.mkString(", ")
      val estimate     = draft.estimate.getOrElse("none")
      val proof        =
        if draft.proofOfWorkRequirements.isEmpty then "none" else draft.proofOfWorkRequirements.mkString(", ")
      val selection    = if draft.included then "included" else "excluded"
      s"""### ${draft.title}
         |Priority: ${normalizedPriority(draft.priority)}
         |Estimate: $estimate
         |Capabilities: $capabilities
         |Dependencies: $dependencies
         |Kaizen skills: $skills
         |Proof of work: $proof
         |Selection: $selection
         |
         |${draft.description}
         |""".stripMargin
    }.mkString("\n")
    s"${preview.summary.trim}\n\n$body".trim

  private def plannerPrompt(transcript: String, workspaceContext: Option[PlannerWorkspaceContext]): String =
    val workspaceBlock = workspaceContext match
      case Some(context) =>
        val pathLine        = context.localPath.fold("unknown")(identity)
        val descriptionLine = context.description.map(_.trim).filter(_.nonEmpty).getOrElse("none")
        s"""Selected workspace context:
           |- Workspace id: ${context.workspaceId}
           |- Workspace name: ${context.name}
           |- Workspace local path: $pathLine
           |- Workspace description: $descriptionLine
           |
           |Use this selected workspace as the repository context for any inspection or planning. Do not assume the current llm4zio source tree is the target unless the workspace metadata explicitly points there.
           |""".stripMargin
      case None          =>
        "No workspace was selected. Plan from the conversation only.\n"

    s"""You are the task-planner agent.
       |
       |Your job is to turn the user's initiative into a structured execution plan.
       |
       |$workspaceBlock
       |
       |Rules:
       |- Break work into atomic, independently executable issues.
       |- Prefer parallelizable tasks when dependencies allow it.
       |- Write clear descriptions and acceptance criteria.
       |- Suggest practical required capabilities for each issue.
       |- Generate prompt templates that an implementation agent can execute directly.
       |- Assign each issue an estimate from: XS, S, M, L, XL.
       |- Include kaizen skill references when useful.
       |- Include concrete proof-of-work requirements when verification matters.
       |- Use stable draft ids like issue-1, issue-2 so dependencies can reference them.
       |- Keep priorities to one of: low, medium, high, critical.
       |- Return only valid JSON matching the schema.
       |
       |Conversation transcript:
       |$transcript
       |""".stripMargin

  private val plannerResponseSchema: JsonSchema =
    Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "summary" -> Json.Obj("type" -> Json.Str("string")),
        "issues"  -> Json.Obj(
          "type"  -> Json.Str("array"),
          "items" -> Json.Obj(
            "type"       -> Json.Str("object"),
            "properties" -> Json.Obj(
              "draftId"                 -> Json.Obj("type" -> Json.Str("string")),
              "title"                   -> Json.Obj("type" -> Json.Str("string")),
              "description"             -> Json.Obj("type" -> Json.Str("string")),
              "issueType"               -> Json.Obj("type" -> Json.Str("string")),
              "priority"                -> Json.Obj("type" -> Json.Str("string")),
              "estimate"                -> Json.Obj(
                "type" -> Json.Str("string"),
                "enum" -> Json.Arr(Chunk.fromIterable(DomainIssue.ValidEstimates.toList.sorted.map(Json.Str.apply))),
              ),
              "requiredCapabilities"    -> Json.Obj(
                "type"  -> Json.Str("array"),
                "items" -> Json.Obj("type" -> Json.Str("string")),
              ),
              "dependencyDraftIds"      -> Json.Obj(
                "type"  -> Json.Str("array"),
                "items" -> Json.Obj("type" -> Json.Str("string")),
              ),
              "acceptanceCriteria"      -> Json.Obj("type" -> Json.Str("string")),
              "promptTemplate"          -> Json.Obj("type" -> Json.Str("string")),
              "kaizenSkills"            -> Json.Obj(
                "type"  -> Json.Str("array"),
                "items" -> Json.Obj("type" -> Json.Str("string")),
              ),
              "proofOfWorkRequirements" -> Json.Obj(
                "type"  -> Json.Str("array"),
                "items" -> Json.Obj("type" -> Json.Str("string")),
              ),
              "included"                -> Json.Obj("type" -> Json.Str("boolean")),
            ),
            "required"   -> Json.Arr(
              Chunk(
                Json.Str("draftId"),
                Json.Str("title"),
                Json.Str("description"),
                Json.Str("issueType"),
                Json.Str("priority"),
                Json.Str("estimate"),
                Json.Str("requiredCapabilities"),
                Json.Str("dependencyDraftIds"),
                Json.Str("acceptanceCriteria"),
                Json.Str("promptTemplate"),
                Json.Str("kaizenSkills"),
                Json.Str("proofOfWorkRequirements"),
                Json.Str("included"),
              )
            ),
          ),
        ),
      ),
      "required"   -> Json.Arr(Chunk(Json.Str("summary"), Json.Str("issues"))),
    )

  private def normalizePlannerResponse(json: Json): Either[String, PlannerStructuredResponse] =
    json match
      case Json.Obj(fields) =>
        val fieldMap = fields.toMap
        extractIssues(fieldMap).map { issues =>
          PlannerStructuredResponse(
            summary = extractString(fieldMap, "summary").getOrElse(defaultPlannerSummary(issues)),
            issues = issues,
          )
        }
      case _                =>
        Left("planner response must be a JSON object")

  private def extractIssues(fields: Map[String, Json]): Either[String, List[PlannerIssueDraft]] =
    fields.get("issues") match
      case Some(Json.Arr(items)) =>
        items.toList.zipWithIndex.foldLeft[Either[String, List[PlannerIssueDraft]]](Right(Nil)) {
          case (acc, (item, idx)) =>
            acc.flatMap(drafts => normalizeIssueDraft(item, idx).map(draft => drafts :+ draft))
        }
      case Some(_)               => Left("planner response field 'issues' must be an array")
      case None                  => Left("planner response is missing required field 'issues'")

  private def normalizeIssueDraft(json: Json, index: Int): Either[String, PlannerIssueDraft] =
    json match
      case Json.Obj(fields) =>
        val fieldMap          = fields.toMap
        val descriptionEither = requiredString(fieldMap, "description", "issue description", index)
        descriptionEither.map { description =>
          val draftId = firstString(fieldMap, "draftId", "draft_id", "issue_id").getOrElse(s"issue-${index + 1}")
          val title   =
            firstString(fieldMap, "title").filter(_.nonEmpty).getOrElse(deriveTitle(description, index))
          PlannerIssueDraft(
            draftId = draftId,
            title = title,
            description = description,
            issueType = firstString(fieldMap, "issueType", "issue_type").getOrElse("task"),
            priority = firstString(fieldMap, "priority").getOrElse("medium"),
            estimate = firstString(fieldMap, "estimate"),
            requiredCapabilities = firstStringList(fieldMap, "requiredCapabilities", "required_capabilities"),
            dependencyDraftIds = firstStringList(fieldMap, "dependencyDraftIds", "dependency_draft_ids", "depends_on"),
            acceptanceCriteria =
              firstString(fieldMap, "acceptanceCriteria", "acceptance_criteria").getOrElse(""),
            promptTemplate = firstString(fieldMap, "promptTemplate", "prompt_template").getOrElse(""),
            kaizenSkills = firstStringList(fieldMap, "kaizenSkills", "kaizen_skills", "kaizen_references"),
            proofOfWorkRequirements =
              firstStringList(fieldMap, "proofOfWorkRequirements", "proof_of_work_requirements", "proof_of_work"),
            included = firstBoolean(fieldMap, "included").getOrElse(true),
          )
        }
      case _                =>
        Left(s"planner issue at index ${index + 1} must be a JSON object")

  private def extractString(fields: Map[String, Json], key: String): Option[String] =
    fields.get(key).flatMap {
      case Json.Str(value) => Some(value.trim).filter(_.nonEmpty)
      case _               => None
    }

  private def firstString(fields: Map[String, Json], keys: String*): Option[String] =
    keys.iterator.flatMap(key => extractString(fields, key)).nextOption()

  private def firstBoolean(fields: Map[String, Json], keys: String*): Option[Boolean] =
    keys.iterator.flatMap(key =>
      fields.get(key).flatMap {
        case Json.Bool(value) => Some(value)
        case _                => None
      }
    ).nextOption()

  private def firstStringList(fields: Map[String, Json], keys: String*): List[String] =
    keys.iterator.map(key =>
      fields.get(key).map {
        case Json.Arr(items)                        =>
          items.toList.collect { case Json.Str(value) if value.trim.nonEmpty => value.trim }
        case Json.Str(value) if value.trim.nonEmpty =>
          List(value.trim)
        case _                                      =>
          Nil
      }.getOrElse(Nil)
    ).find(_.nonEmpty).getOrElse(Nil)

  private def requiredString(
    fields: Map[String, Json],
    key: String,
    label: String,
    index: Int,
  ): Either[String, String] =
    extractString(fields, key).toRight(s"planner issue ${index + 1} is missing required $label")

  private def deriveTitle(description: String, index: Int): String =
    val normalized = description.trim.linesIterator.nextOption().getOrElse(description.trim)
    val trimmed    = normalized.stripSuffix(".").trim
    if trimmed.isEmpty then s"Issue ${index + 1}" else trimmed.take(80)

  private def defaultPlannerSummary(issues: List[PlannerIssueDraft]): String =
    issues.length match
      case 0 => "Generated planner preview."
      case 1 => "Generated planner preview with 1 issue."
      case n => s"Generated planner preview with $n issues."

  private def executeStructuredWithPreferredAgent[A: JsonCodec](
    prompt: String,
    schema: JsonSchema,
    workspaceContext: Option[PlannerWorkspaceContext],
  ): IO[PlannerAgentError, A] =
    configResolver
      .resolveConfig(plannerAgentName)
      .either
      .flatMap {
        case Right(config) =>
          executeStructuredWithConfig[A](config, prompt, schema, workspaceContext)
            .catchAll(_ => executeStructuredWithGlobalConfig(prompt, schema, workspaceContext))
        case Left(_)       =>
          executeStructuredWithGlobalConfig(prompt, schema, workspaceContext)
      }

  private def executeStructuredWithConfig[A: JsonCodec](
    config: AIProviderConfig,
    prompt: String,
    schema: JsonSchema,
    workspaceContext: Option[PlannerWorkspaceContext],
  ): IO[PlannerAgentError, A] =
    fallbackConfigs(config)
      .foldLeft[IO[PlannerAgentError, A]](ZIO.fail(PlannerAgentError.LlmFailure("No planner LLM provider configured"))) {
        (acc, cfg) =>
          acc.orElse(providerFor(cfg, workspaceContext).flatMap(service =>
            service.executeStructured[A](prompt, schema).mapError(mapLlmError)
          ))
      }

  private def executeStructuredWithGlobalConfig[A: JsonCodec](
    prompt: String,
    schema: JsonSchema,
    workspaceContext: Option[PlannerWorkspaceContext],
  ): IO[PlannerAgentError, A] =
    resolveGlobalAiConfig.flatMap(config =>
      executeStructuredWithConfig(config, prompt, schema, workspaceContext)
    )

  private def resolveGlobalAiConfig: IO[PlannerAgentError, AIProviderConfig] =
    configRepository
      .getAllSettings
      .mapError(mapPersistence("planner_global_ai_settings"))
      .map(rows => rows.map(row => row.key -> row.value).toMap)
      .map(settings =>
        SettingsApplier.toAIProviderConfig(settings).map(AIProviderConfig.withDefaults).getOrElse(startupAiConfig)
      )

  private def providerFor(
    config: LlmConfig,
    workspaceContext: Option[PlannerWorkspaceContext],
  ): IO[PlannerAgentError, LlmService] =
    plannerExecutionContext(workspaceContext).map { executionContext =>
      config.provider match
        case LlmProvider.GeminiCli => llm4zio.providers.GeminiCliProvider.make(config, cliExecutor, executionContext)
        case LlmProvider.GeminiApi => llm4zio.providers.GeminiApiProvider.make(config, httpClient)
        case LlmProvider.OpenAI    => llm4zio.providers.OpenAIProvider.make(config, httpClient)
        case LlmProvider.Anthropic => llm4zio.providers.AnthropicProvider.make(config, httpClient)
        case LlmProvider.LmStudio  => llm4zio.providers.LmStudioProvider.make(config, httpClient)
        case LlmProvider.Ollama    => llm4zio.providers.OllamaProvider.make(config, httpClient)
        case LlmProvider.OpenCode  => llm4zio.providers.OpenCodeProvider.make(config, httpClient)
    }

  private def loadWorkspaceContext(
    workspaceId: Option[String]
  ): IO[PlannerAgentError, Option[PlannerWorkspaceContext]] =
    workspaceId match
      case None              => ZIO.none
      case Some(workspaceId) =>
        workspaceRepository
          .get(workspaceId)
          .mapError(err => PlannerAgentError.PersistenceFailure("planner_workspace_lookup", err.toString))
          .map(
            _.map(workspace =>
              PlannerWorkspaceContext(
                workspaceId = workspace.id,
                name = workspace.name,
                localPath = Option(workspace.localPath).map(_.trim).filter(_.nonEmpty),
                description = workspace.description,
              )
            )
          )

  private def plannerExecutionContext(
    workspaceContext: Option[PlannerWorkspaceContext]
  ): IO[PlannerAgentError, GeminiCliExecutionContext] =
    ZIO.succeed(
      workspaceContext.flatMap(_.localPath) match
        case Some(localPath) =>
          GeminiCliExecutionContext(
            cwd = Some(localPath),
            includeDirectories = List(localPath),
          )
        case None            =>
          GeminiCliExecutionContext.default
    )

  private def fallbackConfigs(primary: AIProviderConfig): List[LlmConfig] =
    val primaryLlm = ApplicationDI.aiConfigToLlmConfig(primary)
    val fallback   = primary.fallbackChain.models.map { ref =>
      ApplicationDI.aiConfigToLlmConfig(
        AIProviderConfig.withDefaults(
          primary.copy(
            provider = ref.provider.getOrElse(primary.provider),
            model = ref.modelId,
          )
        )
      )
    }
    (primaryLlm :: fallback).distinct

  private def sanitizeList(values: List[String]): List[String] =
    Option(values).getOrElse(Nil).map(_.trim).filter(_.nonEmpty).distinct

  private def normalizedIssueType(value: String): String =
    Option(value).map(_.trim).filter(_.nonEmpty).getOrElse("task")

  private def normalizedPriority(value: String): String =
    Option(value).map(_.trim.toLowerCase).collect {
      case "low"      => "low"
      case "medium"   => "medium"
      case "high"     => "high"
      case "critical" => "critical"
    }.getOrElse("medium")

  private def normalizedEstimate(value: Option[String]): Option[String] =
    value.flatMap(DomainIssue.normalizeEstimate)

  private def loadInitialStatus: IO[PlannerAgentError, PlannerInitialStatus] =
    configRepository
      .getSetting("planner.create.initialStatus")
      .mapError(mapPersistence("planner_initial_status"))
      .map(_.flatMap(setting => Option(setting.value).map(_.trim.toLowerCase)).getOrElse("backlog"))
      .map {
        case "todo" => PlannerInitialStatus.Todo
        case _      => PlannerInitialStatus.Backlog
      }

  private def publishPlannerBatchActivity(
    state: PlannerPreviewState,
    issueIds: List[IssueId],
    initialStatus: PlannerInitialStatus,
    now: Instant,
  ): UIO[Unit] =
    activityHub.publish(
      ActivityEvent(
        id = EventId.generate,
        eventType = ActivityEventType.RunStateChanged,
        source = "planner",
        summary = s"Planner session #${state.conversationId} created ${issueIds.size} issue(s)",
        payload = Some(
          Json
            .Obj(
              "conversationId" -> Json.Num(state.conversationId),
              "workspaceId"    -> state.workspaceId.fold[Json](Json.Null)(Json.Str(_)),
              "initialStatus"  -> Json.Str(initialStatus.toString.toLowerCase),
              "issueIds"       -> Json.Arr(Chunk.fromIterable(issueIds.map(id => Json.Str(id.value)))),
            )
            .toJson
        ),
        createdAt = now,
      )
    )

  private def mapPersistence(operation: String)(error: PersistenceError): PlannerAgentError =
    PlannerAgentError.PersistenceFailure(operation, error.toString)

  private def mapIssuePersistence(operation: String)(error: shared.errors.PersistenceError): PlannerAgentError =
    PlannerAgentError.PersistenceFailure(operation, error.toString)

  private def mapLlmError(error: LlmError): PlannerAgentError =
    PlannerAgentError.LlmFailure(
      error match
        case LlmError.ParseError(message, _)     => s"Parse error: $message"
        case LlmError.ProviderError(message, _)  => s"Provider error: $message"
        case LlmError.AuthenticationError(msg)   => s"Authentication error: $msg"
        case LlmError.InvalidRequestError(msg)   => s"Invalid request: $msg"
        case LlmError.RateLimitError(retryAfter) =>
          s"Rate limited: ${retryAfter.map(_.toString).getOrElse("retry later")}"
        case LlmError.TimeoutError(duration)     => s"Timeout after $duration"
        case LlmError.ToolError(toolName, msg)   => s"Tool error [$toolName]: $msg"
        case LlmError.ConfigError(msg)           => s"Configuration error: $msg"
    )
