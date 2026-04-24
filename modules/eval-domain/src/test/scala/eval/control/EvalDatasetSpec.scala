package eval.control

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import zio.*
import zio.test.*

object EvalDatasetSpec extends ZIOSpecDefault:

  def spec = suite("EvalDataset")(
    test("parses valid JSONL, skipping blank lines and comments") {
      val lines = List(
        """{"prompt":"sum 2+2","expected":"4"}""",
        "",
        "# this is a comment",
        """{"prompt":"capital?","expected":"Paris","metadata":{"tag":"geography"}}""",
      )
      for
        cases <- EvalDataset.fromLines("mem", lines)
      yield
        assertTrue(cases.size == 2) &&
        assertTrue(cases.head.prompt == "sum 2+2") &&
        assertTrue(cases(1).metadata == Map("tag" -> "geography"))
    },
    test("fails with line number on malformed JSON") {
      for
        err <- EvalDataset.fromLines("mem", List("{bad json")).either
      yield assertTrue(err.left.exists(_.contains("mem:1 invalid JSONL")))
    },
    test("fails on empty dataset") {
      for
        err <- EvalDataset.fromLines("mem", List("", "# only comments")).either
      yield assertTrue(err.left.exists(_.contains("no cases")))
    },
    test("load reads a file from disk") {
      val path = Files.createTempFile("eval-ds-", ".jsonl")
      Files.write(
        path,
        """{"prompt":"hi","expected":"hello"}""".getBytes(StandardCharsets.UTF_8),
      )
      for
        cases <- EvalDataset.load(path)
      yield
        assertTrue(cases.size == 1) &&
        assertTrue(cases.head.prompt == "hi")
    },
  )
