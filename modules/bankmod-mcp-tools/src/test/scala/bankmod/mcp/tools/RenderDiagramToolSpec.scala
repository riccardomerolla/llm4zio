package bankmod.mcp.tools

import zio.Scope
import zio.test.*

import bankmod.graph.model.*
import bankmod.graph.model.Refinements.*

object RenderDiagramToolSpec extends ZIOSpecDefault:

  private def sid(s: String): ServiceId = ServiceId.from(s).toOption.get
  private def port(s: String): PortName = PortName.from(s).toOption.get
  private def url: UrlLikeR             = UrlLike.from("https://example.com").toOption.get

  private val a = sid("svc-a")
  private val b = sid("svc-b")

  private val sla = Sla(
    LatencyMs.from(100).toOption.get,
    Percentage.from(99).toOption.get,
    BoundedRetries.from(3).toOption.get,
  )

  private def svc(id: ServiceId, outbound: Set[Edge]): Service =
    Service(
      id,
      Criticality.Tier1,
      Ownership.Platform,
      Set.empty,
      outbound,
      Set.empty,
      Set.empty,
      sla,
    )

  private def edge(to: ServiceId, portName: String): Edge =
    Edge(
      port("out"),
      to,
      port(portName),
      Protocol.Rest(url),
      Consistency.Strong,
      Ordering.TotalOrder,
    )

  private val fixture = Graph(
    Map(
      a -> svc(a, Set(edge(b, "in"))),
      b -> svc(b, Set.empty),
    )
  )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("RenderDiagramTool.run")(
    test("scope=full, format=mermaid produces non-empty body containing both service ids") {
      val result = RenderDiagramTool.run(RenderDiagramInput("full", "mermaid"), fixture)
      result match
        case Right(out) =>
          assertTrue(
            out.format == "mermaid",
            out.scope == "full",
            out.body.nonEmpty,
            out.body.contains("svc-a"),
            out.body.contains("svc-b"),
          )
        case Left(f)    =>
          assertTrue(false) ?? s"expected Right, got Left($f)"
    },
    test("scope=full, format=d2 produces non-empty body") {
      val result = RenderDiagramTool.run(RenderDiagramInput("full", "d2"), fixture)
      result match
        case Right(out) =>
          assertTrue(out.format == "d2", out.body.nonEmpty)
        case Left(f)    =>
          assertTrue(false) ?? s"expected Right, got Left($f)"
    },
    test("scope=full, format=structurizr produces non-empty body") {
      val result = RenderDiagramTool.run(RenderDiagramInput("full", "structurizr"), fixture)
      result match
        case Right(out) =>
          assertTrue(out.format == "structurizr", out.body.nonEmpty)
        case Left(f)    =>
          assertTrue(false) ?? s"expected Right, got Left($f)"
    },
    test("scope=full, format=json produces non-empty body") {
      val result = RenderDiagramTool.run(RenderDiagramInput("full", "json"), fixture)
      result match
        case Right(out) =>
          assertTrue(out.format == "json", out.body.nonEmpty)
        case Left(f)    =>
          assertTrue(false) ?? s"expected Right, got Left($f)"
    },
    test("scope=service:svc-a, format=mermaid contains svc-a and svc-b (direct outbound)") {
      val result =
        RenderDiagramTool.run(RenderDiagramInput("service:svc-a", "mermaid"), fixture)
      result match
        case Right(out) =>
          assertTrue(
            out.body.contains("svc-a"),
            out.body.contains("svc-b"),
          )
        case Left(f)    =>
          assertTrue(false) ?? s"expected Right, got Left($f)"
    },
    test("scope=service:svc-a, format=mermaid reports scope == service:svc-a") {
      val result =
        RenderDiagramTool.run(RenderDiagramInput("service:svc-a", "mermaid"), fixture)
      result match
        case Right(out) =>
          assertTrue(out.scope == "service:svc-a")
        case Left(f)    =>
          assertTrue(false) ?? s"expected Right, got Left($f)"
    },
    test("unknown scope produces Failure") {
      val result = RenderDiagramTool.run(RenderDiagramInput("bogus", "mermaid"), fixture)
      assertTrue(
        result.isLeft,
        result.swap.toOption.get.message.contains("Unknown scope"),
      )
    },
    test("unknown format produces Failure") {
      val result = RenderDiagramTool.run(RenderDiagramInput("full", "ascii-art"), fixture)
      assertTrue(
        result.isLeft,
        result.swap.toOption.get.message.contains("Unknown format"),
      )
    },
    test("scope=service:<unknown> produces Failure") {
      val result =
        RenderDiagramTool.run(RenderDiagramInput("service:nonexistent", "mermaid"), fixture)
      assertTrue(
        result.isLeft,
        result.swap.toOption.get.message.contains("Unknown service"),
      )
    },
  )
