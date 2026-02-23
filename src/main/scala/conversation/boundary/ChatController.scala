package conversation.boundary

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant

import zio.*
import zio.http.*
import zio.json.*

import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import db.{ ChatRepository, PersistenceError, TaskRepository }
import gateway.control.{ ChannelRegistry, GatewayService, GatewayServiceError, MessageChannelError, SessionContext }
import gateway.entity.{ GatewayMessageRole as GatewayMessageRole, MessageDirection as GatewayMessageDirection, * }
import llm4zio.core.{ LlmError, LlmService, Streaming }
import models.*
import orchestration.control.{ AgentConfigResolver, IssueAssignmentOrchestrator }
import shared.ids.Ids.{ ConversationId, EventId }
import shared.web.{ ErrorHandlingMiddleware, HtmlViews, StreamAbortRegistry }

trait ChatController:
  def routes: Routes[Any, Response]

object ChatController:

  def routes: ZIO[ChatController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[ChatController](_.routes)

  val live
    : ZLayer[
      ChatRepository & LlmService & TaskRepository & IssueAssignmentOrchestrator & AgentConfigResolver &
        GatewayService & ChannelRegistry & StreamAbortRegistry & ActivityHub,
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
) extends ChatController:

  override val routes: Routes[Any, Response] = Routes(
    // Chat Conversations Web Views
    Method.GET / "chat"                                      -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          conversations <- chatRepository.listConversations(0, 20)
          enriched      <- enrichConversationsWithChannel(conversations)
          sessionMeta   <- buildSessionMetaMap(enriched)
        yield html(HtmlViews.chatDashboard(enriched, sessionMeta))
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
    Method.GET / "chat" / string("id")                       -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          convId       <- parseLongId("conversation", id)
          conversation <- chatRepository
                            .getConversation(convId)
                            .someOrFail(PersistenceError.NotFound("conversation", convId))
          sessionMeta  <- resolveConversationSessionMeta(id)
        yield html(HtmlViews.chatDetail(conversation, sessionMeta))
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
                           additionalMetadata = mention.metadata,
                         )
          _           <- routeThroughGateway(gatewayService.processInbound(userInbound))
          _           <- streamAssistantResponse(convId, content).forkDaemon
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
                       additionalMetadata = mention.metadata,
                     )
      _           <- routeThroughGateway(gatewayService.processInbound(userInbound))
      aiConfig    <- configResolver.resolveConfig("chat")
      llmResponse <- llmService
                       .execute(mention.content)
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
  ): UIO[Unit] =
    val effect =
      for
        _                          <- sendStreamEvent(conversationId, "chat-stream-start", "")
        pair                       <- Streaming.cancellable(llmService.executeStream(userContent))
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

  private def buildSessionMetaMap(
    conversations: List[ChatConversation]
  ): IO[PersistenceError, Map[String, ConversationSessionMeta]] =
    ZIO
      .foreach(conversations.flatMap(conv => sanitizeOptional(conv.id))) { id =>
        resolveConversationSessionMeta(id).map(meta => id -> meta)
      }
      .map(_.collect { case (id, Some(meta)) => id -> meta }.toMap)

  private def resolveConversationSessionMeta(
    conversationId: String
  ): IO[PersistenceError, Option[ConversationSessionMeta]] =
    parseLongId("conversation", conversationId).flatMap(chatRepository.getSessionContextByConversation).flatMap {
      case None       => ZIO.none
      case Some(link) =>
        parseSessionContext(link.contextJson).map { parsed =>
          Some(
            ConversationSessionMeta(
              channelName = sanitizeString(link.channelName).getOrElse("web"),
              sessionKey = sanitizeString(link.sessionKey).getOrElse("unknown"),
              linkedTaskRunId = parsed.flatMap(_.runId).map(_.toString),
              updatedAt = link.updatedAt,
            )
          )
        }
    }

  private def parseLongId(entity: String, raw: String): IO[PersistenceError, Long] =
    ZIO
      .fromOption(raw.toLongOption)
      .orElseFail(PersistenceError.QueryFailed(s"parse_$entity", s"Invalid $entity id: '$raw'"))

  private def parseSessionContext(raw: String): IO[PersistenceError, Option[SessionContext]] =
    ZIO
      .fromEither(raw.fromJson[SessionContext])
      .map(Some(_))
      .catchAll(err => ZIO.logWarning(s"invalid session context payload: $err").as(None))

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
