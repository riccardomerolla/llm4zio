package llm4zio.flow

import zio.test.*

object GhToolSpec extends ZIOSpecDefault:
  def spec = suite("GhTool.prCreateArgs")(
    test("builds title/body args, omitting base and draft by default") {
      assertTrue(
        GhTool.prCreateArgs("My PR", "Body text", base = None, draft = false) ==
          List("pr", "create", "--title", "My PR", "--body", "Body text")
      )
    },
    test("includes --base and --draft when given") {
      assertTrue(
        GhTool.prCreateArgs("T", "B", base = Some("main"), draft = true) ==
          List("pr", "create", "--title", "T", "--body", "B", "--base", "main", "--draft")
      )
    },
  )
