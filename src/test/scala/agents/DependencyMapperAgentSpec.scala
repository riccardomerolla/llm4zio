package agents

import java.nio.file.{ Files, Path }

import zio.*
import zio.json.*
import zio.test.*

import core.FileService
import models.*

object DependencyMapperAgentSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Any] = suite("DependencyMapperAgentSpec")(
    test("builds dependency graph with includes and calls") {
      ZIO.scoped {
        val analysis1 = sampleAnalysis(
          "PROG1.cbl",
          copybooks = List("COPY1.cpy", "COPY2.cpy"),
          callContent = "CALL 'PROG2'",
        )
        val analysis2 = sampleAnalysis(
          "PROG2.cbl",
          copybooks = List("COPY2.cpy"),
          callContent = "CALL PROG3",
        )
        val analysis3 = sampleAnalysis("PROG3.cbl", copybooks = List.empty, callContent = "")

        for
          _     <- ZIO.attemptBlocking(Files.createDirectories(Path.of("reports/mapping"))).ignore
          graph <- DependencyMapperAgent
                     .mapDependencies(List(analysis1, analysis2, analysis3))
                     .provide(FileService.live, DependencyMapperAgent.live)
          edges  = graph.edges.map(e => (e.from, e.to, e.edgeType)).toSet
          json  <- readReport()
          parsed = json.fromJson[DependencyGraph]
        yield assertTrue(
          graph.nodes.exists(_.id == "PROG1"),
          graph.nodes.exists(_.id == "COPY2"),
          edges.contains(("PROG1", "COPY1", EdgeType.Includes)),
          edges.contains(("PROG1", "COPY2", EdgeType.Includes)),
          edges.contains(("PROG1", "PROG2", EdgeType.Calls)),
          edges.contains(("PROG2", "PROG3", EdgeType.Calls)),
          graph.serviceCandidates.contains("COPY2"),
          parsed.isRight,
        )
      }
    },
    test("detects cycles and writes migration order") {
      ZIO.scoped {
        val analysis1 = sampleAnalysis("A.cbl", copybooks = List.empty, callContent = "CALL B")
        val analysis2 = sampleAnalysis("B.cbl", copybooks = List.empty, callContent = "CALL A")

        for
          graph   <- DependencyMapperAgent
                       .mapDependencies(List(analysis1, analysis2))
                       .provide(FileService.live, DependencyMapperAgent.live)
          orderMd <- readMigrationOrder()
        yield assertTrue(
          graph.edges.exists(e => e.edgeType == EdgeType.Calls),
          orderMd.contains("Circular dependencies"),
        )
      }
    },
  ) @@ TestAspect.sequential

  private def sampleAnalysis(
    name: String,
    copybooks: List[String],
    callContent: String,
  ): CobolAnalysis =
    CobolAnalysis(
      file = CobolFile(
        path = Path.of(s"/tmp/$name"),
        name = name,
        size = 10,
        lineCount = 2,
        lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
        encoding = "UTF-8",
        fileType = FileType.Program,
      ),
      divisions = CobolDivisions(
        identification = Some("PROGRAM-ID."),
        environment = None,
        data = None,
        procedure = None,
      ),
      variables = List.empty,
      procedures = List(
        Procedure(
          name = "MAIN",
          paragraphs = List("MAIN"),
          statements =
            if callContent.isEmpty then List.empty
            else List(Statement(1, "CALL", callContent)),
        )
      ),
      copybooks = copybooks,
      complexity = ComplexityMetrics(1, 2, 1),
    )

  private def readReport(): Task[String] =
    ZIO.attemptBlocking {
      Files.readString(Path.of("reports/mapping/dependency-graph.json"))
    }

  private def readMigrationOrder(): Task[String] =
    ZIO.attemptBlocking {
      Files.readString(Path.of("reports/mapping/migration-order.md"))
    }
