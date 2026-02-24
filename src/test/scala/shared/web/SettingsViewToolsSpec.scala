package shared.web

import zio.json.ast.Json
import zio.test.*

import llm4zio.tools.{ Tool, ToolSandbox }

object SettingsViewToolsSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("SettingsViewToolsSpec")(
    test("toolsFragment renders tool name and description") {
      val tools = List(
        Tool(
          name = "read_file",
          description = "Read a file from the workspace",
          parameters = Json.Obj(),
          execute = _ => ???,
          tags = Set("file", "read"),
          sandbox = ToolSandbox.WorkspaceReadOnly,
        )
      )
      val html  = SettingsView.toolsFragment(tools)
      assertTrue(
        html.contains("read_file"),
        html.contains("Read a file from the workspace"),
        html.contains("WorkspaceReadOnly"),
      )
    },
    test("toolsFragment renders empty state when no tools registered") {
      val html = SettingsView.toolsFragment(Nil)
      assertTrue(html.contains("No tools registered"))
    },
  )
