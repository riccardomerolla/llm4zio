package llm4zio.providers

import zio.*
import zio.test.*

import llm4zio.core.*

object GeminiCliProviderSpec extends ZIOSpecDefault:
  // Mock executor for testing
  class MockGeminiCliExecutor(shouldSucceed: Boolean = true) extends GeminiCliExecutor:
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

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("GeminiCliProvider")(
    test("execute should return response") {
      val config   = LlmConfig(
        provider = LlmProvider.GeminiCli,
        model = "gemini-2.0-flash-exp",
      )
      val executor = new MockGeminiCliExecutor()
      val provider = GeminiCliProvider.make(config, executor)

      for {
        response <- provider.execute("test prompt")
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
        result <- provider.execute("test").exit
      } yield assertTrue(result.isFailure)
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
  )
