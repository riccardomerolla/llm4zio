package llm4zio.providers

import zio.*
import zio.json.JsonCodec
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

object GeminiCliProviderSpec extends ZIOSpecDefault:
  // Mock executor for testing
  class MockGeminiCliExecutor(
    shouldSucceed: Boolean = true,
    streamEvents: List[GeminiCliStreamEvent] = Nil,
  ) extends GeminiCliExecutor:
    override def checkGeminiInstalled: IO[LlmError, Unit] =
      if shouldSucceed then ZIO.unit
      else ZIO.fail(LlmError.ConfigError("gemini-cli not installed"))

    override def runGeminiProcess(
      prompt: String,
      config: LlmConfig,
      executionContext: GeminiCliExecutionContext,
    ): IO[LlmError, String] =
      if shouldSucceed then ZIO.succeed(s"Response to: $prompt")
      else ZIO.fail(LlmError.ProviderError("Process failed", None))

    override def runGeminiProcessStream(
      prompt: String,
      config: LlmConfig,
      executionContext: GeminiCliExecutionContext,
    ): ZStream[Any, LlmError, GeminiCliStreamEvent] =
      if shouldSucceed then ZStream.fromIterable(streamEvents)
      else ZStream.fail(LlmError.ProviderError("Process failed", None))

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("GeminiCliProvider")(
    test("execute should return response") {
      val config   = LlmConfig(
        provider = LlmProvider.GeminiCli,
        model = "gemini-2.0-flash-exp",
      )
      val executor = new MockGeminiCliExecutor(
        streamEvents = List(
          GeminiCliStreamEvent.Message(
            role = Some("assistant"),
            content = Some("Response to: test prompt"),
            delta = true,
          ),
          GeminiCliStreamEvent.Result(status = Some("success"), errorMessage = None, stats = None),
        )
      )
      val provider = GeminiCliProvider.make(config, executor)

      for {
        response <- Streaming.collect(provider.executeStream("test prompt"))
      } yield assertTrue(
        response.content.contains("Response to: test prompt")
      )
    },
    test("isAvailable should check if gemini is installed") {
      val config   = LlmConfig(
        provider = LlmProvider.GeminiCli,
        model = "gemini-2.0-flash-exp",
      )
      val executor = new MockGeminiCliExecutor(shouldSucceed = true)
      val provider = GeminiCliProvider.make(config, executor)

      for {
        available <- provider.isAvailable
      } yield assertTrue(available)
    },
    test("isAvailable should return false when gemini not installed") {
      val config   = LlmConfig(
        provider = LlmProvider.GeminiCli,
        model = "gemini-2.0-flash-exp",
      )
      val executor = new MockGeminiCliExecutor(shouldSucceed = false)
      val provider = GeminiCliProvider.make(config, executor)

      for {
        available <- provider.isAvailable
      } yield assertTrue(!available)
    },
    test("execute should fail when gemini not installed") {
      val config   = LlmConfig(
        provider = LlmProvider.GeminiCli,
        model = "gemini-2.0-flash-exp",
      )
      val executor = new MockGeminiCliExecutor(shouldSucceed = false)
      val provider = GeminiCliProvider.make(config, executor)

      for {
        result <- Streaming.collect(provider.executeStream("test")).exit
      } yield assertTrue(result.isFailure)
    },
    test("executeStream should emit assistant chunks from Gemini stream-json events and ignore non-json output") {
      val config   = LlmConfig(
        provider = LlmProvider.GeminiCli,
        model = "gemini-2.0-flash-exp",
      )
      val executor = new MockGeminiCliExecutor(
        streamEvents = List(
          GeminiCliStreamEvent.LogLine("Loaded cached credentials."),
          GeminiCliStreamEvent.Init(model = Some("gemini-2.5-pro"), sessionId = Some("s1")),
          GeminiCliStreamEvent.ToolUse(toolName = Some("codebase_investigator"), toolId = Some("tool-1")),
          GeminiCliStreamEvent.ToolResult(toolId = Some("tool-1"), status = Some("success")),
          GeminiCliStreamEvent.LogLine("Error when talking to Gemini API Full report available at: ..."),
          GeminiCliStreamEvent.Message(role = Some("assistant"), content = Some("Hello "), delta = true),
          GeminiCliStreamEvent.Message(role = Some("assistant"), content = Some("world"), delta = true),
          GeminiCliStreamEvent.Result(
            status = Some("success"),
            errorMessage = None,
            stats = Some(GeminiCliProvider.GeminiStreamStats(
              total_tokens = Some(15),
              input_tokens = Some(10),
              output_tokens = Some(5),
            )),
          ),
        )
      )
      val provider = GeminiCliProvider.make(config, executor)

      for
        chunks <- provider.executeStream("test prompt").runCollect
      yield assertTrue(
        chunks.map(_.delta).mkString == "Hello world",
        chunks.lastOption.flatMap(_.finishReason).contains("stop"),
        chunks.lastOption.flatMap(_.usage).contains(TokenUsage(prompt = 10, completion = 5, total = 15)),
      )
    },
    test("executeStream should fail when Gemini stream-json result contains an error") {
      val config   = LlmConfig(
        provider = LlmProvider.GeminiCli,
        model = "gemini-2.0-flash-exp",
      )
      val executor = new MockGeminiCliExecutor(
        streamEvents = List(
          GeminiCliStreamEvent.LogLine("Loaded cached credentials."),
          GeminiCliStreamEvent.Result(
            status = Some("error"),
            errorMessage = Some("You have exhausted your capacity on this model."),
            stats = Some(GeminiCliProvider.GeminiStreamStats(
              total_tokens = Some(1),
              input_tokens = Some(1),
              output_tokens = Some(0),
            )),
          ),
        )
      )
      val provider = GeminiCliProvider.make(config, executor)

      for
        result <- provider.executeStream("test prompt").runCollect.exit
      yield assertTrue(result.isFailure)
    },
    test("executeStructured should parse JSON from stream-json assistant chunks") {
      final case class StructuredReply(summary: String) derives JsonCodec

      val config   = LlmConfig(
        provider = LlmProvider.GeminiCli,
        model = "gemini-2.0-flash-exp",
      )
      val executor = new MockGeminiCliExecutor(
        streamEvents = List(
          GeminiCliStreamEvent.Init(model = Some("gemini-2.5-pro"), sessionId = Some("s1")),
          GeminiCliStreamEvent.Message(role = Some("assistant"), content = Some("""{"summary":"""), delta = true),
          GeminiCliStreamEvent.Message(role = Some("assistant"), content = Some(""""streamed"}"""), delta = true),
          GeminiCliStreamEvent.Result(
            status = Some("success"),
            errorMessage = None,
            stats = None,
          ),
        )
      )
      val provider = GeminiCliProvider.make(config, executor)

      for
        response <- provider.executeStructured[StructuredReply]("test prompt", Json.Obj())
      yield assertTrue(response.summary == "streamed")
    },
    test("executeStructured should parse fenced JSON from stream-json assistant chunks") {
      final case class StructuredReply(summary: String) derives JsonCodec

      val config   = LlmConfig(
        provider = LlmProvider.GeminiCli,
        model = "gemini-2.0-flash-exp",
      )
      val executor = new MockGeminiCliExecutor(
        streamEvents = List(
          GeminiCliStreamEvent.Message(role = Some("assistant"), content = Some("```json\n"), delta = true),
          GeminiCliStreamEvent.Message(
            role = Some("assistant"),
            content = Some("""{"summary":"fenced"}"""),
            delta = true,
          ),
          GeminiCliStreamEvent.Message(role = Some("assistant"), content = Some("\n```"), delta = true),
          GeminiCliStreamEvent.Result(
            status = Some("success"),
            errorMessage = None,
            stats = None,
          ),
        )
      )
      val provider = GeminiCliProvider.make(config, executor)

      for
        response <- provider.executeStructured[StructuredReply]("test prompt", Json.Obj())
      yield assertTrue(response.summary == "fenced")
    },
    test("executeStructured should parse prose plus fenced JSON from stream-json assistant chunks") {
      final case class StructuredReply(summary: String) derives JsonCodec

      val config   = LlmConfig(
        provider = LlmProvider.GeminiCli,
        model = "gemini-2.0-flash-exp",
      )
      val executor = new MockGeminiCliExecutor(
        streamEvents = List(
          GeminiCliStreamEvent.Message(
            role = Some("assistant"),
            content = Some("Here is the structured output:\n```json\n"),
            delta = true,
          ),
          GeminiCliStreamEvent.Message(
            role = Some("assistant"),
            content = Some("""{"summary":"wrapped"}"""),
            delta = true,
          ),
          GeminiCliStreamEvent.Message(role = Some("assistant"), content = Some("\n```\nUse this plan."), delta = true),
          GeminiCliStreamEvent.Result(
            status = Some("success"),
            errorMessage = None,
            stats = None,
          ),
        )
      )
      val provider = GeminiCliProvider.make(config, executor)

      for
        response <- provider.executeStructured[StructuredReply]("test prompt", Json.Obj())
      yield assertTrue(response.summary == "wrapped")
    },
    test("executeStructured should parse uppercase fenced JSON from stream-json assistant chunks") {
      final case class StructuredReply(summary: String) derives JsonCodec

      val config   = LlmConfig(
        provider = LlmProvider.GeminiCli,
        model = "gemini-2.0-flash-exp",
      )
      val executor = new MockGeminiCliExecutor(
        streamEvents = List(
          GeminiCliStreamEvent.Message(
            role = Some("assistant"),
            content = Some("Here is the plan:\n```JSON\n"),
            delta = true,
          ),
          GeminiCliStreamEvent.Message(
            role = Some("assistant"),
            content = Some("""{"summary":"uppercase-fence"}"""),
            delta = true,
          ),
          GeminiCliStreamEvent.Message(role = Some("assistant"), content = Some("\n```"), delta = true),
          GeminiCliStreamEvent.Result(
            status = Some("success"),
            errorMessage = None,
            stats = None,
          ),
        )
      )
      val provider = GeminiCliProvider.make(config, executor)

      for
        response <- provider.executeStructured[StructuredReply]("test prompt", Json.Obj())
      yield assertTrue(response.summary == "uppercase-fence")
    },
    test("extractResponse returns final response from Gemini headless JSON") {
      val output =
        """{"response":"# Architecture Analysis\n\n## Recommended Improvements\nUse smaller modules.","stats":{"turns":1}}"""

      assertTrue(
        GeminiCliProvider.extractResponse(output) == Right(
          "# Architecture Analysis\n\n## Recommended Improvements\nUse smaller modules."
        )
      )
    },
    test("extractResponse ignores non-json prelude and uses the final JSON line") {
      val output =
        """YOLO mode is enabled. All tool calls will be automatically approved.
          |Loaded cached credentials.
          |{"response":"# Code Review Analysis\n\n## Findings\nOnly the final assistant message is kept."}
          |""".stripMargin

      assertTrue(
        GeminiCliProvider.extractResponse(output) == Right(
          "# Code Review Analysis\n\n## Findings\nOnly the final assistant message is kept."
        )
      )
    },
    test("extractResponse surfaces Gemini JSON error payloads") {
      val output =
        """{"response":null,"error":{"type":"rate_limit","message":"Quota exceeded","code":429}}"""

      assertTrue(
        GeminiCliProvider.extractResponse(output) == Left(
          "Gemini CLI returned an error (type=rate_limit, code=429): Quota exceeded"
        )
      )
    },
    test("extractResponse parses pretty-printed JSON after transcript prelude") {
      val output =
        """YOLO mode is enabled. All tool calls will be automatically approved.
          |Loaded cached credentials.
          |{
          |  "session_id": "s1",
          |  "response": "# Architecture Analysis\n\n## Summary\nParsed from pretty JSON.",
          |  "stats": {
          |    "turns": 1
          |  }
          |}
          |""".stripMargin

      assertTrue(
        GeminiCliProvider.extractResponse(output) == Right(
          "# Architecture Analysis\n\n## Summary\nParsed from pretty JSON."
        )
      )
    },
    test("extractResponse falls back to plain-text markdown after preamble when no JSON envelope is present") {
      val output =
        """Loaded cached credentials.
          |Loading extension: chrome-devtools-mcp
          |Loading extension: code-review
          |Server 'chrome-devtools' supports tool updates. Listening for changes...
          |Attempt 1 failed: You have exhausted your capacity on this model. Retrying after 5686ms...
          |## Security Analysis
          |
          |This is the plain-text analysis without a JSON wrapper.
          |""".stripMargin

      assertTrue(
        GeminiCliProvider.extractResponse(output) == Right(
          "## Security Analysis\n\nThis is the plain-text analysis without a JSON wrapper."
        )
      )
    },
    test("extractResponse falls back to plain prose after preamble when no JSON envelope and no markdown heading") {
      val output =
        """Loaded cached credentials.
          |Loading extension: chrome-devtools-mcp
          |Loading extension: code-review
          |Server 'chrome-devtools' supports tool updates. Listening for changes...
          |I have completed the read-only code review analysis of the repository. The results from the analysis are available in the previous turn. Let me know if you have any other questions.
          |""".stripMargin

      assertTrue(
        GeminiCliProvider.extractResponse(output) == Right(
          "I have completed the read-only code review analysis of the repository. The results from the analysis are available in the previous turn. Let me know if you have any other questions."
        )
      )
    },
    test("extractResponse returns error when output is only preamble with no markdown content") {
      val output =
        """Loaded cached credentials.
          |Loading extension: chrome-devtools-mcp
          |""".stripMargin

      assertTrue(
        GeminiCliProvider.extractResponse(output).isLeft
      )
    },
    test("parseStreamEvent decodes stream-json lines and preserves non-json output as log lines") {
      val initLine =
        """{"type":"init","session_id":"session-1","model":"gemini-2.5-pro"}"""
      val toolLine =
        """{"type":"tool_use","tool_name":"codebase_investigator","tool_id":"tool-1"}"""

      assertTrue(
        GeminiCliProvider.parseStreamEvent(initLine) == GeminiCliStreamEvent.Init(
          model = Some("gemini-2.5-pro"),
          sessionId = Some("session-1"),
        ),
        GeminiCliProvider.parseStreamEvent(toolLine) == GeminiCliStreamEvent.ToolUse(
          toolName = Some("codebase_investigator"),
          toolId = Some("tool-1"),
        ),
        GeminiCliProvider.parseStreamEvent("Error when talking to Gemini API Full report available at: ...") ==
          GeminiCliStreamEvent.LogLine("Error when talking to Gemini API Full report available at: ..."),
      )
    },
    test("TurnLimitError carries the configured limit") {
      val err = LlmError.TurnLimitError(Some(5))
      assertTrue(err.limit == Some(5))
    },
    test("TurnLimitError has None limit when not configured") {
      val err = LlmError.TurnLimitError()
      assertTrue(err.limit.isEmpty)
    },
    suite("normalizePromptForWindowsCmd")(
      test("replaces Unix newlines with spaces") {
        val prompt     = "Analyze the repo at:\n/path/to/repo\n\nDo not modify files."
        val normalized = GeminiCliExecutor.normalizePromptForWindowsCmd(prompt)
        assertTrue(
          !normalized.contains("\n"),
          normalized == "Analyze the repo at: /path/to/repo  Do not modify files.",
        )
      },
      test("replaces Windows CRLF sequences with spaces") {
        val prompt     = "Analyze the repo at:\r\n/path/to/repo\r\n\r\nDo not modify files."
        val normalized = GeminiCliExecutor.normalizePromptForWindowsCmd(prompt)
        assertTrue(
          !normalized.contains("\r"),
          !normalized.contains("\n"),
          normalized == "Analyze the repo at: /path/to/repo  Do not modify files.",
        )
      },
      test("replaces bare carriage returns with spaces") {
        val prompt     = "line1\rline2"
        val normalized = GeminiCliExecutor.normalizePromptForWindowsCmd(prompt)
        assertTrue(
          !normalized.contains("\r"),
          normalized == "line1 line2",
        )
      },
      test("leaves single-line prompts unchanged") {
        val prompt     = "Analyze the repo and return markdown only."
        val normalized = GeminiCliExecutor.normalizePromptForWindowsCmd(prompt)
        assertTrue(normalized == prompt)
      },
      test("handles empty prompt") {
        assertTrue(GeminiCliExecutor.normalizePromptForWindowsCmd("") == "")
      },
    ),
  )
