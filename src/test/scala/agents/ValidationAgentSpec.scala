package agents

import java.nio.file.{ Files, Path }

import zio.*
import zio.json.*
import zio.test.*

import core.{ FileService, GeminiService, ResponseParser }
import models.*

object ValidationAgentSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Any] = suite("ValidationAgentSpec")(
    test("validate generates report and writes files") {
      ZIO.scoped {
        for
          tempDir <- ZIO.attemptBlocking(Files.createTempDirectory("validation-spec"))
          project  = sampleProject("VALPROG")
          analysis = sampleAnalysis("VALPROG.cbl")
          report  <- ValidationAgent
                       .validate(project, analysis)
                       .provide(
                         FileService.live,
                         ResponseParser.live,
                         mockGeminiService(
                           """{
                             |  "businessLogicPreserved": true,
                             |  "confidence": 0.91,
                             |  "summary": "Equivalent mapping",
                             |  "issues": []
                             |}""".stripMargin
                         ),
                         ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                         ValidationAgent.live,
                       )
          jsonPath = Path.of("reports/validation/valprog-validation.json")
          mdPath   = Path.of("reports/validation/valprog-validation.md")
          jsonOk  <- ZIO.attemptBlocking(Files.exists(jsonPath))
          mdOk    <- ZIO.attemptBlocking(Files.exists(mdPath))
          json    <- ZIO.attemptBlocking(Files.readString(jsonPath))
          parsed   = json.fromJson[ValidationReport]
        yield assertTrue(
          report.projectName == "VALPROG",
          report.compileResult.success == false,
          report.issues.exists(_.category == IssueCategory.Compile),
          report.overallStatus == ValidationStatus.Failed,
          jsonOk,
          mdOk,
          parsed.isRight,
        )
      }
    },
    test("validate fails with typed error on semantic parse mismatch") {
      ZIO.scoped {
        for
          tempDir <- ZIO.attemptBlocking(Files.createTempDirectory("validation-spec-semantic"))
          project  = sampleProject("BADSEM")
          analysis = sampleAnalysis("BADSEM.cbl")
          result  <- ValidationAgent
                       .validate(project, analysis)
                       .provide(
                         FileService.live,
                         ResponseParser.live,
                         mockGeminiService("""{ "unexpected": true }"""),
                         ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                         ValidationAgent.live,
                       )
                       .either
        yield assertTrue(
          result.isLeft,
          result.left.exists {
            case ValidationError.SemanticValidationFailed(projectName, _) => projectName == "BADSEM"
            case _                                                        => false
          },
        )
      }
    },
  ) @@ TestAspect.sequential

  private def mockGeminiService(output: String): ULayer[GeminiService] =
    ZLayer.succeed(new GeminiService {
      override def execute(prompt: String): ZIO[Any, GeminiError, GeminiResponse] =
        ZIO.succeed(GeminiResponse(output, 0))

      override def executeWithContext(prompt: String, context: String): ZIO[Any, GeminiError, GeminiResponse] =
        execute(prompt)

      override def isAvailable: ZIO[Any, Nothing, Boolean] =
        ZIO.succeed(true)
    })

  private def sampleAnalysis(name: String): CobolAnalysis =
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
        identification = Some("PROGRAM-ID. SAMPLE."),
        environment = None,
        data = Some("01 WS-ID PIC 9(5)."),
        procedure = Some("MAIN. MOVE 1 TO WS-ID."),
      ),
      variables = List(
        Variable("WS-ID", 1, "NUMERIC", Some("9(5)"), None),
        Variable("WS-UNMAPPED", 1, "NUMERIC", Some("9(3)"), None),
      ),
      procedures = List(
        Procedure("MAIN", List("MAIN"), List(Statement(1, "MOVE", "MOVE 1 TO WS-ID"))),
        Procedure("UNMAPPED-PROC", List("UNMAPPED-PROC"), List.empty),
      ),
      copybooks = List("COPY1"),
      complexity = ComplexityMetrics(3, 20, 2),
    )

  private def sampleProject(name: String): SpringBootProject =
    SpringBootProject(
      projectName = name,
      sourceProgram = s"$name.cbl",
      generatedAt = java.time.Instant.parse("2026-02-06T00:00:00Z"),
      entities = List(
        JavaEntity(
          className = "Record",
          packageName = "com.example.record.entity",
          fields = List(JavaField("wsId", "Long", "WS-ID", List("@Column"))),
          annotations = List("@Entity"),
          sourceCode = "public class Record {}",
        )
      ),
      services = List(
        JavaService(
          name = "RecordService",
          methods = List(JavaMethod("main", "void", List.empty, "")),
        )
      ),
      controllers = List(
        JavaController(
          name = "RecordController",
          basePath = "/api/records",
          endpoints = List(RestEndpoint("/run", HttpMethod.POST, "run")),
        )
      ),
      repositories = List(
        JavaRepository(
          name = "RecordRepository",
          entityName = "Record",
          idType = "Long",
          packageName = "com.example.record.repository",
          annotations = List("@Repository"),
          sourceCode = "public interface RecordRepository {}",
        )
      ),
      configuration = ProjectConfiguration(
        groupId = "com.example",
        artifactId = name.toLowerCase,
        dependencies = List("spring-boot-starter-web"),
      ),
      buildFile = BuildFile("maven", "<project/>"),
    )
