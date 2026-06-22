package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64

import zio.*
import zio.test.*

object MermaidSpec extends ZIOSpecDefault:

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Mermaid")(
    suite("fromEvents")(
      test("renders a top-down flowchart of stages in order") {
        val out = Mermaid.fromEvents(
          List(
            FlowEvent.StageStarted("Specify"),
            FlowEvent.StageCompleted("Specify"),
            FlowEvent.StageStarted("Design"),
            FlowEvent.StageCompleted("Design"),
          )
        )
        assertTrue(
          out.startsWith("flowchart TD"),
          out.contains("\"Specify\""),
          out.contains("\"Design\""),
          out.contains("-->"),
        )
      },
      test("collapses repeated stage names into a single node with a count") {
        val impl = List(FlowEvent.StageStarted("Implement"), FlowEvent.StageCompleted("Implement"))
        val out  = Mermaid.fromEvents(impl ++ impl ++ impl)
        assertTrue(out.contains("Implement ×3"), !out.contains("Implement ×3 ×"))
      },
      test("marks a failed stage distinctly") {
        val out = Mermaid.fromEvents(List(FlowEvent.StageStarted("Verify"), FlowEvent.StageFailed("Verify", "boom")))
        assertTrue(out.contains(":::failed"), out.contains("classDef failed"))
      },
      test("ignores non-stage events") {
        val out = Mermaid.fromEvents(
          List(
            FlowEvent.Info("hello"),
            FlowEvent.StageStarted("A"),
            FlowEvent.StageCompleted("A"),
            FlowEvent.ToolUse("bash", "ls"),
          )
        )
        assertTrue(out.contains("\"A\""), !out.contains("hello"), !out.contains("bash"))
      },
      test("renders a placeholder when there are no stages") {
        assertTrue(Mermaid.fromEvents(Nil).toLowerCase.contains("no stages"))
      },
    ),
    suite("liveUrl")(
      test("encodes the diagram as a base64 mermaid.ink link that round-trips") {
        val diagram = "flowchart TD\n  a-->b"
        val url     = Mermaid.liveUrl(diagram)
        val encoded = url.substring(url.lastIndexOf('/') + 1)
        val decoded = new String(Base64.getUrlDecoder.decode(encoded), StandardCharsets.UTF_8)
        assertTrue(url.startsWith("https://mermaid.ink/"), decoded == diagram)
      }
    ),
    suite("document")(
      test("wraps the diagram in a fenced mermaid block and appends the live link") {
        val doc = Mermaid.document("flowchart TD\n  a-->b")
        assertTrue(doc.contains("```mermaid"), doc.contains("flowchart TD"), doc.contains("https://mermaid.ink/"))
      }
    ),
    suite("fromTrace")(
      test("reads stage events from a recorded trace file, ignoring non-stage lines") {
        val trace = List(
          TraceLine(1, "t", "run", "StageStarted", Map("stage" -> "Build")),
          TraceLine(2, "t", "run", "StageCompleted", Map("stage" -> "Build")),
          TraceLine(3, "t", "run", "Info", Map("message" -> "noise")),
          TraceLine(4, "t", "run", "StageStarted", Map("stage" -> "Verify")),
          TraceLine(5, "t", "run", "StageFailed", Map("stage" -> "Verify", "message" -> "boom")),
        ).map(_.toJson).mkString("\n")
        for
          path <- ZIO
                    .attemptBlocking {
                      val f = Files.createTempDirectory("trace").resolve("trace-1.jsonl")
                      Files.writeString(f, trace)
                      f
                    }
                    .orDie
          out  <- Mermaid.fromTrace(path)
        yield assertTrue(
          out.contains("\"Build\""),
          out.contains("\"Verify\""),
          out.contains(":::failed"),
          !out.contains("noise"),
        )
      }
    ),
  )
