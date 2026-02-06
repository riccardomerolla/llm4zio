package integration

import java.nio.file.{ Files, Path }

import zio.*
import zio.logging.backend.SLF4J
import zio.test.*

import agents.{ CobolDiscoveryAgent, DependencyMapperAgent }
import core.FileService
import models.*
import prompts.PromptHelpers

object DependencyMapperIntegrationSpec extends ZIOSpecDefault:

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>> testEnvironment

  private val samplesDir     = Path.of("cobol-source/samples")
  private val targetPrograms = Set("CUSTOMER-DISPLAY.cbl", "CUSTOMER-INQUIRY.cbl")

  def spec: Spec[Any, Any] = suite("DependencyMapperIntegrationSpec")(
    test("maps shared copybooks into service candidates") {
      (for
        _                   <- ensureSamplesDir
        inventory           <- CobolDiscoveryAgent.discover(samplesDir)
        programFiles         = inventory.files.filter(f => f.fileType == FileType.Program && targetPrograms.contains(f.name))
        analyses            <- ZIO.foreach(programFiles) { file =>
                                 FileService.readFile(file.path).map(content => buildAnalysis(file, content))
                               }
        graph               <- DependencyMapperAgent.mapDependencies(analyses)
        includesCustomerData = Set(
                                 DependencyEdge("CUSTOMER-DISPLAY", "CUSTOMER-DATA", EdgeType.Includes),
                                 DependencyEdge("CUSTOMER-INQUIRY", "CUSTOMER-DATA", EdgeType.Includes),
                               )
        callEdge             = DependencyEdge("CUSTOMER-INQUIRY", "CUSTOMER-DISPLAY", EdgeType.Calls)
      yield assertTrue(
        graph.nodes.exists(n => n.id == "CUSTOMER-DISPLAY" && n.nodeType == NodeType.Program),
        graph.nodes.exists(n => n.id == "CUSTOMER-INQUIRY" && n.nodeType == NodeType.Program),
        graph.nodes.exists(n => n.id == "CUSTOMER-DATA" && n.nodeType == NodeType.Copybook),
        graph.nodes.exists(n => n.id == "ERROR-CODES" && n.nodeType == NodeType.Copybook),
        includesCustomerData.subsetOf(graph.edges.toSet),
        graph.edges.contains(DependencyEdge("CUSTOMER-INQUIRY", "ERROR-CODES", EdgeType.Includes)),
        graph.edges.contains(callEdge),
        graph.serviceCandidates.contains("CUSTOMER-DATA"),
        !graph.serviceCandidates.contains("ERROR-CODES"),
      )).provide(
        FileService.live,
        ZLayer.succeed(MigrationConfig(sourceDir = samplesDir, outputDir = Path.of("target/it-output"))),
        CobolDiscoveryAgent.live,
        DependencyMapperAgent.live,
      )
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  private def buildAnalysis(cobolFile: CobolFile, content: String): CobolAnalysis =
    val divisions  = PromptHelpers.chunkByDivision(content)
    val lines      = content.linesIterator.toList
    val copybooks  = extractMatches(lines, "(?i)\\bCOPY\\s+([A-Z0-9-]+)\\.?")
    val statements = lines.zipWithIndex.collect {
      case (line, idx) if line.toUpperCase.contains("CALL") =>
        Statement(lineNumber = idx + 1, statementType = "CALL", content = line.trim)
    }
    val procedures =
      if statements.isEmpty then List(Procedure(name = "MAIN", paragraphs = List("MAIN"), statements = List.empty))
      else List(Procedure(name = "MAIN", paragraphs = List("MAIN"), statements = statements))
    val ifCount    = lines.count(line => line.trim.toUpperCase.startsWith("IF "))
    val loc        = lines.count(line => line.trim.nonEmpty && !line.trim.startsWith("*"))
    CobolAnalysis(
      file = cobolFile,
      divisions = CobolDivisions(
        identification = divisions.get("IDENTIFICATION"),
        environment = divisions.get("ENVIRONMENT"),
        data = divisions.get("DATA"),
        procedure = divisions.get("PROCEDURE"),
      ),
      variables = List.empty,
      procedures = procedures,
      copybooks = copybooks,
      complexity = ComplexityMetrics(
        cyclomaticComplexity = Math.max(1, ifCount + 1),
        linesOfCode = loc,
        numberOfProcedures = procedures.size,
      ),
    )

  private def extractMatches(lines: List[String], pattern: String): List[String] =
    val regex = pattern.r
    lines
      .flatMap(line => regex.findAllMatchIn(line).map(_.group(1)).toList)
      .map(_.trim.toUpperCase)
      .filter(_.nonEmpty)
      .distinct

  private def ensureSamplesDir: Task[Unit] =
    ZIO
      .attemptBlocking(Files.isDirectory(samplesDir))
      .flatMap { isDir =>
        ZIO.fail(new RuntimeException(s"Missing COBOL samples at $samplesDir")).unless(isDir)
      }
      .unit
