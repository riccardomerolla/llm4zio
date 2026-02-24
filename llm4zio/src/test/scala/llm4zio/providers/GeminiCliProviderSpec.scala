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

    override def runGeminiProcess(prompt: String, config: LlmConfig): IO[LlmError, String] =
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
  )
