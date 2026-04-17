package conversation.boundary

import java.net.{ URLDecoder, URLEncoder }
import java.nio.charset.StandardCharsets
import java.time.Instant

import zio.*
import zio.http.*
import zio.json.*

import _root_.config.entity.ProviderConfig
import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import conversation.entity.ChatRepository
import conversation.entity.api.*
import taskrun.entity.TaskRepository
import gateway.control.{ ChannelRegistry, GatewayService, GatewayServiceError, MessageChannelError }
import gateway.entity.{ GatewayMessageRole as GatewayMessageRole, MessageDirection as GatewayMessageDirection, * }
import issues.entity.{ IssueReport, IssueWorkReport }
import llm4zio.core.{ ConversationThread, LlmError, LlmService, Streaming, ToolConversationManager }
import llm4zio.providers.{ GeminiCliExecutor, HttpClient }
import llm4zio.tools.ToolRegistry
import orchestration.boundary.PlanPreviewComponents
import orchestration.control.*
import orchestration.entity.PlannerPlanPreview
import plan.entity.PlanTaskDraft
import shared.errors.PersistenceError
import shared.errors.PersistenceError as WorkspacePersistenceError
import shared.ids.Ids.{ ConversationId, EventId, IssueId, ReportId }
import shared.web.*
import taskrun.entity.{ TaskReportRow, TaskRepository }
import workspace.boundary.{ RunChainItem, RunSessionUiMeta }
import workspace.entity.WorkspaceRepository

trait ChatController:
  def routes: Routes[Any, Response]

object ChatController:

  def routes: ZIO[ChatController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[ChatController](_.routes)

  val live
    : ZLayer[
      ChatRepository & LlmService & TaskRepository & IssueAssignmentOrchestrator & AgentConfigResolver &
        GatewayService & ChannelRegistry & StreamAbortRegistry & ActivityHub & ToolRegistry & HttpClient &
        GeminiCliExecutor & WorkspaceRepository & PlannerAgentService,
      Nothing,
      ChatController,
    ] =
    ZLayer.fromFunction(ChatControllerLive.apply)

final case class ChatControllerLive(
  chatRepository: ChatRepository,
  llmService: LlmService,
  migrationRepository: TaskRepository,
  issueAssignmentOrchestrator: IssueAssignmentOrchestrator,
  configResolver: AgentConfigResolver,
  gatewayService: GatewayService,
  channelRegistry: ChannelRegistry,
  streamAbortRegistry: StreamAbortRegistry,
  activityHub: ActivityHub,
  toolRegistry: ToolRegistry,
  httpClient: HttpClient,
  cliExecutor: GeminiCliExecutor,
  workspaceRepository: WorkspaceRepository,
  plannerAgentService: PlannerAgentService,
) extends ChatController:

  private val sessionSupport = ChatSessionSupport(
    chatRepository = chatRepository,
    channelRegistry = channelRegistry,
    sanitizeString = sanitizeString,
    resolveAgentName = resolveAgentName,
  )

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "planner"                                              -> handler { (_: Request) =>
      ZIO.succeed(redirect("/chat/new?mode=plan"))
    },
    Method.GET / "planner" / string("id")                               -> handler { (id: String, _: Request) =>
      parseLongId("conversation", id)
        .as(redirect(s"/chat/$id"))
        .catchAll(error => ZIO.succeed(Response.text(error.toString).status(Status.BadRequest)))
    },
    // Chat Conversations Web Views
    Method.GET / "chat"                                                 -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          conversations   <- chatRepository.listConversations(0, 80)
          enriched        <- enrichConversationsWithChannel(conversations)
          workspaceGroups <- buildWorkspaceFolders(enriched)
          sessions        <- sessionSupport.listChatSessions
        yield html(
          HtmlViews.chatDashboard(
            conversations = enriched,
            sessions = sessions,
            workspaceFolders = workspaceGroups,
          )
        )
      }
    },
    Method.GET / "chat" / "new"                                         -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          conversations   <- chatRepository.listConversations(0, 80)
          enriched        <- enrichConversationsWithChannel(conversations)
          workspaceGroups <- buildWorkspaceFolders(enriched)
          workspaces      <- workspaceRepository.list.mapError(mapWorkspaceRepoError)
        yield html(
          HtmlViews.chatNew(
            workspaceFolders = workspaceGroups,
            workspaces = workspaces
              .sortBy(ws => ws.name.toLowerCase)
              .map(ws => ws.id -> ws.name),
          )
        )
      }
    },
    Method.GET / "chat" / "sidebar-nav"                                 -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          conversations      <- chatRepository.listConversations(0, 80)
          enriched           <- enrichConversationsWithChannel(conversations)
          workspaceGroups    <- buildWorkspaceFolders(enriched)
          now                <- Clock.instant
          currentPath         = req.url.queryParams.getAll("path").headOption.flatMap(sanitizeString)
          currentConversation = currentPath.flatMap(sessionSupport.parseCurrentConversationIdFromPath)
          nav                 = toLayoutWorkspaceNav(workspaceGroups, currentConversation, now)
        yield html(Layout.chatWorkspacesTree(nav).render)
      }
    },
    Method.POST / "chat"                                                -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form           <- parseForm(req)
          title          <- ZIO
                              .fromOption(form.get("title").map(_.trim).filter(_.nonEmpty))
                              .orElseFail(PersistenceError.QueryFailed("parseForm", "Missing title"))
          description     = form.get("description").map(_.trim).filter(_.nonEmpty)
          runId           = form.get("run_id").map(_.trim).filter(_.nonEmpty)
          now            <- Clock.instant
          conversation    = ChatConversation(
                              runId = runId,
                              title = title,
                              description = description,
                              createdAt = now,
                              updatedAt = now,
                            )
          conversationId <- chatRepository.createConversation(conversation)
        yield Response(
          status = Status.SeeOther,
          headers = Headers(Header.Custom("Location", s"/chat/$conversationId")),
        )
      }
    },
    Method.POST / "chat" / "new"                                        -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form          <- parseForm(req)
          rawContent    <- ZIO
                             .fromOption(form.get("content").map(_.trim).filter(_.nonEmpty))
                             .orElseFail(PersistenceError.QueryFailed("parseForm", "Missing content"))
          mode          <- parseConversationMode(form.get("mode"))
          workspaceIdRaw = form.get("workspace_id").flatMap(sanitizeString)
          workspaceId   <- resolveSelectedWorkspaceId(workspaceIdRaw)
          convId        <- mode match
                             case ConversationMode.Plan =>
                               startPlanConversation(rawContent, workspaceId)
                             case ConversationMode.Chat =>
                               startChatConversation(rawContent, workspaceId)
        yield Response(
          status = Status.SeeOther,
          headers = Headers(Header.Custom("Location", s"/chat/$convId")),
        )
      }
    },
    Method.GET / "chat" / string("id")                                  -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId           <- parseLongId("conversation", id)
          conversation     <- chatRepository
                                .getConversation(convId)
                                .someOrFail(PersistenceError.NotFound("conversation", convId.toString))
          sessionMeta      <- resolveConversationSessionMeta(id)
          runMeta          <- resolveRunSessionMeta(conversation)
          detailContext    <- resolveChatDetailContext(conversation, sessionMeta)
          allConversations <- chatRepository.listConversations(0, 80)
          enrichedAll      <- enrichConversationsWithChannel(allConversations)
          workspaceGroups  <- buildWorkspaceFolders(enrichedAll)
        yield html(HtmlViews.chatDetail(conversation, sessionMeta, runMeta, workspaceGroups, detailContext))
      }
    },
    Method.GET / "chat" / string("id") / "messages"                     -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId   <- parseLongId("conversation", id)
          messages <- chatRepository.getMessages(convId)
        yield html(HtmlViews.chatMessagesFragment(messages))
      }
    },
    Method.POST / "chat" / string("id") / "messages"                    -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId       <- parseLongId("conversation", id)
          conversation <- chatRepository
                            .getConversation(convId)
                            .someOrFail(PersistenceError.NotFound("conversation", convId.toString))
          form         <- parseForm(req)
          rawContent   <- ZIO
                            .fromOption(form.get("content").map(_.trim).filter(_.nonEmpty))
                            .orElseFail(PersistenceError.QueryFailed("parseForm", "Missing content"))
          now          <- Clock.instant
          _            <- conversationMode(conversation) match
                            case ConversationMode.Plan =>
                              plannerAgentService
                                .appendUserMessage(convId, rawContent)
                                .mapError(mapPlannerError("planner_append_user_message"))
                                .unit
                            case ConversationMode.Chat =>
                              for
                                mention     <- ZIO.succeed(parsePreferredAgentMention(rawContent))
                                content      = mention.content
                                preferred   <- resolvePreferredAgent(convId, mention.metadata.get("preferredAgent"))
                                _           <- chatRepository.addMessage(
                                                 ConversationEntry(
                                                   conversationId = id,
                                                   sender = "user",
                                                   senderType = SenderType.User,
                                                   content = rawContent,
                                                   messageType = MessageType.Text,
                                                   createdAt = now,
                                                   updatedAt = now,
                                                 )
                                               )
                                _           <- ensureConversationTitle(convId, content, now)
                                userInbound <- toGatewayMessage(
                                                 convId,
                                                 SenderType.User,
                                                 content,
                                                 None,
                                                 GatewayMessageDirection.Inbound,
                                                 additionalMetadata = withPreferredAgentMetadata(mention.metadata, preferred),
                                               )
                                _           <- routeThroughGateway(gatewayService.processInbound(userInbound))
                                _           <- streamAssistantResponse(convId, content, preferred).forkDaemon
                              yield ()
          _            <- activityHub.publish(
                            ActivityEvent(
                              id = EventId.generate,
                              eventType = ActivityEventType.MessageSent,
                              source = "chat",
                              conversationId = Some(ConversationId(id)),
                              summary = s"Message sent in conversation #$convId",
                              createdAt = now,
                            )
                          )
          messages     <- chatRepository.getMessages(convId)
        yield html(HtmlViews.chatMessagesFragment(messages))
      }
    },
    Method.GET / "chat" / string("id") / "plan-fragment"                -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId <- parseLongId("conversation", id)
          state  <- plannerAgentService.getPreview(convId).mapError(mapPlannerError("planner_get_preview"))
        yield html(
          PlanPreviewComponents
            .planPanelsContent(
              state,
              basePath = s"/chat/$convId/plan",
              fragmentPath = s"/chat/$convId/plan-fragment",
            )
            .render
        )
      }
    },
    Method.POST / "chat" / string("id") / "plan" / "chat"               -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId <- parseLongId("conversation", id)
          form   <- parseMultiForm(req)
          msg    <- required(form, "message")
          _      <- plannerAgentService
                      .appendUserMessage(convId, msg)
                      .mapError(mapPlannerError("planner_append_user_message"))
        yield seeOtherToChat(convId)
      }
    },
    Method.POST / "chat" / string("id") / "plan" / "refresh"            -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId <- parseLongId("conversation", id)
          _      <- plannerAgentService.regeneratePreview(convId).mapError(mapPlannerError("planner_regenerate_preview"))
        yield seeOtherToChat(convId)
      }
    },
    Method.POST / "chat" / string("id") / "plan" / "preview"            -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId  <- parseLongId("conversation", id)
          form    <- parseMultiForm(req)
          preview <- parsePreviewForm(form)
          _       <- plannerAgentService
                       .updatePreview(convId, preview)
                       .mapError(mapPlannerError("planner_update_preview"))
        yield seeOtherToChat(convId)
      }
    },
    Method.POST / "chat" / string("id") / "plan" / "preview" / "add"    -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId <- parseLongId("conversation", id)
          _      <- plannerAgentService.addBlankIssue(convId).mapError(mapPlannerError("planner_add_blank_issue"))
        yield seeOtherToChat(convId)
      }
    },
    Method.POST / "chat" / string("id") / "plan" / "preview" / "remove" -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId  <- parseLongId("conversation", id)
          form    <- parseMultiForm(req)
          draftId <-
            optional(form, "remove_draft_id")
              .orElse(optional(form, "draft_id"))
              .fold[IO[PersistenceError, String]](
                ZIO.fail(PersistenceError.QueryFailed("planner_form", "Missing required field: remove_draft_id"))
              )(ZIO.succeed(_))
          _       <- plannerAgentService.removeIssue(convId, draftId).mapError(mapPlannerError("planner_remove_issue"))
        yield seeOtherToChat(convId)
      }
    },
    Method.POST / "chat" / string("id") / "plan" / "confirm"            -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId <- parseLongId("conversation", id)
          result <- plannerAgentService.confirmPlan(convId).mapError(mapPlannerError("planner_confirm_plan"))
        yield seeOther(boardRedirect(result.issueIds.map(_.value)))
      }
    },
    Method.POST / "chat" / string("id") / "plan" / "mode"               -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId       <- parseLongId("conversation", id)
          conversation <- chatRepository
                            .getConversation(convId)
                            .someOrFail(PersistenceError.NotFound("conversation", convId.toString))
          form         <- parseForm(req)
          mode         <- parseConversationMode(form.get("mode"))
          workspaceId   = conversation.workspaceId
                            .orElse(parseWorkspaceMarkerDescription(conversation.description))
          updatedAt    <- Clock.instant
          updated       = conversation.copy(
                            workspaceId = workspaceId,
                            updatedAt = updatedAt,
                          )
          _            <- chatRepository.updateConversation(updated)
          _            <- mode match
                            case ConversationMode.Plan =>
                              plannerAgentService
                                .getPreview(convId)
                                .either
                                .flatMap {
                                  case Right(_)                                   => ZIO.unit
                                  case Left(PlannerAgentError.PreviewNotFound(_)) =>
                                    plannerAgentService
                                      .regeneratePreview(convId)
                                      .mapError(mapPlannerError("planner_regenerate_preview"))
                                      .unit
                                  case Left(error)                                =>
                                    ZIO.fail(mapPlannerError("planner_get_preview")(error))
                                }
                            case ConversationMode.Chat =>
                              ZIO.unit
        yield seeOtherToChat(convId)
      }
    },
    // Abort streaming
    Method.POST / "api" / "chat" / string("id") / "abort"               -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        parseLongId("conversation", id).flatMap(streamAbortRegistry.abort).map { aborted =>
          Response.json(Map("aborted" -> aborted.toString).toJson)
        }
      }
    },
    // Chat API Endpoints
    Method.POST / "api" / "chat"                                        -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body        <- req.body.asString.mapError(err =>
                           PersistenceError.QueryFailed("request_body", err.getMessage)
                         )
          request     <- ZIO
                           .fromEither(body.fromJson[ChatConversationCreateRequest])
                           .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          now         <- Clock.instant
          conversation = ChatConversation(
                           runId = request.runId,
                           title = request.title,
                           description = request.description,
                           createdAt = now,
                           updatedAt = now,
                         )
          convId      <- chatRepository.createConversation(conversation)
          created     <- chatRepository
                           .getConversation(convId)
                           .someOrFail(PersistenceError.NotFound("conversation", convId.toString))
        yield Response.json(created.toJson)
      }
    },
    Method.GET / "api" / "chat" / string("id")                          -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId       <- parseLongId("conversation", id)
          conversation <- chatRepository
                            .getConversation(convId)
                            .someOrFail(PersistenceError.NotFound("conversation", convId.toString))
        yield Response.json(conversation.toJson)
      }
    },
    Method.POST / "api" / "chat" / string("id") / "messages"            -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId     <- parseLongId("conversation", id)
          body       <- req.body.asString.mapError(err =>
                          PersistenceError.QueryFailed("request_body", err.getMessage)
                        )
          msgRequest <- ZIO
                          .fromEither(body.fromJson[ConversationMessageRequest])
                          .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          aiMessage  <-
            addUserAndAssistantMessage(convId, msgRequest.content, msgRequest.messageType, msgRequest.metadata)
        yield Response.json(aiMessage.toJson)
      }
    },
    Method.GET / "api" / "chat" / string("id") / "messages"             -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        val since = req.queryParam("since").flatMap(parseInstantOption)
        for
          convId   <- parseLongId("conversation", id)
          messages <-
            if since.isDefined then chatRepository.getMessagesSince(convId, since.get)
            else chatRepository.getMessages(convId)
        yield Response.json(messages.toJson)
      }
    },
    Method.GET / "api" / "sessions"                                     -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        sessionSupport.listChatSessions.map(sessions => Response.json(sessions.toJson))
      }
    },
    Method.GET / "api" / "sessions" / string("id")                      -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        val decoded = urlDecode(id)
        for
          session <- sessionSupport.getChatSession(decoded)
        yield Response.json(session.toJson)
      }
    },
    Method.DELETE / "api" / "sessions" / string("id")                   -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        val decoded = urlDecode(id)
        for
          _ <- sessionSupport.endSession(decoded)
        yield Response.json(SessionDeleteResponse(deleted = true, sessionId = decoded).toJson)
      }
    },
    Method.DELETE / "api" / "conversations" / string("id")              -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId <- parseLongId("conversation", id)
          _      <- chatRepository.deleteConversation(convId)
        yield Response(status = Status.NoContent)
      }
    },
  )

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def redirect(path: String): Response =
    Response.redirect(URL.decode(path).toOption.getOrElse(URL.root))

  private def seeOther(path: String): Response =
    Response(
      status = Status.SeeOther,
      headers = Headers(Header.Location(URL.decode(path).toOption.getOrElse(URL.root))),
    )

  private def seeOtherToChat(conversationId: Long): Response =
    seeOther(s"/chat/$conversationId")

  private def addUserAndAssistantMessage(
    conversationId: Long,
    userContent: String,
    messageType: MessageType,
    metadata: Option[String],
  ): IO[PersistenceError, ConversationEntry] =
    for
      mention     <- ZIO.succeed(parsePreferredAgentMention(userContent))
      preferred   <- resolvePreferredAgent(conversationId, mention.metadata.get("preferredAgent"))
      now         <- Clock.instant
      _           <- chatRepository.addMessage(
                       ConversationEntry(
                         conversationId = conversationId.toString,
                         sender = "user",
                         senderType = SenderType.User,
                         content = userContent,
                         messageType = messageType,
                         metadata = metadata,
                         createdAt = now,
                         updatedAt = now,
                       )
                     )
      _           <- ensureConversationTitle(conversationId, userContent, now)
      userInbound <- toGatewayMessage(
                       conversationId = conversationId,
                       senderType = SenderType.User,
                       content = mention.content,
                       metadata = metadata,
                       direction = GatewayMessageDirection.Inbound,
                       additionalMetadata = withPreferredAgentMetadata(mention.metadata, preferred),
                     )
      _           <- routeThroughGateway(gatewayService.processInbound(userInbound))
      toolsEnabled = metadata.flatMap(m => m.fromJson[Map[String, String]].toOption)
                       .flatMap(_.get("toolsEnabled")).contains("true")
      llmResponse <-
        if toolsEnabled then
          for
            tools      <- toolRegistry.list
            threadId    = java.util.UUID.randomUUID().toString
            now3       <- Clock.instant
            thread      = ConversationThread.create(threadId, now3)
            toolResult <- ToolConversationManager
                            .run(
                              prompt = mention.content,
                              thread = thread,
                              llmService = llmService,
                              toolRegistry = toolRegistry,
                              tools = tools,
                              maxIterations = 8,
                            )
                            .mapError(convertLlmError)
          yield toolResult.response
        else
          executeWithPreferredAgent(preferred, mention.content)
            .mapError(convertLlmError)
      now2        <- Clock.instant
      aiMessage    = ConversationEntry(
                       conversationId = conversationId.toString,
                       sender = "assistant",
                       senderType = SenderType.Assistant,
                       content = llmResponse.content,
                       messageType = MessageType.Text,
                       metadata = Some(llmResponse.metadata.toJson),
                       createdAt = now2,
                       updatedAt = now2,
                     )
      _           <- chatRepository.addMessage(aiMessage)
      aiOutbound  <- toGatewayMessage(
                       conversationId = conversationId,
                       senderType = SenderType.Assistant,
                       content = aiMessage.content,
                       metadata = aiMessage.metadata,
                       direction = GatewayMessageDirection.Outbound,
                     )
      _           <- routeThroughGateway(gatewayService.processOutbound(aiOutbound).unit)
      conv        <- chatRepository
                       .getConversation(conversationId)
                       .someOrFail(PersistenceError.NotFound("conversation", conversationId.toString))
      _           <- chatRepository.updateConversation(conv.copy(updatedAt = now2))
    yield aiMessage

  private def streamAssistantResponse(
    conversationId: Long,
    userContent: String,
    preferredAgent: Option[String],
  ): UIO[Unit] =
    val effect =
      for
        _                          <- sendStreamEvent(conversationId, "chat-stream-start", "")
        pair                       <- Streaming.cancellable(executeStreamWithPreferredAgent(preferredAgent, userContent))
        (cancellableStream, cancel) = pair
        _                          <- streamAbortRegistry.register(conversationId, cancel)
        accumulated                <- cancellableStream
                                        .mapZIO { chunk =>
                                          sendStreamEvent(conversationId, "chat-chunk", chunk.delta).as(chunk.delta)
                                        }
                                        .runFold("")(_ + _)
        _                          <- streamAbortRegistry.unregister(conversationId)
        _                          <- sendStreamEvent(conversationId, "chat-stream-end", "")
        now                        <- Clock.instant
        aiMessage                   = ConversationEntry(
                                        conversationId = conversationId.toString,
                                        sender = "assistant",
                                        senderType = SenderType.Assistant,
                                        content = accumulated,
                                        messageType = MessageType.Text,
                                        createdAt = now,
                                        updatedAt = now,
                                      )
        _                          <- chatRepository.addMessage(aiMessage)
        aiOutbound                 <- toGatewayMessage(
                                        conversationId = conversationId,
                                        senderType = SenderType.Assistant,
                                        content = accumulated,
                                        metadata = None,
                                        direction = GatewayMessageDirection.Outbound,
                                      )
        _                          <- routeThroughGateway(gatewayService.processOutbound(aiOutbound).unit)
        conv                       <- chatRepository
                                        .getConversation(conversationId)
                                        .someOrFail(PersistenceError.NotFound("conversation", conversationId.toString))
        _                          <- chatRepository.updateConversation(conv.copy(updatedAt = now))
      yield ()
    effect.catchAll(err => ZIO.logWarning(s"streaming response failed for conversation $conversationId: $err"))

  private def startChatConversation(
    rawContent: String,
    workspaceId: Option[String],
  ): IO[PersistenceError, Long] =
    for
      now         <- Clock.instant
      conversation = ChatConversation(
                       runId = None,
                       title = "",
                       createdAt = now,
                       updatedAt = now,
                       workspaceId = workspaceId,
                     )
      convId      <- chatRepository.createConversation(conversation)
      mention      = parsePreferredAgentMention(rawContent)
      content      = mention.content
      preferred   <- resolvePreferredAgent(convId, mention.metadata.get("preferredAgent"))
      _           <- chatRepository.addMessage(
                       ConversationEntry(
                         conversationId = convId.toString,
                         sender = "user",
                         senderType = SenderType.User,
                         content = rawContent,
                         messageType = MessageType.Text,
                         createdAt = now,
                         updatedAt = now,
                       )
                     )
      _           <- ensureConversationTitle(convId, content, now)
      userInbound <- toGatewayMessage(
                       convId,
                       SenderType.User,
                       content,
                       None,
                       GatewayMessageDirection.Inbound,
                       additionalMetadata = withPreferredAgentMetadata(mention.metadata, preferred),
                     )
      _           <- routeThroughGateway(gatewayService.processInbound(userInbound))
      _           <- streamAssistantResponse(convId, content, preferred).forkDaemon
    yield convId

  private def startPlanConversation(
    rawContent: String,
    workspaceId: Option[String],
  ): IO[PersistenceError, Long] =
    plannerAgentService
      .startSession(rawContent, workspaceId)
      .mapError(error => PersistenceError.QueryFailed("planner_start_session", error.message))
      .flatMap { start =>
        chatRepository.getConversation(start.conversationId).flatMap {
          case Some(conversation) =>
            chatRepository.updateConversation(
              conversation.copy(workspaceId = workspaceId)
            ).as(start.conversationId)
          case None               =>
            ZIO.fail(PersistenceError.NotFound("conversation", start.conversationId.toString))
        }
      }

  private def sendStreamEvent(conversationId: Long, eventType: String, payload: String): UIO[Unit] =
    val jsonContent = Map("type" -> eventType, "delta" -> payload).toJson
    (for
      now       <- Clock.instant
      sessionKey = SessionScopeStrategy.PerConversation.build("websocket", conversationId.toString)
      msg        = NormalizedMessage(
                     id = s"stream-$conversationId-${now.toEpochMilli}-$eventType",
                     channelName = "websocket",
                     sessionKey = sessionKey,
                     direction = GatewayMessageDirection.Outbound,
                     role = GatewayMessageRole.Assistant,
                     content = jsonContent,
                     metadata = Map(
                       "conversationId"  -> conversationId.toString,
                       "streamEventType" -> eventType,
                     ),
                     timestamp = now,
                   )
      _         <- ensureWebSocketSession(conversationId)
      _         <- gatewayService.processOutbound(msg).unit
    yield ()).catchAll(err => ZIO.logWarning(s"stream event send failed: $err"))

  private def toGatewayMessage(
    conversationId: Long,
    senderType: SenderType,
    content: String,
    metadata: Option[String],
    direction: GatewayMessageDirection,
    additionalMetadata: Map[String, String] = Map.empty,
  ): UIO[NormalizedMessage] =
    for
      now <- Clock.instant
      _   <- ensureWebSocketSession(conversationId)
    yield NormalizedMessage(
      id = s"chat-$conversationId-${now.toEpochMilli}-${senderType.toString.toLowerCase}",
      channelName = "websocket",
      sessionKey = SessionScopeStrategy.PerConversation.build("websocket", conversationId.toString),
      direction = direction,
      role = senderType match
        case SenderType.User      => GatewayMessageRole.User
        case SenderType.Assistant => GatewayMessageRole.Assistant
        case SenderType.System    => GatewayMessageRole.System
      ,
      content = content,
      metadata =
        Map("conversationId" -> conversationId.toString) ++ metadata.map("raw" -> _).toMap ++ additionalMetadata,
      timestamp = now,
    )

  final private case class PreferredAgentMention(
    content: String,
    metadata: Map[String, String],
  )

  final private case class SessionDeleteResponse(
    deleted: Boolean,
    sessionId: String,
  ) derives JsonCodec

  private def parsePreferredAgentMention(rawContent: String): PreferredAgentMention =
    val MentionPattern = """^\s*@([A-Za-z][A-Za-z0-9_-]*)\b[:\-]?\s*(.*)$""".r
    rawContent match
      case MentionPattern(agentName, remainder) if remainder.trim.nonEmpty =>
        PreferredAgentMention(
          content = remainder.trim,
          metadata = Map(
            "preferredAgent" -> agentName,
            "intent.agent"   -> agentName,
          ),
        )
      case _                                                               =>
        PreferredAgentMention(
          content = rawContent,
          metadata = Map.empty,
        )

  private def parseConversationMode(raw: Option[String]): IO[PersistenceError, ConversationMode] =
    raw.flatMap(sanitizeString).map(_.toLowerCase(java.util.Locale.ROOT)) match
      case None | Some("chat") => ZIO.succeed(ConversationMode.Chat)
      case Some("plan")        => ZIO.succeed(ConversationMode.Plan)
      case Some(other)         =>
        ZIO.fail(PersistenceError.QueryFailed("parse_mode", s"Unsupported conversation mode: $other"))

  private def parsePreviewForm(form: Map[String, List[String]]): IO[PersistenceError, PlannerPlanPreview] =
    val summary      = optional(form, "summary").getOrElse("")
    val draftIds     = values(form, "draft_id")
    val titles       = values(form, "title")
    val descriptions = values(form, "description")
    val issueTypes   = values(form, "issue_type")
    val priorities   = values(form, "priority")
    val estimates    = values(form, "estimate")
    val capabilities = values(form, "required_capabilities")
    val dependencies = values(form, "dependency_draft_ids")
    val acceptance   = values(form, "acceptance_criteria")
    val prompts      = values(form, "prompt_template")
    val skills       = values(form, "kaizen_skills")
    val proof        = values(form, "proof_of_work_requirements")
    val included     = values(form, "included")
    val sizes        = List(
      draftIds.size,
      titles.size,
      descriptions.size,
      issueTypes.size,
      priorities.size,
      estimates.size,
      capabilities.size,
      dependencies.size,
      acceptance.size,
      prompts.size,
      skills.size,
      proof.size,
      included.size,
    ).distinct

    if sizes.size > 1 then
      ZIO.fail(PersistenceError.QueryFailed("planner_preview_form", "Planner preview form fields were incomplete"))
    else
      ZIO.succeed(
        PlannerPlanPreview(
          summary = summary,
          issues = draftIds.indices.toList.map { idx =>
            PlanTaskDraft(
              draftId = draftIds(idx),
              title = titles(idx),
              description = descriptions(idx),
              issueType = issueTypes(idx),
              priority = priorities(idx),
              estimate = Option(estimates(idx)).map(_.trim).filter(_.nonEmpty),
              requiredCapabilities = splitCsv(capabilities(idx)),
              dependencyDraftIds = splitCsv(dependencies(idx)),
              acceptanceCriteria = acceptance(idx),
              promptTemplate = prompts(idx),
              kaizenSkills = splitCsv(skills(idx)),
              proofOfWorkRequirements = splitCsv(proof(idx)),
              included = included(idx).equalsIgnoreCase("true"),
            )
          },
        )
      )

  private def ensureWebSocketSession(conversationId: Long): UIO[Unit] =
    val sessionKey = SessionScopeStrategy.PerConversation.build("websocket", conversationId.toString)
    channelRegistry
      .get("websocket")
      .flatMap(_.open(sessionKey))
      .catchAll {
        case MessageChannelError.ChannelNotFound(_)       => ZIO.unit
        case MessageChannelError.UnsupportedSession(_, _) =>
          ZIO.logWarning("websocket session adapter rejected unsupported session")
        case MessageChannelError.ChannelClosed(_)         =>
          ZIO.logWarning("websocket channel is closed while adapting session")
        case _                                            => ZIO.unit
      }

  private def routeThroughGateway(effect: IO[GatewayServiceError, Unit]): UIO[Unit] =
    effect.catchAll(err => ZIO.logWarning(s"gateway routing skipped: $err"))

  private def mapPlannerError(operation: String)(error: PlannerAgentError): PersistenceError =
    PersistenceError.QueryFailed(operation, error.message)

  private def enrichConversationsWithChannel(
    conversations: List[ChatConversation]
  ): IO[PersistenceError, List[ChatConversation]] =
    ZIO.foreach(conversations) { conversation =>
      conversation.id match
        case Some(id) =>
          resolveConversationSessionMeta(id).map { meta =>
            conversation.copy(channel = meta.map(_.channelName).orElse(conversation.channel))
          }
        case None     => ZIO.succeed(conversation)
    }

  private def resolveConversationSessionMeta(
    conversationId: String
  ): IO[PersistenceError, Option[ConversationSessionMeta]] =
    parseLongId("conversation", conversationId).flatMap(chatRepository.getSessionContextStateByConversation).map(
      _.map(link =>
        ConversationSessionMeta(
          channelName = sanitizeString(link.channelName).getOrElse("web"),
          sessionKey = sanitizeString(link.sessionKey).getOrElse("unknown"),
          linkedTaskRunId = link.context.runId.map(_.toString),
          updatedAt = link.updatedAt,
        )
      )
    )

  private def resolveRunSessionMeta(conversation: ChatConversation): IO[PersistenceError, Option[RunSessionUiMeta]] =
    sanitizeOptional(conversation.runId) match
      case None        => ZIO.none
      case Some(runId) =>
        workspaceRepository.getRun(runId).mapError(mapWorkspaceRepoError).flatMap {
          case None      => ZIO.none
          case Some(run) =>
            workspaceRepository.listRuns(run.workspaceId).mapError(mapWorkspaceRepoError).map { runs =>
              val byId       = runs.map(r => r.id -> r).toMap
              val parentItem = run.parentRunId.flatMap(byId.get).map(toChainItem)
              val nextItem   = runs.find(_.parentRunId.contains(run.id)).map(toChainItem)
              val breadcrumb = buildRunBreadcrumb(run, byId)
              Some(
                RunSessionUiMeta(
                  runId = run.id,
                  workspaceId = run.workspaceId,
                  status = run.status,
                  attachedUsersCount = run.attachedUsers.size,
                  parent = parentItem,
                  next = nextItem,
                  breadcrumb = breadcrumb,
                )
              )
            }
        }

  private def resolveChatDetailContext(
    conversation: ChatConversation,
    sessionMeta: Option[ConversationSessionMeta],
  ): IO[PersistenceError, ChatDetailContext] =
    val plannerStateEffect =
      if conversationMode(conversation) == ConversationMode.Plan then
        sanitizeOptional(conversation.id).flatMap(_.toLongOption) match
          case Some(conversationId) =>
            plannerAgentService
              .getPreview(conversationId)
              .either
              .map(_.toOption)
          case None                 =>
            ZIO.none
      else ZIO.none

    sanitizeOptional(conversation.runId).flatMap(_.toLongOption) match
      case None        =>
        plannerStateEffect.map(plannerState =>
          ChatDetailContext.empty.copy(
            memorySessionId = sessionMeta.map(_.sessionKey),
            plannerState = plannerState,
          )
        )
      case Some(runId) =>
        for
          reports      <- migrationRepository.getReportsByTask(runId)
          plannerState <- plannerStateEffect
          graphReports  = reports.filter(report => report.reportType.trim.equalsIgnoreCase("graph"))
          proofOfWork   = toSyntheticProofOfWork(conversation, runId, reports)
        yield ChatDetailContext(
          proofOfWork = proofOfWork,
          reports = reports,
          graphReports = graphReports,
          memorySessionId = sessionMeta.map(_.sessionKey),
          plannerState = plannerState,
        )

  private def toSyntheticProofOfWork(
    conversation: ChatConversation,
    runId: Long,
    reports: List[TaskReportRow],
  ): Option[IssueWorkReport] =
    val issueId = issueIdFromConversation(conversation, runId)
    if reports.isEmpty then None
    else
      val createdAt = reports.map(_.createdAt).maxOption.getOrElse(conversation.updatedAt)
      Some(
        IssueWorkReport.empty(issueId, createdAt).copy(
          reports = reports.sortBy(_.createdAt).map(report =>
            IssueReport(
              id = ReportId(report.id.toString),
              stepName = report.stepName,
              reportType = report.reportType,
              content = report.content,
              createdAt = report.createdAt,
            )
          ),
          runtimeSeconds = None,
          lastUpdated = createdAt,
        )
      )

  private def issueIdFromConversation(conversation: ChatConversation, runId: Long): IssueId =
    val candidate = sanitizeOptional(conversation.runId)
      .orElse(sanitizeOptional(conversation.id))
      .getOrElse(runId.toString)
    IssueId(candidate)

  private def buildRunBreadcrumb(
    current: workspace.entity.WorkspaceRun,
    byId: Map[String, workspace.entity.WorkspaceRun],
  ): List[RunChainItem] =
    @annotation.tailrec
    def collectParents(
      cursor: Option[workspace.entity.WorkspaceRun],
      acc: List[RunChainItem],
      seen: Set[String],
      depth: Int,
    ): List[RunChainItem] =
      cursor match
        case None                                              => acc
        case Some(run) if depth >= 32 || seen.contains(run.id) =>
          RunChainItem(run.id, run.conversationId) :: acc
        case Some(run)                                         =>
          val parent = run.parentRunId.flatMap(byId.get)
          collectParents(parent, RunChainItem(run.id, run.conversationId) :: acc, seen + run.id, depth + 1)

    @annotation.tailrec
    def collectChildren(
      cursor: workspace.entity.WorkspaceRun,
      acc: List[RunChainItem],
      seen: Set[String],
      depth: Int,
    ): List[RunChainItem] =
      if depth >= 32 || seen.contains(cursor.id) then acc
      else
        val nextChild = byId.values
          .filter(_.parentRunId.contains(cursor.id))
          .toList
          .sortBy(_.createdAt)
          .headOption
        nextChild match
          case None      => acc
          case Some(run) =>
            collectChildren(
              run,
              acc :+ RunChainItem(run.id, run.conversationId),
              seen + cursor.id,
              depth + 1,
            )

    val parentToCurrent = collectParents(Some(current), Nil, Set.empty, 0)
    val childTail       = collectChildren(current, Nil, Set.empty, 0)
    parentToCurrent ++ childTail

  private def toChainItem(run: workspace.entity.WorkspaceRun): RunChainItem =
    RunChainItem(run.id, run.conversationId)

  private def mapWorkspaceRepoError(err: WorkspacePersistenceError): PersistenceError =
    PersistenceError.QueryFailed("workspace_repository", err.toString)

  private def resolveSelectedWorkspaceId(raw: Option[String]): IO[PersistenceError, Option[String]] =
    raw match
      case None                           => ZIO.none
      case Some(value) if value == "chat" => ZIO.none
      case Some(workspaceId)              =>
        workspaceRepository.get(workspaceId).mapError(mapWorkspaceRepoError).flatMap {
          case Some(_) => ZIO.succeed(Some(workspaceId))
          case None    => ZIO.fail(PersistenceError.QueryFailed("workspace", s"Workspace not found: $workspaceId"))
        }

  private def conversationMode(conversation: ChatConversation): ConversationMode =
    conversation.description match
      case Some(desc)
           if desc.startsWith(
             "planner-session"
           ) || desc.split("\\|").toList.exists(_.trim.equalsIgnoreCase("mode:plan")) =>
        ConversationMode.Plan
      case _ =>
        ConversationMode.Chat

  private def parseWorkspaceMarkerDescription(raw: Option[String]): Option[String] =
    try
      sanitizeOptional(raw) match
        case None              => None
        case Some(description) =>
          sanitizeString(description) match
            case None        => None
            case Some(value) =>
              value
                .split("\\|")
                .iterator
                .map(_.trim)
                .find(_.startsWith("workspace:"))
                .map(_.stripPrefix("workspace:"))
                .flatMap(sanitizeString)
    catch
      case _: Throwable => None

  private def buildWorkspaceFolders(
    conversations: List[ChatConversation]
  ): IO[PersistenceError, List[ChatView.ChatWorkspaceFolder]] =
    for
      workspaces      <- workspaceRepository.list.mapError(mapWorkspaceRepoError)
      runsByWs        <- ZIO
                           .foreach(workspaces)(ws =>
                             workspaceRepository
                               .listRuns(ws.id)
                               .mapError(mapWorkspaceRepoError)
                               .map(runs => ws.id -> runs)
                           )
      runIdToWorkspace = runsByWs.toMap.flatMap { (workspaceId, runs) =>
                           runs.map(run => run.id -> workspaceId)
                         }
      workspaceById    = workspaces.map(ws => ws.id -> ws).toMap
      grouped          = conversations.foldLeft(Map.empty[String, List[ChatConversation]]) { (acc, conversation) =>
                           val descriptionWorkspace = parseWorkspaceMarkerDescription(conversation.description)
                           val folderId             =
                             sanitizeOptional(conversation.runId)
                               .flatMap(runIdToWorkspace.get)
                               .orElse(conversation.workspaceId.filter(workspaceById.contains))
                               .orElse(descriptionWorkspace.filter(workspaceById.contains))
                               .getOrElse("chat")
                           val existing             = acc.getOrElse(folderId, Nil)
                           acc.updated(folderId, conversation :: existing)
                         }
      workspaceFolders =
        grouped.toList
          .filter(_._1 != "chat")
          .sortBy {
            case (folderId, _) =>
              workspaceById.get(folderId).flatMap(ws => sanitizeString(ws.name)).getOrElse(folderId).toLowerCase
          }
          .map {
            case (workspaceId, chats) =>
              ChatView.ChatWorkspaceFolder(
                id = workspaceId,
                label = workspaceById
                  .get(workspaceId)
                  .flatMap(ws => sanitizeString(ws.name))
                  .getOrElse(workspaceId),
                chats = chats.sortBy(_.updatedAt)(using Ordering[Instant].reverse),
              )
          }
      chatFolder       = grouped.get("chat").map(chats =>
                           ChatView.ChatWorkspaceFolder(
                             id = "chat",
                             label = "Chat",
                             chats = chats.sortBy(_.updatedAt)(using Ordering[Instant].reverse),
                           )
                         )
    yield workspaceFolders ++ chatFolder.toList

  private def toLayoutWorkspaceNav(
    workspaceFolders: List[ChatView.ChatWorkspaceFolder],
    currentConversationId: Option[String],
    renderedAt: Instant,
  ): Layout.ChatWorkspaceNav =
    Layout.ChatWorkspaceNav(
      groups = workspaceFolders.map { folder =>
        val chats = folder.chats
          .sortBy(_.updatedAt)(using Ordering[java.time.Instant].reverse)
          .take(80)
          .map { chat =>
            val conversationId = sanitizeOptional(chat.id).getOrElse("unknown")
            Layout.ChatNavItem(
              conversationId = conversationId,
              title = sanitizeString(chat.title).getOrElse("Untitled chat"),
              href = s"/chat/$conversationId",
              active = currentConversationId.contains(conversationId),
              isPlan = conversationMode(chat) == ConversationMode.Plan,
            )
          }
        Layout.ChatWorkspaceGroup(
          id = folder.id,
          label = folder.label,
          chats = chats,
          expanded = false,
        )
      },
      showNewChat = true,
      renderedAt = renderedAt,
    )

  private def resolveAgentName(metadata: Map[String, String]): Option[String] =
    metadata
      .get("preferredAgent")
      .orElse(metadata.get("intent.agent"))
      .orElse(metadata.get("agentName"))
      .orElse(metadata.get("assignedAgent"))
      .map(_.trim)
      .filter(_.nonEmpty)

  private def resolvePreferredAgent(
    conversationId: Long,
    mentionedAgent: Option[String],
  ): IO[PersistenceError, Option[String]] =
    mentionedAgent.map(_.trim).filter(_.nonEmpty) match
      case some @ Some(_) => ZIO.succeed(some)
      case None           =>
        chatRepository
          .getSessionContextStateByConversation(conversationId)
          .map(_.flatMap(link => resolveAgentName(link.context.metadata)))

  private def withPreferredAgentMetadata(
    metadata: Map[String, String],
    preferredAgent: Option[String],
  ): Map[String, String] =
    preferredAgent match
      case Some(name) => metadata ++ Map("preferredAgent" -> name, "intent.agent" -> name)
      case None       => metadata

  private def parseInstantOption(value: String): Option[Instant] =
    Option(value).map(_.trim).filter(_.nonEmpty).flatMap { raw =>
      raw.toLongOption.map(Instant.ofEpochMilli).orElse(
        scala.util.control.Exception.nonFatalCatch.opt(Instant.parse(raw))
      )
    }

  private def parseLongId(entity: String, raw: String): IO[PersistenceError, Long] =
    ZIO
      .fromOption(raw.toLongOption)
      .orElseFail(PersistenceError.QueryFailed(s"parse_$entity", s"Invalid $entity id: '$raw'"))

  private def executeWithPreferredAgent(agentName: Option[String], prompt: String)
    : IO[LlmError, llm4zio.core.LlmResponse] =
    agentName.map(_.trim).filter(_.nonEmpty) match
      case Some(name) =>
        configResolver
          .resolveConfig(name)
          .either
          .flatMap {
            case Right(config) =>
              ZIO.logInfo(s"chat using agent override '$name' provider=${config.provider} model=${config.model}") *>
                executeWithConfig(config, prompt).catchAll(err =>
                  ZIO.logWarning(
                    s"chat agent override execution failed for '$name': ${formatLlmError(err)}; falling back to global provider"
                  ) *> Streaming.collect(llmService.executeStream(prompt))
                )
            case Left(err)     =>
              ZIO.logWarning(s"chat agent override resolution failed for '$name': $err; using global provider") *>
                Streaming.collect(llmService.executeStream(prompt))
          }
      case None       =>
        Streaming.collect(llmService.executeStream(prompt))

  private def executeStreamWithPreferredAgent(
    agentName: Option[String],
    prompt: String,
  ): zio.stream.Stream[LlmError, llm4zio.core.LlmChunk] =
    zio.stream.ZStream.unwrap {
      agentName.map(_.trim).filter(_.nonEmpty) match
        case Some(name) =>
          configResolver
            .resolveConfig(name)
            .either
            .map {
              case Right(config) =>
                zio.stream.ZStream.fromZIO(
                  ZIO.logInfo(
                    s"chat stream using agent override '$name' provider=${config.provider} model=${config.model}"
                  )
                ).drain ++
                  executeStreamWithConfig(config, prompt).catchAll(err =>
                    zio.stream.ZStream.fromZIO(
                      ZIO.logWarning(
                        s"chat stream agent override execution failed for '$name': ${formatLlmError(err)}; falling back to global provider"
                      )
                    ).drain ++ llmService.executeStream(prompt)
                  )
              case Left(err)     =>
                zio.stream.ZStream.fromZIO(
                  ZIO.logWarning(
                    s"chat stream agent override resolution failed for '$name': $err; using global provider"
                  )
                ).drain ++ llmService.executeStream(prompt)
            }
        case None       =>
          ZIO.succeed(llmService.executeStream(prompt))
    }

  private def executeWithConfig(config: ProviderConfig, prompt: String): IO[LlmError, llm4zio.core.LlmResponse] =
    fallbackConfigs(config)
      .foldLeft[IO[LlmError, llm4zio.core.LlmResponse]](ZIO.fail(LlmError.ConfigError("No LLM provider configured"))) {
        (acc, cfg) => acc.orElse(providerFor(cfg).flatMap(svc => Streaming.collect(svc.executeStream(prompt))))
      }

  private def executeStreamWithConfig(
    config: ProviderConfig,
    prompt: String,
  ): zio.stream.Stream[LlmError, llm4zio.core.LlmChunk] =
    failoverStreamByConfig(fallbackConfigs(config))(service => service.executeStream(prompt))

  private def failoverStreamByConfig(
    configs: List[llm4zio.core.LlmConfig]
  )(
    run: LlmService => zio.stream.Stream[LlmError, llm4zio.core.LlmChunk]
  ): zio.stream.Stream[LlmError, llm4zio.core.LlmChunk] =
    configs match
      case head :: tail =>
        zio.stream.ZStream.unwrap(
          providerFor(head).either.map {
            case Right(service) =>
              run(service).catchAll(err =>
                if tail.nonEmpty then failoverStreamByConfig(tail)(run) else zio.stream.ZStream.fail(err)
              )
            case Left(err)      =>
              if tail.nonEmpty then failoverStreamByConfig(tail)(run) else zio.stream.ZStream.fail(err)
          }
        )
      case Nil          =>
        zio.stream.ZStream.fail(LlmError.ConfigError("No LLM provider configured"))

  private def fallbackConfigs(primary: ProviderConfig): List[llm4zio.core.LlmConfig] =
    val primaryLlm = primary.toLlmConfig
    val fallback   = primary.fallbackChain.models.map { ref =>
      ProviderConfig.withDefaults(
        primary.copy(
          provider = ref.provider.getOrElse(primary.provider),
          model = ref.modelId,
        )
      ).toLlmConfig
    }
    (primaryLlm :: fallback).distinct

  private def formatLlmError(error: LlmError): String =
    error match
      case LlmError.ParseError(message, raw)     =>
        val compact = raw.replaceAll("\\s+", " ").trim
        val sample  = if compact.length <= 240 then compact else compact.take(240) + "..."
        s"ParseError(message=$message, raw=$sample)"
      case LlmError.ProviderError(message, _)    =>
        s"ProviderError(message=$message)"
      case LlmError.AuthenticationError(message) =>
        s"AuthenticationError(message=$message)"
      case LlmError.InvalidRequestError(message) =>
        s"InvalidRequestError(message=$message)"
      case LlmError.RateLimitError(retryAfter)   =>
        s"RateLimitError(retryAfter=${retryAfter.map(_.toString).getOrElse("unknown")})"
      case LlmError.TimeoutError(duration)       =>
        s"TimeoutError(duration=$duration)"
      case LlmError.ToolError(toolName, message) =>
        s"ToolError(tool=$toolName, message=$message)"
      case LlmError.ConfigError(message)         =>
        s"ConfigError(message=$message)"
      case LlmError.TurnLimitError(limit)        =>
        s"TurnLimitError(limit=${limit.getOrElse(-1)})"

  private def providerFor(cfg: llm4zio.core.LlmConfig): IO[LlmError, LlmService] =
    ZIO
      .attempt(buildProvider(cfg))
      .mapError(th => LlmError.ConfigError(Option(th.getMessage).getOrElse(th.toString)))

  private def buildProvider(cfg: llm4zio.core.LlmConfig): LlmService =
    cfg.provider match
      case llm4zio.core.LlmProvider.GeminiCli => llm4zio.providers.GeminiCliProvider.make(cfg, cliExecutor)
      case llm4zio.core.LlmProvider.GeminiApi => llm4zio.providers.GeminiApiProvider.make(cfg, httpClient)
      case llm4zio.core.LlmProvider.OpenAI    => llm4zio.providers.OpenAIProvider.make(cfg, httpClient)
      case llm4zio.core.LlmProvider.Anthropic => llm4zio.providers.AnthropicProvider.make(cfg, httpClient)
      case llm4zio.core.LlmProvider.LmStudio  => llm4zio.providers.LmStudioProvider.make(cfg, httpClient)
      case llm4zio.core.LlmProvider.Ollama    => llm4zio.providers.OllamaProvider.make(cfg, httpClient)
      case llm4zio.core.LlmProvider.OpenCode  => llm4zio.providers.OpenCodeProvider.make(cfg, httpClient)
      case llm4zio.core.LlmProvider.Mock      => llm4zio.providers.MockProvider.make(cfg)

  private def sanitizeOptional[A](value: Option[A]): Option[A] =
    try
      value match
        case Some(v) => Option(v)
        case _       => None
    catch
      case _: Throwable => None

  private def sanitizeString(value: String): Option[String] =
    Option(value).map(_.trim).filter(_.nonEmpty)

  private def ensureConversationTitle(
    conversationId: Long,
    firstUserMessage: String,
    now: Instant,
  ): IO[PersistenceError, Unit] =
    chatRepository
      .getConversation(conversationId)
      .flatMap {
        case None               => ZIO.unit
        case Some(conversation) =>
          val isMissing = conversation.title.trim.isEmpty
          if isMissing then
            ChatConversation.autoTitleFromFirstMessage(firstUserMessage) match
              case Some(generated) =>
                chatRepository.updateConversation(conversation.copy(title = generated, updatedAt = now))
              case None            =>
                ZIO.unit
          else ZIO.unit
      }

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
        PersistenceError.QueryFailed("llm_service", s"Parse error: $message")
      case LlmError.ToolError(toolName, message)  =>
        PersistenceError.QueryFailed("llm_service", s"Tool error ($toolName): $message")
      case LlmError.ConfigError(message)          =>
        PersistenceError.QueryFailed("llm_service", s"Configuration error: $message")
      case LlmError.TurnLimitError(limit)         =>
        PersistenceError.QueryFailed(
          "llm_service",
          s"Turn limit exceeded${limit.map(l => s" (limit: $l)").getOrElse("")}",
        )

  private def parseForm(req: Request): IO[PersistenceError, Map[String, String]] =
    req.body.asString
      .map { body =>
        body
          .split("&")
          .toList
          .flatMap { kv =>
            kv.split("=", 2).toList match
              case key :: value :: Nil => Some(urlDecode(key) -> urlDecode(value))
              case key :: Nil          => Some(urlDecode(key) -> "")
              case _                   => None
          }
          .toMap
      }
      .mapError(err => PersistenceError.QueryFailed("parseForm", err.getMessage))

  private def parseMultiForm(req: Request): IO[PersistenceError, Map[String, List[String]]] =
    req.body.asString
      .map { body =>
        body
          .split("&")
          .toList
          .flatMap {
            _.split("=", 2).toList match
              case key :: value :: Nil => Some(urlDecode(key) -> urlDecode(value))
              case key :: Nil          => Some(urlDecode(key) -> "")
              case _                   => None
          }
          .groupMap(_._1)(_._2)
      }
      .mapError(err => PersistenceError.QueryFailed("parseMultiForm", err.getMessage))

  private def required(form: Map[String, List[String]], key: String): IO[PersistenceError, String] =
    ZIO
      .fromOption(optional(form, key))
      .orElseFail(PersistenceError.QueryFailed("planner_form", s"Missing required field: $key"))

  private def optional(form: Map[String, List[String]], key: String): Option[String] =
    form.get(key).flatMap(_.headOption).map(_.trim).filter(_.nonEmpty)

  private def values(form: Map[String, List[String]], key: String): List[String] =
    form.getOrElse(key, Nil).map(_.trim)

  private def splitCsv(raw: String): List[String] =
    raw.split(",").toList.map(_.trim).filter(_.nonEmpty).distinct

  private def boardRedirect(issueIds: List[String]): String =
    val normalized = issueIds.map(_.trim).filter(_.nonEmpty).distinct
    if normalized.isEmpty then "/board?mode=list"
    else
      val query = URLEncoder.encode(normalized.mkString(","), StandardCharsets.UTF_8)
      s"/board?mode=list&plannerCreated=${normalized.size}&q=$query"

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)
