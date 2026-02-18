package gateway

import java.util.UUID

import zio.*
import zio.json.*

import agents.AgentRegistry
import gateway.models.NormalizedMessage
import gateway.telegram.{ IntentConversationState, IntentDecision, IntentParser }
import llm4zio.core.LlmService

enum GatewayServiceError:
  case Router(error: MessageRouterError)
  case QueueClosed

enum GatewayQueueCommand:
  case Inbound(message: NormalizedMessage)
  case Outbound(message: NormalizedMessage)

case class ChannelMetrics(
  inboundEnqueued: Long = 0L,
  outboundEnqueued: Long = 0L,
  inboundProcessed: Long = 0L,
  outboundProcessed: Long = 0L,
  failed: Long = 0L,
  lastActivityTs: Option[Long] = None,
) derives JsonCodec

case class GatewayMetricsSnapshot(
  enqueued: Long = 0L,
  processed: Long = 0L,
  failed: Long = 0L,
  chunkedMessages: Long = 0L,
  emittedChunks: Long = 0L,
  steeringForwarded: Long = 0L,
  perChannel: Map[String, ChannelMetrics] = Map.empty,
)

trait GatewayService:
  def enqueueInbound(message: NormalizedMessage): UIO[Unit]
  def enqueueOutbound(message: NormalizedMessage): UIO[Unit]

  def processInbound(message: NormalizedMessage): IO[GatewayServiceError, Unit]
  def processOutbound(message: NormalizedMessage): IO[GatewayServiceError, List[NormalizedMessage]]

  def metrics: UIO[GatewayMetricsSnapshot]

object GatewayService:
  def enqueueInbound(message: NormalizedMessage): ZIO[GatewayService, Nothing, Unit] =
    ZIO.serviceWithZIO[GatewayService](_.enqueueInbound(message))

  def enqueueOutbound(message: NormalizedMessage): ZIO[GatewayService, Nothing, Unit] =
    ZIO.serviceWithZIO[GatewayService](_.enqueueOutbound(message))

  def processInbound(message: NormalizedMessage): ZIO[GatewayService, GatewayServiceError, Unit] =
    ZIO.serviceWithZIO[GatewayService](_.processInbound(message))

  def processOutbound(message: NormalizedMessage): ZIO[GatewayService, GatewayServiceError, List[NormalizedMessage]] =
    ZIO.serviceWithZIO[GatewayService](_.processOutbound(message))

  def metrics: ZIO[GatewayService, Nothing, GatewayMetricsSnapshot] =
    ZIO.serviceWithZIO[GatewayService](_.metrics)

  val live: ZLayer[MessageRouter & AgentRegistry & LlmService, Nothing, GatewayService] =
    ZLayer.scoped {
      for
        router        <- ZIO.service[MessageRouter]
        agentRegistry <- ZIO.service[AgentRegistry]
        llmService    <- ZIO.service[LlmService]
        queue         <- Queue.unbounded[GatewayQueueCommand]
        metrics       <- Ref.make(GatewayMetricsSnapshot())
        intents       <- Ref.make(Map.empty[gateway.models.SessionKey, IntentConversationState])
        _             <- startWorker(queue, router, metrics, None).forkScoped
      yield GatewayServiceLive(queue, router, agentRegistry, llmService, metrics, None, intents)
    }

  val liveWithSteeringQueue: ZLayer[MessageRouter & Queue[NormalizedMessage] & AgentRegistry & LlmService, Nothing, GatewayService] =
    ZLayer.scoped {
      for
        router        <- ZIO.service[MessageRouter]
        agentRegistry <- ZIO.service[AgentRegistry]
        llmService    <- ZIO.service[LlmService]
        steeringQueue <- ZIO.service[Queue[NormalizedMessage]]
        queue         <- Queue.unbounded[GatewayQueueCommand]
        metrics       <- Ref.make(GatewayMetricsSnapshot())
        intents       <- Ref.make(Map.empty[gateway.models.SessionKey, IntentConversationState])
        _             <- startWorker(queue, router, metrics, Some(steeringQueue)).forkScoped
      yield GatewayServiceLive(queue, router, agentRegistry, llmService, metrics, Some(steeringQueue), intents)
    }

  private def startWorker(
    queue: Queue[GatewayQueueCommand],
    router: MessageRouter,
    metricsRef: Ref[GatewayMetricsSnapshot],
    steeringQueue: Option[Queue[NormalizedMessage]],
  ): UIO[Unit] =
    def markProcessed: UIO[Unit] =
      metricsRef.update(current => current.copy(processed = current.processed + 1))

    def markFailed(channelName: String, timestamp: Long): UIO[Unit] =
      metricsRef.update(current =>
        GatewayService.markChannelFailed(current.copy(failed = current.failed + 1), channelName, timestamp)
      )

    def forwardSteering(message: NormalizedMessage): UIO[Unit] =
      steeringQueue match
        case None    => ZIO.unit
        case Some(q) =>
          q.offer(message).unit *>
            metricsRef.update(current => current.copy(steeringForwarded = current.steeringForwarded + 1))

    def runCommand(command: GatewayQueueCommand): UIO[Unit] =
      command match
        case GatewayQueueCommand.Inbound(message)  =>
          (forwardSteering(message) *> router.routeInbound(message).mapError(GatewayServiceError.Router.apply))
            .tapError(err => ZIO.logWarning(s"gateway inbound processing failed: $err"))
            .foldZIO(
              _ => markFailed(message.channelName, message.timestamp.toEpochMilli),
              _ =>
                markProcessed *>
                  metricsRef.update(current =>
                    GatewayService.markInboundProcessed(current, message.channelName, message.timestamp.toEpochMilli)
                  ),
            )
        case GatewayQueueCommand.Outbound(message) =>
          val chunks       = ResponseChunker.chunkMessageForChannel(message)
          val markChunking =
            metricsRef.update { current =>
              if chunks.length > 1 then
                current.copy(
                  chunkedMessages = current.chunkedMessages + 1,
                  emittedChunks = current.emittedChunks + chunks.length.toLong,
                )
              else current
            }
          (forwardSteering(message) *>
            markChunking *>
            ZIO.foreachDiscard(chunks)(chunk => router.routeOutbound(chunk).mapError(GatewayServiceError.Router.apply)))
            .tapError(err => ZIO.logWarning(s"gateway outbound processing failed: $err"))
            .foldZIO(
              _ => markFailed(message.channelName, message.timestamp.toEpochMilli),
              _ =>
                markProcessed *>
                  metricsRef.update(current =>
                    GatewayService.markOutboundProcessed(current, message.channelName, message.timestamp.toEpochMilli)
                  ),
            )

    def loop: UIO[Unit] =
      queue.take.flatMap(runCommand).forever

    loop

  private def updateChannelMetrics(
    snapshot: GatewayMetricsSnapshot,
    channelName: String,
  )(update: ChannelMetrics => ChannelMetrics): GatewayMetricsSnapshot =
    val current = snapshot.perChannel.getOrElse(channelName, ChannelMetrics())
    snapshot.copy(perChannel = snapshot.perChannel.updated(channelName, update(current)))

  private[gateway] def markInboundEnqueued(
    snapshot: GatewayMetricsSnapshot,
    channelName: String,
    timestamp: Long,
  ): GatewayMetricsSnapshot =
    updateChannelMetrics(snapshot, channelName)(metrics =>
      metrics.copy(inboundEnqueued = metrics.inboundEnqueued + 1L, lastActivityTs = Some(timestamp))
    )

  private[gateway] def markOutboundEnqueued(
    snapshot: GatewayMetricsSnapshot,
    channelName: String,
    timestamp: Long,
  ): GatewayMetricsSnapshot =
    updateChannelMetrics(snapshot, channelName)(metrics =>
      metrics.copy(outboundEnqueued = metrics.outboundEnqueued + 1L, lastActivityTs = Some(timestamp))
    )

  private[gateway] def markInboundProcessed(
    snapshot: GatewayMetricsSnapshot,
    channelName: String,
    timestamp: Long,
  ): GatewayMetricsSnapshot =
    updateChannelMetrics(snapshot, channelName)(metrics =>
      metrics.copy(inboundProcessed = metrics.inboundProcessed + 1L, lastActivityTs = Some(timestamp))
    )

  private[gateway] def markOutboundProcessed(
    snapshot: GatewayMetricsSnapshot,
    channelName: String,
    timestamp: Long,
  ): GatewayMetricsSnapshot =
    updateChannelMetrics(snapshot, channelName)(metrics =>
      metrics.copy(outboundProcessed = metrics.outboundProcessed + 1L, lastActivityTs = Some(timestamp))
    )

  private[gateway] def markChannelFailed(
    snapshot: GatewayMetricsSnapshot,
    channelName: String,
    timestamp: Long,
  ): GatewayMetricsSnapshot =
    updateChannelMetrics(snapshot, channelName)(metrics =>
      metrics.copy(failed = metrics.failed + 1L, lastActivityTs = Some(timestamp))
    )

final case class GatewayServiceLive(
  queue: Queue[GatewayQueueCommand],
  router: MessageRouter,
  agentRegistry: AgentRegistry,
  llmService: LlmService,
  metricsRef: Ref[GatewayMetricsSnapshot],
  steeringQueue: Option[Queue[NormalizedMessage]],
  intentStateRef: Ref[Map[gateway.models.SessionKey, IntentConversationState]],
) extends GatewayService:

  override def enqueueInbound(message: NormalizedMessage): UIO[Unit] =
    queue.offer(GatewayQueueCommand.Inbound(message)).unit *>
      metricsRef.update(current =>
        GatewayService.markInboundEnqueued(
          current.copy(enqueued = current.enqueued + 1),
          message.channelName,
          message.timestamp.toEpochMilli,
        )
      )

  override def enqueueOutbound(message: NormalizedMessage): UIO[Unit] =
    queue.offer(GatewayQueueCommand.Outbound(message)).unit *>
      metricsRef.update(current =>
        GatewayService.markOutboundEnqueued(
          current.copy(enqueued = current.enqueued + 1),
          message.channelName,
          message.timestamp.toEpochMilli,
        )
      )

  override def processInbound(message: NormalizedMessage): IO[GatewayServiceError, Unit] =
    for
      _ <- steeringQueue match
             case None    => ZIO.unit
             case Some(q) =>
               q.offer(message).unit *>
                 metricsRef.update(current => current.copy(steeringForwarded = current.steeringForwarded + 1))
      _ <- router.routeInbound(message).mapError(GatewayServiceError.Router.apply)
      _ <- handleIntentRouting(message)
      _ <- metricsRef.update(current =>
             GatewayService.markInboundProcessed(
               current.copy(processed = current.processed + 1),
               message.channelName,
               message.timestamp.toEpochMilli,
             )
           )
    yield ()

  override def processOutbound(message: NormalizedMessage): IO[GatewayServiceError, List[NormalizedMessage]] =
    val chunks = ResponseChunker.chunkMessageForChannel(message)
    for
      _ <- steeringQueue match
             case None    => ZIO.unit
             case Some(q) =>
               q.offer(message).unit *>
                 metricsRef.update(current => current.copy(steeringForwarded = current.steeringForwarded + 1))
      _ <- ZIO.foreachDiscard(chunks)(chunk => router.routeOutbound(chunk).mapError(GatewayServiceError.Router.apply))
      _ <- metricsRef.update { current =>
             GatewayService.markOutboundProcessed(
               current.copy(
                 processed = current.processed + 1,
                 chunkedMessages = current.chunkedMessages + (if chunks.length > 1 then 1 else 0),
                 emittedChunks = current.emittedChunks + chunks.length.toLong,
               ),
               message.channelName,
               message.timestamp.toEpochMilli,
             )
           }
    yield chunks

  override def metrics: UIO[GatewayMetricsSnapshot] =
    metricsRef.get

  private def handleIntentRouting(message: NormalizedMessage): IO[GatewayServiceError, Unit] =
    if shouldParseIntent(message) then
      for
        current         <- intentStateRef.get.map(_.getOrElse(message.sessionKey, IntentConversationState()))
        availableAgents <- agentRegistry.getAllAgents
        decision        <- IntentParser.parse(message.content, current, availableAgents).provideEnvironment(
                             ZEnvironment(llmService)
                           )
        _       <- decision match
                     case IntentDecision.Route(agentName, rationale) =>
                       val text =
                         s"Routing request to `$agentName` ($rationale). " +
                           "Send another message if you want to switch agent."
                       sendAssistantReply(message, text, Some(agentName)) *>
                         updateIntentState(message, current.copy(pendingOptions = Nil, lastAgent = Some(agentName))) *>
                         executeRoutedAgentReply(
                           inbound = message,
                           selectedAgent = agentName,
                           skipExecution = current.pendingOptions.nonEmpty,
                         )
                     case IntentDecision.Clarify(question, options)  =>
                       val numbered = options.zipWithIndex.map { case (option, idx) => s"${idx + 1}. $option" }.mkString("\n")
                       val text     = s"$question\n$numbered"
                       sendAssistantReply(message, text, None) *>
                         updateIntentState(message, current.copy(pendingOptions = options))
                     case IntentDecision.Unknown                     =>
                       ZIO.unit
      yield ()
    else ZIO.unit

  private def shouldParseIntent(message: NormalizedMessage): Boolean =
    message.channelName == "telegram" &&
    message.direction == gateway.models.MessageDirection.Inbound &&
    message.role == gateway.models.MessageRole.User

  private def sendAssistantReply(
    inbound: NormalizedMessage,
    text: String,
    routedAgent: Option[String],
  ): IO[GatewayServiceError, Unit] =
    for
      now <- Clock.instant
      msg  = NormalizedMessage(
               id = s"intent:${now.toEpochMilli}:${UUID.randomUUID().toString.take(8)}",
               channelName = inbound.channelName,
               sessionKey = inbound.sessionKey,
               direction = gateway.models.MessageDirection.Outbound,
               role = gateway.models.MessageRole.Assistant,
               content = text,
               metadata = inbound.metadata ++ routedAgent.map(value => "intent.agent" -> value).toMap,
               timestamp = now,
             )
      _   <- router.routeOutbound(msg).mapError(GatewayServiceError.Router.apply)
    yield ()

  private def executeRoutedAgentReply(
    inbound: NormalizedMessage,
    selectedAgent: String,
    skipExecution: Boolean,
  ): IO[GatewayServiceError, Unit] =
    if skipExecution then ZIO.unit
    else
      llmService.execute(inbound.content).foldZIO(
        error =>
          sendAssistantReply(
            inbound,
            s"Agent `$selectedAgent` failed: ${error.toString}",
            Some(selectedAgent),
          ),
        response =>
          sendAssistantReply(
            inbound,
            response.content,
            Some(selectedAgent),
          ),
      )

  private def updateIntentState(
    inbound: NormalizedMessage,
    state: IntentConversationState,
  ): UIO[Unit] =
    intentStateRef.update { current =>
      val updatedHistory = (state.history :+ inbound.content).takeRight(20)
      current.updated(inbound.sessionKey, state.copy(history = updatedHistory))
    }
