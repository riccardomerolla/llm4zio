package conversation.boundary

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant

import zio.*
import zio.http.*
import zio.json.*

import _root_.config.entity.{ AIProvider, AIProviderConfig }
import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import conversation.entity.api.*
import db.{ ChatRepository, PersistenceError, TaskRepository }
import gateway.control.{ ChannelRegistry, GatewayService, GatewayServiceError, MessageChannelError }
import gateway.entity.{ GatewayMessageRole as GatewayMessageRole, MessageDirection as GatewayMessageDirection, * }
import issues.entity.{ IssueReport, IssueWorkReport }
import llm4zio.core.{ ConversationThread, LlmError, LlmService, Streaming, ToolConversationManager }
import llm4zio.providers.{ GeminiCliExecutor, HttpClient }
import llm4zio.tools.ToolRegistry
import orchestration.control.{ AgentConfigResolver, IssueAssignmentOrchestrator }
import shared.errors.PersistenceError as WorkspacePersistenceError
import shared.ids.Ids.{ ConversationId, EventId, IssueId, ReportId }
import shared.web.*
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
        GeminiCliExecutor & WorkspaceRepository,
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
) extends ChatController:

  override val routes: Routes[Any, Response] = Routes(
    // Chat Conversations Web Views
    Method.GET / "chat"                                      -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          conversations <- chatRepository.listConversations(0, 80)
          enriched      <- enrichConversationsWithChannel(conversations)
          latestId       = enriched.sortBy(_.updatedAt)(Ordering[Instant].reverse).flatMap(conv =>
                             sanitizeOptional(conv.id)
                           ).headOption
        yield latestId match
          case Some(id) =>
            Response(
              status = Status.SeeOther,
              headers = Headers(Header.Custom("Location", s"/chat/$id")),
            )
          case None     =>
            Response(
              status = Status.SeeOther,
              headers = Headers(Header.Custom("Location", "/chat/new")),
            )
      }
    },
    Method.GET / "chat" / "new"                              -> handler { (_: Request) =>
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
    Method.GET / "chat" / "sidebar-nav"                      -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          conversations      <- chatRepository.listConversations(0, 80)
          enriched           <- enrichConversationsWithChannel(conversations)
          workspaceGroups    <- buildWorkspaceFolders(enriched)
          now                <- Clock.instant
          currentPath         = req.url.queryParams.getAll("path").headOption.flatMap(sanitizeString)
          currentConversation = currentPath.flatMap(parseCurrentConversationIdFromPath)
          nav                 = toLayoutWorkspaceNav(workspaceGroups, currentConversation, now)
        yield html(Layout.chatWorkspacesTree(nav).render)
      }
    },
    Method.POST / "chat"                                     -> handler { (req: Request) =>
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
    Method.POST / "chat" / "new"                             -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form          <- parseForm(req)
          rawContent    <- ZIO
                             .fromOption(form.get("content").map(_.trim).filter(_.nonEmpty))
                             .orElseFail(PersistenceError.QueryFailed("parseForm", "Missing content"))
          workspaceIdRaw = form.get("workspace_id").flatMap(sanitizeString)
          workspaceId   <- resolveSelectedWorkspaceId(workspaceIdRaw)
          now           <- Clock.instant
          conversation   = ChatConversation(
                             runId = None,
                             title = "",
                             description = workspaceId.map(workspaceMarkerDescription),
                             createdAt = now,
                             updatedAt = now,
                           )
          convId        <- chatRepository.createConversation(conversation)
          mention        = parsePreferredAgentMention(rawContent)
          content        = mention.content
          preferred     <- resolvePreferredAgent(convId, mention.metadata.get("preferredAgent"))
          _             <- chatRepository.addMessage(
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
          _             <- ensureConversationTitle(convId, content, now)
          userInbound   <- toGatewayMessage(
                             convId,
                             SenderType.User,
                             content,
                             None,
                             GatewayMessageDirection.Inbound,
                             additionalMetadata = withPreferredAgentMetadata(mention.metadata, preferred),
                           )
          _             <- routeThroughGateway(gatewayService.processInbound(userInbound))
          _             <- streamAssistantResponse(convId, content, preferred).forkDaemon
        yield Response(
          status = Status.SeeOther,
          headers = Headers(Header.Custom("Location", s"/chat/$convId")),
        )
      }
    },
    Method.GET / "chat" / string("id")                       -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId           <- parseLongId("conversation", id)
          conversation     <- chatRepository
                                .getConversation(convId)
                                .someOrFail(PersistenceError.NotFound("conversation", convId))
          sessionMeta      <- resolveConversationSessionMeta(id)
          runMeta          <- resolveRunSessionMeta(conversation)
          detailContext    <- resolveChatDetailContext(conversation, sessionMeta)
          allConversations <- chatRepository.listConversations(0, 80)
          enrichedAll      <- enrichConversationsWithChannel(allConversations)
          workspaceGroups  <- buildWorkspaceFolders(enrichedAll)
        yield html(HtmlViews.chatDetail(conversation, sessionMeta, runMeta, workspaceGroups, detailContext))
      }
    },
    Method.GET / "chat" / string("id") / "messages"          -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId   <- parseLongId("conversation", id)
          messages <- chatRepository.getMessages(convId)
        yield html(HtmlViews.chatMessagesFragment(messages))
      }
    },
    Method.POST / "chat" / string("id") / "messages"         -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId      <- parseLongId("conversation", id)
          form        <- parseForm(req)
          rawContent  <- ZIO
                           .fromOption(form.get("content").map(_.trim).filter(_.nonEmpty))
                           .orElseFail(PersistenceError.QueryFailed("parseForm", "Missing content"))
          mention      = parsePreferredAgentMention(rawContent)
          content      = mention.content
          preferred   <- resolvePreferredAgent(convId, mention.metadata.get("preferredAgent"))
          now         <- Clock.instant
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
          _           <- activityHub.publish(
                           ActivityEvent(
                             id = EventId.generate,
                             eventType = ActivityEventType.MessageSent,
                             source = "chat",
                             conversationId = Some(ConversationId(id)),
                             summary = s"Message sent in conversation #$convId",
                             createdAt = now,
                           )
                         )
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
          messages    <- chatRepository.getMessages(convId)
        yield html(HtmlViews.chatMessagesFragment(messages))
      }
    },
    // Abort streaming
    Method.POST / "api" / "chat" / string("id") / "abort"    -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        parseLongId("conversation", id).flatMap(streamAbortRegistry.abort).map { aborted =>
          Response.json(Map("aborted" -> aborted.toString).toJson)
        }
      }
    },
    // Chat API Endpoints
    Method.POST / "api" / "chat"                             -> handler { (req: Request) =>
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
                           .someOrFail(PersistenceError.NotFound("conversation", convId))
        yield Response.json(created.toJson)
      }
    },
    Method.GET / "api" / "chat" / string("id")               -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId       <- parseLongId("conversation", id)
          conversation <- chatRepository
                            .getConversation(convId)
                            .someOrFail(PersistenceError.NotFound("conversation", convId))
        yield Response.json(conversation.toJson)
      }
    },
    Method.POST / "api" / "chat" / string("id") / "messages" -> handler { (id: String, req: Request) =>
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
    Method.GET / "api" / "chat" / string("id") / "messages"  -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        val since = req.queryParam("since").flatMap(s => scala.util.Try(Instant.parse(s)).toOption)
        for
          convId   <- parseLongId("conversation", id)
          messages <-
            if since.isDefined then chatRepository.getMessagesSince(convId, since.get)
            else chatRepository.getMessages(convId)
        yield Response.json(messages.toJson)
      }
    },
    Method.GET / "api" / "sessions"                          -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        listChatSessions.map(sessions => Response.json(sessions.toJson))
      }
    },
    Method.GET / "api" / "sessions" / string("id")           -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        val decoded = urlDecode(id)
        for
          session <- getChatSession(decoded)
        yield Response.json(session.toJson)
      }
    },
    Method.DELETE / "api" / "sessions" / string("id")        -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        val decoded = urlDecode(id)
        for
          _ <- endSession(decoded)
        yield Response.json(SessionDeleteResponse(deleted = true, sessionId = decoded).toJson)
      }
    },
    Method.DELETE / "api" / "conversations" / string("id")   -> handler { (id: String, _: Request) =>
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
                       .someOrFail(PersistenceError.NotFound("conversation", conversationId))
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
                                        .someOrFail(PersistenceError.NotFound("conversation", conversationId))
        _                          <- chatRepository.updateConversation(conv.copy(updatedAt = now))
      yield ()
    effect.catchAll(err => ZIO.logWarning(s"streaming response failed for conversation $conversationId: $err"))

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
    sanitizeOptional(conversation.runId).flatMap(_.toLongOption) match
      case None        =>
        ZIO.succeed(ChatDetailContext.empty.copy(memorySessionId = sessionMeta.map(_.sessionKey)))
      case Some(runId) =>
        migrationRepository.getReportsByTask(runId).map { reports =>
          val graphReports = reports.filter(report => report.reportType.trim.equalsIgnoreCase("graph"))
          val proofOfWork  = toSyntheticProofOfWork(conversation, runId, reports)
          ChatDetailContext(
            proofOfWork = proofOfWork,
            reports = reports,
            graphReports = graphReports,
            memorySessionId = sessionMeta.map(_.sessionKey),
          )
        }

  private def toSyntheticProofOfWork(
    conversation: ChatConversation,
    runId: Long,
    reports: List[db.TaskReportRow],
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

  private def workspaceMarkerDescription(workspaceId: String): String =
    s"workspace:$workspaceId"

  private def parseWorkspaceMarkerDescription(raw: Option[String]): Option[String] =
    raw.flatMap { description =>
      sanitizeString(description).flatMap { value =>
        val Prefix = "workspace:"
        if value.startsWith(Prefix) then sanitizeString(value.drop(Prefix.length))
        else None
      }
    }

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
                           val folderId =
                             sanitizeOptional(conversation.runId)
                               .flatMap(runIdToWorkspace.get)
                               .orElse(parseWorkspaceMarkerDescription(conversation.description).filter(workspaceById.contains))
                               .getOrElse("chat")
                           val existing = acc.getOrElse(folderId, Nil)
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
                chats = chats.sortBy(_.updatedAt)(Ordering[Instant].reverse),
              )
          }
      chatFolder       = grouped.get("chat").map(chats =>
                           ChatView.ChatWorkspaceFolder(
                             id = "chat",
                             label = "Chat",
                             chats = chats.sortBy(_.updatedAt)(Ordering[Instant].reverse),
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
          .sortBy(_.updatedAt)(Ordering[java.time.Instant].reverse)
          .take(80)
          .map { chat =>
            val conversationId = sanitizeOptional(chat.id).getOrElse("unknown")
            Layout.ChatNavItem(
              conversationId = conversationId,
              title = sanitizeString(chat.title).getOrElse("Untitled chat"),
              href = s"/chat/$conversationId",
              active = currentConversationId.contains(conversationId),
            )
          }
        Layout.ChatWorkspaceGroup(
          id = folder.id,
          label = folder.label,
          chats = chats,
          expanded = chats.exists(_.active) || folder.id == "chat",
        )
      },
      showNewChat = true,
      renderedAt = renderedAt,
    )

  private def parseCurrentConversationIdFromPath(path: String): Option[String] =
    if path.startsWith("/chat/") then
      val id = path.stripPrefix("/chat/").takeWhile(_ != '/').takeWhile(_ != '?')
      sanitizeString(id)
    else None

  private def listChatSessions: IO[PersistenceError, List[ChatSession]] =
    for
      links         <- chatRepository.listSessionContextStates
      conversations <- chatRepository.listConversations(0, Int.MaxValue)
      convById       = conversations.flatMap(conversation => safeConversationId(conversation).map(_ -> conversation)).toMap
      sessions      <- ZIO.foreach(links)(buildChatSession(_, convById))
    yield sessions
      .filterNot(_.state.equalsIgnoreCase("closed"))
      .sortBy(_.lastActivity)(Ordering[Instant].reverse)

  private def buildChatSession(
    link: StoredSessionContextLink,
    convById: Map[Long, ChatConversation],
  ): UIO[ChatSession] =
    val context               = link.context
    val conversationFromStore = safeOption(context.conversationId) match
      case Some(conversationId) => convById.get(conversationId)
      case None                 => None
    val sessionId             = s"${link.channelName.trim}:${link.sessionKey.trim}"
    val runIdFromConversation = conversationFromStore match
      case Some(conversation) => safeLongFromStringOption(safeOption(conversation.runId))
      case None               => None
    val resolvedRunId         = safeOption(context.runId).orElse(runIdFromConversation)
    val resolvedState         = conversationFromStore.map(_.status).getOrElse("active")
    val resolvedCount         = conversationFromStore.map(_.messages.length).getOrElse(0)
    val resolvedConversation  =
      safeOption(context.conversationId).orElse {
        conversationFromStore match
          case Some(conversation) => safeConversationId(conversation)
          case None               => None
      }
    ZIO.succeed(
      ChatSession(
        sessionId = sessionId,
        channel = sanitizeString(link.channelName).getOrElse("unknown"),
        sessionKey = link.sessionKey,
        agentName = resolveAgentName(context.metadata),
        messageCount = resolvedCount,
        lastActivity = link.updatedAt,
        state = resolvedState,
        conversationId = resolvedConversation,
        runId = resolvedRunId,
      )
    )

  private def safeOption[A](value: Option[A]): Option[A] =
    try
      value match
        case Some(inner) => Option(inner)
        case None        => None
    catch
      case _: Throwable => None

  private def safeLongFromStringOption(value: Option[String]): Option[Long] =
    safeOption(value) match
      case Some(raw) => raw.toLongOption
      case None      => None

  private def safeConversationId(conversation: ChatConversation): Option[Long] =
    Option(conversation) match
      case Some(conv) => safeLongFromStringOption(safeOption(conv.id))
      case None       => None

  private def getChatSession(sessionId: String): IO[PersistenceError, ChatSession] =
    listChatSessions.flatMap { sessions =>
      sessions
        .find(_.sessionId == sessionId)
        .fold[IO[PersistenceError, ChatSession]](
          ZIO.fail(PersistenceError.QueryFailed("get_session", s"Session not found: $sessionId"))
        )(ZIO.succeed(_))
    }

  private def endSession(sessionId: String): IO[PersistenceError, Unit] =
    for
      ids      <- parseSessionId(sessionId)
      (ch, key) = ids
      now      <- Clock.instant
      context  <- chatRepository.getSessionContextState(ch, key)
      _        <- ZIO.foreachDiscard(context.flatMap(_.conversationId)) { conversationId =>
                    chatRepository.getConversation(conversationId).flatMap {
                      case Some(conversation) =>
                        chatRepository.updateConversation(conversation.copy(status = "closed", updatedAt = now))
                      case None               => ZIO.unit
                    }
                  }
      _        <- chatRepository.deleteSessionContext(ch, key)
      _        <- channelRegistry.get(ch).flatMap(_.close(SessionKey(ch, key))).catchAll(_ => ZIO.unit)
    yield ()

  private def parseSessionId(sessionId: String): IO[PersistenceError, (String, String)] =
    val normalized = sessionId.trim
    val idx        = normalized.indexOf(':')
    if idx <= 0 || idx >= normalized.length - 1 then
      ZIO.fail(PersistenceError.QueryFailed("parse_session_id", s"Invalid session id '$sessionId'"))
    else
      val channel    = normalized.take(idx).trim
      val sessionKey = normalized.drop(idx + 1).trim
      if channel.isEmpty || sessionKey.isEmpty then
        ZIO.fail(PersistenceError.QueryFailed("parse_session_id", s"Invalid session id '$sessionId'"))
      else ZIO.succeed((channel, sessionKey))

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
                  ) *> llmService.execute(prompt)
                )
            case Left(err)     =>
              ZIO.logWarning(s"chat agent override resolution failed for '$name': $err; using global provider") *>
                llmService.execute(prompt)
          }
      case None       =>
        llmService.execute(prompt)

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

  private def executeWithConfig(config: AIProviderConfig, prompt: String): IO[LlmError, llm4zio.core.LlmResponse] =
    fallbackConfigs(config)
      .foldLeft[IO[LlmError, llm4zio.core.LlmResponse]](ZIO.fail(LlmError.ConfigError("No LLM provider configured"))) {
        (acc, cfg) => acc.orElse(providerFor(cfg).flatMap(_.execute(prompt)))
      }

  private def executeStreamWithConfig(
    config: AIProviderConfig,
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

  private def fallbackConfigs(primary: AIProviderConfig): List[llm4zio.core.LlmConfig] =
    val primaryLlm = aiConfigToLlmConfig(primary)
    val fallback   = primary.fallbackChain.models.map { ref =>
      aiConfigToLlmConfig(
        AIProviderConfig.withDefaults(
          primary.copy(
            provider = ref.provider.getOrElse(primary.provider),
            model = ref.modelId,
          )
        )
      )
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

  private def aiConfigToLlmConfig(aiConfig: AIProviderConfig): llm4zio.core.LlmConfig =
    llm4zio.core.LlmConfig(
      provider = aiProviderToLlmProvider(aiConfig.provider),
      model = aiConfig.model,
      baseUrl = aiConfig.baseUrl,
      apiKey = aiConfig.apiKey,
      timeout = aiConfig.timeout,
      maxRetries = aiConfig.maxRetries,
      requestsPerMinute = aiConfig.requestsPerMinute,
      burstSize = aiConfig.burstSize,
      acquireTimeout = aiConfig.acquireTimeout,
      temperature = aiConfig.temperature,
      maxTokens = aiConfig.maxTokens,
    )

  private def aiProviderToLlmProvider(aiProvider: AIProvider): llm4zio.core.LlmProvider =
    aiProvider match
      case AIProvider.GeminiCli => llm4zio.core.LlmProvider.GeminiCli
      case AIProvider.GeminiApi => llm4zio.core.LlmProvider.GeminiApi
      case AIProvider.OpenAi    => llm4zio.core.LlmProvider.OpenAI
      case AIProvider.Anthropic => llm4zio.core.LlmProvider.Anthropic
      case AIProvider.LmStudio  => llm4zio.core.LlmProvider.LmStudio
      case AIProvider.Ollama    => llm4zio.core.LlmProvider.Ollama
      case AIProvider.OpenCode  => llm4zio.core.LlmProvider.OpenCode

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

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)
