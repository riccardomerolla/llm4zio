package agents

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.json.*
import zio.test.*

import core.FileService
import models.*
import orchestration.MigrationResult

object DocumentationAgentSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Any] = suite("DocumentationAgentSpec")(
    test("generateDocs writes markdown/html/json and diagrams") {
      ZIO.scoped {
        for
          docs              <- DocumentationAgent
                                 .generateDocs(sampleResult)
                                 .provide(
                                   FileService.live,
                                   DocumentationAgent.live,
                                 )
          summaryMdExists   <- ZIO.attemptBlocking(Files.exists(Path.of("reports/documentation/migration-summary.md")))
          summaryHtmlExists <-
            ZIO.attemptBlocking(Files.exists(Path.of("reports/documentation/migration-summary.html")))
          jsonExists        <- ZIO.attemptBlocking(Files.exists(Path.of("reports/documentation/documentation.json")))
          diagramExists     <- ZIO.attemptBlocking(Files.exists(Path.of("reports/documentation/diagrams/architecture.mmd")))
          json              <- ZIO.attemptBlocking(Files.readString(Path.of("reports/documentation/documentation.json")))
          parsed             = json.fromJson[MigrationDocumentation]
        yield assertTrue(
          docs.summaryReport.contains("Migration Summary Report"),
          docs.diagrams.nonEmpty,
          summaryMdExists,
          summaryHtmlExists,
          jsonExists,
          diagramExists,
          parsed.isRight,
        )
      }
    },
    test("generateDocs fails for invalid result") {
      val invalid = MigrationResult(
        success = false,
        projects = List.empty,
        documentation = MigrationDocumentation.empty,
        validationReports = List.empty,
      )

      for
        result <- DocumentationAgent
                    .generateDocs(invalid)
                    .provide(FileService.live, DocumentationAgent.live)
                    .either
      yield assertTrue(
        result.left.exists {
          case DocError.InvalidResult(_) => true
          case _                         => false
        }
      )
    },
  ) @@ TestAspect.sequential

  private def sampleResult: MigrationResult =
    MigrationResult(
      success = true,
      projects = List(sampleProject("CUSTPROG")),
      documentation = MigrationDocumentation.empty,
      validationReports = List(sampleValidationReport("CUSTPROG")),
    )

  private def sampleProject(name: String): SpringBootProject =
    SpringBootProject(
      projectName = name,
      sourceProgram = s"$name.cbl",
      generatedAt = Instant.parse("2026-02-06T00:00:00Z"),
      entities = List(
        JavaEntity(
          className = "Customer",
          packageName = "com.example.customer.entity",
          fields = List(JavaField("customerId", "Long", "WS-CUSTOMER-ID", List("@Id"))),
          annotations = List("@Entity"),
          sourceCode = "public class Customer {}",
        )
      ),
      services =
        List(JavaService("CustomerService", List(JavaMethod("process", "String", List.empty, "return \"ok\";")))),
      controllers = List(JavaController(
        "CustomerController",
        "/api/customers",
        List(RestEndpoint("/process", HttpMethod.POST, "process")),
      )),
      repositories = List(
        JavaRepository(
          name = "CustomerRepository",
          entityName = "Customer",
          idType = "Long",
          packageName = "com.example.customer.repository",
          annotations = List("@Repository"),
          sourceCode = "public interface CustomerRepository {}",
        )
      ),
      configuration = ProjectConfiguration("com.example", name.toLowerCase, List("spring-boot-starter-web")),
      buildFile = BuildFile("maven", "<project/>"),
    )

  private def sampleValidationReport(projectName: String): ValidationReport =
    ValidationReport(
      projectName = projectName,
      validatedAt = Instant.parse("2026-02-06T00:00:00Z"),
      compileResult = CompileResult(success = true, exitCode = 0, output = ""),
      coverageMetrics = CoverageMetrics(100.0, 100.0, 100.0, List.empty),
      issues = List.empty,
      semanticValidation = SemanticValidation(
        businessLogicPreserved = true,
        confidence = 0.95,
        summary = "Equivalent",
        issues = List.empty,
      ),
      overallStatus = ValidationStatus.Passed,
    )
