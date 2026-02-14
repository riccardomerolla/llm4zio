package llm4zio.providers

import zio.*
import zio.json.*
import zio.stream.ZStream

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

/** OpenCode AI Provider (OpenAI-compatible)
  *
  * API base: http://localhost:4096
  * Endpoint pattern: /chat/completions
  */
object OpenCodeProvider:
  def make(config: LlmConfig, httpClient: HttpClient): LlmService =
    new LlmService:
      override def execute(prompt: String): IO[LlmError, LlmResponse] =
        executeRequest(
          messages = List(ChatMessage(role = "user", content = prompt)),
          responseFormat = None,
        )

      override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        ZStream.fromZIO(
          executeStreamRequest(
            messages = List(ChatMessage(role = "user", content = prompt))
          )
        ).flatMap(chunks => ZStream.fromIterable(chunks))

      override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
        executeRequest(
          messages = toChatMessages(messages),
          responseFormat = None,
        )

      override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
        ZStream.fromZIO(executeStreamRequest(toChatMessages(messages))).flatMap(chunks => ZStream.fromIterable(chunks))

      override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
        ZIO.fail(LlmError.InvalidRequestError("OpenCode provider does not yet support tool calling in this implementation"))

      override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
        val responseFormat = Some(
          OpenCodeResponseFormat(
            `type` = "json_schema",
            json_schema = Some(OpenCodeJsonSchemaSpec(name = "response", schema = schema)),
          )
        )

        for
          response <- executeRequest(
                        messages = List(ChatMessage(role = "user", content = prompt)),
                        responseFormat = responseFormat,
                      )
          parsed <- ZIO
                      .fromEither(response.content.fromJson[A])
                      .mapError(err => LlmError.ParseError(s"Failed to parse structured response: $err", response.content))
        yield parsed

      override def isAvailable: UIO[Boolean] =
        config.baseUrl match
          case None          => ZIO.succeed(false)
          case Some(baseUrl) =>
            val authCheck = config.apiKey.isDefined
            if !authCheck then ZIO.succeed(false)
            else
              httpClient
                .get(
                  url = s"${baseUrl.stripSuffix("/")}/models",
                  headers = authHeaders,
                  timeout = config.timeout,
                )
                .as(true)
                .catchAll(_ => ZIO.succeed(false))

      private def executeRequest(
        messages: List[ChatMessage],
        responseFormat: Option[OpenCodeResponseFormat],
      ): IO[LlmError, LlmResponse] =
        for
          baseUrl <- requireBaseUrl
          _ <- requireApiKey
          request = OpenCodeCompletionRequest(
                      model = config.model,
                      messages = messages,
                      temperature = config.temperature.orElse(Some(0.7)),
                      max_tokens = config.maxTokens,
                      max_completion_tokens = None,
                      stream = Some(false),
                      response_format = responseFormat,
                    )
          body <- httpClient.postJson(
                    url = s"${baseUrl.stripSuffix("/")}/chat/completions",
                    body = request.toJson,
                    headers = authHeaders,
                    timeout = config.timeout,
                  )
          parsed <- ZIO
                      .fromEither(body.fromJson[OpenCodeCompletionResponse])
                      .mapError(err => LlmError.ParseError(s"Failed to decode OpenCode response: $err", body))
          content <- extractContent(parsed)
        yield LlmResponse(
          content = content,
          usage = extractUsage(parsed),
          metadata = baseMetadata(parsed),
        )

      private def executeStreamRequest(messages: List[ChatMessage]): IO[LlmError, List[LlmChunk]] =
        for
          baseUrl <- requireBaseUrl
          _ <- requireApiKey
          request = OpenCodeCompletionRequest(
                      model = config.model,
                      messages = messages,
                      temperature = config.temperature.orElse(Some(0.7)),
                      max_tokens = config.maxTokens,
                      max_completion_tokens = None,
                      stream = Some(true),
                      response_format = None,
                    )
          body <- httpClient.postJson(
                    url = s"${baseUrl.stripSuffix("/")}/chat/completions",
                    body = request.toJson,
                    headers = authHeaders,
                    timeout = config.timeout,
                  )
          chunks <- parseSseBody(body)
        yield chunks

      private def parseSseBody(raw: String): IO[LlmError, List[LlmChunk]] =
        val payloads = raw
          .split("\\n")
          .toList
          .map(_.trim)
          .filter(_.startsWith("data:"))
          .map(_.stripPrefix("data:").trim)
          .filter(payload => payload.nonEmpty && payload != "[DONE]")

        ZIO
          .foreach(payloads) { payload =>
            ZIO
              .fromEither(payload.fromJson[OpenCodeCompletionResponse])
              .mapError(err => LlmError.ParseError(s"Failed to parse OpenCode SSE payload: $err", payload))
              .map(toChunk)
          }
          .flatMap { chunks =>
            if chunks.isEmpty then
              ZIO.fail(LlmError.ParseError("No stream chunks parsed from OpenCode SSE response", raw))
            else ZIO.succeed(chunks)
          }

      private def toChunk(response: OpenCodeCompletionResponse): LlmChunk =
        val choice = response.choices.headOption
        val deltaText = choice.flatMap(_.delta.flatMap(_.content)).orElse(choice.flatMap(_.message.map(_.content))).orElse(choice.flatMap(_.text)).getOrElse("")
        val finish = choice.flatMap(_.finish_reason)

        LlmChunk(
          delta = deltaText,
          finishReason = finish,
          usage = extractUsage(response),
          metadata = baseMetadata(response),
        )

      private def requireBaseUrl: IO[LlmError, String] =
        ZIO.fromOption(config.baseUrl).orElseFail(LlmError.ConfigError("Missing baseUrl for OpenCode provider"))

      private def requireApiKey: IO[LlmError, String] =
        ZIO.fromOption(config.apiKey).orElseFail(LlmError.AuthenticationError("Missing API key for OpenCode provider"))

      private def authHeaders: Map[String, String] =
        config.apiKey.map(key => Map("Authorization" -> s"Bearer $key")).getOrElse(Map.empty)

      private def toChatMessages(messages: List[Message]): List[ChatMessage] =
        messages.map { message =>
          ChatMessage(
            role = message.role match
              case MessageRole.System    => "system"
              case MessageRole.User      => "user"
              case MessageRole.Assistant => "assistant"
              case MessageRole.Tool      => "tool",
            content = message.content,
          )
        }

      private def extractContent(response: OpenCodeCompletionResponse): IO[LlmError, String] =
        val content =
          for
            choice <- response.choices.headOption
            text <- choice.message.map(_.content).orElse(choice.text)
            value = text.trim
            if value.nonEmpty
          yield value

        ZIO.fromOption(content).orElseFail(
          LlmError.ParseError(
            "OpenCode response missing choices[0].message.content",
            response.toJson,
          )
        )

      private def extractUsage(response: OpenCodeCompletionResponse): Option[TokenUsage] =
        response.usage.map { usage =>
          TokenUsage(
            prompt = usage.prompt_tokens.getOrElse(0),
            completion = usage.completion_tokens.getOrElse(0),
            total = usage.total_tokens.getOrElse(0),
          )
        }

      private def baseMetadata(response: OpenCodeCompletionResponse): Map[String, String] =
        Map(
          "provider" -> "opencode",
          "model" -> config.model,
        ) ++
          response.id.map(id => Map("id" -> id)).getOrElse(Map.empty) ++
          response.model.map(model => Map("response_model" -> model)).getOrElse(Map.empty)

  val layer: ZLayer[LlmConfig & HttpClient, Nothing, LlmService] =
    ZLayer.fromFunction { (config: LlmConfig, httpClient: HttpClient) =>
      make(config, httpClient)
    }

case class OpenCodeJsonSchemaSpec(
  name: String,
  schema: JsonSchema,
) derives JsonCodec

case class OpenCodeResponseFormat(
  `type`: String,
  json_schema: Option[OpenCodeJsonSchemaSpec] = None,
) derives JsonCodec

case class OpenCodeCompletionRequest(
  model: String,
  messages: List[ChatMessage],
  temperature: Option[Double] = None,
  max_tokens: Option[Int] = None,
  max_completion_tokens: Option[Int] = None,
  stream: Option[Boolean] = None,
  response_format: Option[OpenCodeResponseFormat] = None,
) derives JsonCodec

case class OpenCodeDelta(
  content: Option[String] = None,
) derives JsonCodec

case class OpenCodeCompletionResponse(
  id: Option[String] = None,
  choices: List[OpenCodeChoice],
  usage: Option[OpenCodeTokenUsage] = None,
  model: Option[String] = None,
) derives JsonCodec

case class OpenCodeChoice(
  index: Int = 0,
  message: Option[ChatMessage] = None,
  delta: Option[OpenCodeDelta] = None,
  text: Option[String] = None,
  finish_reason: Option[String] = None,
) derives JsonCodec

case class OpenCodeTokenUsage(
  prompt_tokens: Option[Int] = None,
  completion_tokens: Option[Int] = None,
  total_tokens: Option[Int] = None,
) derives JsonCodec
