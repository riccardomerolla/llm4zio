package llm4zio.core

import zio.*
import zio.json.*
import zio.test.*

object ConnectorIdSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ConnectorId")(
    test("known API connector ids") {
      assertTrue(
        ConnectorId.OpenAI.value == "openai",
        ConnectorId.Anthropic.value == "anthropic",
        ConnectorId.GeminiApi.value == "gemini-api",
        ConnectorId.LmStudio.value == "lm-studio",
        ConnectorId.Ollama.value == "ollama",
      )
    },
    test("known CLI connector ids") {
      assertTrue(
        ConnectorId.ClaudeCli.value == "claude-cli",
        ConnectorId.GeminiCli.value == "gemini-cli",
        ConnectorId.OpenCode.value == "opencode",
        ConnectorId.Codex.value == "codex",
        ConnectorId.Copilot.value == "copilot",
      )
    },
    test("ConnectorKind enum values") {
      assertTrue(
        ConnectorKind.Api != ConnectorKind.Cli
      )
    },
    test("JSON round-trip") {
      val id     = ConnectorId.OpenAI
      val json   = id.toJson
      val parsed = json.fromJson[ConnectorId]
      assertTrue(parsed == Right(id))
    },
  )
