package llm4zio.providers

import zio.*
import zio.json.*
import zio.stream.ZStream

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

/** LM Studio Provider - Native API v1
  *
  * LM Studio provides a native API at /api/v1/chat with enhanced features:
  *   - Stateful chat conversations
  *   - Model load/unload management
  *   - MCP (Model Context Protocol) support
  *   - Enhanced streaming events
  *
  * Default endpoint: http://localhost:1234
  *
  * Note: This uses the native LM Studio API, not the OpenAI-compatible endpoint. For OpenAI compatibility, use the
  * OpenAI provider with LM Studio's /v1 endpoint.
  */
object LmStudioProvider:
  def make(config: LlmConfig, httpClient: HttpClient): LlmService =
    new LlmService:
      override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        executeStreamRequest(List(LmStudioMessage(role = "user", content = prompt)))

      override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
        val lmStudioMessages = messages.map { msg =>
          LmStudioMessage(
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
        executeStreamRequest(lmStudioMessages)

      override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
        // LM Studio supports tool calling if the underlying model does
        // However, not all models support this, so we fail for now
        ZIO.fail(
          LlmError.InvalidRequestError("LmStudio provider does not yet support tool calling in this implementation")
        )

      override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
        // LM Studio native API can use JSON instructions in the prompt
        val jsonPrompt = s"$prompt\n\nPlease respond with valid JSON only, no additional text or markdown formatting."

        for
          response <- executeRequest(List(LmStudioMessage(role = "user", content = jsonPrompt)))
          parsed   <-
            ZIO.fromEither(response.content.fromJson[A])
              .mapError(err => LlmError.ParseError(s"Failed to parse structured response: $err", response.content))
        yield parsed

      override def isAvailable: UIO[Boolean] =
        config.baseUrl match
          case None          => ZIO.succeed(false)
          case Some(baseUrl) =>
            val normalized = normalizeBaseUrl(baseUrl)
            httpClient
              .get(
                url = s"${normalized}/api/v1/models",
                headers = Map.empty,
                timeout = config.timeout,
              )
              .as(true)
              .catchAll(_ => ZIO.succeed(false))

      /** Real token-by-token streaming via LM Studio's OpenAI-compatible /v1/chat/completions endpoint */
      private def executeStreamRequest(messages: List[LmStudioMessage]): ZStream[Any, LlmError, LlmChunk] =
        ZStream.unwrap {
          for
            baseUrl     <- ZIO.fromOption(config.baseUrl).orElseFail(
                             LlmError.ConfigError("Missing baseUrl for LmStudio provider")
                           )
            normalized   = normalizeBaseUrl(baseUrl)
            chatMessages = messages.map(m => ChatMessage(role = m.role, content = m.content))
            request      = ChatCompletionRequest(
                             model = config.model,
                             messages = chatMessages,
                             temperature = config.temperature.orElse(Some(0.7)),
                             max_tokens = config.maxTokens,
                             max_completion_tokens = None,
                             stream = Some(true),
                             response_format = None,
                           )
            url          = s"$normalized/v1/chat/completions"
          yield httpClient
            .postJsonStreamSSE(url, request.toJson, authHeaders, config.timeout)
            .mapZIO { line =>
              ZIO
                .fromEither(line.fromJson[OpenAIChatChunk])
                .mapError(err => LlmError.ParseError(s"Failed to parse LmStudio stream chunk: $err", line))
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
                  metadata = Map("provider" -> "lmstudio", "model" -> config.model),
                ))
              else ZStream.empty
            }
        }

      private def executeRequest(
        messages: List[LmStudioMessage]
      ): IO[LlmError, LlmResponse] =
        for
          baseUrl     <- ZIO.fromOption(config.baseUrl).orElseFail(
                           LlmError.ConfigError("Missing baseUrl for LmStudio provider")
                         )
          normalized   = normalizeBaseUrl(baseUrl)
          inputPayload = renderInputPayload(messages)
          systemPrompt = renderSystemPrompt(messages)
          request      = LmStudioChatRequest(
                           model = config.model,
                           input = inputPayload,
                           system_prompt = systemPrompt,
                           temperature = config.temperature.orElse(Some(0.7)),
                           max_output_tokens = config.maxTokens,
                           stream = Some(false),
                         )
          url          = s"${normalized}/api/v1/chat"
          body        <- httpClient.postJson(
                           url = url,
                           body = request.toJson,
                           headers = authHeaders,
                           timeout = config.timeout,
                         )
          parsed      <-
            ZIO
              .fromEither(body.fromJson[LmStudioChatResponse])
              .mapError(err => LlmError.ParseError(s"Failed to decode LmStudio native API response: $err", body))
          content     <- extractContent(parsed)
          usage        = extractUsage(parsed)
        yield LlmResponse(
          content = content,
          usage = usage,
          metadata = baseMetadata(parsed),
        )

      private def authHeaders: Map[String, String] =
        // LM Studio doesn't require API key, but if provided, use it
        config.apiKey.map(key => Map("Authorization" -> s"Bearer $key")).getOrElse(Map.empty)

      private def renderInputPayload(messages: List[LmStudioMessage]): LmStudioInputPayload =
        val nonSystem = messages.filterNot(_.role == "system")
        val text      = renderInputText(if nonSystem.nonEmpty then nonSystem else messages)
        LmStudioInputPayload.Text(text)

      private def renderSystemPrompt(messages: List[LmStudioMessage]): Option[String] =
        val systemText = messages.filter(_.role == "system").map(_.content.trim).filter(_.nonEmpty)
        if systemText.isEmpty then None else Some(systemText.mkString("\n"))

      private def renderInputText(messages: List[LmStudioMessage]): String =
        messages.map(m => s"${m.role}: ${m.content}").mkString("\n")

      private def normalizeBaseUrl(raw: String): String =
        val trimmed = raw.trim.stripSuffix("/")
        if trimmed.endsWith("/v1") then trimmed.stripSuffix("/v1") else trimmed

      private def extractContent(response: LmStudioChatResponse): IO[LlmError, String] =
        val content =
          for
            item   <- response.output.find(item => item.`type` == "message" && item.content.exists(_.trim.nonEmpty))
            text   <- item.content
            trimmed = text.trim
            if trimmed.nonEmpty
          yield trimmed

        ZIO.fromOption(content)
          .orElseFail(LlmError.ParseError(
            "LmStudio response missing choices[0].message.content",
            response.toJson,
          ))

      private def extractUsage(response: LmStudioChatResponse): Option[TokenUsage] =
        response.stats.map { stats =>
          val prompt     = stats.input_tokens.getOrElse(0)
          val completion = stats.total_output_tokens.getOrElse(0)
          TokenUsage(
            prompt = prompt,
            completion = completion,
            total = prompt + completion,
          )
        }

      private def baseMetadata(response: LmStudioChatResponse): Map[String, String] =
        val base = Map(
          "provider"          -> "lmstudio",
          "model"             -> config.model,
          "model_instance_id" -> response.model_instance_id,
        )

        val statsMeta = response.stats.map { stats =>
          Map(
            "tokens_per_second"           -> stats.tokens_per_second.map(_.toString).getOrElse(""),
            "time_to_first_token_seconds" -> stats.time_to_first_token_seconds.map(_.toString).getOrElse(""),
            "model_load_time_seconds"     -> stats.model_load_time_seconds.map(_.toString).getOrElse(""),
          ).filter(_._2.nonEmpty)
        }.getOrElse(Map.empty)

        base ++ statsMeta ++ response.response_id.map(id => Map("response_id" -> id)).getOrElse(Map.empty)

  val layer: ZLayer[LlmConfig & HttpClient, Nothing, LlmService] =
    ZLayer.fromFunction { (config: LlmConfig, httpClient: HttpClient) =>
      make(config, httpClient)
    }
