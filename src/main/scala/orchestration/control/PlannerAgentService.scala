package orchestration.control

import java.time.Instant

import zio.*
import zio.json.*
import zio.json.ast.Json

import _root_.config.entity.AIProviderConfig
import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import app.ApplicationDI
import conversation.entity.api.{ ChatConversation, ConversationEntry, MessageType, SenderType }
import db.{ ChatRepository, ConfigRepository, PersistenceError }
import issues.entity.{ IssueEvent, IssueRepository }
import llm4zio.core.{ LlmConfig, LlmError, LlmProvider, LlmService }
import llm4zio.providers.{ GeminiCliExecutor, HttpClient }
import llm4zio.tools.JsonSchema
import shared.ids.Ids.{ EventId, IssueId }

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
  ): IO[PlannerAgentError, Long]
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
  ): ZIO[PlannerAgentService, PlannerAgentError, Long] =
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
      ChatRepository & IssueRepository & ConfigRepository & ActivityHub & AgentConfigResolver & LlmService &
        HttpClient & GeminiCliExecutor,
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
        llmService       <- ZIO.service[LlmService]
        httpClient       <- ZIO.service[HttpClient]
        cliExecutor      <- ZIO.service[GeminiCliExecutor]
        previewState     <- Ref.Synchronized.make(Map.empty[Long, PlannerPreviewState])
      yield PlannerAgentServiceLive(
        chatRepository = chatRepository,
        issueRepository = issueRepository,
        configRepository = configRepository,
        activityHub = activityHub,
        configResolver = configResolver,
        llmService = llmService,
        httpClient = httpClient,
        cliExecutor = cliExecutor,
        previewState = previewState,
      )
    }

final private case class PlannerStructuredResponse(
  summary: String,
  issues: List[PlannerIssueDraft],
) derives JsonCodec

final case class PlannerAgentServiceLive(
  chatRepository: ChatRepository,
  issueRepository: IssueRepository,
  configRepository: ConfigRepository,
  activityHub: ActivityHub,
  configResolver: AgentConfigResolver,
  llmService: LlmService,
  httpClient: HttpClient,
  cliExecutor: GeminiCliExecutor,
  previewState: Ref.Synchronized[Map[Long, PlannerPreviewState]],
) extends PlannerAgentService:

  private val plannerAgentName = "task-planner"

  override def startSession(
    initialRequest: String,
    workspaceId: Option[String],
    createdBy: Option[String] = None,
  ): IO[PlannerAgentError, Long] =
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
      _              <- regeneratePreview(conversationId)
    yield conversationId

  override def regeneratePreview(conversationId: Long): IO[PlannerAgentError, PlannerPreviewState] =
    for
      conversation <- getConversation(conversationId)
      messages     <- chatRepository.getMessages(conversationId).mapError(mapPersistence("planner_messages"))
      transcript   <- buildTranscript(conversationId, messages)
      preview      <- executePlannerPrompt(transcript)
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
                      )
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

  private def executePlannerPrompt(transcript: String): IO[PlannerAgentError, PlannerPlanPreview] =
    executeStructuredWithPreferredAgent[PlannerStructuredResponse](plannerPrompt(transcript), plannerResponseSchema)
      .map(response => PlannerPlanPreview(summary = response.summary, issues = response.issues))

  private def savePreviewState(
    conversationId: Long,
    workspaceId: Option[String],
    preview: PlannerPlanPreview,
    preserveConfirmation: Boolean,
  ): UIO[PlannerPreviewState] =
    previewState.modify { current =>
      val existing     = current.get(conversationId)
      val confirmedIds = if preserveConfirmation then existing.flatMap(_.confirmedIssueIds) else None
      val next         = PlannerPreviewState(
        conversationId = conversationId,
        workspaceId = workspaceId.orElse(existing.flatMap(_.workspaceId)),
        preview = preview,
        confirmedIssueIds = confirmedIds,
      )
      next -> current.updated(conversationId, next)
    }

  private def createIssuesFromPreview(
    state: PlannerPreviewState
  ): IO[PlannerAgentError, PlannerConfirmation] =
    for
      now             <- Clock.instant
      initialStatus   <- loadInitialStatus
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
    val normalizedIssues = preview.issues.zipWithIndex.map { (draft, index) =>
      val draftId = Option(draft.draftId).map(_.trim).filter(_.nonEmpty).getOrElse(s"issue-${index + 1}")
      draft.copy(
        draftId = draftId,
        title = draft.title.trim,
        description = draft.description.trim,
        issueType = normalizedIssueType(draft.issueType),
        priority = normalizedPriority(draft.priority),
        requiredCapabilities = sanitizeList(draft.requiredCapabilities),
        dependencyDraftIds = sanitizeList(draft.dependencyDraftIds).filterNot(_ == draftId),
        acceptanceCriteria = draft.acceptanceCriteria.trim,
        promptTemplate = draft.promptTemplate.trim,
        kaizenSkills = sanitizeList(draft.kaizenSkills),
        proofOfWorkRequirements = sanitizeList(draft.proofOfWorkRequirements),
        included = draft.included,
      )
    }
    val duplicateIds     = normalizedIssues.groupBy(_.draftId).collect { case (id, values) if values.size > 1 => id }.toList
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

  private def blankIssue(index: Int): PlannerIssueDraft =
    PlannerIssueDraft(
      draftId = s"issue-$index",
      title = s"Planned Issue $index",
      description = "",
      acceptanceCriteria = "",
      promptTemplate = "",
      proofOfWorkRequirements = Nil,
      included = true,
    )

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
      val proof        =
        if draft.proofOfWorkRequirements.isEmpty then "none" else draft.proofOfWorkRequirements.mkString(", ")
      val selection    = if draft.included then "included" else "excluded"
      s"""### ${draft.title}
         |Priority: ${normalizedPriority(draft.priority)}
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

  private def plannerPrompt(transcript: String): String =
    s"""You are the task-planner agent for llm4zio.
       |
       |Your job is to turn the user's initiative into a structured execution plan.
       |
       |Rules:
       |- Break work into atomic, independently executable issues.
       |- Prefer parallelizable tasks when dependencies allow it.
       |- Write clear descriptions and acceptance criteria.
       |- Suggest practical required capabilities for each issue.
       |- Generate prompt templates that an implementation agent can execute directly.
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

  private def executeStructuredWithPreferredAgent[A: JsonCodec](
    prompt: String,
    schema: JsonSchema,
  ): IO[PlannerAgentError, A] =
    configResolver
      .resolveConfig(plannerAgentName)
      .either
      .flatMap {
        case Right(config) =>
          executeStructuredWithConfig[A](config, prompt, schema).catchAll(_ =>
            llmService.executeStructured[A](prompt, schema).mapError(mapLlmError)
          )
        case Left(_)       =>
          llmService.executeStructured[A](prompt, schema).mapError(mapLlmError)
      }

  private def executeStructuredWithConfig[A: JsonCodec](
    config: AIProviderConfig,
    prompt: String,
    schema: JsonSchema,
  ): IO[PlannerAgentError, A] =
    fallbackConfigs(config)
      .foldLeft[IO[PlannerAgentError, A]](ZIO.fail(PlannerAgentError.LlmFailure("No planner LLM provider configured"))) {
        (acc, cfg) =>
          acc.orElse(providerFor(cfg).flatMap(service =>
            service.executeStructured[A](prompt, schema).mapError(mapLlmError)
          ))
      }

  private def providerFor(config: LlmConfig): IO[PlannerAgentError, LlmService] =
    ZIO.succeed {
      config.provider match
        case LlmProvider.GeminiCli => llm4zio.providers.GeminiCliProvider.make(config, cliExecutor)
        case LlmProvider.GeminiApi => llm4zio.providers.GeminiApiProvider.make(config, httpClient)
        case LlmProvider.OpenAI    => llm4zio.providers.OpenAIProvider.make(config, httpClient)
        case LlmProvider.Anthropic => llm4zio.providers.AnthropicProvider.make(config, httpClient)
        case LlmProvider.LmStudio  => llm4zio.providers.LmStudioProvider.make(config, httpClient)
        case LlmProvider.Ollama    => llm4zio.providers.OllamaProvider.make(config, httpClient)
        case LlmProvider.OpenCode  => llm4zio.providers.OpenCodeProvider.make(config, httpClient)
    }

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
