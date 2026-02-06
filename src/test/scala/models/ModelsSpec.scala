package models

import java.nio.file.Paths
import java.time.Instant

import zio.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

import models.Codecs.given

object ModelsSpec extends ZIOSpecDefault:

  /** Helper to test round-trip JSON serialization */
  def roundTripTest[A: JsonEncoder: JsonDecoder](name: String, value: A): Spec[Any, Nothing] =
    test(s"$name round-trip serialization") {
      val json    = value.toJson
      val decoded = json.fromJson[A]
      assertTrue(decoded == Right(value))
    }

  /** Helper to test JSON encoding produces expected structure */
  def encodingTest[A: JsonEncoder](name: String, value: A, expectedJson: String): Spec[Any, Nothing] =
    test(s"$name encoding") {
      val json = value.toJson
      assertTrue(json == expectedJson)
    }

  def spec: Spec[Any, Nothing] = suite("ModelsSpec")(
    // ========================================================================
    // Custom Codec Tests
    // ========================================================================
    suite("Custom Codecs")(
      test("Path codec round-trip") {
        val path    = Paths.get("/some/path/to/file.cbl")
        val json    = path.toJson
        val decoded = json.fromJson[java.nio.file.Path]
        assertTrue(
          json == "\"/some/path/to/file.cbl\"",
          decoded == Right(path),
        )
      },
      test("Instant codec round-trip") {
        val instant = Instant.parse("2024-01-15T10:30:00Z")
        val json    = instant.toJson
        val decoded = json.fromJson[Instant]
        assertTrue(
          json == "\"2024-01-15T10:30:00Z\"",
          decoded == Right(instant),
        )
      },
      test("Instant.EPOCH codec") {
        val epoch   = Instant.EPOCH
        val json    = epoch.toJson
        val decoded = json.fromJson[Instant]
        assertTrue(
          json == "\"1970-01-01T00:00:00Z\"",
          decoded == Right(epoch),
        )
      },
    ),
    // ========================================================================
    // Enum Tests
    // ========================================================================
    suite("Enum Codecs")(
      roundTripTest("FileType.Program", FileType.Program),
      roundTripTest("FileType.Copybook", FileType.Copybook),
      roundTripTest("FileType.JCL", FileType.JCL),
      roundTripTest("NodeType.Program", NodeType.Program),
      roundTripTest("NodeType.SharedService", NodeType.SharedService),
      roundTripTest("EdgeType.Includes", EdgeType.Includes),
      roundTripTest("EdgeType.Calls", EdgeType.Calls),
      roundTripTest("HttpMethod.GET", HttpMethod.GET),
      roundTripTest("HttpMethod.POST", HttpMethod.POST),
      roundTripTest("MigrationStep.Discovery", MigrationStep.Discovery),
      roundTripTest("MigrationStep.Transformation", MigrationStep.Transformation),
      roundTripTest("AgentType.CobolDiscovery", AgentType.CobolDiscovery),
      roundTripTest("AgentType.JavaTransformer", AgentType.JavaTransformer),
    ),
    // ========================================================================
    // Discovery Phase Models
    // ========================================================================
    suite("Discovery Phase Models")(
      roundTripTest(
        "CobolFile",
        CobolFile(
          path = Paths.get("/cobol/PROGRAM1.cbl"),
          name = "PROGRAM1.cbl",
          size = 12345L,
          lineCount = 250,
          lastModified = Instant.parse("2024-01-15T10:30:00Z"),
          encoding = "UTF-8",
          fileType = FileType.Program,
        ),
      ),
      roundTripTest(
        "FileInventory",
        FileInventory(
          discoveredAt = Instant.parse("2026-02-05T10:00:00Z"),
          sourceDirectory = Paths.get("/cobol"),
          files = List(
            CobolFile(
              path = Paths.get("/cobol/PROG1.cbl"),
              name = "PROG1.cbl",
              size = 1000L,
              lineCount = 100,
              lastModified = Instant.EPOCH,
              encoding = "UTF-8",
              fileType = FileType.Program,
            )
          ),
          summary = InventorySummary(
            totalFiles = 1,
            programFiles = 1,
            copybooks = 0,
            jclFiles = 0,
            totalLines = 100,
            totalBytes = 1000L,
          ),
        ),
      ),
      roundTripTest(
        "FileInventory empty",
        FileInventory(
          discoveredAt = Instant.parse("2026-02-05T10:00:00Z"),
          sourceDirectory = Paths.get("/cobol"),
          files = List.empty,
          summary = InventorySummary(
            totalFiles = 0,
            programFiles = 0,
            copybooks = 0,
            jclFiles = 0,
            totalLines = 0L,
            totalBytes = 0L,
          ),
        ),
      ),
    ),
    // ========================================================================
    // Analysis Phase Models
    // ========================================================================
    suite("Analysis Phase Models")(
      roundTripTest(
        "Variable",
        Variable(
          name = "WS-COUNTER",
          level = 5,
          dataType = "NUMERIC",
          picture = Some("9(5)"),
          usage = Some("COMP-3"),
        ),
      ),
      roundTripTest(
        "Variable with None",
        Variable(
          name = "WS-FLAG",
          level = 88,
          dataType = "CONDITION",
          picture = None,
          usage = None,
        ),
      ),
      roundTripTest(
        "Statement",
        Statement(
          lineNumber = 100,
          statementType = "MOVE",
          content = "MOVE 0 TO WS-COUNTER",
        ),
      ),
      roundTripTest(
        "Procedure",
        Procedure(
          name = "MAIN-PARA",
          paragraphs = List("INIT-PARA", "PROCESS-PARA", "CLEANUP-PARA"),
          statements = List(
            Statement(100, "PERFORM", "PERFORM INIT-PARA"),
            Statement(101, "PERFORM", "PERFORM PROCESS-PARA"),
          ),
        ),
      ),
      roundTripTest(
        "ComplexityMetrics",
        ComplexityMetrics(
          cyclomaticComplexity = 15,
          linesOfCode = 500,
          numberOfProcedures = 10,
        ),
      ),
      roundTripTest(
        "CobolDivisions",
        CobolDivisions(
          identification = Some("PROGRAM-ID. MYPROGRAM."),
          environment = Some("CONFIGURATION SECTION."),
          data = Some("WORKING-STORAGE SECTION."),
          procedure = Some("PROCEDURE DIVISION."),
        ),
      ),
      roundTripTest("CobolAnalysis.empty", CobolAnalysis.empty),
    ),
    // ========================================================================
    // Dependency Mapping Phase Models
    // ========================================================================
    suite("Dependency Mapping Phase Models")(
      roundTripTest(
        "DependencyNode",
        DependencyNode(
          id = "node-1",
          name = "PROGRAM1",
          nodeType = NodeType.Program,
          complexity = 25,
        ),
      ),
      roundTripTest(
        "DependencyEdge",
        DependencyEdge(
          from = "PROGRAM1",
          to = "COPYBOOK1",
          edgeType = EdgeType.Includes,
        ),
      ),
      roundTripTest(
        "DependencyGraph",
        DependencyGraph(
          nodes = List(
            DependencyNode("n1", "PROG1", NodeType.Program, 10),
            DependencyNode("n2", "COPY1", NodeType.Copybook, 5),
          ),
          edges = List(
            DependencyEdge("n1", "n2", EdgeType.Includes)
          ),
          serviceCandidates = List("PROG1"),
        ),
      ),
      roundTripTest("DependencyGraph.empty", DependencyGraph.empty),
    ),
    // ========================================================================
    // Transformation Phase Models
    // ========================================================================
    suite("Transformation Phase Models")(
      roundTripTest(
        "JavaField",
        JavaField(
          name = "counter",
          javaType = "Long",
          cobolSource = "WS-COUNTER PIC 9(5)",
          annotations = List("@Column(name = \"counter\")"),
        ),
      ),
      roundTripTest(
        "JavaEntity",
        JavaEntity(
          className = "Customer",
          packageName = "com.example.customer.entity",
          fields = List(
            JavaField("id", "Long", "CUSTOMER-ID", List("@Id", "@GeneratedValue")),
            JavaField("name", "String", "CUSTOMER-NAME", List("@Column")),
          ),
          annotations = List("@Entity", "@Table(name = \"customers\")"),
          sourceCode = "public class Customer { }",
        ),
      ),
      roundTripTest(
        "JavaParameter",
        JavaParameter(name = "customerId", javaType = "Long"),
      ),
      roundTripTest(
        "JavaMethod",
        JavaMethod(
          name = "findById",
          returnType = "Optional<Customer>",
          parameters = List(JavaParameter("id", "Long")),
          body = "return repository.findById(id);",
        ),
      ),
      roundTripTest(
        "JavaService",
        JavaService(
          name = "CustomerService",
          methods = List(
            JavaMethod("findAll", "List<Customer>", List.empty, "return repository.findAll();")
          ),
        ),
      ),
      roundTripTest(
        "RestEndpoint",
        RestEndpoint(
          path = "/customers/{id}",
          method = HttpMethod.GET,
          methodName = "getCustomerById",
        ),
      ),
      roundTripTest(
        "JavaController",
        JavaController(
          name = "CustomerController",
          basePath = "/api/v1",
          endpoints = List(
            RestEndpoint("/customers", HttpMethod.GET, "getAllCustomers"),
            RestEndpoint("/customers", HttpMethod.POST, "createCustomer"),
          ),
        ),
      ),
      roundTripTest(
        "JavaRepository",
        JavaRepository(
          name = "CustomerRepository",
          entityName = "Customer",
          idType = "Long",
          packageName = "com.example.customer.repository",
          annotations = List("@Repository"),
          sourceCode = "public interface CustomerRepository {}",
        ),
      ),
      roundTripTest(
        "BuildFile",
        BuildFile(tool = "maven", content = "<project></project>"),
      ),
      roundTripTest(
        "ProjectConfiguration",
        ProjectConfiguration(
          groupId = "com.example",
          artifactId = "customer-service",
          dependencies = List("spring-boot-starter-web", "spring-boot-starter-data-jpa"),
        ),
      ),
      roundTripTest("SpringBootProject.empty", SpringBootProject.empty),
    ),
    // ========================================================================
    // Validation Phase Models
    // ========================================================================
    suite("Validation Phase Models")(
      roundTripTest(
        "TestResults",
        TestResults(totalTests = 100, passed = 95, failed = 5),
      ),
      roundTripTest(
        "CompileResult",
        CompileResult(success = true, exitCode = 0, output = ""),
      ),
      roundTripTest(
        "CoverageMetrics",
        CoverageMetrics(
          variablesCovered = 85.5,
          proceduresCovered = 72.3,
          fileSectionCovered = 90.0,
          unmappedItems = List("WS-NAME"),
        ),
      ),
      roundTripTest(
        "ValidationIssue",
        ValidationIssue(
          severity = Severity.WARNING,
          category = IssueCategory.StaticAnalysis,
          message = "unused variable",
          file = Some("CustomerService.java"),
          line = Some(42),
          suggestion = Some("Remove or use the variable"),
        ),
      ),
      roundTripTest(
        "SemanticValidation",
        SemanticValidation(
          businessLogicPreserved = true,
          confidence = 0.93,
          summary = "Equivalent control flow",
          issues = List.empty,
        ),
      ),
      roundTripTest("ValidationStatus.Passed", ValidationStatus.Passed),
      roundTripTest("ValidationStatus.Failed", ValidationStatus.Failed),
      roundTripTest("Severity.ERROR", Severity.ERROR),
      roundTripTest("IssueCategory.Semantic", IssueCategory.Semantic),
      roundTripTest(
        "ValidationReport",
        ValidationReport(
          projectName = "CUSTPROG",
          validatedAt = Instant.parse("2026-02-06T00:00:00Z"),
          compileResult = CompileResult(success = true, exitCode = 0, output = ""),
          coverageMetrics = CoverageMetrics(80.0, 70.0, 85.0, List("WS-ID")),
          issues = List(
            ValidationIssue(
              severity = Severity.INFO,
              category = IssueCategory.Coverage,
              message = "Procedure coverage below 100%",
              file = None,
              line = None,
              suggestion = None,
            )
          ),
          semanticValidation = SemanticValidation(
            businessLogicPreserved = true,
            confidence = 0.88,
            summary = "Functionally equivalent with minor differences",
            issues = List.empty,
          ),
          overallStatus = ValidationStatus.PassedWithWarnings,
        ),
      ),
      roundTripTest("ValidationReport.empty", ValidationReport.empty),
    ),
    // ========================================================================
    // Documentation Phase Models
    // ========================================================================
    suite("Documentation Phase Models")(
      roundTripTest(
        "MigrationDocumentation",
        MigrationDocumentation(
          technicalDesign = "# Technical Design\n\nArchitecture overview...",
          apiReference = "# API Reference\n\nEndpoints...",
          dataModelMappings = "# Data Model Mappings\n\nCOBOL to Java...",
          migrationSummary = "# Migration Summary\n\nFiles processed: 100",
          deploymentGuide = "# Deployment Guide\n\nSteps...",
        ),
      ),
      roundTripTest("MigrationDocumentation.empty", MigrationDocumentation.empty),
    ),
    // ========================================================================
    // State Management Models
    // ========================================================================
    suite("State Management Models")(
      roundTripTest(
        "MigrationState with data",
        MigrationState(
          runId = "test-run",
          startedAt = Instant.parse("2024-01-15T08:00:00Z"),
          currentStep = MigrationStep.Analysis,
          completedSteps = Set(MigrationStep.Discovery),
          artifacts = Map.empty,
          errors = List.empty,
          config = MigrationConfig(
            sourceDir = Paths.get("cobol-source"),
            outputDir = Paths.get("java-output"),
          ),
          fileInventory = Some(
            FileInventory(
              discoveredAt = Instant.parse("2024-01-15T08:05:00Z"),
              sourceDirectory = Paths.get("cobol-source"),
              files = List.empty,
              summary = InventorySummary(
                totalFiles = 0,
                programFiles = 0,
                copybooks = 0,
                jclFiles = 0,
                totalLines = 0L,
                totalBytes = 0L,
              ),
            )
          ),
          analyses = List.empty,
          dependencyGraph = None,
          projects = List.empty,
          validationReports = List.empty,
          lastCheckpoint = Instant.parse("2024-01-15T09:30:00Z"),
        ),
      ),
      test("MigrationState.empty creates valid state") {
        for state <- MigrationState.empty
        yield assertTrue(
          state.currentStep == MigrationStep.Discovery,
          state.completedSteps.isEmpty,
          state.fileInventory.isEmpty,
        )
      },
    ),
    // ========================================================================
    // Agent Message Models
    // ========================================================================
    suite("Agent Message Models")(
      roundTripTest(
        "AgentMessage",
        AgentMessage(
          id = "msg-123",
          sourceAgent = AgentType.CobolDiscovery,
          targetAgent = AgentType.CobolAnalyzer,
          payload = """{"files": ["PROG1.cbl"]}""",
          timestamp = Instant.parse("2024-01-15T10:00:00Z"),
        ),
      )
    ),
    // ========================================================================
    // Complex Nested Structure Tests
    // ========================================================================
    suite("Complex Nested Structures")(
      test("Full SpringBootProject round-trip") {
        val project = SpringBootProject(
          projectName = "legacy-migration",
          sourceProgram = "MAINPROG.cbl",
          generatedAt = Instant.parse("2026-02-06T00:00:00Z"),
          entities = List(
            JavaEntity(
              className = "Customer",
              packageName = "com.example.customer.entity",
              fields = List(JavaField("id", "Long", "CUSTOMER-ID", List("@Id"))),
              annotations = List("@Entity"),
              sourceCode = "public class Customer {}",
            )
          ),
          services = List(
            JavaService(
              "CustomerService",
              List(JavaMethod("findAll", "List<Customer>", List.empty, "return repo.findAll();")),
            )
          ),
          controllers = List(
            JavaController(
              "CustomerController",
              "/api",
              List(RestEndpoint("/customers", HttpMethod.GET, "getAll")),
            )
          ),
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
          configuration = ProjectConfiguration("com.example", "app", List("spring-web")),
          buildFile = BuildFile("maven", "<project></project>"),
        )

        val json    = project.toJson
        val decoded = json.fromJson[SpringBootProject]
        assertTrue(decoded == Right(project))
      },
      test("Full CobolAnalysis round-trip") {
        val analysis = CobolAnalysis(
          file = CobolFile(
            Paths.get("/cobol/MAINPROG.cbl"),
            "MAINPROG.cbl",
            5000L,
            250,
            Instant.parse("2024-01-01T00:00:00Z"),
            "EBCDIC",
            FileType.Program,
          ),
          divisions = CobolDivisions(
            Some("PROGRAM-ID. MAINPROG."),
            Some("CONFIGURATION SECTION."),
            Some("WORKING-STORAGE SECTION.\n01 WS-VAR PIC X(10)."),
            Some("PROCEDURE DIVISION.\nMAIN-PARA.\n    STOP RUN."),
          ),
          variables = List(
            Variable("WS-VAR", 1, "ALPHANUMERIC", Some("X(10)"), None)
          ),
          procedures = List(
            Procedure(
              "MAIN-PARA",
              List.empty,
              List(Statement(100, "STOP", "STOP RUN")),
            )
          ),
          copybooks = List("COMMON.cpy", "ERRORS.cpy"),
          complexity = ComplexityMetrics(5, 100, 3),
        )

        val json    = analysis.toJson
        val decoded = json.fromJson[CobolAnalysis]
        assertTrue(decoded == Right(analysis))
      },
    ),
    // ========================================================================
    // Edge Cases
    // ========================================================================
    suite("Edge Cases")(
      test("Empty strings in models") {
        val doc     = MigrationDocumentation("", "", "", "", "")
        val json    = doc.toJson
        val decoded = json.fromJson[MigrationDocumentation]
        assertTrue(decoded == Right(doc))
      },
      test("Unicode content in strings") {
        val statement = Statement(1, "DISPLAY", "DISPLAY 'Hello \u4e16\u754c'")
        val json      = statement.toJson
        val decoded   = json.fromJson[Statement]
        assertTrue(decoded == Right(statement))
      },
      test("Large numbers in metrics") {
        val metrics = ComplexityMetrics(
          cyclomaticComplexity = Int.MaxValue,
          linesOfCode = Int.MaxValue,
          numberOfProcedures = Int.MaxValue,
        )
        val json    = metrics.toJson
        val decoded = json.fromJson[ComplexityMetrics]
        assertTrue(decoded == Right(metrics))
      },
      test("Special characters in paths") {
        val file    = CobolFile(
          path = Paths.get("/path/with spaces/and-dashes/file.cbl"),
          name = "file.cbl",
          size = 100L,
          lineCount = 10,
          lastModified = Instant.EPOCH,
          encoding = "UTF-8",
          fileType = FileType.Program,
        )
        val json    = file.toJson
        val decoded = json.fromJson[CobolFile]
        assertTrue(decoded == Right(file))
      },
    ),
  )
