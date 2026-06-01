package llm4zio.runner

import zio.test.*

import java.nio.file.Path

import llm4zio.core.{LlmConfig, LlmProvider}
import llm4zio.providers.MockProvider

object DefaultFlowContextSpec extends ZIOSpecDefault:
  def spec = suite("DefaultFlowContext.make")(
    test("bundles the given connectors and exposes the same hub as the context's events") {
      val reasoning = MockProvider.make(LlmConfig(LlmProvider.Mock, "r"))
      val coder     = MockProvider.make(LlmConfig(LlmProvider.Mock, "c"))
      for bundle <- DefaultFlowContext.make(reasoning, coder, Path.of("/tmp/repo"))
      yield
        val (ctx, hub) = bundle
        assertTrue(ctx.reasoning eq reasoning, ctx.coder eq coder, ctx.events eq hub)
    }
  )
