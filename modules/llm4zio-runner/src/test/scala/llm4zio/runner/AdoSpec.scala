package llm4zio.runner

import zio.Scope
import zio.test.*

object AdoSpec extends ZIOSpecDefault:

  private val pipelineEnv = Map(
    "SYSTEM_COLLECTIONURI"  -> "https://dev.azure.com/acme/",
    "SYSTEM_TEAMPROJECT"    -> "Widgets",
    "BUILD_REPOSITORY_NAME" -> "widgets-repo",
    "SYSTEM_ACCESSTOKEN"    -> "tok",
  )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Ado.configFrom")(
    test("reads ADO pipeline predefined variables, stripping the collection-uri trailing slash") {
      val cfg = Ado.configFrom(pipelineEnv)
      assertTrue(
        cfg.map(_.orgUrl).contains("https://dev.azure.com/acme"),
        cfg.map(_.project).contains("Widgets"),
        cfg.map(_.repository).contains("widgets-repo"),
        cfg.map(_.pat).contains("tok"),
      )
    },
    test("LLM4ZIO_ADO_* overrides work for local runs outside a pipeline") {
      val env = Map(
        "LLM4ZIO_ADO_ORG_URL" -> "https://dev.azure.com/me",
        "LLM4ZIO_ADO_PROJECT" -> "Proj",
        "LLM4ZIO_ADO_REPO"    -> "repo",
        "LLM4ZIO_ADO_PAT"     -> "pat123",
      )
      assertTrue(Ado.configFrom(env).map(_.orgUrl).contains("https://dev.azure.com/me"))
    },
    test("a missing required variable is a clear Left") {
      val cfg = Ado.configFrom(pipelineEnv - "SYSTEM_TEAMPROJECT")
      assertTrue(cfg.isLeft, cfg.swap.exists(_.toLowerCase.contains("project")))
    },
  )
