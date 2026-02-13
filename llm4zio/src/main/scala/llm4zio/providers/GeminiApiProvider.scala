package llm4zio.providers

import zio.*
import zio.json.*
import zio.stream.ZStream
import llm4zio.core.*
import llm4zio.tools.{AnyTool, JsonSchema}

object GeminiApiProvider:
  def make(config: LlmConfig, httpClient: HttpClient): LlmService =
    new LlmService:
      override def execute(prompt: String): IO[LlmError, LlmResponse] =
        executeRequest(prompt, None)

      override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        // Gemini API doesn't support streaming in this implementation, convert to single chunk
        ZStream.fromZIO(execute(prompt)).map { response =>
          LlmChunk(
            delta = response.content,
            finishReason = Some("stop"),
            usage = response.usage
          )
        }

      override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
        // Convert messages to Gemini content format
        val contents = messages.map { msg =>
          GeminiContent(parts = List(GeminiPart(text = msg.content)))
        }
        executeRequestWithContents(contents, None)

      override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
        ZStream.fromZIO(executeWithHistory(messages)).map { response =>
          LlmChunk(
            delta = response.content,
            finishReason = Some("stop"),
            usage = response.usage
          )
        }

      override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
        // Gemini API doesn't support tool calling in this basic implementation
        ZIO.fail(LlmError.InvalidRequestError("Gemini API provider does not yet support tool calling"))

      override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
        for
          response <- executeRequest(prompt, Some(schema))
          parsed   <- ZIO.fromEither(response.content.fromJson[A])
                        .mapError(err => LlmError.ParseError(s"Failed to parse structured response: $err", response.content))
        yield parsed

      override def isAvailable: UIO[Boolean] =
        execute("health check").fold(_ => false, _ => true)

      private def executeRequest(
        prompt: String,
        schema: Option[JsonSchema],
      ): IO[LlmError, LlmResponse] =
        val contents = List(GeminiContent(parts = List(GeminiPart(text = prompt))))
        executeRequestWithContents(contents, schema)

      private def executeRequestWithContents(
        contents: List[GeminiContent],
        schema: Option[JsonSchema],
      ): IO[LlmError, LlmResponse] =
        for
          baseUrl  <- ZIO.fromOption(config.baseUrl).orElseFail(
                        LlmError.ConfigError("Missing baseUrl for Gemini API provider")
                      )
          apiKey   <- ZIO.fromOption(config.apiKey).orElseFail(
                        LlmError.AuthenticationError("Missing API key for Gemini API provider")
                      )
          genConfig = schema.map(s =>
                        GeminiGenerationConfig(
                          responseMimeType = Some("application/json"),
                          responseSchema = Some(s),
                        )
                      )
          request   = GeminiGenerateContentRequest(
                        contents = contents,
                        generationConfig = genConfig,
                      )
          url       = s"${baseUrl.stripSuffix("/")}/v1beta/models/${config.model}:generateContent"
          body     <- httpClient.postJson(
                        url = url,
                        body = request.toJson,
                        headers = Map("x-goog-api-key" -> apiKey),
                        timeout = config.timeout,
                      )
          parsed   <- ZIO
                        .fromEither(body.fromJson[GeminiGenerateContentResponse])
                        .mapError(err => LlmError.ParseError(s"Failed to decode Gemini API response: $err", body))
          output   <- extractText(parsed)
          usage     = extractUsage(parsed)
        yield LlmResponse(
          content = output,
          usage = usage,
          metadata = baseMetadata(parsed),
        )

      private def extractText(response: GeminiGenerateContentResponse): IO[LlmError, String] =
        val text =
          for
            candidate <- response.candidates.headOption
            part      <- candidate.content.parts.headOption
            value      = part.text.trim
            if value.nonEmpty
          yield value

        ZIO.fromOption(text)
          .orElseFail(LlmError.ParseError(
            "Gemini API response missing candidates[0].content.parts[0].text",
            response.toJson
          ))

      private def extractUsage(response: GeminiGenerateContentResponse): Option[TokenUsage] =
        response.usageMetadata.map { meta =>
          TokenUsage(
            prompt = meta.promptTokenCount.getOrElse(0),
            completion = meta.candidatesTokenCount.getOrElse(0),
            total = meta.totalTokenCount.getOrElse(0)
          )
        }

      private def baseMetadata(response: GeminiGenerateContentResponse): Map[String, String] =
        val usage = response.usageMetadata.toList.flatMap { meta =>
          List(
            meta.promptTokenCount.map(v => "promptTokenCount" -> v.toString),
            meta.candidatesTokenCount.map(v => "candidatesTokenCount" -> v.toString),
            meta.totalTokenCount.map(v => "totalTokenCount" -> v.toString),
          ).flatten
        }.toMap

        usage ++ Map(
          "provider" -> "gemini-api",
          "model"    -> config.model,
        )

  val layer: ZLayer[LlmConfig & HttpClient, Nothing, LlmService] =
    ZLayer.fromFunction { (config: LlmConfig, httpClient: HttpClient) =>
      make(config, httpClient)
    }
