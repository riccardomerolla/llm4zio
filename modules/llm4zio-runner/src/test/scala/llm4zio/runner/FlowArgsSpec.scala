package llm4zio.runner

import java.nio.file.Path

import zio.Scope
import zio.test.*

object FlowArgsSpec extends ZIOSpecDefault:

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("FlowArgs.parse")(
    test("empty args parse to an empty FlowArgs") {
      assertTrue(FlowArgs.parse(Nil) == Right(FlowArgs()))
    },
    test("a positional arg becomes the prompt text, trimmed") {
      assertTrue(FlowArgs.parse(List("  do it  ")) == Right(FlowArgs(promptText = Some("do it"))))
    },
    test("a blank positional arg is ignored (so the default can apply)") {
      assertTrue(FlowArgs.parse(List("   ")) == Right(FlowArgs()))
    },
    test("only the first positional is kept; extras are ignored") {
      assertTrue(FlowArgs.parse(List("do it", "extra")) == Right(FlowArgs(promptText = Some("do it"))))
    },
    test("--prompt-file <path> sets promptFile") {
      assertTrue(FlowArgs.parse(List("--prompt-file", "specs/x.md")) == Right(FlowArgs(promptFile =
        Some(Path.of("specs/x.md"))
      )))
    },
    test("--prompt-file=<path> sets promptFile") {
      assertTrue(FlowArgs.parse(List("--prompt-file=specs/x.md")) == Right(FlowArgs(promptFile =
        Some(Path.of("specs/x.md"))
      )))
    },
    test("@<path> is sugar for --prompt-file") {
      assertTrue(FlowArgs.parse(List("@specs/x.md")) == Right(FlowArgs(promptFile = Some(Path.of("specs/x.md")))))
    },
    test("a bare @ is treated as literal prompt text, not a file") {
      assertTrue(FlowArgs.parse(List("@")) == Right(FlowArgs(promptText = Some("@"))))
    },
    test("--prompt-file and a positional are both captured (precedence resolved later)") {
      assertTrue(
        FlowArgs.parse(List("--prompt-file", "a.md", "text")) ==
          Right(FlowArgs(promptText = Some("text"), promptFile = Some(Path.of("a.md"))))
      )
    },
    test("--repo <path> sets repo") {
      assertTrue(FlowArgs.parse(List("--repo", "/x")) == Right(FlowArgs(repo = Some(Path.of("/x")))))
    },
    test("-C <path> is an alias for --repo") {
      assertTrue(FlowArgs.parse(List("-C", "/x")) == Right(FlowArgs(repo = Some(Path.of("/x")))))
    },
    test("--repo=<path> sets repo") {
      assertTrue(FlowArgs.parse(List("--repo=/x")) == Right(FlowArgs(repo = Some(Path.of("/x")))))
    },
    test("--repo and a positional prompt coexist") {
      assertTrue(
        FlowArgs.parse(List("--repo", "/x", "do it")) ==
          Right(FlowArgs(promptText = Some("do it"), repo = Some(Path.of("/x"))))
      )
    },
    test("--repo with no value is a usage error") {
      assertTrue(FlowArgs.parse(List("--repo")).isLeft)
    },
    test("-C with no value is a usage error") {
      assertTrue(FlowArgs.parse(List("-C")).isLeft)
    },
    test("an unknown flag is a usage error") {
      assertTrue(FlowArgs.parse(List("--bogus")).isLeft)
    },
    test("--prompt-file with no value is a usage error") {
      assertTrue(FlowArgs.parse(List("--prompt-file")).isLeft)
    },
  )
