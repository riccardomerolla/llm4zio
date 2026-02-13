package llm4zio.providers

import zio.*
import zio.json.*
import zio.stream.ZStream
import llm4zio.core.*
import llm4zio.tools.{AnyTool, JsonSchema}

// Ollama API models
case class OllamaGenerateRequest(
  model: String,
  prompt: String,
  stream: Boolean = false,
  format: Option[String] = None,
  options: Option[OllamaOptions] = None,
) derives JsonCodec

case class OllamaChatRequest(
  model: String,
  messages: List[OllamaMessage],
  stream: Boolean = false,
  format: Option[String] = None,
  options: Option[OllamaOptions] = None,
) derives JsonCodec

case class OllamaMessage(
  role: String,
  content: String,
) derives JsonCodec

case class OllamaOptions(
  temperature: Option[Double] = None,
  num_predict: Option[Int] = None,
) derives JsonCodec

case class OllamaGenerateResponse(
  model: String,
  response: String,
  done: Boolean,
  context: Option[List[Int]] = None,
  total_duration: Option[Long] = None,
  load_duration: Option[Long] = None,
  prompt_eval_count: Option[Int] = None,
  eval_count: Option[Int] = None,
) derives JsonCodec

case class OllamaChatResponse(
  model: String,
  message: OllamaMessage,
  done: Boolean,
  total_duration: Option[Long] = None,
  load_duration: Option[Long] = None,
  prompt_eval_count: Option[Int] = None,
  eval_count: Option[Int] = None,
) derives JsonCodec

case class OllamaModelInfo(
  name: String,
  modified_at: String,
  size: Long,
) derives JsonCodec

case class OllamaModelsResponse(
  models: List[OllamaModelInfo],
) derives JsonCodec

object OllamaProvider:
  def make(config: LlmConfig, httpClient: HttpClient): LlmService =
    new LlmService:
      override def execute(prompt: String): IO[LlmError, LlmResponse] =
        executeGenerate(prompt, None)

      override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        // Ollama supports streaming, but not implemented in this basic version
        ZStream.fromZIO(execute(prompt)).map { response =>
          LlmChunk(
            delta = response.content,
            finishReason = Some("stop"),
            usage = response.usage
          )
        }

      override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
        val ollamaMessages = messages.map { msg =>
          OllamaMessage(
            role = msg.role match
              case MessageRole.System    => "system"
              case MessageRole.User      => "user"
              case MessageRole.Assistant => "assistant"
              case MessageRole.Tool      => "user" // Ollama doesn't have tool role
            ,
            content = msg.content
          )
        }
        executeChatRequest(ollamaMessages, None)

      override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
        ZStream.fromZIO(executeWithHistory(messages)).map { response =>
          LlmChunk(
            delta = response.content,
            finishReason = Some("stop"),
            usage = response.usage
          )
        }

      override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
        // Ollama doesn't have native tool calling support yet
        ZIO.fail(LlmError.InvalidRequestError("Ollama provider does not support tool calling"))

      override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
        // Ollama supports JSON format mode
        val jsonPrompt = s"$prompt\n\nPlease respond with valid JSON matching the provided schema."
        for
          response <- executeGenerate(jsonPrompt, Some("json"))
          parsed   <- ZIO.fromEither(response.content.fromJson[A])
                        .mapError(err => LlmError.ParseError(s"Failed to parse structured response: $err", response.content))
        yield parsed

      override def isAvailable: UIO[Boolean] =
        config.baseUrl match
          case None          => ZIO.succeed(false)
          case Some(baseUrl) =>
            httpClient
              .get(
                url = s"${baseUrl.stripSuffix("/")}/api/tags",
                headers = Map.empty,
                timeout = config.timeout,
              )
              .as(true)
              .catchAll(_ => ZIO.succeed(false))

      private def executeGenerate(
        prompt: String,
        format: Option[String],
      ): IO[LlmError, LlmResponse] =
        for
          baseUrl <- ZIO.fromOption(config.baseUrl).orElseFail(
                       LlmError.ConfigError("Missing baseUrl for Ollama provider")
                     )
          request  = OllamaGenerateRequest(
                       model = config.model,
                       prompt = prompt,
                       stream = false,
                       format = format,
                       options = Some(OllamaOptions(
                         temperature = config.temperature,
                         num_predict = config.maxTokens
                       ))
                     )
          url      = s"${baseUrl.stripSuffix("/")}/api/generate"
          body    <- httpClient.postJson(
                       url = url,
                       body = request.toJson,
                       headers = Map.empty, // Ollama doesn't require auth headers
                       timeout = config.timeout,
                     )
          parsed  <- ZIO
                       .fromEither(body.fromJson[OllamaGenerateResponse])
                       .mapError(err => LlmError.ParseError(s"Failed to decode Ollama response: $err", body))
          usage    = extractGenerateUsage(parsed)
        yield LlmResponse(
          content = parsed.response,
          usage = usage,
          metadata = baseMetadata(parsed.model),
        )

      private def executeChatRequest(
        messages: List[OllamaMessage],
        format: Option[String],
      ): IO[LlmError, LlmResponse] =
        for
          baseUrl <- ZIO.fromOption(config.baseUrl).orElseFail(
                       LlmError.ConfigError("Missing baseUrl for Ollama provider")
                     )
          request  = OllamaChatRequest(
                       model = config.model,
                       messages = messages,
                       stream = false,
                       format = format,
                       options = Some(OllamaOptions(
                         temperature = config.temperature,
                         num_predict = config.maxTokens
                       ))
                     )
          url      = s"${baseUrl.stripSuffix("/")}/api/chat"
          body    <- httpClient.postJson(
                       url = url,
                       body = request.toJson,
                       headers = Map.empty,
                       timeout = config.timeout,
                     )
          parsed  <- ZIO
                       .fromEither(body.fromJson[OllamaChatResponse])
                       .mapError(err => LlmError.ParseError(s"Failed to decode Ollama chat response: $err", body))
          usage    = extractChatUsage(parsed)
        yield LlmResponse(
          content = parsed.message.content,
          usage = usage,
          metadata = baseMetadata(parsed.model),
        )

      private def extractGenerateUsage(response: OllamaGenerateResponse): Option[TokenUsage] =
        for
          promptTokens <- response.prompt_eval_count
          completionTokens <- response.eval_count
        yield TokenUsage(
          prompt = promptTokens,
          completion = completionTokens,
          total = promptTokens + completionTokens
        )

      private def extractChatUsage(response: OllamaChatResponse): Option[TokenUsage] =
        for
          promptTokens <- response.prompt_eval_count
          completionTokens <- response.eval_count
        yield TokenUsage(
          prompt = promptTokens,
          completion = completionTokens,
          total = promptTokens + completionTokens
        )

      private def baseMetadata(model: String): Map[String, String] =
        Map(
          "provider" -> "ollama",
          "model"    -> model,
        )

  val layer: ZLayer[LlmConfig & HttpClient, Nothing, LlmService] =
    ZLayer.fromFunction { (config: LlmConfig, httpClient: HttpClient) =>
      make(config, httpClient)
    }
