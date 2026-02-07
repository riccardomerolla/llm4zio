package agents

import java.nio.file.{ Files, Path }

import zio.*
import zio.json.*
import zio.test.*

import core.{ AIService, FileService, ResponseParser }
import models.*

object CobolAnalyzerAgentSpec extends ZIOSpecDefault:

  private val sampleAnalysisJson: String =
    """{
      |  "file": {
      |    "path": "/tmp/PLACEHOLDER.cbl",
      |    "name": "PLACEHOLDER.cbl",
      |    "size": 10,
      |    "lineCount": 2,
      |    "lastModified": "2026-02-06T00:00:00Z",
      |    "encoding": "UTF-8",
      |    "fileType": "Program"
      |  },
      |  "divisions": {
      |    "identification": "PROGRAM-ID. PLACEHOLDER.",
      |    "environment": null,
      |    "data": "WORKING-STORAGE SECTION.",
      |    "procedure": "PROCEDURE DIVISION."
      |  },
      |  "variables": [
      |    { "name": "WS-ID", "level": 1, "dataType": "NUMERIC", "picture": "9(5)", "usage": null }
      |  ],
      |  "procedures": [
      |    { "name": "MAIN", "paragraphs": ["MAIN"], "statements": [{ "lineNumber": 1, "statementType": "STOP", "content": "STOP RUN" }] }
      |  ],
      |  "copybooks": ["COPY1"],
      |  "complexity": { "cyclomaticComplexity": 1, "linesOfCode": 2, "numberOfProcedures": 1 }
      |}""".stripMargin

  def spec: Spec[Any, Any] = suite("CobolAnalyzerAgentSpec")(
    test("analyze returns parsed analysis and writes report") {
      ZIO.scoped {
        for
          tempDir  <- ZIO.attemptBlocking(Files.createTempDirectory("analyzer-spec"))
          cobol     = tempDir.resolve("PROG1.cbl")
          _        <- writeFile(cobol, "IDENTIFICATION DIVISION.\nPROCEDURE DIVISION.\n")
          file      = CobolFile(
                        path = cobol,
                        name = "PROG1.cbl",
                        size = 10,
                        lineCount = 2,
                        lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
                        encoding = "UTF-8",
                        fileType = FileType.Program,
                      )
          analysis <- CobolAnalyzerAgent
                        .analyze(file)
                        .provide(
                          FileService.live,
                          ResponseParser.live,
                          mockAIService(sampleAnalysisJson),
                          ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                          CobolAnalyzerAgent.live,
                        )
          report   <- readReport("PROG1.cbl")
          parsed    = report.fromJson[CobolAnalysis]
        yield assertTrue(
          analysis.file.name == "PROG1.cbl",
          analysis.complexity.linesOfCode == 2,
          parsed.isRight,
        )
      }
    },
    test("analyzeAll writes summary report") {
      ZIO.scoped {
        for
          tempDir <- ZIO.attemptBlocking(Files.createTempDirectory("analyzer-spec-all"))
          cobol1   = tempDir.resolve("PROG1.cbl")
          cobol2   = tempDir.resolve("PROG2.cbl")
          _       <- writeFile(cobol1, "IDENTIFICATION.\nPROCEDURE.\n")
          _       <- writeFile(cobol2, "IDENTIFICATION.\nPROCEDURE.\n")
          files    = List(
                       CobolFile(
                         path = cobol1,
                         name = "PROG1.cbl",
                         size = 10,
                         lineCount = 2,
                         lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
                         encoding = "UTF-8",
                         fileType = FileType.Program,
                       ),
                       CobolFile(
                         path = cobol2,
                         name = "PROG2.cbl",
                         size = 10,
                         lineCount = 2,
                         lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
                         encoding = "UTF-8",
                         fileType = FileType.Program,
                       ),
                     )
          _       <- CobolAnalyzerAgent
                       .analyzeAll(files)
                       .runCollect
                       .provide(
                         FileService.live,
                         ResponseParser.live,
                         mockAIService(sampleAnalysisJson),
                         ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir, parallelism = 2)),
                         CobolAnalyzerAgent.live,
                       )
          summary <- readSummary()
          parsed   = summary.fromJson[List[CobolAnalysis]]
        yield assertTrue(parsed.exists(_.length == 2))
      }
    },
  ) @@ TestAspect.sequential

  private def mockAIService(output: String): ULayer[AIService] =
    ZLayer.succeed(new AIService {
      override def execute(prompt: String): ZIO[Any, AIError, AIResponse] =
        ZIO.succeed(AIResponse(output))

      override def executeWithContext(prompt: String, context: String): ZIO[Any, AIError, AIResponse] =
        ZIO.succeed(AIResponse(output))

      override def isAvailable: ZIO[Any, Nothing, Boolean] =
        ZIO.succeed(true)
    })

  private def writeFile(path: Path, content: String): Task[Unit] =
    ZIO.attemptBlocking {
      Files.createDirectories(path.getParent)
      Files.writeString(path, content)
    }.unit

  private def readReport(name: String): Task[String] =
    ZIO.attemptBlocking {
      Files.readString(Path.of("reports/analysis").resolve(s"$name.json"))
    }

  private def readSummary(): Task[String] =
    ZIO.attemptBlocking {
      Files.readString(Path.of("reports/analysis/analysis-summary.json"))
    }
