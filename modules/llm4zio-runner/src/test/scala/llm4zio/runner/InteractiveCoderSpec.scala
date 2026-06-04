package llm4zio.runner

import java.nio.file.Path

import zio.test.*

object InteractiveCoderSpec extends ZIOSpecDefault:

  def spec: Spec[Environment & TestEnvironment, Any] = suite("InteractiveCoder")(
    test("sessionFlags points claude at the MCP config, approval tool, and both allowed tools") {
      val flags = InteractiveCoder.sessionFlags("llm4zio", Path.of("/tmp/mcp.json"))
      assertTrue(
        flags.containsSlice(List("--mcp-config", "/tmp/mcp.json")),
        flags.containsSlice(List("--permission-prompt-tool", "mcp__llm4zio__approve")),
        flags.containsSlice(List("--allowedTools", "mcp__llm4zio__ask_user,mcp__llm4zio__approve")),
      )
    }
  )
