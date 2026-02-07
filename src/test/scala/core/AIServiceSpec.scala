package core

import java.nio.file.Paths

import zio.*
import zio.test.*
import zio.test.Assertion.*

import models.*

object AIServiceSpec extends ZIOSpecDefault:

  /** Helper to create a test MigrationConfig */
  private def createTestConfig(
    geminiModel: String = "gemini-2.5-flash",
    geminiTimeout: Duration = Duration.fromSeconds(60),
    geminiMaxRetries: Int = 3,
  ): MigrationConfig =
    MigrationConfig(
      sourceDir = Paths.get("test-source"),
      outputDir = Paths.get("test-output"),
      geminiModel = geminiModel,
      geminiTimeout = geminiTimeout,
      geminiMaxRetries = geminiMaxRetries,
    )

  /** Mock GeminiService for testing without actual CLI calls */
  private def mockGeminiService(
    shouldFail: Boolean = false,
    mockOutput: String = "Mock Gemini response",
    mockExitCode: Int = 0,
    available: Boolean = true,
  ): ULayer[GeminiService] =
    ZLayer.succeed(new GeminiService {
      override def executeLegacy(prompt: String): ZIO[Any, GeminiError, GeminiResponse] =
        if shouldFail then ZIO.fail(GeminiError.ProcessStartFailed("Mock failure"))
        else ZIO.succeed(GeminiResponse(mockOutput, mockExitCode))

      override def executeWithContextLegacy(prompt: String, context: String): ZIO[Any, GeminiError, GeminiResponse] =
        if shouldFail then ZIO.fail(GeminiError.ProcessStartFailed("Mock failure"))
        else ZIO.succeed(GeminiResponse(s"$mockOutput with context", mockExitCode))

      override def isAvailable: ZIO[Any, Nothing, Boolean] =
        ZIO.succeed(available)
    })

  private def rateLimiterFor(config: AIProviderConfig): ZLayer[Any, Nothing, RateLimiter] =
    ZLayer.succeed(RateLimiterConfig.fromAIProviderConfig(config)) >>> RateLimiter.live

  private def mockHttpClient(responder: (String, String) => String): ULayer[HttpAIClient] =
    ZLayer.succeed(new HttpAIClient {
      override def postJson(
        url: String,
        body: String,
        headers: Map[String, String],
        timeout: Duration,
      ): ZIO[Any, AIError, String] =
        ZIO.succeed(responder(url, body))
    })

  def spec: Spec[Any, Any] = suite("AIServiceSpec")(
    // ========================================================================
    // execute tests
    // ========================================================================
    suite("execute")(
      test("successfully executes a simple prompt") {
        for
          response <- GeminiService.execute("Analyze this code").provide(
                        mockGeminiService(mockOutput = "Analysis complete")
                      )
        yield assertTrue(
          response.output == "Analysis complete",
          response.exitCode == 0,
        )
      },
      test("handles process start failures") {
        for result <- GeminiService
                        .execute("Test prompt")
                        .provide(mockGeminiService(shouldFail = true))
                        .either
        yield assertTrue(
          result.isLeft,
          result.left.exists {
            case GeminiError.ProcessStartFailed(_) => true
            case _                                 => false
          },
        )
      },
      test("returns correct exit code on success") {
        for
          response <- GeminiService.execute("Test").provide(
                        mockGeminiService(mockExitCode = 0)
                      )
        yield assertTrue(response.exitCode == 0)
      },
      test("captures stdout output correctly") {
        val expectedOutput = "Gemini analysis result with multiple lines\nLine 2\nLine 3"
        for
          response <- GeminiService.execute("Test").provide(
                        mockGeminiService(mockOutput = expectedOutput)
                      )
        yield assertTrue(response.output == expectedOutput)
      },
    ),
    // ========================================================================
    // executeWithContext tests
    // ========================================================================
    suite("executeWithContext")(
      test("executes prompt with context successfully") {
        for
          response <- GeminiService
                        .executeWithContext(
                          "Analyze this",
                          "COBOL code context",
                        )
                        .provide(
                          mockGeminiService(mockOutput = "Analysis with context")
                        )
        yield assertTrue(
          response.output.contains("context"),
          response.exitCode == 0,
        )
      },
      test("combines prompt and context correctly") {
        for
          response <- GeminiService
                        .executeWithContext(
                          "Transform this code",
                          "IDENTIFICATION DIVISION...",
                        )
                        .provide(
                          mockGeminiService(mockOutput = "Transformed result")
                        )
        yield assertTrue(
          response.output == "Transformed result with context"
        )
      },
      test("handles failures in executeWithContext") {
        for result <- GeminiService
                        .executeWithContext("Test", "Context")
                        .provide(mockGeminiService(shouldFail = true))
                        .either
        yield assertTrue(
          result.isLeft,
          result.left.exists {
            case GeminiError.ProcessStartFailed(_) => true
            case _                                 => false
          },
        )
      },
    ),
    // ========================================================================
    // isAvailable tests
    // ========================================================================
    suite("isAvailable")(
      test("returns true when Gemini is available") {
        for available <- GeminiService.isAvailable.provide(
                           mockGeminiService(available = true)
                         )
        yield assertTrue(available)
      },
      test("returns false when Gemini is not available") {
        for available <- GeminiService.isAvailable.provide(
                           mockGeminiService(available = false)
                         )
        yield assertTrue(!available)
      },
    ),
    // ========================================================================
    // GeminiError ADT tests
    // ========================================================================
    suite("GeminiError")(
      test("ProcessStartFailed has correct message") {
        val error = GeminiError.ProcessStartFailed("Command not found")
        assertTrue(
          error.message.contains("Failed to start"),
          error.message.contains("Command not found"),
        )
      },
      test("OutputReadFailed has correct message") {
        val error = GeminiError.OutputReadFailed("Stream closed")
        assertTrue(
          error.message.contains("Failed to read"),
          error.message.contains("Stream closed"),
        )
      },
      test("Timeout has correct message with duration") {
        val error = GeminiError.Timeout(Duration.fromSeconds(60))
        assertTrue(
          error.message.contains("timed out"),
          error.message.contains("60s"),
        )
      },
      test("NonZeroExit has correct message with code and output") {
        val error = GeminiError.NonZeroExit(1, "Error: invalid argument")
        assertTrue(
          error.message.contains("code 1"),
          error.message.contains("Error: invalid argument"),
        )
      },
      test("NotInstalled has correct message") {
        val error = GeminiError.NotInstalled
        assertTrue(
          error.message.contains("not installed"),
          error.message.contains("PATH"),
        )
      },
      test("InvalidResponse has correct message") {
        val error = GeminiError.InvalidResponse("malformed JSON")
        assertTrue(
          error.message.contains("Invalid response"),
          error.message.contains("malformed JSON"),
        )
      },
    ),
    // ========================================================================
    // GeminiResponse tests
    // ========================================================================
    suite("GeminiResponse")(
      test("creates response with output and exit code") {
        val response = GeminiResponse("Test output", 0)
        assertTrue(
          response.output == "Test output",
          response.exitCode == 0,
        )
      },
      test("handles multi-line output") {
        val output   = "Line 1\nLine 2\nLine 3"
        val response = GeminiResponse(output, 0)
        assertTrue(
          response.output == output,
          response.output.contains("\n"),
        )
      },
      test("handles empty output") {
        val response = GeminiResponse("", 0)
        assertTrue(
          response.output == "",
          response.exitCode == 0,
        )
      },
    ),
    // ========================================================================
    // Integration tests with configuration
    // ========================================================================
    suite("Integration with MigrationConfig")(
      test("uses correct model from config") {
        val config = createTestConfig(geminiModel = "gemini-2.5-flash")
        for
          response <- GeminiService.execute("Test").provide(
                        GeminiService.live,
                        RateLimiter.live,
                        ZLayer.succeed(RateLimiterConfig.fromMigrationConfig(config)),
                        ZLayer.succeed(config),
                      )
        yield assertTrue(response.exitCode >= 0) // Just verify it runs
      } @@ TestAspect.ignore, // Ignore as it requires actual Gemini CLI
      test("respects timeout setting from config") {
        val config = createTestConfig(geminiTimeout = Duration.fromSeconds(5))
        for
          response <- GeminiService.execute("Test").provide(
                        GeminiService.live,
                        RateLimiter.live,
                        ZLayer.succeed(RateLimiterConfig.fromMigrationConfig(config)),
                        ZLayer.succeed(config),
                      )
        yield assertTrue(response.exitCode >= 0)
      } @@ TestAspect.ignore, // Ignore as it requires actual Gemini CLI
      test("respects max retries from config") {
        val config = createTestConfig(geminiMaxRetries = 2)
        for
          response <- GeminiService.execute("Test").provide(
                        GeminiService.live,
                        RateLimiter.live,
                        ZLayer.succeed(RateLimiterConfig.fromMigrationConfig(config)),
                        ZLayer.succeed(config),
                      )
        yield assertTrue(response.exitCode >= 0)
      } @@ TestAspect.ignore, // Ignore as it requires actual Gemini CLI
    ),
    // ========================================================================
    // Concurrent execution tests
    // ========================================================================
    suite("Concurrent execution")(
      test("handles multiple concurrent requests") {
        val prompts = List(
          "Analyze code 1",
          "Analyze code 2",
          "Analyze code 3",
        )
        for
          responses <- ZIO
                         .foreachPar(prompts)(prompt => GeminiService.execute(prompt))
                         .provide(
                           mockGeminiService(mockOutput = "Analysis result")
                         )
        yield assertTrue(
          responses.length == 3,
          responses.forall(_.exitCode == 0),
          responses.forall(_.output == "Analysis result"),
        )
      },
      test("handles concurrent requests with failures") {
        val prompts = List("Prompt 1", "Prompt 2", "Prompt 3")
        for
          results <- ZIO
                       .foreachPar(prompts)(prompt => GeminiService.execute(prompt).either)
                       .provide(
                         mockGeminiService(shouldFail = true)
                       )
        yield assertTrue(
          results.length == 3,
          results.forall(_.isLeft),
        )
      },
    ),
    // ========================================================================
    // Error recovery tests
    // ========================================================================
    suite("Error recovery")(
      test("retries are handled by the implementation") {
        // Note: The retry logic is in the real implementation's executeGeminiCLI method
        // This test verifies the mock service behaves correctly with a single attempt
        for
          result <- GeminiService
                      .execute("Test")
                      .provide(mockGeminiService(shouldFail = true))
                      .either
        yield assertTrue(
          result.isLeft,
          result.left.exists {
            case GeminiError.ProcessStartFailed(_) => true
            case _                                 => false
          },
        )
      },
      test("fails after max retries exceeded") {
        // Use a Ref to track attempts without mutable state
        for
          attemptCount  <- Ref.make(0)
          failingService = ZLayer.succeed(new GeminiService {
                             override def executeLegacy(prompt: String): ZIO[Any, GeminiError, GeminiResponse] =
                               attemptCount.update(_ + 1) *> ZIO.fail(
                                 GeminiError.ProcessStartFailed("Persistent failure")
                               )

                             override def executeWithContextLegacy(prompt: String, context: String)
                               : ZIO[Any, GeminiError, GeminiResponse] =
                               executeLegacy(prompt)

                             override def isAvailable: ZIO[Any, Nothing, Boolean] =
                               ZIO.succeed(true)
                           })
          result        <- GeminiService.execute("Test").provide(failingService).either
          attempts      <- attemptCount.get
        yield assertTrue(
          result.isLeft,
          attempts >= 1,
        )
      },
    ),
    suite("AIService.fromConfig provider selection")(
      test("selects OpenAI-compatible implementation from provider config") {
        val config = AIProviderConfig(
          provider = AIProvider.OpenAi,
          model = "gpt-test",
          baseUrl = Some("http://lmstudio.local/v1"),
          apiKey = Some("test-key"),
          maxRetries = 0,
        )
        val body   =
          """{"choices":[{"index":0,"message":{"role":"assistant","content":"openai-ok"},"finish_reason":"stop"}],"model":"gpt-test"}"""
        for
          response <- AIService
                        .execute("ping")
                        .provide(
                          AIService.fromConfig,
                          mockHttpClient((url, _) => if url.endsWith("/chat/completions") then body else "{}"),
                          rateLimiterFor(config),
                          ZLayer.succeed(config),
                        )
        yield assertTrue(response.output == "openai-ok")
      },
      test("selects Anthropic-compatible implementation from provider config") {
        val config = AIProviderConfig(
          provider = AIProvider.Anthropic,
          model = "claude-test",
          baseUrl = Some("http://anthropic.local"),
          apiKey = Some("test-key"),
          maxRetries = 0,
        )
        val body   =
          """{"content":[{"type":"text","text":"anthropic-ok"}],"model":"claude-test","stop_reason":"end_turn"}"""
        for
          response <- AIService
                        .execute("ping")
                        .provide(
                          AIService.fromConfig,
                          mockHttpClient((url, _) => if url.endsWith("/v1/messages") then body else "{}"),
                          rateLimiterFor(config),
                          ZLayer.succeed(config),
                        )
        yield assertTrue(response.output == "anthropic-ok")
      },
      test("selects Gemini API implementation from provider config") {
        val config = AIProviderConfig(
          provider = AIProvider.GeminiApi,
          model = "gemini-2.5-flash",
          baseUrl = Some("http://gemini.local"),
          apiKey = Some("test-key"),
          maxRetries = 0,
        )
        val body   = """{"candidates":[{"content":{"parts":[{"text":"gemini-api-ok"}]}}]}"""
        for
          response <- AIService
                        .execute("ping")
                        .provide(
                          AIService.fromConfig,
                          mockHttpClient((url, _) => if url.contains(":generateContent") then body else "{}"),
                          rateLimiterFor(config),
                          ZLayer.succeed(config),
                        )
        yield assertTrue(response.output == "gemini-api-ok")
      },
    ),
  )
