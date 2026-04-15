package llm4zio.providers

import zio.*
import zio.json.*
import zio.stream.ZStream

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object AnthropicProvider:
  def make(config: LlmConfig, httpClient: HttpClient): ApiConnector =
    new ApiConnector:
      override def id: ConnectorId = ConnectorId.Anthropic

      override def healthCheck: IO[LlmError, HealthStatus] =
        val start = java.lang.System.nanoTime()
        isAvailable.map { available =>
          val latency = Duration.fromNanos(java.lang.System.nanoTime() - start)
          if available then HealthStatus(Availability.Healthy, AuthStatus.Valid, Some(latency))
          else HealthStatus(Availability.Unhealthy, AuthStatus.Invalid, Some(latency))
        }
      override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        executeStreamRequest(List(ChatMessage(role = "user", content = prompt)), None)

      override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
        val systemMsg    = messages.find(_.role == MessageRole.System).map(_.content)
        val chatMessages = messages
          .filter(_.role != MessageRole.System)
          .map { msg =>
            ChatMessage(
              role = msg.role match
                case MessageRole.User      => "user"
                case MessageRole.Assistant => "assistant"
                case _                     =>
                  "user"
              ,
              content = msg.content,
            )
          }
        executeStreamRequest(chatMessages, systemMsg)

      override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
        for
          baseUrl       <- ZIO.fromOption(config.baseUrl).orElseFail(
                             LlmError.ConfigError("Missing baseUrl for Anthropic provider")
                           )
          apiKey        <- ZIO.fromOption(config.apiKey).orElseFail(
                             LlmError.AuthenticationError("Missing API key for Anthropic provider")
                           )
          anthropicTools = tools.map { t =>
                             AnthropicTool(
                               name = t.name,
                               description = t.description,
                               input_schema = AnthropicToolInputSchema(properties = t.parameters),
                             )
                           }
          request        = AnthropicRequestWithTools(
                             model = config.model,
                             max_tokens = config.maxTokens.getOrElse(4096),
                             messages = List(ChatMessage(role = "user", content = prompt)),
                             tools = anthropicTools,
                             temperature = config.temperature,
                           )
          url            = s"${baseUrl.stripSuffix("/")}/messages"
          body          <- httpClient.postJson(
                             url = url,
                             body = request.toJson,
                             headers = authHeaders(apiKey),
                             timeout = config.timeout,
                           )
          parsed        <- ZIO
                             .fromEither(body.fromJson[AnthropicResponseWithTools])
                             .mapError(err => LlmError.ParseError(s"Failed to decode Anthropic tool response: $err", body))
        yield
          val toolUseBlocks = parsed.content.filter(_.`type` == "tool_use")
          val textContent   = parsed.content.find(_.`type` == "text").flatMap(_.text)
          ToolCallResponse(
            content = textContent,
            toolCalls = toolUseBlocks.map { block =>
              ToolCall(
                id = block.id.getOrElse(""),
                name = block.name.getOrElse(""),
                arguments = block.input.map(_.toJson).getOrElse("{}"),
              )
            },
            finishReason = parsed.stop_reason.getOrElse("stop"),
          )

      override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
        // Anthropic doesn't have native JSON schema support like OpenAI,
        // so we ask for JSON and parse it
        val jsonPrompt = s"$prompt\n\nPlease respond with valid JSON matching the provided schema."
        for
          response <- executeRequest(List(ChatMessage(role = "user", content = jsonPrompt)), None)
          parsed   <-
            ZIO.fromEither(response.content.fromJson[A])
              .mapError(err => LlmError.ParseError(s"Failed to parse structured response: $err", response.content))
        yield parsed

      override def isAvailable: UIO[Boolean] =
        llm4zio.core.Streaming.collect(executeStream("health check")).fold(_ => false, _ => true)

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

      private def executeStreamRequest(
        messages: List[ChatMessage],
        systemMessage: Option[String],
      ): ZStream[Any, LlmError, LlmChunk] =
        ZStream.unwrap {
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
                         stream = Some(true),
                       )
            url      = s"${baseUrl.stripSuffix("/")}/messages"
          yield httpClient
            .postJsonStreamSSE(url, request.toJson, authHeaders(apiKey), config.timeout)
            .mapZIO { line =>
              ZIO
                .fromEither(line.fromJson[AnthropicStreamChunk])
                .mapError(err => LlmError.ParseError(s"Failed to parse Anthropic stream chunk: $err", line))
            }
            .flatMap { chunk =>
              chunk.`type` match
                case "content_block_delta" =>
                  val text = chunk.delta.flatMap(_.text).getOrElse("")
                  if text.nonEmpty then
                    ZStream.succeed(LlmChunk(
                      delta = text,
                      finishReason = None,
                      usage = None,
                      metadata = Map("provider" -> "anthropic", "model" -> config.model),
                    ))
                  else ZStream.empty
                case "message_delta"       =>
                  val stopReason = chunk.delta.flatMap(_.stop_reason)
                  if stopReason.isDefined then
                    ZStream.succeed(LlmChunk(
                      delta = "",
                      finishReason = stopReason,
                      usage = None,
                      metadata = Map("provider" -> "anthropic", "model" -> config.model),
                    ))
                  else ZStream.empty
                case _                     => ZStream.empty
            }
        }

      private def authHeaders(apiKey: String): Map[String, String] =
        Map(
          "x-api-key"         -> apiKey,
          "anthropic-version" -> "2023-06-01",
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
            response.toJson,
          ))

      private def extractUsage(response: AnthropicResponse): Option[TokenUsage] =
        response.usage.map { u =>
          val inputTokens  = u.input_tokens.getOrElse(0)
          val outputTokens = u.output_tokens.getOrElse(0)
          TokenUsage(
            prompt = inputTokens,
            completion = outputTokens,
            total = inputTokens + outputTokens,
          )
        }

      private def baseMetadata(response: AnthropicResponse): Map[String, String] =
        val base = Map(
          "provider" -> "anthropic",
          "model"    -> config.model,
        )

        val idMeta     = response.id.map(id => Map("id" -> id)).getOrElse(Map.empty)
        val modelMeta  = response.model.map(m => Map("response_model" -> m)).getOrElse(Map.empty)
        val stopReason = response.stop_reason.map(r => Map("stop_reason" -> r)).getOrElse(Map.empty)

        base ++ idMeta ++ modelMeta ++ stopReason

  val layer: ZLayer[LlmConfig & HttpClient, Nothing, LlmService] =
    ZLayer.fromFunction { (config: LlmConfig, httpClient: HttpClient) =>
      make(config, httpClient)
    }
