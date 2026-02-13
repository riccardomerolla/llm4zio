package llm4zio.providers

import zio.*
import zio.json.*
import zio.stream.ZStream
import llm4zio.core.*
import llm4zio.tools.{AnyTool, JsonSchema}

/** LM Studio Provider - OpenAI-compatible local LLM server
  *
  * LM Studio provides an OpenAI-compatible API for local model execution.
  * It uses the same request/response format as OpenAI but runs locally
  * and doesn't require an API key.
  *
  * Default endpoint: http://localhost:1234/v1
  */
object LmStudioProvider:
  def make(config: LlmConfig, httpClient: HttpClient): LlmService =
    new LlmService:
      override def execute(prompt: String): IO[LlmError, LlmResponse] =
        executeRequest(List(ChatMessage(role = "user", content = prompt)), None)

      override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        // LM Studio supports streaming, but not implemented in this basic version
        ZStream.fromZIO(execute(prompt)).map { response =>
          LlmChunk(
            delta = response.content,
            finishReason = Some("stop"),
            usage = response.usage
          )
        }

      override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
        val chatMessages = messages.map { msg =>
          ChatMessage(
            role = msg.role match
              case MessageRole.System    => "system"
              case MessageRole.User      => "user"
              case MessageRole.Assistant => "assistant"
              case MessageRole.Tool      => "tool"
            ,
            content = msg.content
          )
        }
        executeRequest(chatMessages, None)

      override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
        ZStream.fromZIO(executeWithHistory(messages)).map { response =>
          LlmChunk(
            delta = response.content,
            finishReason = Some("stop"),
            usage = response.usage
          )
        }

      override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
        // LM Studio supports tool calling if the underlying model does
        // However, not all models support this, so we fail for now
        ZIO.fail(LlmError.InvalidRequestError("LmStudio provider does not yet support tool calling in this implementation"))

      override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
        // LM Studio supports JSON mode similar to OpenAI
        val responseFormat = Some(ResponseFormat(
          `type` = "json_schema",
          json_schema = Some(JsonSchemaSpec(
            name = "response",
            schema = schema
          ))
        ))

        for
          response <- executeRequest(List(ChatMessage(role = "user", content = prompt)), responseFormat)
          parsed   <- ZIO.fromEither(response.content.fromJson[A])
                        .mapError(err => LlmError.ParseError(s"Failed to parse structured response: $err", response.content))
        yield parsed

      override def isAvailable: UIO[Boolean] =
        config.baseUrl match
          case None          => ZIO.succeed(false)
          case Some(baseUrl) =>
            httpClient
              .get(
                url = s"${baseUrl.stripSuffix("/")}/models",
                headers = Map.empty,
                timeout = config.timeout,
              )
              .as(true)
              .catchAll(_ => ZIO.succeed(false))

      private def executeRequest(
        messages: List[ChatMessage],
        responseFormat: Option[ResponseFormat],
      ): IO[LlmError, LlmResponse] =
        for
          baseUrl <- ZIO.fromOption(config.baseUrl).orElseFail(
                       LlmError.ConfigError("Missing baseUrl for LmStudio provider")
                     )
          request  = ChatCompletionRequest(
                       model = config.model,
                       messages = messages,
                       temperature = config.temperature.orElse(Some(0.7)),
                       max_tokens = config.maxTokens,
                       max_completion_tokens = None,
                       stream = Some(false),
                       response_format = responseFormat,
                     )
          url      = s"${baseUrl.stripSuffix("/")}/chat/completions"
          body    <- httpClient.postJson(
                       url = url,
                       body = request.toJson,
                       headers = authHeaders, // May be empty for local LM Studio
                       timeout = config.timeout,
                     )
          parsed  <- ZIO
                       .fromEither(body.fromJson[ChatCompletionResponse])
                       .mapError(err => LlmError.ParseError(s"Failed to decode LmStudio response: $err", body))
          content <- extractContent(parsed)
          usage    = extractUsage(parsed)
        yield LlmResponse(
          content = content,
          usage = usage,
          metadata = baseMetadata(parsed),
        )

      private def authHeaders: Map[String, String] =
        // LM Studio doesn't require API key, but if provided, use it
        config.apiKey.map(key => Map("Authorization" -> s"Bearer $key")).getOrElse(Map.empty)

      private def extractContent(response: ChatCompletionResponse): IO[LlmError, String] =
        val content =
          for
            choice  <- response.choices.headOption
            message <- choice.message
            text     = message.content.trim
            if text.nonEmpty
          yield text

        ZIO.fromOption(content)
          .orElseFail(LlmError.ParseError(
            "LmStudio response missing choices[0].message.content",
            response.toJson
          ))

      private def extractUsage(response: ChatCompletionResponse): Option[TokenUsage] =
        response.usage.map { u =>
          TokenUsage(
            prompt = u.prompt_tokens.getOrElse(0),
            completion = u.completion_tokens.getOrElse(0),
            total = u.total_tokens.getOrElse(0)
          )
        }

      private def baseMetadata(response: ChatCompletionResponse): Map[String, String] =
        val base = Map(
          "provider" -> "lmstudio",
          "model"    -> config.model,
        )

        val idMeta = response.id.map(id => Map("id" -> id)).getOrElse(Map.empty)
        val modelMeta = response.model.map(m => Map("response_model" -> m)).getOrElse(Map.empty)

        base ++ idMeta ++ modelMeta

  val layer: ZLayer[LlmConfig & HttpClient, Nothing, LlmService] =
    ZLayer.fromFunction { (config: LlmConfig, httpClient: HttpClient) =>
      make(config, httpClient)
    }
