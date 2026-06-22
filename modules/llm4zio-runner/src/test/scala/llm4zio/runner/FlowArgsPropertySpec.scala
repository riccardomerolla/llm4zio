package llm4zio.runner

import java.nio.file.Path

import zio.Scope
import zio.test.*

/** Property-based laws for the CLI parser: it stays total over realistic argument lists, and each flag consumes its
  * value as specified — properties the example cases assert only at a handful of points.
  */
object FlowArgsPropertySpec extends ZIOSpecDefault:

  // Path-safe tokens (no NUL/control chars, which java.nio Path.of rejects — a shell never produces them).
  private val pathSafe = Gen.alphaNumericStringBounded(1, 16)

  // A realistic mix of flags, values, and sugar.
  private val argToken: Gen[Sized, String] =
    Gen.oneOf(
      Gen.const("--prompt-file"),
      Gen.const("--repo"),
      Gen.const("-C"),
      Gen.const("--bogus"),
      Gen.alphaNumericStringBounded(0, 12),
      pathSafe.map("@" + _),
      pathSafe.map("--prompt-file=" + _),
    )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("FlowArgs.parse property laws")(
    test("is total over realistic argument lists (returns a value, never throws)") {
      check(Gen.listOf(argToken)) { args =>
        val r = FlowArgs.parse(args)
        assertTrue(r.isRight || r.isLeft)
      }
    },
    test("--prompt-file consumes the next token as the prompt file") {
      check(pathSafe)(p =>
        assertTrue(FlowArgs.parse(List("--prompt-file", p)) == Right(FlowArgs(promptFile = Some(Path.of(p)))))
      )
    },
    test("--repo / -C consume the next token as the repo") {
      check(pathSafe, Gen.elements("--repo", "-C")) { (p, flag) =>
        assertTrue(FlowArgs.parse(List(flag, p)) == Right(FlowArgs(repo = Some(Path.of(p)))))
      }
    },
    test("a non-flag positional becomes the (trimmed) prompt text") {
      check(pathSafe)(t => assertTrue(FlowArgs.parse(List(t)) == Right(FlowArgs(promptText = Some(t)))))
    },
    test("@<path> is sugar for --prompt-file") {
      check(pathSafe)(p => assertTrue(FlowArgs.parse(List("@" + p)) == Right(FlowArgs(promptFile = Some(Path.of(p))))))
    },
    test("an explicit --prompt-file wins over a positional, regardless of order") {
      check(pathSafe, pathSafe) { (file, text) =>
        val a = FlowArgs.parse(List("--prompt-file", file, text))
        val b = FlowArgs.parse(List(text, "--prompt-file", file))
        assertTrue(a.map(_.promptFile) == Right(Some(Path.of(file))), b.map(_.promptFile) == Right(Some(Path.of(file))))
      }
    },
  )
