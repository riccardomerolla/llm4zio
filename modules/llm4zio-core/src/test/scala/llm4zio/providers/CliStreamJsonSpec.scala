package llm4zio.providers

import zio.test.*
import zio.Scope

object CliStreamJsonSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("CliStreamJson")(
    test("parses a JSON object line") {
      assertTrue(CliStreamJson.parseLine("""{"type":"x"}""").isDefined)
    },
    test("ignores blank and non-JSON lines") {
      assertTrue(CliStreamJson.parseLine("   ").isEmpty, CliStreamJson.parseLine("hello").isEmpty)
    },
    test("string field reads a top-level string") {
      val obj = CliStreamJson.parseLine("""{"model":"claude-x"}""").get
      assertTrue(CliStreamJson.str(obj, "model").contains("claude-x"))
    },
    test("toolChunk builds the contract metadata") {
      val c = CliStreamJson.toolChunk("Edit", """{"file_path":"a.rs"}""")
      assertTrue(
        c.metadata.get("event").contains("tool_use"),
        c.metadata.get("tool_name").contains("Edit"),
        c.metadata.get("tool_input").contains("""{"file_path":"a.rs"}"""),
        c.delta.isEmpty,
      )
    },
  )
