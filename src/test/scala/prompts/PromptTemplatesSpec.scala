package prompts

import java.nio.file.Paths
import java.time.Instant

import zio.Scope
import zio.test.*

import models.*

/** Tests for prompt template generation
  *
  * Verifies that:
  *   - Prompts generate valid output strings
  *   - Dynamic content is properly interpolated
  *   - Schema references are included
  *   - Version tracking works
  *   - Chunking logic works for large files
  */
object PromptTemplatesSpec extends ZIOSpecDefault:

  val spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("PromptTemplatesSpec")(
    suite("CobolAnalyzer")(
      test("analyzeStructure generates valid prompt with file metadata") {
        val cobolFile = CobolFile(
          path = Paths.get("/cobol/CUSTPROG.cbl"),
          name = "CUSTPROG.cbl",
          size = 1024,
          lastModified = Instant.parse("2026-01-15T10:00:00Z"),
          encoding = "UTF-8",
          fileType = FileType.Program,
        )

        val cobolCode =
          """IDENTIFICATION DIVISION.
            |PROGRAM-ID. CUSTPROG.
            |DATA DIVISION.
            |WORKING-STORAGE SECTION.
            |01 WS-CUSTOMER-ID PIC 9(5).
            |PROCEDURE DIVISION.
            |MAIN-PARA.
            |    STOP RUN.
            |""".stripMargin

        val prompt = PromptTemplates.CobolAnalyzer.analyzeStructure(cobolFile, cobolCode)

        assertTrue(
          prompt.contains("CUSTPROG.cbl"),
          prompt.contains("```cobol"),
          prompt.contains("CobolAnalysis"),
          prompt.contains("TEMPLATE_VERSION: 1.0.0"),
        )
      },
      test("handles large files with chunking") {
        val cobolFile = CobolFile(
          path = Paths.get("/cobol/LARGE.cbl"),
          name = "LARGE.cbl",
          size = 50000,
          lastModified = Instant.parse("2026-02-05T10:00:00Z"),
          encoding = "UTF-8",
          fileType = FileType.Program,
        )

        // Create large COBOL code that will trigger chunking
        val largeCode = "IDENTIFICATION DIVISION.\nPROGRAM-ID. LARGE.\n" + ("*> COMMENT\n" * 1000)

        val prompt = PromptTemplates.CobolAnalyzer.analyzeStructure(cobolFile, largeCode)

        assertTrue(
          prompt.contains("LARGE.cbl"),
          prompt.contains("IDENTIFICATION"),
        )
      },
    ),
    suite("DependencyMapper")(
      test("extractDependencies generates valid prompt with multiple analyses") {
        val analysis1 = createSampleAnalysis("PROG1", List("COPY1", "COPY2"))
        val analysis2 = createSampleAnalysis("PROG2", List("COPY1", "COPY3"))

        val prompt = PromptTemplates.DependencyMapper.extractDependencies(List(analysis1, analysis2))

        assertTrue(
          prompt.contains("PROG1"),
          prompt.contains("PROG2"),
          prompt.contains("COPY1"),
          prompt.contains("DependencyGraph"),
          prompt.contains("TEMPLATE_VERSION: 1.0.0"),
        )
      }
    ),
    suite("JavaTransformer")(
      test("generateEntity generates valid prompt") {
        val analysis = createSampleAnalysis("CUSTPROG", List.empty)

        val prompt = PromptTemplates.JavaTransformer.generateEntity(analysis)

        assertTrue(
          prompt.contains("CUSTPROG"),
          prompt.contains("JavaEntity"),
          prompt.contains("CamelCase"),
          prompt.contains("@Entity"),
        )
      },
      test("generateService generates valid prompt with dependencies") {
        val analysis = createSampleAnalysis("CUSTPROG", List.empty)

        val prompt = PromptTemplates.JavaTransformer.generateService(analysis, List("ValidateService"))

        assertTrue(
          prompt.contains("CUSTPROG"),
          prompt.contains("ValidateService"),
          prompt.contains("JavaService"),
          prompt.contains("@Service"),
        )
      },
      test("generateController generates valid prompt") {
        val analysis = createSampleAnalysis("CUSTPROG", List.empty)

        val prompt = PromptTemplates.JavaTransformer.generateController(analysis, "CustomerService")

        assertTrue(
          prompt.contains("CUSTPROG"),
          prompt.contains("CustomerService"),
          prompt.contains("JavaController"),
          prompt.contains("@RestController"),
        )
      },
    ),
    suite("Validation")(
      test("generateUnitTests generates valid prompt") {
        val methods = List(
          JavaMethod(
            name = "creditCheck",
            returnType = "String",
            parameters = List(JavaParameter("balance", "BigDecimal")),
            body = "return \"APPROVED\";",
          )
        )

        val cobolCode = "IF WS-BALANCE > 1000 MOVE 'APPROVED' TO WS-STATUS."

        val prompt = PromptTemplates.Validation.generateUnitTests("CustomerService", methods, cobolCode)

        assertTrue(
          prompt.contains("CustomerService"),
          prompt.contains("creditCheck"),
          prompt.contains("JUnit 5"),
          prompt.contains("@Test"),
        )
      },
      test("validateTransformation generates valid prompt") {
        val analysis  = createSampleAnalysis("CUSTPROG", List.empty)
        val cobolCode = "MOVE 100 TO WS-TOTAL."
        val javaCode  = "total = 100;"

        val prompt = PromptTemplates.Validation.validateTransformation(cobolCode, javaCode, analysis)

        assertTrue(
          prompt.contains("ORIGINAL COBOL:"),
          prompt.contains("GENERATED JAVA:"),
          prompt.contains("ValidationReport"),
          prompt.contains("businessLogicValidation"),
        )
      },
    ),
    suite("Documentation")(
      test("generateTechnicalDesign generates valid prompt") {
        val analysis = createSampleAnalysis("CUSTPROG", List("CUSTREC"))
        val graph    = DependencyGraph.empty
        val project  = SpringBootProject.empty

        val prompt = PromptTemplates.Documentation.generateTechnicalDesign(List(analysis), graph, List(project))

        assertTrue(
          prompt.contains("MIGRATION SCOPE:"),
          prompt.contains("MigrationDocumentation"),
          prompt.contains("Architecture Overview"),
          prompt.contains("Mermaid"),
        )
      },
      test("generateMigrationSummary generates valid prompt") {
        val startTime = Instant.parse("2026-02-05T10:00:00Z")
        val endTime   = Instant.parse("2026-02-05T10:45:00Z")
        val analysis  = createSampleAnalysis("CUSTPROG", List.empty)
        val report    = ValidationReport.empty

        val prompt =
          PromptTemplates.Documentation.generateMigrationSummary(startTime, endTime, List(analysis), List(report))

        assertTrue(
          prompt.contains("MIGRATION METRICS:"),
          prompt.contains("Duration:"),
          prompt.contains("migrationSummary"),
          prompt.contains("Success Criteria"),
        )
      },
    ),
    suite("OutputSchemas")(
      test("contains all required schemas") {
        assertTrue(
          OutputSchemas.schemaMap.contains("CobolAnalysis"),
          OutputSchemas.schemaMap.contains("DependencyGraph"),
          OutputSchemas.schemaMap.contains("JavaEntity"),
          OutputSchemas.schemaMap.contains("JavaService"),
          OutputSchemas.schemaMap.contains("JavaController"),
          OutputSchemas.schemaMap.contains("ValidationReport"),
          OutputSchemas.schemaMap.contains("MigrationDocumentation"),
        )
      },
      test("getSchema returns schema for valid class name") {
        val schema = OutputSchemas.getSchema("CobolAnalysis")

        assertTrue(
          schema.contains("file"),
          schema.contains("divisions"),
          schema.contains("variables"),
          schema.contains("procedures"),
        )
      },
      test("getSchema returns error message for invalid class name") {
        val schema = OutputSchemas.getSchema("InvalidClassName")

        assertTrue(schema.contains("not found"))
      },
    ),
    suite("PromptHelpers")(
      test("formatCobolCode wraps code in markdown block") {
        val code      = "MOVE 100 TO WS-TOTAL."
        val formatted = PromptHelpers.formatCobolCode(code)

        assertTrue(
          formatted.contains("```cobol"),
          formatted.contains(code),
          formatted.contains("```"),
        )
      },
      test("schemaReference generates proper instructions") {
        val ref = PromptHelpers.schemaReference("CobolAnalysis")

        assertTrue(
          ref.contains("OUTPUT FORMAT:"),
          ref.contains("CobolAnalysis"),
          ref.contains("valid JSON"),
        )
      },
      test("chunkByDivision extracts divisions correctly") {
        val cobolCode =
          """IDENTIFICATION DIVISION.
            |PROGRAM-ID. TEST.
            |
            |DATA DIVISION.
            |WORKING-STORAGE SECTION.
            |01 WS-VAR PIC X.
            |
            |PROCEDURE DIVISION.
            |MAIN.
            |    STOP RUN.
            |""".stripMargin

        val chunks = PromptHelpers.chunkByDivision(cobolCode)

        assertTrue(
          chunks.contains("IDENTIFICATION"),
          chunks.contains("DATA"),
          chunks.contains("PROCEDURE"),
          chunks("IDENTIFICATION").contains("PROGRAM-ID. TEST"),
          chunks("DATA").contains("WORKING-STORAGE"),
          chunks("PROCEDURE").contains("MAIN"),
        )
      },
      test("validationRules generates requirement list") {
        val rules = PromptHelpers.validationRules(List("field1", "field2"))

        assertTrue(
          rules.contains("STRICT VALIDATION REQUIREMENTS:"),
          rules.contains("field1 is REQUIRED"),
          rules.contains("field2 is REQUIRED"),
        )
      },
      test("fewShotExample formats example correctly") {
        val example = PromptHelpers.fewShotExample("Test example", "input data", "output data")

        assertTrue(
          example.contains("EXAMPLE: Test example"),
          example.contains("Input:"),
          example.contains("input data"),
          example.contains("Expected Output:"),
          example.contains("output data"),
        )
      },
      test("estimateTokens returns reasonable estimate") {
        val text   = "a" * 1000
        val tokens = PromptHelpers.estimateTokens(text)

        assertTrue(tokens > 200 && tokens < 300) // ~4 chars per token = ~250 tokens
      },
      test("shouldChunk returns true for large text") {
        val largeText = "a" * 40000 // ~10k tokens

        assertTrue(PromptHelpers.shouldChunk(largeText))
      },
      test("shouldChunk returns false for small text") {
        val smallText = "a" * 1000 // ~250 tokens

        assertTrue(!PromptHelpers.shouldChunk(smallText))
      },
    ),
    suite("PromptTemplates")(
      test("versions returns all template versions") {
        val versions = PromptTemplates.versions

        assertTrue(
          versions.contains("CobolAnalyzer"),
          versions.contains("DependencyMapper"),
          versions.contains("JavaTransformer"),
          versions.contains("Validation"),
          versions.contains("Documentation"),
          versions.values.forall(_ == "1.0.0"),
        )
      },
      test("versionSummary returns formatted version string") {
        val summary = PromptTemplates.versionSummary

        assertTrue(
          summary.contains("Prompt Template Versions:"),
          summary.contains("CobolAnalyzer: 1.0.0"),
          summary.contains("Validation: 1.0.0"),
        )
      },
    ),
  )

  /** Helper to create sample CobolAnalysis for testing */
  private def createSampleAnalysis(programName: String, copybooks: List[String]): CobolAnalysis =
    CobolAnalysis(
      file = CobolFile(
        path = Paths.get(s"/cobol/$programName.cbl"),
        name = s"$programName.cbl",
        size = 1024,
        lastModified = Instant.parse("2026-02-05T10:00:00Z"),
        encoding = "UTF-8",
        fileType = FileType.Program,
      ),
      divisions = CobolDivisions(
        identification = Some(s"PROGRAM-ID. $programName."),
        environment = None,
        data = Some("WORKING-STORAGE SECTION."),
        procedure = Some("MAIN. STOP RUN."),
      ),
      variables = List(
        Variable("WS-TOTAL", 1, "numeric", Some("9(5)"), None)
      ),
      procedures = List(
        Procedure(
          "MAIN",
          List("MAIN"),
          List(Statement(1, "STOP", "STOP RUN")),
        )
      ),
      copybooks = copybooks,
      complexity = ComplexityMetrics(1, 10, 1),
    )
