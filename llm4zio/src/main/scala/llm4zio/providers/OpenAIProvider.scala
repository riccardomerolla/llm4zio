package llm4zio.providers

import zio.*
import zio.json.*
import zio.stream.ZStream

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object OpenAIProvider:
  def make(config: LlmConfig, httpClient: HttpClient): ApiConnector =
    new ApiConnector:
      override def id: ConnectorId = ConnectorId.OpenAI

      override def healthCheck: IO[LlmError, HealthStatus] =
        val start = java.lang.System.nanoTime()
        isAvailable.map { available =>
          val latency = Duration.fromNanos(java.lang.System.nanoTime() - start)
          if available then HealthStatus(Availability.Healthy, AuthStatus.Valid, Some(latency))
          else HealthStatus(Availability.Unhealthy, AuthStatus.Invalid, Some(latency))
        }
      override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        executeStreamRequest(List(ChatMessage(role = "user", content = prompt)))

      override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
        val chatMessages = messages.map { msg =>
          ChatMessage(
            role = msg.role match
              case MessageRole.System    => "system"
              case MessageRole.User      => "user"
              case MessageRole.Assistant => "assistant"
              case MessageRole.Tool      =>
                "tool"
            ,
            content = msg.content,
          )
        }
        executeStreamRequest(chatMessages)

      override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
        for
          baseUrl    <- ZIO.fromOption(config.baseUrl).orElseFail(
                          LlmError.ConfigError("Missing baseUrl for OpenAI provider")
                        )
          _          <- ZIO.fromOption(config.apiKey).orElseFail(
                          LlmError.AuthenticationError("Missing API key for OpenAI provider")
                        )
          openAiTools = tools.map { t =>
                          OpenAITool(function =
                            OpenAIFunction(
                              name = t.name,
                              description = t.description,
                              parameters = t.parameters,
                            )
                          )
                        }
          request     = ChatCompletionRequestWithTools(
                          model = config.model,
                          messages = List(ChatMessage(role = "user", content = prompt)),
                          tools = openAiTools,
                          temperature = config.temperature.orElse(Some(0.7)),
                          max_tokens = config.maxTokens,
                          stream = Some(false),
                        )
          url         = s"${baseUrl.stripSuffix("/")}/chat/completions"
          body       <- httpClient.postJson(
                          url = url,
                          body = request.toJson,
                          headers = authHeaders,
                          timeout = config.timeout,
                        )
          parsed     <- ZIO
                          .fromEither(body.fromJson[ChatCompletionResponseWithTools])
                          .mapError(err => LlmError.ParseError(s"Failed to decode OpenAI tool response: $err", body))
        yield
          val choice    = parsed.choices.headOption
          val toolCalls = choice.flatMap(_.message).flatMap(_.tool_calls).getOrElse(Nil)
          val content   = choice.flatMap(_.message).flatMap(_.content)
          val finish    = choice.flatMap(_.finish_reason).getOrElse("stop")
          ToolCallResponse(
            content = content,
            toolCalls =
              toolCalls.map(tc => ToolCall(id = tc.id, name = tc.function.name, arguments = tc.function.arguments)),
            finishReason = finish,
          )

      override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
        val responseFormat = Some(ResponseFormat(
          `type` = "json_schema",
          json_schema = Some(JsonSchemaSpec(
            name = "response",
            schema = schema,
          )),
        ))

        for
          response <- executeRequest(List(ChatMessage(role = "user", content = prompt)), responseFormat)
          parsed   <-
            ZIO.fromEither(response.content.fromJson[A])
              .mapError(err => LlmError.ParseError(s"Failed to parse structured response: $err", response.content))
        yield parsed

      override def isAvailable: UIO[Boolean] =
        config.baseUrl match
          case None          => ZIO.succeed(false)
          case Some(baseUrl) =>
            httpClient
              .get(
                url = s"${baseUrl.stripSuffix("/")}/models",
                headers = authHeaders,
                timeout = config.timeout,
              )
              .as(true)
              .catchAll(_ => ZIO.succeed(true))

      private def executeRequest(
        messages: List[ChatMessage],
        responseFormat: Option[ResponseFormat],
      ): IO[LlmError, LlmResponse] =
        for
          baseUrl <- ZIO.fromOption(config.baseUrl).orElseFail(
                       LlmError.ConfigError("Missing baseUrl for OpenAI provider")
                     )
          _       <- ZIO.fromOption(config.apiKey).orElseFail(
                       LlmError.AuthenticationError("Missing API key for OpenAI provider")
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
                       headers = authHeaders,
                       timeout = config.timeout,
                     )
          parsed  <- ZIO
                       .fromEither(body.fromJson[ChatCompletionResponse])
                       .mapError(err => LlmError.ParseError(s"Failed to decode OpenAI response: $err", body))
          content <- extractContent(parsed)
          usage    = extractUsage(parsed)
        yield LlmResponse(
          content = content,
          usage = usage,
          metadata = baseMetadata(parsed),
        )

      private def executeStreamRequest(messages: List[ChatMessage]): ZStream[Any, LlmError, LlmChunk] =
        ZStream.unwrap {
          for
            baseUrl <- ZIO.fromOption(config.baseUrl).orElseFail(
                         LlmError.ConfigError("Missing baseUrl for OpenAI provider")
                       )
            _       <- ZIO.fromOption(config.apiKey).orElseFail(
                         LlmError.AuthenticationError("Missing API key for OpenAI provider")
                       )
            request  = ChatCompletionRequest(
                         model = config.model,
                         messages = messages,
                         temperature = config.temperature.orElse(Some(0.7)),
                         max_tokens = config.maxTokens,
                         max_completion_tokens = None,
                         stream = Some(true),
                         response_format = None,
                       )
            url      = s"${baseUrl.stripSuffix("/")}/chat/completions"
          yield httpClient
            .postJsonStreamSSE(url, request.toJson, authHeaders, config.timeout)
            .mapZIO { line =>
              ZIO
                .fromEither(line.fromJson[OpenAIChatChunk])
                .mapError(err => LlmError.ParseError(s"Failed to parse OpenAI stream chunk: $err", line))
            }
            .flatMap { chunk =>
              val choice = chunk.choices.headOption
              val delta  = choice.flatMap(_.delta).flatMap(_.content).getOrElse("")
              val finish = choice.flatMap(_.finish_reason)
              if delta.nonEmpty || finish.isDefined then
                ZStream.succeed(LlmChunk(
                  delta = delta,
                  finishReason = finish,
                  usage = None,
                  metadata = Map("provider" -> "openai", "model" -> config.model),
                ))
              else ZStream.empty
            }
        }

      private def authHeaders: Map[String, String] =
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
            "OpenAI response missing choices[0].message.content",
            response.toJson,
          ))

      private def extractUsage(response: ChatCompletionResponse): Option[TokenUsage] =
        response.usage.map { u =>
          TokenUsage(
            prompt = u.prompt_tokens.getOrElse(0),
            completion = u.completion_tokens.getOrElse(0),
            total = u.total_tokens.getOrElse(0),
          )
        }

      private def baseMetadata(response: ChatCompletionResponse): Map[String, String] =
        val base = Map(
          "provider" -> "openai",
          "model"    -> config.model,
        )

        val idMeta    = response.id.map(id => Map("id" -> id)).getOrElse(Map.empty)
        val modelMeta = response.model.map(m => Map("response_model" -> m)).getOrElse(Map.empty)

        base ++ idMeta ++ modelMeta

  val layer: ZLayer[LlmConfig & HttpClient, Nothing, LlmService] =
    ZLayer.fromFunction { (config: LlmConfig, httpClient: HttpClient) =>
      make(config, httpClient)
    }
