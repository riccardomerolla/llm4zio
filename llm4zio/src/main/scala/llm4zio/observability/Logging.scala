package llm4zio.observability

import zio.*
import zio.json.*
import zio.stream.ZStream

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

import java.time.Instant

enum StructuredLogLevel derives JsonCodec:
  case Debug, Info, Error

case class StructuredLogEvent(
  level: StructuredLogLevel,
  message: String,
  correlationId: String,
  timestamp: Instant,
  fields: Map[String, String] = Map.empty,
) derives JsonCodec

trait StructuredLogSink:
  def publish(event: StructuredLogEvent): UIO[Unit]

object StructuredLogSink:
  val zioDefault: StructuredLogSink =
    new StructuredLogSink:
      override def publish(event: StructuredLogEvent): UIO[Unit] =
        val renderedFields = event.fields.map { case (k, v) => s"$k=$v" }.mkString(" ")
        event.level match
          case StructuredLogLevel.Debug => ZIO.logDebug(s"${event.message} correlation_id=${event.correlationId} $renderedFields")
          case StructuredLogLevel.Info  => ZIO.logInfo(s"${event.message} correlation_id=${event.correlationId} $renderedFields")
          case StructuredLogLevel.Error => ZIO.logError(s"${event.message} correlation_id=${event.correlationId} $renderedFields")

  def inMemory: UIO[(StructuredLogSink, UIO[List[StructuredLogEvent]])] =
    Ref.make(List.empty[StructuredLogEvent]).map { ref =>
      val sink = new StructuredLogSink:
        override def publish(event: StructuredLogEvent): UIO[Unit] =
          ref.update(current => (event :: current).take(5000))

      val read = ref.get.map(_.reverse)
      (sink, read)
    }

case class LoggingConfig(
  includePayloadsAtDebug: Boolean = true,
  redactLargePayloadThreshold: Int = 4096,
)

object ProductionLogging:
  def observed(
    service: LlmService,
    tracing: TracingService,
    metrics: MetricsCollector,
    sink: StructuredLogSink = StructuredLogSink.zioDefault,
    langfuse: Option[LangfuseClient] = None,
    config: LoggingConfig = LoggingConfig(),
  ): LlmService =
    new LlmService:
      override def execute(prompt: String): IO[LlmError, LlmResponse] =
        runObserved(
          operation = "execute",
          prompt = prompt,
          providerHint = None,
          modelHint = None,
          agent = None,
          runId = None,
          step = None,
          executeEffect = service.execute(prompt),
        )

      override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        ZStream.fromZIO(execute(prompt)).map(response =>
          LlmChunk(
            delta = response.content,
            finishReason = Some("stop"),
            usage = response.usage,
            metadata = response.metadata,
          )
        )

      override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
        val prompt = messages.map(message => s"${message.role.toString}: ${message.content}").mkString("\n")
        runObserved(
          operation = "executeWithHistory",
          prompt = prompt,
          providerHint = None,
          modelHint = None,
          agent = None,
          runId = None,
          step = None,
          executeEffect = service.executeWithHistory(messages),
        )

      override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
        ZStream.fromZIO(executeWithHistory(messages)).map(response =>
          LlmChunk(
            delta = response.content,
            finishReason = Some("stop"),
            usage = response.usage,
            metadata = response.metadata,
          )
        )

      override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
        for
          correlationId <- tracing.correlationId
          now <- Clock.instant
          _ <- sink.publish(
                 StructuredLogEvent(
                   level = StructuredLogLevel.Info,
                   message = "llm.executeWithTools.request",
                   correlationId = correlationId,
                   timestamp = now,
                   fields = Map(
                     "tool_count" -> tools.length.toString,
                     "operation" -> "executeWithTools",
                   ),
                 )
               )
          response <- service.executeWithTools(prompt, tools)
          completedAt <- Clock.instant
          _ <- sink.publish(
                 StructuredLogEvent(
                   level = StructuredLogLevel.Info,
                   message = "llm.executeWithTools.response",
                   correlationId = correlationId,
                   timestamp = completedAt,
                   fields = Map(
                     "tool_calls" -> response.toolCalls.length.toString,
                     "finish_reason" -> response.finishReason,
                     "operation" -> "executeWithTools",
                   ),
                 )
               )
        yield response

      override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
        service.executeStructured(prompt, schema)

      override def isAvailable: UIO[Boolean] =
        service.isAvailable

      private def runObserved(
        operation: String,
        prompt: String,
        providerHint: Option[String],
        modelHint: Option[String],
        agent: Option[String],
        runId: Option[String],
        step: Option[String],
        executeEffect: IO[LlmError, LlmResponse],
      ): IO[LlmError, LlmResponse] =
        tracing.withCorrelationId(None) {
          for
            correlationId <- tracing.correlationId
            provider = providerHint.getOrElse("unknown")
            model = modelHint.getOrElse("unknown")
            labels = RequestLabels(provider = provider, model = model, agent = agent, runId = runId, workflowStep = step)
            _ <- metrics.markRequestStarted(labels)
            startedAt <- Clock.nanoTime
            requestTime <- Clock.instant
            _ <- sink.publish(
                   StructuredLogEvent(
                     level = StructuredLogLevel.Info,
                     message = s"llm.$operation.request",
                     correlationId = correlationId,
                     timestamp = requestTime,
                     fields = Map(
                       "provider" -> provider,
                       "model" -> model,
                       "prompt_hash" -> TracingService.promptHash(prompt),
                     ) ++ debugPayloadFields(prompt, None),
                   )
                 )
            response <- tracing.inSpan(
                          name = s"llm.$operation",
                          attributes = TraceAttributes(
                            provider = Some(provider),
                            model = Some(model),
                            promptHash = Some(TracingService.promptHash(prompt)),
                            agentName = agent,
                            runId = runId,
                            workflowStep = step,
                          ),
                        )(executeEffect).either
            endedAt <- Clock.nanoTime
            latencyMs = ((endedAt - startedAt) / 1_000_000L).max(0L)
            output <- response match
                        case Right(success) =>
                          val usage = success.usage
                          val metric = RequestMetrics(
                            labels = labels,
                            tokenUsage = usage,
                            latencyMs = latencyMs,
                            success = true,
                          )
                          for
                            _ <- metrics.recordCompleted(metric)
                            completionAt <- Clock.instant
                            _ <- sink.publish(
                                   StructuredLogEvent(
                                     level = StructuredLogLevel.Info,
                                     message = s"llm.$operation.response",
                                     correlationId = correlationId,
                                     timestamp = completionAt,
                                     fields = Map(
                                       "provider" -> provider,
                                       "model" -> model,
                                       "latency_ms" -> latencyMs.toString,
                                       "tokens_total" -> usage.map(_.total.toString).getOrElse("0"),
                                       "success" -> "true",
                                     ) ++ debugPayloadFields(prompt, Some(success.content)),
                                   )
                                 )
                            _ <- ZIO.foreachDiscard(langfuse) { client =>
                                   client
                                     .track(
                                       LangfuseEvent(
                                         correlationId = correlationId,
                                         traceName = s"llm.$operation",
                                         input = truncate(prompt),
                                         output = Some(truncate(success.content)),
                                         metadata = Map(
                                           "provider" -> provider,
                                           "model" -> model,
                                           "latency_ms" -> latencyMs.toString,
                                         ),
                                         usage = usage.map(u => Map("prompt" -> u.prompt.toLong, "completion" -> u.completion.toLong, "total" -> u.total.toLong)),
                                         success = true,
                                       )
                                     )
                                     .ignore
                                 }
                          yield success

                        case Left(error)    =>
                          val metric = RequestMetrics(
                            labels = labels,
                            tokenUsage = None,
                            latencyMs = latencyMs,
                            success = false,
                            errorType = Some(error.getClass.getSimpleName),
                          )
                          for
                            _ <- metrics.recordCompleted(metric)
                            failureAt <- Clock.instant
                            _ <- sink.publish(
                                   StructuredLogEvent(
                                     level = StructuredLogLevel.Error,
                                     message = s"llm.$operation.error",
                                     correlationId = correlationId,
                                     timestamp = failureAt,
                                     fields = Map(
                                       "provider" -> provider,
                                       "model" -> model,
                                       "latency_ms" -> latencyMs.toString,
                                       "error" -> error.toString,
                                       "success" -> "false",
                                     ) ++ debugPayloadFields(prompt, None),
                                   )
                                 )
                            _ <- ZIO.foreachDiscard(langfuse) { client =>
                                   client
                                     .track(
                                       LangfuseEvent(
                                         correlationId = correlationId,
                                         traceName = s"llm.$operation",
                                         input = truncate(prompt),
                                         output = None,
                                         metadata = Map(
                                           "provider" -> provider,
                                           "model" -> model,
                                           "latency_ms" -> latencyMs.toString,
                                           "error" -> error.toString,
                                         ),
                                         success = false,
                                       )
                                     )
                                     .ignore
                                 }
                            failed <- ZIO.fail(error)
                          yield failed
          yield output
        }

      private def debugPayloadFields(prompt: String, response: Option[String]): Map[String, String] =
        if !config.includePayloadsAtDebug then Map.empty
        else
          Map("prompt" -> truncate(prompt)) ++ response.map(content => Map("response" -> truncate(content))).getOrElse(Map.empty)

      private def truncate(value: String): String =
        if value.length <= config.redactLargePayloadThreshold then value
        else s"${value.take(config.redactLargePayloadThreshold)}...<truncated>"
