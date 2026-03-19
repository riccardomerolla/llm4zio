package prompts

import zio.*
import zio.test.*

object PromptLoaderSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] =
    suite("PromptLoaderSpec")(
      test("loads prompt template and interpolates placeholders") {
        for
          rendered <- PromptLoader.load(
                        "intent-router",
                        Map(
                          "agentList" -> "- alpha: test agent",
                          "message"   -> "route this task",
                        ),
                      )
        yield assertTrue(
          rendered.contains("You are a request router."),
          rendered.contains("- alpha: test agent"),
          rendered.contains("route this task"),
        )
      },
      test("fails with MissingPlaceholders when required placeholders are missing") {
        for
          result <- PromptLoader
                      .load(
                        "intent-router",
                        Map("agentList" -> "- alpha: test agent"),
                      )
                      .either
        yield assertTrue(
          result match
            case Left(PromptError.MissingPlaceholders(_, placeholders)) => placeholders.contains("message")
            case _                                                      => false
        )
      },
      test("fails with MissingPrompt when resource does not exist") {
        for
          result <- PromptLoader.load("does-not-exist", Map.empty).either
        yield assertTrue(
          result match
            case Left(PromptError.MissingPrompt(name)) => name == "does-not-exist"
            case _                                     => false
        )
      },
    ).provide(PromptLoader.live)
