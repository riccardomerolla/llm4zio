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
    test("analyze retries on truncated JSON and succeeds on second attempt") {
      ZIO.scoped {
        for
          tempDir      <- ZIO.attemptBlocking(Files.createTempDirectory("analyzer-retry"))
          cobol         = tempDir.resolve("PROG1.cbl")
          _            <- writeFile(cobol, "IDENTIFICATION DIVISION.\nPROCEDURE DIVISION.\n")
          file          = CobolFile(
                            path = cobol,
                            name = "PROG1.cbl",
                            size = 10,
                            lineCount = 2,
                            lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
                            encoding = "UTF-8",
                            fileType = FileType.Program,
                          )
          attemptRef   <- Ref.make(0)
          truncatedJson = sampleAnalysisJson.take(50) // Truncated — invalid JSON
          aiLayer       = failThenSucceedAIService(attemptRef, truncatedJson, sampleAnalysisJson)
          analysis     <- CobolAnalyzerAgent
                            .analyze(file)
                            .provide(
                              FileService.live,
                              ResponseParser.live,
                              aiLayer,
                              ZLayer.succeed(MigrationConfig(
                                sourceDir = tempDir,
                                outputDir = tempDir,
                                maxCompileRetries = 2,
                              )),
                              CobolAnalyzerAgent.live,
                            )
          attempts     <- attemptRef.get
        yield assertTrue(
          analysis.file.name == "PROG1.cbl",
          attempts == 2,
        )
      }
    },
    test("analyze normalizes divisions returned as array into object") {
      val arrayDivisionsJson =
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
          |  "divisions": [
          |    "PROGRAM-ID. ARRAYPROG.",
          |    "INPUT-OUTPUT SECTION.",
          |    "WORKING-STORAGE SECTION.",
          |    "MAIN-PARA. STOP RUN."
          |  ],
          |  "variables": [],
          |  "procedures": [],
          |  "copybooks": [],
          |  "complexity": { "cyclomaticComplexity": 1, "linesOfCode": 5, "numberOfProcedures": 1 }
          |}""".stripMargin

      ZIO.scoped {
        for
          tempDir  <- ZIO.attemptBlocking(Files.createTempDirectory("analyzer-div-array"))
          cobol     = tempDir.resolve("ARRAYPROG.cbl")
          _        <- writeFile(cobol, "IDENTIFICATION DIVISION.\nPROCEDURE DIVISION.\n")
          file      = CobolFile(
                        path = cobol,
                        name = "ARRAYPROG.cbl",
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
                          mockAIService(arrayDivisionsJson),
                          ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                          CobolAnalyzerAgent.live,
                        )
        yield assertTrue(
          analysis.divisions.identification.contains("PROGRAM-ID. ARRAYPROG."),
          analysis.divisions.environment.contains("INPUT-OUTPUT SECTION."),
          analysis.divisions.data.contains("WORKING-STORAGE SECTION."),
          analysis.divisions.procedure.contains("MAIN-PARA. STOP RUN."),
        )
      }
    },
    test("analyze normalizes variables with missing required fields") {
      val missingFieldsJson =
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
          |    "identification": "PROGRAM-ID. MISSING.",
          |    "environment": null,
          |    "data": null,
          |    "procedure": null
          |  },
          |  "variables": [
          |    { "name": "WS-AMOUNT", "dataType": "numeric", "picture": "9(7)V99" }
          |  ],
          |  "procedures": [
          |    { "name": "MAIN", "statements": [{ "statementType": "STOP", "content": "STOP RUN" }] }
          |  ],
          |  "copybooks": [],
          |  "complexity": { "cyclomaticComplexity": 1, "linesOfCode": 5, "numberOfProcedures": 1 }
          |}""".stripMargin

      ZIO.scoped {
        for
          tempDir  <- ZIO.attemptBlocking(Files.createTempDirectory("analyzer-missing-fields"))
          cobol     = tempDir.resolve("MISSING.cbl")
          _        <- writeFile(cobol, "IDENTIFICATION DIVISION.\nPROCEDURE DIVISION.\n")
          file      = CobolFile(
                        path = cobol,
                        name = "MISSING.cbl",
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
                          mockAIService(missingFieldsJson),
                          ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                          CobolAnalyzerAgent.live,
                        )
        yield assertTrue(
          analysis.variables.head.name == "WS-AMOUNT",
          analysis.variables.head.level == 1,
          analysis.procedures.head.paragraphs == List("MAIN"),
          analysis.procedures.head.statements.head.lineNumber == 0,
        )
      }
    },
    test("analyze injects file when AI omits it entirely") {
      val noFileJson =
        """{
          |  "divisions": {
          |    "identification": "PROGRAM-ID. NOFILE.",
          |    "environment": null,
          |    "data": null,
          |    "procedure": null
          |  },
          |  "variables": [],
          |  "procedures": [],
          |  "copybooks": [],
          |  "complexity": { "cyclomaticComplexity": 1, "linesOfCode": 3, "numberOfProcedures": 1 }
          |}""".stripMargin

      ZIO.scoped {
        for
          tempDir  <- ZIO.attemptBlocking(Files.createTempDirectory("analyzer-no-file"))
          cobol     = tempDir.resolve("NOFILE.cbl")
          _        <- writeFile(cobol, "IDENTIFICATION DIVISION.\nPROCEDURE DIVISION.\n")
          file      = CobolFile(
                        path = cobol,
                        name = "NOFILE.cbl",
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
                          mockAIService(noFileJson),
                          ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                          CobolAnalyzerAgent.live,
                        )
        yield assertTrue(
          analysis.file.name == "NOFILE.cbl",
          analysis.complexity.linesOfCode == 3,
        )
      }
    },
    test("analyze filters out empty UNKNOWN statements from procedures") {
      val garbageStatementsJson =
        """{
          |  "divisions": { "identification": "PROGRAM-ID. GARBAGE.", "environment": null, "data": null, "procedure": null },
          |  "variables": [],
          |  "procedures": [
          |    {
          |      "name": "MAIN",
          |      "paragraphs": ["MAIN"],
          |      "statements": [
          |        { "lineNumber": 1, "statementType": "MOVE", "content": "MOVE 1 TO X" },
          |        { },
          |        { "statementType": "UNKNOWN" },
          |        { "lineNumber": 5, "statementType": "IF", "content": "IF X > 0" },
          |        { "content": "" },
          |        { "lineNumber": 0, "statementType": "UNKNOWN", "content": "" }
          |      ]
          |    }
          |  ],
          |  "copybooks": [],
          |  "complexity": { "cyclomaticComplexity": 2, "linesOfCode": 10, "numberOfProcedures": 1 }
          |}""".stripMargin

      ZIO.scoped {
        for
          tempDir  <- ZIO.attemptBlocking(Files.createTempDirectory("analyzer-garbage-stmt"))
          cobol     = tempDir.resolve("GARBAGE.cbl")
          _        <- writeFile(cobol, "IDENTIFICATION DIVISION.\nPROCEDURE DIVISION.\n")
          file      = CobolFile(
                        path = cobol,
                        name = "GARBAGE.cbl",
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
                          mockAIService(garbageStatementsJson),
                          ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                          CobolAnalyzerAgent.live,
                        )
        yield assertTrue(
          analysis.procedures.head.statements.length == 2,
          analysis.procedures.head.statements(0).statementType == "MOVE",
          analysis.procedures.head.statements(1).statementType == "IF",
        )
      }
    },
    test("analyze normalizes copybooks returned as objects into strings") {
      val objectCopybooksJson =
        """{
          |  "divisions": {
          |    "identification": "PROGRAM-ID. COPYBK.",
          |    "environment": null,
          |    "data": null,
          |    "procedure": null
          |  },
          |  "variables": [],
          |  "procedures": [],
          |  "copybooks": [
          |    { "name": "ZBNKSET" },
          |    { "copybook": "ERRCODE" },
          |    "PLAIN"
          |  ],
          |  "complexity": { "cyclomaticComplexity": 1, "linesOfCode": 5, "numberOfProcedures": 1 }
          |}""".stripMargin

      ZIO.scoped {
        for
          tempDir  <- ZIO.attemptBlocking(Files.createTempDirectory("analyzer-copybook-obj"))
          cobol     = tempDir.resolve("COPYBK.cbl")
          _        <- writeFile(cobol, "IDENTIFICATION DIVISION.\nPROCEDURE DIVISION.\n")
          file      = CobolFile(
                        path = cobol,
                        name = "COPYBK.cbl",
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
                          mockAIService(objectCopybooksJson),
                          ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                          CobolAnalyzerAgent.live,
                        )
        yield assertTrue(
          analysis.copybooks == List("ZBNKSET", "ERRCODE", "PLAIN")
        )
      }
    },
  ) @@ TestAspect.sequential

  private def failThenSucceedAIService(ref: Ref[Int], bad: String, good: String): ULayer[AIService] =
    ZLayer.succeed(new AIService {
      override def execute(prompt: String): ZIO[Any, AIError, AIResponse] =
        ref.updateAndGet(_ + 1).map { count =>
          if count <= 1 then AIResponse(bad) else AIResponse(good)
        }

      override def executeWithContext(prompt: String, context: String): ZIO[Any, AIError, AIResponse] =
        execute(prompt)

      override def isAvailable: ZIO[Any, Nothing, Boolean] = ZIO.succeed(true)
    })

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
