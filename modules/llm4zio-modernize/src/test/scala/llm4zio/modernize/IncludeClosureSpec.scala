package llm4zio.modernize

import zio.Scope
import zio.test.*

import llm4zio.flow.{ SurveyEdge, SurveyGraph, SurveyNode }

object IncludeClosureSpec extends ZIOSpecDefault:

  private def node(path: String) = SurveyNode(path, path.split('/').last.takeWhile(_ != '.'), 10, 1)

  private val graph = SurveyGraph(
    nodes =
      List(node("cobol/ACCTXFR.cbl"), node("copy/ACCTREC.cpy"), node("copy/COMMON.cpy"), node("cobol/BALINQ.cbl")),
    edges = List(
      SurveyEdge("ACCTXFR", "ACCTREC", "copy"),
      SurveyEdge("ACCTREC", "COMMON", "copy"), // transitive
      SurveyEdge("BALINQ", "COMMON", "copy"), // unrelated branch
    ),
  )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("include closure")(
    test("closureFor walks transitively and excludes unrelated units") {
      val out = ExtractFlow.closureFor(graph, "ACCTXFR", maxFiles = 50)
      assertTrue(
        out == List("copy/ACCTREC.cpy", "copy/COMMON.cpy"),
        !out.contains("cobol/BALINQ.cbl"),
        !out.contains("cobol/ACCTXFR.cbl"), // the program itself is named separately, not in its closure
      )
    },
    test("closureFor terminates on a dependency cycle") {
      val cyclic = graph.copy(edges = graph.edges :+ SurveyEdge("COMMON", "ACCTXFR", "copy"))
      val out    = ExtractFlow.closureFor(cyclic, "ACCTXFR", maxFiles = 50)
      assertTrue(out == List("copy/ACCTREC.cpy", "copy/COMMON.cpy"))
    },
    test("closureFor caps the file count") {
      val out = ExtractFlow.closureFor(graph, "ACCTXFR", maxFiles = 1)
      assertTrue(out == List("copy/ACCTREC.cpy"))
    },
  )
