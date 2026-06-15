package llm4zio.runner

import zio.Scope
import zio.test.*

import llm4zio.core.ConnectorId

object ConnectorsSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Connectors")(
    test("presets target the right CLI with edit-capable defaults") {
      assertTrue(
        claude.connectorId == ConnectorId.ClaudeCli,
        claude.flags == Map("permission-mode" -> "acceptEdits"),
        codex.connectorId == ConnectorId.Codex,
        codex.flags == Map("sandbox" -> "workspace-write"),
        gemini.connectorId == ConnectorId.GeminiCli,
        gemini.flags == Map.empty,
        pi.connectorId == ConnectorId.Pi,
        pi.flags.isEmpty,
      )
    },
    test("lmStudio is a local API reasoning seat") {
      assertTrue(
        lmStudio.connectorId == ConnectorId.LmStudio,
        lmStudio.baseUrl == Some("http://localhost:1234/v1"),
      )
    },
    test("withModel pins a model") {
      assertTrue(
        claude.withModel("opus").model == Some("opus"),
        pi.withModel("lmstudio/foo").model == Some("lmstudio/foo"),
      )
    },
    test("coderFromEnv honours LLM4ZIO_CODER and defaults to claude") {
      assertTrue(
        Connectors.coderFromEnv(Map.empty) == claude,
        Connectors.coderFromEnv(Map("LLM4ZIO_CODER" -> "codex")) == codex,
        Connectors.coderFromEnv(Map("LLM4ZIO_CODER" -> "gemini")) == gemini,
        Connectors.coderFromEnv(Map("LLM4ZIO_CODER" -> "pi")) == pi,
        Connectors.coderFromEnv(Map("LLM4ZIO_CODER" -> "anything-else")) == claude,
      )
    },
  )
