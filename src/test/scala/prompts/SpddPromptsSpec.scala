package prompts

import zio.*
import zio.test.*

/** Smoke test for the eight SPDD prompt templates.
  *
  * Each template must:
  *   1. Exist on the classpath under prompts/spdd-*.md.
  *   2. Render with the placeholder set the M4 MCP tool will supply.
  *   3. Surface a MissingPlaceholders error if any declared placeholder is
  *      omitted (regression test against silent template drift).
  */
object SpddPromptsSpec extends ZIOSpecDefault:

  private val sentinel = "__SPDD_TEST_SENTINEL__"

  private val sampleContexts: Map[String, Map[String, String]] = Map(
    "spdd-story"          -> Map("enhancement"           -> sentinel, "repoContext" -> "modules/...")
,
    "spdd-analysis"       -> Map(
      "story"            -> sentinel,
      "repoContext"      -> "modules/...",
      "similarCanvases"  -> "(none yet)",
    ),
    "spdd-reasons-canvas" -> Map(
      "analysis"          -> sentinel,
      "normProfile"       -> "norms-default v1: BigDecimal HALF_UP",
      "safeguardProfile"  -> "safeguards-billing v1: idempotency, rounding",
    ),
    "spdd-generate"       -> Map("canvas" -> sentinel, "operationId" -> "op-001"),
    "spdd-api-test"       -> Map("canvas" -> sentinel),
    "spdd-prompt-update"  -> Map(
      "currentCanvas"  -> "<canvas v3>",
      "deltaIntent"    -> sentinel,
      "sectionsHint"   -> "Operations,Safeguards",
    ),
    "spdd-sync"           -> Map("currentCanvas" -> "<canvas v3>", "codeDiff" -> sentinel),
    "spdd-unit-test"      -> Map("canvas" -> sentinel, "existingTestInventory" -> "(empty)"),
  )

  private def loadsAndContainsSentinel(name: String, ctx: Map[String, String]) =
    test(s"$name loads and renders all placeholders") {
      for
        rendered <- PromptLoader.load(name, ctx)
      yield assertTrue(
        rendered.nonEmpty,
        rendered.contains(sentinel) || ctx.values.exists(rendered.contains),
        // No leftover {{placeholders}} after rendering.
        !rendered.contains("{{"),
      )
    }

  private def failsWhenAPlaceholderMissing(name: String, ctx: Map[String, String]) =
    test(s"$name fails with MissingPlaceholders when one key is omitted") {
      val keyToDrop = ctx.keys.head
      val partial   = ctx - keyToDrop
      for
        result <- PromptLoader.load(name, partial).either
      yield assertTrue(
        result match
          case Left(PromptError.MissingPlaceholders(n, missing)) =>
            n == name && missing.contains(keyToDrop)
          case _                                                 => false
      )
    }

  def spec: Spec[TestEnvironment, Any] =
    suite("SpddPromptsSpec")(
      sampleContexts.toList.flatMap { case (name, ctx) =>
        List(
          loadsAndContainsSentinel(name, ctx),
          failsWhenAPlaceholderMissing(name, ctx),
        )
      } *
    ).provide(PromptLoader.live)
