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
    test("parseStreamEvent decodes error event into Error variant") {
      val line = """{"type":"error","error":{"type":"rate_limit","message":"quota exceeded","code":429}}"""
      assertTrue(
        GeminiCliProvider.parseStreamEvent(line) == GeminiCliStreamEvent.Error(
          message = Some("quota exceeded"),
          code = Some(429),
          errorType = Some("rate_limit"),
        )
      )
    },
    test("parseStreamEvent decodes tool_use with input into ToolUse") {
      val line = """{"type":"tool_use","tool_name":"read_file","tool_id":"t1","tool_input":"{\"path\":\"/foo\"}"}"""
      assertTrue(
        GeminiCliProvider.parseStreamEvent(line) == GeminiCliStreamEvent.ToolUse(
          toolName = Some("read_file"),
          toolId = Some("t1"),
          input = Some("""{"path":"/foo"}"""),
        )
      )
    },
    test("parseStreamEvent decodes tool_result with content into ToolResult") {
      val line = """{"type":"tool_result","tool_id":"t1","status":"success","content":"file body"}"""
      assertTrue(
        GeminiCliProvider.parseStreamEvent(line) == GeminiCliStreamEvent.ToolResult(
          toolId = Some("t1"),
          status = Some("success"),
          content = Some("file body"),
        )
      )
    },
    test("parseStreamEvent decodes init event with model and session_id") {
      val line = """{"type":"init","model":"gemini-2.5-pro","session_id":"s42"}"""
      assertTrue(
        GeminiCliProvider.parseStreamEvent(line) == GeminiCliStreamEvent.Init(
          model = Some("gemini-2.5-pro"),
          sessionId = Some("s42"),
        )
      )
    },
    suite("GeminiSandbox")(
      test("envValue returns None for Default sandbox") {
        assertTrue(GeminiSandbox.envValue(GeminiSandbox.Default).isEmpty)
      },
      test("envValue returns docker for Docker sandbox") {
        assertTrue(GeminiSandbox.envValue(GeminiSandbox.Docker) == Some("docker"))
      },
      test("envValue returns podman for Podman sandbox") {
        assertTrue(GeminiSandbox.envValue(GeminiSandbox.Podman) == Some("podman"))
      },
      test("envValue returns sandbox-exec for SeatbeltMacOS sandbox") {
        assertTrue(GeminiSandbox.envValue(GeminiSandbox.SeatbeltMacOS) == Some("sandbox-exec"))
      },
      test("envValue returns runsc for Runsc sandbox") {
        assertTrue(GeminiSandbox.envValue(GeminiSandbox.Runsc) == Some("runsc"))
      },
      test("envValue returns lxc for Lxc sandbox") {
        assertTrue(GeminiSandbox.envValue(GeminiSandbox.Lxc) == Some("lxc"))
      },
    ),
    test("GeminiCliExecutionContext includes sandbox and turnLimit fields") {
      val ctx = GeminiCliExecutionContext(
        sandbox = Some(GeminiSandbox.Docker),
        turnLimit = Some(10),
      )
      assertTrue(
        ctx.sandbox == Some(GeminiSandbox.Docker),
        ctx.turnLimit == Some(10),
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
    test("GeminiCliStreamEvent.Error carries message, code, and errorType") {
      val event: GeminiCliStreamEvent.Error = GeminiCliStreamEvent.Error(
        message = Some("auth failed"),
        code = Some(401),
        errorType = Some("authentication_error"),
      )
      assertTrue(
        event.message == Some("auth failed"),
        event.code == Some(401),
        event.errorType == Some("authentication_error"),
      )
    },
    test("GeminiCliStreamEvent.ToolUse carries input field") {
      val event: GeminiCliStreamEvent.ToolUse = GeminiCliStreamEvent.ToolUse(
        toolName = Some("read_file"),
        toolId = Some("t1"),
        input = Some("""{"path":"/src/Main.scala"}"""),
      )
      assertTrue(event.input == Some("""{"path":"/src/Main.scala"}"""))
    },
    test("GeminiCliStreamEvent.ToolResult carries content field") {
      val event: GeminiCliStreamEvent.ToolResult = GeminiCliStreamEvent.ToolResult(
        toolId = Some("t1"),
        status = Some("success"),
        content = Some("file content here"),
      )
      assertTrue(event.content == Some("file content here"))
    },
    suite("buildGeminiArgs — new")(
      {
        val config = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val ctx    = GeminiCliExecutionContext()
        Seq(
          test("includes -p flag and prompt") {
            val args = GeminiCliExecutor.buildGeminiArgs("hello", config, ctx, "stream-json", false)
            assertTrue(args.contains("-p"), args.contains("hello"))
          },
          test("includes -m flag and model") {
            val args = GeminiCliExecutor.buildGeminiArgs("hello", config, ctx, "stream-json", false)
            assertTrue(args.contains("-m"), args.contains(config.model))
          },
          test("includes --output-format flag") {
            val args = GeminiCliExecutor.buildGeminiArgs("hello", config, ctx, "stream-json", false)
            assertTrue(args.contains("--output-format"), args.contains("stream-json"))
          },
          test("includes -s when sandbox is set") {
            val ctxS = GeminiCliExecutionContext(sandbox = Some(GeminiSandbox.Docker))
            val args = GeminiCliExecutor.buildGeminiArgs("hello", config, ctxS, "stream-json", false)
            assertTrue(args.contains("-s"))
          },
          test("no -s when sandbox is None") {
            val ctxN = GeminiCliExecutionContext(sandbox = None)
            val args = GeminiCliExecutor.buildGeminiArgs("hello", config, ctxN, "stream-json", false)
            assertTrue(!args.contains("-s"))
          },
          test("includes --turn-limit when turnLimit is set") {
            val ctxT = GeminiCliExecutionContext(turnLimit = Some(5))
            val args = GeminiCliExecutor.buildGeminiArgs("hello", config, ctxT, "stream-json", false)
            assertTrue(args.contains("--turn-limit"), args.contains("5"))
          },
          test("no --turn-limit when turnLimit is None") {
            val ctxT = GeminiCliExecutionContext(turnLimit = None)
            val args = GeminiCliExecutor.buildGeminiArgs("hello", config, ctxT, "stream-json", false)
            assertTrue(!args.contains("--turn-limit"))
          },
          test("includes --include-directories for each includeDirectories entry") {
            val ctxD = GeminiCliExecutionContext(includeDirectories = List("src", "docs"))
            val args = GeminiCliExecutor.buildGeminiArgs("hello", config, ctxD, "stream-json", false)
            assertTrue(
              args.count(_ == "--include-directories") == 2,
              args.contains("src"),
              args.contains("docs"),
            )
          },
          test("Default sandbox still produces -s flag") {
            val ctxDef = GeminiCliExecutionContext(sandbox = Some(GeminiSandbox.Default))
            val args   = GeminiCliExecutor.buildGeminiArgs("hello", config, ctxDef, "stream-json", false)
            assertTrue(args.contains("-s"))
          },
        )
      }*
    ),
    suite("buildGeminiArgs")(
      test("includes base flags") {
        val config = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val ctx    = GeminiCliExecutionContext()
        val args   = GeminiCliExecutor.buildGeminiArgs("my prompt", config, ctx, "stream-json", isWindows = false)
        assertTrue(
          args.contains("-p"),
          args.contains("my prompt"),
          args.contains("-m"),
          args.contains("gemini-2.5-pro"),
          args.contains("-y"),
          args.contains("--output-format"),
          args.contains("stream-json"),
        )
      },
      test("includes -s flag when sandbox is set") {
        val config = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val ctx    = GeminiCliExecutionContext(sandbox = Some(GeminiSandbox.Docker))
        val args   = GeminiCliExecutor.buildGeminiArgs("prompt", config, ctx, "json", isWindows = false)
        assertTrue(args.contains("-s"))
      },
      test("omits -s flag when sandbox is None") {
        val config = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val ctx    = GeminiCliExecutionContext(sandbox = None)
        val args   = GeminiCliExecutor.buildGeminiArgs("prompt", config, ctx, "json", isWindows = false)
        assertTrue(!args.contains("-s"))
      },
      test("includes --turn-limit when configured") {
        val config = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val ctx    = GeminiCliExecutionContext(turnLimit = Some(5))
        val args   = GeminiCliExecutor.buildGeminiArgs("prompt", config, ctx, "json", isWindows = false)
        assertTrue(
          args.contains("--turn-limit"),
          args.contains("5"),
        )
      },
      test("omits --turn-limit when not configured") {
        val config = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val ctx    = GeminiCliExecutionContext()
        val args   = GeminiCliExecutor.buildGeminiArgs("prompt", config, ctx, "json", isWindows = false)
        assertTrue(!args.contains("--turn-limit"))
      },
      test("includes --include-directories for each includeDirectories entry") {
        val config = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val ctx    = GeminiCliExecutionContext(includeDirectories = List("/a", "/b"))
        val args   = GeminiCliExecutor.buildGeminiArgs("prompt", config, ctx, "json", isWindows = false)
        assertTrue(
          args.count(_ == "--include-directories") == 2,
          args.contains("/a"),
          args.contains("/b"),
        )
      },
      test("deduplicates include-directories") {
        val config = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val ctx    = GeminiCliExecutionContext(includeDirectories = List("/a", "/a"))
        val args   = GeminiCliExecutor.buildGeminiArgs("prompt", config, ctx, "json", isWindows = false)
        assertTrue(args.count(_ == "/a") == 1)
      },
      test("normalizes prompt newlines on Windows") {
        val config    = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val ctx       = GeminiCliExecutionContext()
        val args      = GeminiCliExecutor.buildGeminiArgs("line1\nline2", config, ctx, "json", isWindows = true)
        val promptIdx = args.indexOf("-p") + 1
        assertTrue(!args(promptIdx).contains("\n"))
      },
      test("Default sandbox still produces -s flag") {
        val config = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val ctx    = GeminiCliExecutionContext(sandbox = Some(GeminiSandbox.Default))
        val args   = GeminiCliExecutor.buildGeminiArgs("prompt", config, ctx, "json", isWindows = false)
        assertTrue(args.contains("-s"))
      },
    ),
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
    suite("executeStream metadata and tool observability")(
      test("Init event propagates model and session_id to subsequent chunk metadata") {
        val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val executor = new MockGeminiCliExecutor(
          streamEvents = List(
            GeminiCliStreamEvent.Init(model = Some("gemini-2.5-pro-live"), sessionId = Some("sess-99")),
            GeminiCliStreamEvent.Message(role = Some("assistant"), content = Some("hello"), delta = true),
            GeminiCliStreamEvent.Result(status = Some("success"), errorMessage = None, stats = None),
          )
        )
        val provider = GeminiCliProvider.make(config, executor)
        for
          chunks    <- provider.executeStream("hi").runCollect
          textChunks = chunks.filter(_.delta.nonEmpty)
        yield assertTrue(
          textChunks.nonEmpty,
          textChunks.head.metadata.get("session_id") == Some("sess-99"),
          textChunks.head.metadata.get("model") == Some("gemini-2.5-pro-live"),
        )
      },
      test("ToolUse event emits a zero-delta chunk with tool_use metadata") {
        val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val executor = new MockGeminiCliExecutor(
          streamEvents = List(
            GeminiCliStreamEvent.ToolUse(
              toolName = Some("read_file"),
              toolId = Some("t42"),
              input = Some("""{"path":"/foo"}"""),
            ),
            GeminiCliStreamEvent.Message(role = Some("assistant"), content = Some("done"), delta = true),
            GeminiCliStreamEvent.Result(status = Some("success"), errorMessage = None, stats = None),
          )
        )
        val provider = GeminiCliProvider.make(config, executor)
        for
          chunks    <- provider.executeStream("hi").runCollect
          toolChunks = chunks.filter(_.metadata.get("event").contains("tool_use"))
        yield assertTrue(
          toolChunks.size == 1,
          toolChunks.head.delta.isEmpty,
          toolChunks.head.metadata.get("tool_name") == Some("read_file"),
          toolChunks.head.metadata.get("tool_id") == Some("t42"),
          toolChunks.head.metadata.get("tool_input") == Some("""{"path":"/foo"}"""),
        )
      },
      test("ToolResult event emits a zero-delta chunk with tool_result metadata") {
        val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val executor = new MockGeminiCliExecutor(
          streamEvents = List(
            GeminiCliStreamEvent.ToolResult(
              toolId = Some("t42"),
              status = Some("success"),
              content = Some("file body"),
            ),
            GeminiCliStreamEvent.Result(status = Some("success"), errorMessage = None, stats = None),
          )
        )
        val provider = GeminiCliProvider.make(config, executor)
        for
          chunks      <- provider.executeStream("hi").runCollect
          resultChunks = chunks.filter(_.metadata.get("event").contains("tool_result"))
        yield assertTrue(
          resultChunks.size == 1,
          resultChunks.head.delta.isEmpty,
          resultChunks.head.metadata.get("tool_id") == Some("t42"),
          resultChunks.head.metadata.get("tool_status") == Some("success"),
          resultChunks.head.metadata.get("tool_content") == Some("file body"),
        )
      },
      test("Error stream event fails the stream with ProviderError") {
        val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val executor = new MockGeminiCliExecutor(
          streamEvents = List(
            GeminiCliStreamEvent.Error(
              message = Some("connection reset"),
              code = Some(500),
              errorType = Some("server_error"),
            )
          )
        )
        val provider = GeminiCliProvider.make(config, executor)
        for
          result <- provider.executeStream("hi").runCollect.exit
        yield assertTrue(result.isFailure)
      },
    ),
    suite("formatHistory via executeStreamWithHistory")(
      test("empty message list fails with InvalidRequestError") {
        val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val executor = new MockGeminiCliExecutor()
        val provider = GeminiCliProvider.make(config, executor)
        for
          result <- provider.executeStreamWithHistory(Nil).runCollect.exit
        yield assertTrue(result.isFailure)
      },
      test("system-only message list fails with InvalidRequestError") {
        val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val executor = new MockGeminiCliExecutor()
        val provider = GeminiCliProvider.make(config, executor)
        for
          result <- provider.executeStreamWithHistory(
                      List(Message(MessageRole.System, "You are an assistant."))
                    ).runCollect.exit
        yield assertTrue(result.isFailure)
      },
      test("user+assistant messages formatted with bold role markers") {
        val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val messages = List(
          Message(MessageRole.User, "Hello"),
          Message(MessageRole.Assistant, "Hi there"),
          Message(MessageRole.User, "What is 2+2?"),
        )
        for
          capturedRef    <- Ref.make("")
          executor        = new MockGeminiCliExecutor(
                              streamEvents = List(
                                GeminiCliStreamEvent.Message(role = Some("assistant"), content = Some("ok"), delta = true),
                                GeminiCliStreamEvent.Result(status = Some("success"), errorMessage = None, stats = None),
                              )
                            ) {
                              override def runGeminiProcessStream(
                                prompt: String,
                                config: LlmConfig,
                                executionContext: GeminiCliExecutionContext,
                              ): ZStream[Any, LlmError, GeminiCliStreamEvent] =
                                ZStream.fromZIO(capturedRef.set(prompt)).drain ++ super.runGeminiProcessStream(
                                  prompt,
                                  config,
                                  executionContext,
                                )
                            }
          provider        = GeminiCliProvider.make(config, executor)
          _              <- provider.executeStreamWithHistory(messages).runDrain
          capturedPrompt <- capturedRef.get
        yield assertTrue(
          capturedPrompt.contains("**User:**"),
          capturedPrompt.contains("**Assistant:**"),
          capturedPrompt.contains("Hello"),
          capturedPrompt.contains("Hi there"),
          capturedPrompt.contains("What is 2+2?"),
          !capturedPrompt.startsWith("[SYSTEM CONTEXT]"),
        )
      },
      test("system messages appear in [SYSTEM CONTEXT] block") {
        val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val messages = List(
          Message(MessageRole.System, "You are a helpful assistant."),
          Message(MessageRole.User, "Hello"),
        )
        for
          capturedRef    <- Ref.make("")
          executor        = new MockGeminiCliExecutor(
                              streamEvents = List(
                                GeminiCliStreamEvent.Result(status = Some("success"), errorMessage = None, stats = None)
                              )
                            ) {
                              override def runGeminiProcessStream(
                                prompt: String,
                                config: LlmConfig,
                                executionContext: GeminiCliExecutionContext,
                              ): ZStream[Any, LlmError, GeminiCliStreamEvent] =
                                ZStream.fromZIO(capturedRef.set(prompt)).drain ++ super.runGeminiProcessStream(
                                  prompt,
                                  config,
                                  executionContext,
                                )
                            }
          provider        = GeminiCliProvider.make(config, executor)
          _              <- provider.executeStreamWithHistory(messages).runDrain
          capturedPrompt <- capturedRef.get
        yield assertTrue(
          capturedPrompt.startsWith("[SYSTEM CONTEXT]"),
          capturedPrompt.contains("You are a helpful assistant."),
          capturedPrompt.contains("---"),
          capturedPrompt.contains("**User:**"),
        )
      },
      test("single user message formats correctly") {
        val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val messages = List(Message(MessageRole.User, "hello"))
        for
          capturedRef    <- Ref.make("")
          executor        = new MockGeminiCliExecutor(
                              streamEvents = List(
                                GeminiCliStreamEvent.Result(status = Some("success"), errorMessage = None, stats = None)
                              )
                            ) {
                              override def runGeminiProcessStream(
                                prompt: String,
                                config: LlmConfig,
                                executionContext: GeminiCliExecutionContext,
                              ): ZStream[Any, LlmError, GeminiCliStreamEvent] =
                                ZStream.fromZIO(capturedRef.set(prompt)).drain ++ super.runGeminiProcessStream(
                                  prompt,
                                  config,
                                  executionContext,
                                )
                            }
          provider        = GeminiCliProvider.make(config, executor)
          _              <- provider.executeStreamWithHistory(messages).runDrain
          capturedPrompt <- capturedRef.get
        yield assertTrue(
          capturedPrompt == "**User:** hello"
        )
      },
      test("system + user message formats correctly") {
        val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val messages = List(
          Message(MessageRole.System, "Be concise."),
          Message(MessageRole.User, "What time is it?"),
        )
        for
          capturedRef    <- Ref.make("")
          executor        = new MockGeminiCliExecutor(
                              streamEvents = List(
                                GeminiCliStreamEvent.Result(status = Some("success"), errorMessage = None, stats = None)
                              )
                            ) {
                              override def runGeminiProcessStream(
                                prompt: String,
                                config: LlmConfig,
                                executionContext: GeminiCliExecutionContext,
                              ): ZStream[Any, LlmError, GeminiCliStreamEvent] =
                                ZStream.fromZIO(capturedRef.set(prompt)).drain ++ super.runGeminiProcessStream(
                                  prompt,
                                  config,
                                  executionContext,
                                )
                            }
          provider        = GeminiCliProvider.make(config, executor)
          _              <- provider.executeStreamWithHistory(messages).runDrain
          capturedPrompt <- capturedRef.get
        yield assertTrue(
          capturedPrompt.startsWith("[SYSTEM CONTEXT]"),
          capturedPrompt.contains("**User:**"),
        )
      },
      test("user + assistant + user formats with newline separators") {
        val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val messages = List(
          Message(MessageRole.User, "First question"),
          Message(MessageRole.Assistant, "First answer"),
          Message(MessageRole.User, "Second question"),
        )
        for
          capturedRef    <- Ref.make("")
          executor        = new MockGeminiCliExecutor(
                              streamEvents = List(
                                GeminiCliStreamEvent.Result(status = Some("success"), errorMessage = None, stats = None)
                              )
                            ) {
                              override def runGeminiProcessStream(
                                prompt: String,
                                config: LlmConfig,
                                executionContext: GeminiCliExecutionContext,
                              ): ZStream[Any, LlmError, GeminiCliStreamEvent] =
                                ZStream.fromZIO(capturedRef.set(prompt)).drain ++ super.runGeminiProcessStream(
                                  prompt,
                                  config,
                                  executionContext,
                                )
                            }
          provider        = GeminiCliProvider.make(config, executor)
          _              <- provider.executeStreamWithHistory(messages).runDrain
          capturedPrompt <- capturedRef.get
        yield assertTrue(
          capturedPrompt.count(_.toString == "*") >= 6, // at least 3 bold markers (**X:**)
          capturedPrompt.contains("**User:**"),
          capturedPrompt.contains("**Assistant:**"),
          capturedPrompt.contains("First question"),
          capturedPrompt.contains("First answer"),
          capturedPrompt.contains("Second question"),
        )
      },
    ),
    suite("exit code error semantics")(
      test("TurnLimitError is an LlmError") {
        val err: LlmError = LlmError.TurnLimitError(Some(3))
        assertTrue(err.isInstanceOf[LlmError])
      },
      test("executeStream surfaces TurnLimitError from executor") {
        val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        // shouldSucceed = true so checkGeminiInstalled passes; stream override controls the failure
        val executor = new MockGeminiCliExecutor(shouldSucceed = true) {
          override def runGeminiProcessStream(
            prompt: String,
            config: LlmConfig,
            executionContext: GeminiCliExecutionContext,
          ): ZStream[Any, LlmError, GeminiCliStreamEvent] =
            ZStream.fail(LlmError.TurnLimitError(Some(3)))
        }
        val provider = GeminiCliProvider.make(config, executor)
        for
          result <- provider.executeStream("test").runCollect.exit
        yield assertTrue(result.isFailure)
      },
      test("executeStream surfaces InvalidRequestError from executor") {
        val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        // shouldSucceed = true so checkGeminiInstalled passes; stream override controls the failure
        val executor = new MockGeminiCliExecutor(shouldSucceed = true) {
          override def runGeminiProcessStream(
            prompt: String,
            config: LlmConfig,
            executionContext: GeminiCliExecutionContext,
          ): ZStream[Any, LlmError, GeminiCliStreamEvent] =
            ZStream.fail(LlmError.InvalidRequestError("bad input (exit 42)"))
        }
        val provider = GeminiCliProvider.make(config, executor)
        for
          result <- provider.executeStream("test").runCollect.exit
        yield assertTrue(result.isFailure)
      },
      test("exit 0 returns success") {
        for
          result <- GeminiCliExecutor.validateExitCode(0, "", None).exit
        yield assertTrue(result == Exit.succeed(()))
      },
      test("exit 1 returns ProviderError") {
        for
          result <- GeminiCliExecutor.validateExitCode(1, "oops", None).exit
        yield assertTrue(
          result.isFailure,
          result.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[LlmError.ProviderError]),
        )
      },
      test("exit 42 returns InvalidRequestError") {
        for
          result <- GeminiCliExecutor.validateExitCode(42, "bad input", None).exit
        yield assertTrue(
          result.isFailure,
          result.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[LlmError.InvalidRequestError]),
        )
      },
      test("exit 53 returns TurnLimitError with limit from context") {
        for
          result <- GeminiCliExecutor.validateExitCode(53, "", Some(3)).exit
        yield assertTrue(
          result.isFailure,
          result.causeOption.flatMap(_.failureOption).contains(LlmError.TurnLimitError(Some(3))),
        )
      },
      test("exit 53 returns TurnLimitError with None when no limit set") {
        for
          result <- GeminiCliExecutor.validateExitCode(53, "", None).exit
        yield assertTrue(
          result.isFailure,
          result.causeOption.flatMap(_.failureOption).contains(LlmError.TurnLimitError(None)),
        )
      },
      test("exit 99 returns ProviderError as fallback") {
        for
          result <- GeminiCliExecutor.validateExitCode(99, "unknown", None).exit
        yield assertTrue(
          result.isFailure,
          result.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[LlmError.ProviderError]),
        )
      },
    ),
    suite("CliConnector")(
      test("implements CliConnector with correct id") {
        val executor  = new MockGeminiCliExecutor()
        val config    = LlmConfig(LlmProvider.GeminiCli, "gemini-2.5-flash")
        val connector = GeminiCliProvider.make(config, executor)
        assertTrue(
          connector.id == ConnectorId.GeminiCli,
          connector.kind == ConnectorKind.Cli,
        )
      },
      test("buildArgv produces gemini CLI flags") {
        val executor  = new MockGeminiCliExecutor()
        val config    = LlmConfig(LlmProvider.GeminiCli, "gemini-2.5-flash")
        val connector = GeminiCliProvider.make(config, executor)
        val ctx       = CliContext(worktreePath = "/workspace", repoPath = "/repo")
        val argv      = connector.buildArgv("fix the bug", ctx)
        assertTrue(
          argv.contains("gemini"),
          argv.contains("--yolo"),
          argv.contains("-p"),
          argv.contains("fix the bug"),
          argv.contains("--include-directories"),
          argv.contains("/repo"),
        )
      },
      test("buildArgv omits --include-directories when repoPath is empty") {
        val executor  = new MockGeminiCliExecutor()
        val config    = LlmConfig(LlmProvider.GeminiCli, "gemini-2.5-flash")
        val connector = GeminiCliProvider.make(config, executor)
        val ctx       = CliContext(worktreePath = "/workspace", repoPath = "")
        val argv      = connector.buildArgv("fix the bug", ctx)
        assertTrue(
          !argv.contains("--include-directories"),
        )
      },
      test("buildArgv includes --turn-limit when set") {
        val executor  = new MockGeminiCliExecutor()
        val config    = LlmConfig(LlmProvider.GeminiCli, "gemini-2.5-flash")
        val connector = GeminiCliProvider.make(config, executor)
        val ctx       = CliContext(worktreePath = "/workspace", repoPath = "/repo", turnLimit = Some(10))
        val argv      = connector.buildArgv("fix the bug", ctx)
        assertTrue(
          argv.contains("--turn-limit"),
          argv.contains("10"),
        )
      },
      test("buildInteractiveArgv does not include -p or prompt") {
        val executor  = new MockGeminiCliExecutor()
        val config    = LlmConfig(LlmProvider.GeminiCli, "gemini-2.5-flash")
        val connector = GeminiCliProvider.make(config, executor)
        val ctx       = CliContext(worktreePath = "/workspace", repoPath = "/repo")
        val argv      = connector.buildInteractiveArgv(ctx)
        assertTrue(
          argv.contains("gemini"),
          argv.contains("--yolo"),
          argv.contains("--include-directories"),
          !argv.contains("-p"),
        )
      },
      test("interactionSupport is InteractiveStdin") {
        val executor  = new MockGeminiCliExecutor()
        val config    = LlmConfig(LlmProvider.GeminiCli, "gemini-2.5-flash")
        val connector = GeminiCliProvider.make(config, executor)
        assertTrue(connector.interactionSupport == InteractionSupport.InteractiveStdin)
      },
      test("healthCheck returns Healthy when gemini is installed") {
        val executor  = new MockGeminiCliExecutor(shouldSucceed = true)
        val config    = LlmConfig(LlmProvider.GeminiCli, "gemini-2.5-flash")
        val connector = GeminiCliProvider.make(config, executor)
        for
          status <- connector.healthCheck
        yield assertTrue(
          status.availability == Availability.Healthy,
          status.authStatus == AuthStatus.Valid,
        )
      },
      test("healthCheck returns Unhealthy when gemini is not installed") {
        val executor  = new MockGeminiCliExecutor(shouldSucceed = false)
        val config    = LlmConfig(LlmProvider.GeminiCli, "gemini-2.5-flash")
        val connector = GeminiCliProvider.make(config, executor)
        for
          status <- connector.healthCheck
        yield assertTrue(
          status.availability == Availability.Unhealthy,
        )
      },
      test("completeStream delegates to executeStream") {
        val config    = LlmConfig(LlmProvider.GeminiCli, "gemini-2.5-flash")
        val executor  = new MockGeminiCliExecutor(
          streamEvents = List(
            GeminiCliStreamEvent.Message(role = Some("assistant"), content = Some("hello"), delta = true),
            GeminiCliStreamEvent.Result(status = Some("success"), errorMessage = None, stats = None),
          )
        )
        val connector = GeminiCliProvider.make(config, executor)
        for
          chunks <- connector.completeStream("hi").runCollect
        yield assertTrue(chunks.map(_.delta).mkString.contains("hello"))
      },
    ),
    suite("executeStream event handling")(
      test("Init event populates metadata for subsequent chunks") {
        val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val executor = new MockGeminiCliExecutor(
          shouldSucceed = true,
          streamEvents = List(
            GeminiCliStreamEvent.Init(model = Some("gemini-2.5-pro"), sessionId = Some("sess-1")),
            GeminiCliStreamEvent.Message(role = Some("assistant"), content = Some("hello"), delta = true),
            GeminiCliStreamEvent.Result(status = Some("success"), errorMessage = None, stats = None),
          ),
        )
        val provider = GeminiCliProvider.make(config, executor)
        for
          chunks    <- provider.executeStream("hi").runCollect
          textChunks = chunks.filter(_.delta.nonEmpty)
        yield assertTrue(
          textChunks.nonEmpty,
          textChunks.exists(_.metadata.get("model") == Some("gemini-2.5-pro")),
          textChunks.exists(_.metadata.get("sessionId") == Some("sess-1")),
        )
      },
      test("Error event causes stream failure") {
        val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val executor = new MockGeminiCliExecutor(
          shouldSucceed = true,
          streamEvents = List(
            GeminiCliStreamEvent.Error(
              message = Some("api error"),
              code = Some(500),
              errorType = Some("server_error"),
            )
          ),
        )
        val provider = GeminiCliProvider.make(config, executor)
        for
          result <- provider.executeStream("hi").runCollect.exit
        yield assertTrue(result.isFailure)
      },
      test("Result with error status causes stream failure") {
        val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val executor = new MockGeminiCliExecutor(
          shouldSucceed = true,
          streamEvents = List(
            GeminiCliStreamEvent.Result(
              status = Some("error"),
              errorMessage = Some("something went wrong"),
              stats = None,
            )
          ),
        )
        val provider = GeminiCliProvider.make(config, executor)
        for
          result <- provider.executeStream("hi").runCollect.exit
        yield assertTrue(result.isFailure)
      },
      test("ToolUse event emits observability chunk") {
        val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val executor = new MockGeminiCliExecutor(
          shouldSucceed = true,
          streamEvents = List(
            GeminiCliStreamEvent.ToolUse(
              toolName = Some("read_file"),
              toolId = Some("t1"),
              input = Some("""{"path":"foo.txt"}"""),
            ),
            GeminiCliStreamEvent.Result(status = Some("success"), errorMessage = None, stats = None),
          ),
        )
        val provider = GeminiCliProvider.make(config, executor)
        for
          chunks <- provider.executeStream("hi").runCollect
        yield assertTrue(
          chunks.exists(_.metadata.get("toolName") == Some("read_file"))
        )
      },
      test("ToolResult event emits observability chunk") {
        val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
        val executor = new MockGeminiCliExecutor(
          shouldSucceed = true,
          streamEvents = List(
            GeminiCliStreamEvent.ToolResult(
              toolId = Some("t1"),
              status = Some("success"),
              content = Some("file content"),
            ),
            GeminiCliStreamEvent.Result(status = Some("success"), errorMessage = None, stats = None),
          ),
        )
        val provider = GeminiCliProvider.make(config, executor)
        for
          chunks <- provider.executeStream("hi").runCollect
        yield assertTrue(
          chunks.exists(_.metadata.get("toolId") == Some("t1"))
        )
      },
    ),
  )
