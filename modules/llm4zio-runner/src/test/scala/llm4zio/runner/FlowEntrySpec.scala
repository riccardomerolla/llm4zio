package llm4zio.runner

import zio.Scope
import zio.test.*

/** Compile-compat for the widened `flow()` entry (issue #716): pre-capability bodies (`FlowContext ?=> …`) must adapt
  * unchanged to the `(FlowContext, Caps.All) ?=>` body type, and restricted bodies must be blocked from powers their
  * type parameter does not declare.
  */
object FlowEntrySpec extends ZIOSpecDefault:

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("flow entry compile-compat")(
    test("a pre-capability body adapts to the widened plain-flow body type") {
      assertTrue(
        scala.compiletime.testing.typeChecks(
          "import zio.ZIO\nimport llm4zio.flow.*\n" +
            "def legacy: FlowContext ?=> ZIO[Any, FlowError, Any] = ZIO.unit\n" +
            "def widened(b: (FlowContext, Caps.All) ?=> ZIO[Any, FlowError, Any]): Unit = ()\n" +
            "def use: Unit = widened(legacy)"
        )
      )
    },
    test("a plain-flow-shaped body may use every bare name and tool method") {
      assertTrue(
        scala.compiletime.testing.typeChecks(
          "import zio.ZIO\nimport llm4zio.flow.*\n" +
            "def body: (FlowContext, Caps.All) ?=> ZIO[Any, FlowError, Any] =\n" +
            "  git.commitAll(\"x\") *> gh.createPr(\"t\", \"b\") *> ZIO.unit"
        )
      )
    },
    test("a restricted body cannot reach beyond its declared capabilities") {
      assertTrue(
        // Allowed: read-grade git under GitRead.
        scala.compiletime.testing.typeChecks(
          "import zio.ZIO\nimport llm4zio.flow.*\n" +
            "def body: (FlowContext, Caps.GitRead & Caps.Reasoning) ?=> ZIO[Any, FlowError, Any] = git.diff"
        ),
        // Blocked: commit under a read-only declaration.
        !scala.compiletime.testing.typeChecks(
          "import zio.ZIO\nimport llm4zio.flow.*\n" +
            "def body: (FlowContext, Caps.GitRead & Caps.Reasoning) ?=> ZIO[Any, FlowError, Any] = git.commitAll(\"x\").unit"
        ),
        // Blocked: opening a PR without GhWrite.
        !scala.compiletime.testing.typeChecks(
          "import zio.ZIO\nimport llm4zio.flow.*\n" +
            "def body: (FlowContext, Caps.GitRead & Caps.Reasoning) ?=> ZIO[Any, FlowError, Any] = gh.createPr(\"t\", \"b\").unit"
        ),
      )
    },
  )
