package llm4zio.providers

import zio.*
import zio.json.*
import zio.stream.ZStream
import llm4zio.core.*
import llm4zio.tools.{AnyTool, JsonSchema}

object AnthropicProvider:
  def make(config: LlmConfig, httpClient: HttpClient): LlmService =
    new LlmService:
      override def execute(prompt: String): IO[LlmError, LlmResponse] =
        executeRequest(List(ChatMessage(role = "user", content = prompt)), None)

      override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        // Anthropic supports streaming, but not implemented in this basic version
        ZStream.fromZIO(execute(prompt)).map { response =>
          LlmChunk(
            delta = response.content,
            finishReason = Some("stop"),
            usage = response.usage
          )
        }

      override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
        // Separate system messages from user/assistant messages
        val systemMsg = messages.find(_.role == MessageRole.System).map(_.content)
        val chatMessages = messages
          .filter(_.role != MessageRole.System)
          .map { msg =>
            ChatMessage(
              role = msg.role match
                case MessageRole.User      => "user"
                case MessageRole.Assistant => "assistant"
                case _                     => "user" // fallback
              ,
              content = msg.content
            )
          }
        executeRequest(chatMessages, systemMsg)

      override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
        ZStream.fromZIO(executeWithHistory(messages)).map { response =>
          LlmChunk(
            delta = response.content,
            finishReason = Some("stop"),
            usage = response.usage
          )
        }

      override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
        // Anthropic supports tool calling, but not implemented in this basic version
        ZIO.fail(LlmError.InvalidRequestError("Anthropic provider does not yet support tool calling in this implementation"))

      override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
        // Anthropic doesn't have native JSON schema support like OpenAI,
        // so we ask for JSON and parse it
        val jsonPrompt = s"$prompt\n\nPlease respond with valid JSON matching the provided schema."
        for
          response <- execute(jsonPrompt)
          parsed   <- ZIO.fromEither(response.content.fromJson[A])
                        .mapError(err => LlmError.ParseError(s"Failed to parse structured response: $err", response.content))
        yield parsed

      override def isAvailable: UIO[Boolean] =
        execute("health check").fold(_ => false, _ => true)

      private def executeRequest(
        messages: List[ChatMessage],
        systemMessage: Option[String],
      ): IO[LlmError, LlmResponse] =
        for
          baseUrl <- ZIO.fromOption(config.baseUrl).orElseFail(
                       LlmError.ConfigError("Missing baseUrl for Anthropic provider")
                     )
          apiKey  <- ZIO.fromOption(config.apiKey).orElseFail(
                       LlmError.AuthenticationError("Missing API key for Anthropic provider")
                     )
          request  = AnthropicRequest(
                       model = config.model,
                       max_tokens = config.maxTokens.getOrElse(4096),
                       messages = messages,
                       temperature = config.temperature,
                       system = systemMessage,
                     )
          url      = s"${baseUrl.stripSuffix("/")}/messages"
          body    <- httpClient.postJson(
                       url = url,
                       body = request.toJson,
                       headers = authHeaders(apiKey),
                       timeout = config.timeout,
                     )
          parsed  <- ZIO
                       .fromEither(body.fromJson[AnthropicResponse])
                       .mapError(err => LlmError.ParseError(s"Failed to decode Anthropic response: $err", body))
          content <- extractContent(parsed)
          usage    = extractUsage(parsed)
        yield LlmResponse(
          content = content,
          usage = usage,
          metadata = baseMetadata(parsed),
        )

      private def authHeaders(apiKey: String): Map[String, String] =
        Map(
          "x-api-key" -> apiKey,
          "anthropic-version" -> "2023-06-01"
        )

      private def extractContent(response: AnthropicResponse): IO[LlmError, String] =
        val content =
          for
            block <- response.content.headOption
            text  <- block.text
            value  = text.trim
            if value.nonEmpty
          yield value

        ZIO.fromOption(content)
          .orElseFail(LlmError.ParseError(
            "Anthropic response missing content[0].text",
            response.toJson
          ))

      private def extractUsage(response: AnthropicResponse): Option[TokenUsage] =
        response.usage.map { u =>
          val inputTokens = u.input_tokens.getOrElse(0)
          val outputTokens = u.output_tokens.getOrElse(0)
          TokenUsage(
            prompt = inputTokens,
            completion = outputTokens,
            total = inputTokens + outputTokens
          )
        }

      private def baseMetadata(response: AnthropicResponse): Map[String, String] =
        val base = Map(
          "provider" -> "anthropic",
          "model"    -> config.model,
        )

        val idMeta = response.id.map(id => Map("id" -> id)).getOrElse(Map.empty)
        val modelMeta = response.model.map(m => Map("response_model" -> m)).getOrElse(Map.empty)
        val stopReason = response.stop_reason.map(r => Map("stop_reason" -> r)).getOrElse(Map.empty)

        base ++ idMeta ++ modelMeta ++ stopReason

  val layer: ZLayer[LlmConfig & HttpClient, Nothing, LlmService] =
    ZLayer.fromFunction { (config: LlmConfig, httpClient: HttpClient) =>
      make(config, httpClient)
    }
