package llm4zio.providers

import zio.*
import zio.json.*
import zio.stream.ZStream

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object GeminiApiProvider:
  def make(config: LlmConfig, httpClient: HttpClient): LlmService =
    new LlmService:
      override def execute(prompt: String): IO[LlmError, LlmResponse] =
        executeRequest(prompt, None)

      override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        val contents = List(GeminiContent(parts = List(GeminiPart(text = Some(prompt)))))
        executeStreamWithContents(contents)

      override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
        // Convert messages to Gemini content format
        val contents = messages.map { msg =>
          GeminiContent(parts = List(GeminiPart(text = Some(msg.content))))
        }
        executeRequestWithContents(contents, None)

      override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
        val contents = messages.map { msg =>
          GeminiContent(parts = List(GeminiPart(text = Some(msg.content))))
        }
        executeStreamWithContents(contents)

      override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
        for
          baseUrl    <- ZIO.fromOption(config.baseUrl).orElseFail(
                          LlmError.ConfigError("Missing baseUrl for Gemini API provider")
                        )
          apiKey     <- ZIO.fromOption(config.apiKey).orElseFail(
                          LlmError.AuthenticationError("Missing API key for Gemini API provider")
                        )
          geminiTools = List(GeminiToolDef(
                          functionDeclarations = tools.map { t =>
                            GeminiFunctionDeclaration(
                              name = t.name,
                              description = t.description,
                              parameters = t.parameters,
                            )
                          }
                        ))
          request     = GeminiGenerateContentRequestWithTools(
                          contents = List(GeminiContent(parts = List(GeminiPart(text = Some(prompt))))),
                          tools = geminiTools,
                        )
          url         = s"${baseUrl.stripSuffix("/")}/v1beta/models/${config.model}:generateContent"
          body       <- httpClient.postJson(
                          url = url,
                          body = request.toJson,
                          headers = Map("x-goog-api-key" -> apiKey),
                          timeout = config.timeout,
                        )
          parsed     <- ZIO
                          .fromEither(body.fromJson[GeminiGenerateContentResponseFull])
                          .mapError(err => LlmError.ParseError(s"Failed to decode Gemini tool response: $err", body))
          candidate  <- ZIO
                          .fromOption(parsed.candidates.headOption)
                          .orElseFail(LlmError.ParseError(
                            "Gemini tool response has no candidates",
                            body,
                          ))
        yield
          val parts     = candidate.content.parts
          val fnCalls   = parts.flatMap(_.functionCall)
          val textParts = parts.flatMap(_.text)
          val finish    = candidate.finishReason.getOrElse("STOP")
          ToolCallResponse(
            content = textParts.headOption,
            toolCalls = fnCalls.zipWithIndex.map {
              case (fc, i) =>
                ToolCall(
                  id = s"gemini_call_$i",
                  name = fc.name,
                  arguments = fc.args.toJson,
                )
            },
            finishReason = finish,
          )

      override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
        for
          response <- executeRequest(prompt, Some(schema))
          parsed   <-
            ZIO.fromEither(response.content.fromJson[A])
              .mapError(err => LlmError.ParseError(s"Failed to parse structured response: $err", response.content))
        yield parsed

      override def isAvailable: UIO[Boolean] =
        execute("health check").fold(_ => false, _ => true)

      private def executeRequest(
        prompt: String,
        schema: Option[JsonSchema],
      ): IO[LlmError, LlmResponse] =
        val contents = List(GeminiContent(parts = List(GeminiPart(text = Some(prompt)))))
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
          output   <- extractText(parsed, body)
          usage     = extractUsage(parsed)
        yield LlmResponse(
          content = output,
          usage = usage,
          metadata = baseMetadata(parsed),
        )

      private def executeStreamWithContents(
        contents: List[GeminiContent]
      ): ZStream[Any, LlmError, LlmChunk] =
        ZStream.unwrap {
          for
            baseUrl <- ZIO.fromOption(config.baseUrl).orElseFail(
                         LlmError.ConfigError("Missing baseUrl for Gemini API provider")
                       )
            apiKey  <- ZIO.fromOption(config.apiKey).orElseFail(
                         LlmError.AuthenticationError("Missing API key for Gemini API provider")
                       )
            request  = GeminiGenerateContentRequest(contents = contents)
            url      = s"${baseUrl.stripSuffix("/")}/v1beta/models/${config.model}:streamGenerateContent"
          yield httpClient
            .postJsonStream(
              url = url,
              body = request.toJson,
              headers = Map("x-goog-api-key" -> apiKey),
              timeout = config.timeout,
            )
            .map(_.trim)
            .filter(_.nonEmpty)
            .map(stripStreamPrefix)
            .filter(payload => payload.nonEmpty && payload != "[DONE]")
            .mapZIO { payload =>
              ZIO
                .fromEither(payload.fromJson[GeminiGenerateContentResponse])
                .mapError(err => LlmError.ParseError(s"Failed to decode Gemini stream chunk: $err", payload))
                .map(toStreamChunk)
            }
            .filter(chunk => chunk.delta.nonEmpty || chunk.finishReason.nonEmpty || chunk.usage.nonEmpty)
        }

      private def stripStreamPrefix(payload: String): String =
        if payload.startsWith("data:") then payload.stripPrefix("data:").trim
        else payload

      private def toStreamChunk(response: GeminiGenerateContentResponse): LlmChunk =
        val delta  = response.candidates.headOption.toList
          .flatMap(_.content.parts)
          .flatMap(_.text)
          .mkString
        val finish = response.candidates.headOption.flatMap(_.finishReason).map(_.toLowerCase)
        LlmChunk(
          delta = delta,
          finishReason = finish,
          usage = extractUsage(response),
          metadata = baseMetadata(response),
        )

      private def extractText(response: GeminiGenerateContentResponse, rawBody: String): IO[LlmError, String] =
        val text = response.candidates.headOption.toList
          .flatMap(_.content.parts)
          .flatMap(_.text.map(_.trim))
          .find(_.nonEmpty)

        ZIO.fromOption(text)
          .orElseFail(LlmError.ParseError(
            "Gemini API response has no text content in candidates. The response may be safety-filtered or malformed.",
            rawBody,
          ))

      private def extractUsage(response: GeminiGenerateContentResponse): Option[TokenUsage] =
        response.usageMetadata.map { meta =>
          TokenUsage(
            prompt = meta.promptTokenCount.getOrElse(0),
            completion = meta.candidatesTokenCount.getOrElse(0),
            total = meta.totalTokenCount.getOrElse(0),
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
