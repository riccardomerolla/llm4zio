package llm4zio.observability

import zio.*
import zio.json.*
import zio.json.ast.Json

import llm4zio.providers.HttpClient
import llm4zio.core.LlmError

enum LangfuseError derives JsonCodec:
  case Disabled
  case Transport(message: String)
  case InvalidConfig(message: String)

case class LangfuseConfig(
  baseUrl: String,
  publicKey: String,
  secretKey: String,
  enabled: Boolean = true,
  timeout: Duration = 10.seconds,
) derives JsonCodec

case class LangfuseEvent(
  correlationId: String,
  traceName: String,
  input: String,
  output: Option[String],
  metadata: Map[String, String] = Map.empty,
  tags: List[String] = List.empty,
  usage: Option[Map[String, Long]] = None,
  costUsd: Option[Double] = None,
  success: Boolean = true,
) derives JsonCodec

trait LangfuseClient:
  def track(event: LangfuseEvent): IO[LangfuseError, Unit]

object LangfuseClient:
  val disabled: LangfuseClient =
    new LangfuseClient:
      override def track(event: LangfuseEvent): IO[LangfuseError, Unit] = ZIO.unit

  def http(config: LangfuseConfig, httpClient: HttpClient): LangfuseClient =
    new LangfuseClient:
      override def track(event: LangfuseEvent): IO[LangfuseError, Unit] =
        if !config.enabled then ZIO.unit
        else if config.baseUrl.trim.isEmpty then ZIO.fail(LangfuseError.InvalidConfig("Langfuse baseUrl must be non-empty"))
        else
          val body = Json.Obj(
            "name" -> Json.Str(event.traceName),
            "input" -> Json.Str(event.input),
            "output" -> event.output.map(Json.Str(_)).getOrElse(Json.Null),
            "metadata" -> Json.Obj(event.metadata.toList.map { case (k, v) => k -> Json.Str(v) }*),
            "tags" -> Json.Arr(Chunk.fromIterable(event.tags.map(Json.Str(_)))),
            "sessionId" -> Json.Str(event.correlationId),
            "level" -> Json.Str(if event.success then "DEFAULT" else "ERROR"),
          )

          httpClient
            .postJson(
              url = s"${config.baseUrl.stripSuffix("/")}/api/public/traces",
              body = body.toJson,
              headers = Map(
                "Authorization" -> s"Basic ${authHeader(config)}",
                "Content-Type" -> "application/json",
              ),
              timeout = config.timeout,
            )
            .mapError {
              case LlmError.ProviderError(message, _)    => LangfuseError.Transport(message)
              case LlmError.TimeoutError(duration)       => LangfuseError.Transport(s"Langfuse timeout after ${duration.toMillis}ms")
              case LlmError.InvalidRequestError(message) => LangfuseError.Transport(message)
              case LlmError.AuthenticationError(message) => LangfuseError.Transport(message)
              case LlmError.ParseError(message, _)       => LangfuseError.Transport(message)
              case LlmError.RateLimitError(_)            => LangfuseError.Transport("Langfuse API rate limited")
              case LlmError.ConfigError(message)         => LangfuseError.InvalidConfig(message)
              case LlmError.ToolError(tool, message)     => LangfuseError.Transport(s"Unexpected tool error ($tool): $message")
            }
            .unit

  private def authHeader(config: LangfuseConfig): String =
    val credentials = s"${config.publicKey}:${config.secretKey}"
    java.util.Base64.getEncoder.encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8))
