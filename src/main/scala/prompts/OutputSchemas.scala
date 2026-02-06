package prompts

/** JSON schema documentation for Gemini CLI output formats
  *
  * Provides schema specifications that guide Gemini to generate correctly structured JSON responses. These schemas
  * correspond to the case classes in the models package and ensure type-safe parsing of Gemini's output.
  *
  * Each schema is a human-readable JSON template with:
  *   - Field names matching case class field names exactly
  *   - Type annotations (string, number, boolean, array, object)
  *   - Optional fields marked with "?" suffix
  *   - Nested object structures
  *   - Example values where helpful
  *
  * Usage: Include these schemas in prompts via PromptHelpers.schemaReference()
  */
object OutputSchemas:

  /** Schema for CobolAnalysis - complete structural analysis of a COBOL program
    *
    * Returned by: CobolAnalyzerAgent
    *
    * Contains divisions, variables, procedures, copybook references, and complexity metrics
    */
  val cobolAnalysis: String =
    """
      |{
      |  "file": {
      |    "path": "string (absolute file path)",
      |    "name": "string (filename with extension)",
      |    "size": "number (bytes)",
      |    "lastModified": "string (ISO-8601 timestamp)",
      |    "encoding": "string (e.g., UTF-8)",
      |    "fileType": "string (Program | Copybook | JCL)"
      |  },
      |  "divisions": {
      |    "identification": "string? (raw text of IDENTIFICATION DIVISION)",
      |    "environment": "string? (raw text of ENVIRONMENT DIVISION)",
      |    "data": "string? (raw text of DATA DIVISION)",
      |    "procedure": "string? (raw text of PROCEDURE DIVISION)"
      |  },
      |  "variables": [
      |    {
      |      "name": "string (variable name, e.g., WS-CUSTOMER-ID)",
      |      "level": "number (COBOL level: 1, 5, 10, 77, 88)",
      |      "dataType": "string (numeric | alphanumeric | group)",
      |      "picture": "string? (PIC clause, e.g., 9(5), X(30))",
      |      "usage": "string? (USAGE clause, e.g., COMP, COMP-3)"
      |    }
      |  ],
      |  "procedures": [
      |    {
      |      "name": "string (paragraph or section name)",
      |      "paragraphs": ["string (list of paragraph names in this section)"],
      |      "statements": [
      |        {
      |          "lineNumber": "number",
      |          "statementType": "string (MOVE | IF | PERFORM | EVALUATE | CALL | etc.)",
      |          "content": "string (full statement text)"
      |        }
      |      ]
      |    }
      |  ],
      |  "copybooks": ["string (copybook names without extension, e.g., CUSTREC)"],
      |  "complexity": {
      |    "cyclomaticComplexity": "number (count of decision points)",
      |    "linesOfCode": "number (non-comment lines)",
      |    "numberOfProcedures": "number (count of paragraphs/sections)"
      |  }
      |}
      |""".stripMargin

  /** Schema for DependencyGraph - relationships between COBOL programs and copybooks
    *
    * Returned by: DependencyMapperAgent
    *
    * Contains nodes (programs/copybooks) and edges (includes/calls relationships)
    */
  val dependencyGraph: String =
    """
      |{
      |  "nodes": [
      |    {
      |      "id": "string (unique identifier, use program/copybook name)",
      |      "name": "string (display name)",
      |      "nodeType": "string (Program | Copybook | SharedService)",
      |      "complexity": "number (cyclomatic complexity or 0 for copybooks)"
      |    }
      |  ],
      |  "edges": [
      |    {
      |      "from": "string (source node id)",
      |      "to": "string (target node id)",
      |      "edgeType": "string (Includes | Calls | Uses)"
      |    }
      |  ],
      |  "serviceCandidates": ["string (copybook names used by 3+ programs)"]
      |}
      |""".stripMargin

  /** Schema for JavaEntity - JPA entity class from COBOL data structure
    *
    * Returned by: JavaTransformerAgent.generateEntity
    *
    * Converts COBOL DATA DIVISION variables to Java entity fields
    */
  val javaEntity: String =
    """
      |{
      |  "className": "string (CamelCase class name, e.g., CustomerRecord)",
      |  "packageName": "string (Java package, e.g., com.example.customer.entity)",
      |  "fields": [
      |    {
      |      "name": "string (camelCase field name, e.g., customerId)",
      |      "javaType": "string (Integer | Long | String | BigDecimal | etc.)",
      |      "cobolSource": "string (original COBOL field name or PIC clause)",
      |      "annotations": [
      |        "string (e.g., @Id, @Column(length = 30), @NotNull)"
      |      ]
      |    }
      |  ],
      |  "annotations": [
      |    "string (class-level annotations: @Entity, @Table(name = \"...\"))"
      |  ],
      |  "sourceCode": "string (full Java class source)"
      |}
      |""".stripMargin

  /** Schema for JavaService - Spring service class from COBOL procedures
    *
    * Returned by: JavaTransformerAgent.generateService
    *
    * Converts COBOL PROCEDURE DIVISION to Spring service methods
    */
  val javaService: String =
    """
      |{
      |  "name": "string (Service class name, e.g., CustomerService)",
      |  "methods": [
      |    {
      |      "name": "string (camelCase method name, e.g., creditCheck)",
      |      "returnType": "string (Java return type: void | String | etc.)",
      |      "parameters": [
      |        {
      |          "name": "string (parameter name)",
      |          "javaType": "string (parameter type)"
      |        }
      |      ],
      |      "body": "string (method implementation as Java code)"
      |    }
      |  ]
      |}
      |""".stripMargin

  /** Schema for JavaController - REST controller from COBOL program entry points
    *
    * Returned by: JavaTransformerAgent.generateController
    *
    * Creates REST API endpoints for COBOL program execution
    */
  val javaController: String =
    """
      |{
      |  "name": "string (Controller class name, e.g., CustomerController)",
      |  "basePath": "string (base API path, e.g., /api/customer)",
      |  "endpoints": [
      |    {
      |      "path": "string (endpoint path, e.g., /process or /{id})",
      |      "method": "string (GET | POST | PUT | DELETE | PATCH)",
      |      "methodName": "string (handler method name, e.g., processCustomer)"
      |    }
      |  ]
      |}
      |""".stripMargin

  /** Schema for SemanticValidation - AI semantic equivalence validation
    *
    * Returned by: ValidationAgent semantic validation prompt
    */
  val semanticValidation: String =
    """
      |{
      |  "businessLogicPreserved": "boolean (true if Java logic preserves COBOL behavior)",
      |  "confidence": "number (0.0-1.0 confidence score)",
      |  "summary": "string (short explanation of semantic validation result)",
      |  "issues": [
      |    {
      |      "severity": "string (ERROR | WARNING | INFO)",
      |      "category": "string (Semantic | Coverage | StaticAnalysis | Compile | Convention)",
      |      "message": "string (issue description)",
      |      "file": "string? (optional filename)",
      |      "line": "number? (optional line number)",
      |      "suggestion": "string? (recommended remediation)"
      |    }
      |  ]
      |}
      |""".stripMargin

  /** Schema for ValidationReport - code quality and correctness validation
    *
    * Returned by: ValidationAgent
    *
    * Contains compile result, coverage metrics, semantic validation, and issue classifications
    */
  val validationReport: String =
    """
      |{
      |  "projectName": "string (Spring Boot project name)",
      |  "validatedAt": "string (ISO-8601 timestamp)",
      |  "compileResult": {
      |    "success": "boolean",
      |    "exitCode": "number",
      |    "output": "string (truncated compile output)"
      |  },
      |  "coverageMetrics": {
      |    "variablesCovered": "number (percentage 0.0-100.0)",
      |    "proceduresCovered": "number (percentage 0.0-100.0)",
      |    "fileSectionCovered": "number (percentage 0.0-100.0)",
      |    "unmappedItems": ["string (COBOL variables/procedures not mapped)"]
      |  },
      |  "issues": [
      |    {
      |      "severity": "string (ERROR | WARNING | INFO)",
      |      "category": "string (Compile | Coverage | StaticAnalysis | Semantic | Convention)",
      |      "message": "string",
      |      "file": "string?",
      |      "line": "number?",
      |      "suggestion": "string?"
      |    }
      |  ],
      |  "semanticValidation": {
      |    "businessLogicPreserved": "boolean",
      |    "confidence": "number (0.0-1.0)",
      |    "summary": "string",
      |    "issues": ["ValidationIssue objects with semantic findings"]
      |  },
      |  "overallStatus": "string (Passed | PassedWithWarnings | Failed)"
      |}
      |""".stripMargin

  /** Schema for TestResults - generated testing summary
    *
    * Returned by: ValidationAgent test-generation prompt
    */
  val testResults: String =
    """
      |{
      |  "totalTests": "number (count of all generated tests)",
      |  "passed": "number (count of passing tests)",
      |  "failed": "number (count of failing tests)"
      |}
      |""".stripMargin

  /** Schema for ValidationIssue - reusable issue format
    */
  val validationIssue: String =
    """
      |{
      |  "severity": "string (ERROR | WARNING | INFO)",
      |  "category": "string (Compile | Coverage | StaticAnalysis | Semantic | Convention)",
      |  "message": "string",
      |  "file": "string?",
      |  "line": "number?",
      |  "suggestion": "string?"
      |}
      |""".stripMargin

  /** Schema for ValidationStatus - overall validation status enum
    */
  val validationStatus: String =
    """
      |{
      |  "value": "string (Passed | PassedWithWarnings | Failed)"
      |}
      |""".stripMargin

  /** Schema for MigrationDocumentation - comprehensive migration documentation
    *
    * Returned by: DocumentationAgent
    *
    * Contains technical design, API docs, data mappings, summary, and deployment guide
    */
  val migrationDocumentation: String =
    """
      |{
      |  "technicalDesign": "string (Markdown-formatted technical design document)",
      |  "apiReference": "string (Markdown-formatted API reference documentation)",
      |  "dataModelMappings": "string (Markdown-formatted COBOL to Java data mappings)",
      |  "migrationSummary": "string (Markdown-formatted migration summary report)",
      |  "deploymentGuide": "string (Markdown-formatted deployment instructions)"
      |}
      |""".stripMargin

  /** Map of case class names to their schema definitions
    *
    * Allows lookup by class name for dynamic schema reference generation
    */
  val schemaMap: Map[String, String] = Map(
    "CobolAnalysis"          -> cobolAnalysis,
    "DependencyGraph"        -> dependencyGraph,
    "JavaEntity"             -> javaEntity,
    "JavaService"            -> javaService,
    "JavaController"         -> javaController,
    "SemanticValidation"     -> semanticValidation,
    "ValidationIssue"        -> validationIssue,
    "ValidationStatus"       -> validationStatus,
    "TestResults"            -> testResults,
    "ValidationReport"       -> validationReport,
    "MigrationDocumentation" -> migrationDocumentation,
  )

  /** Get schema by case class name with error handling
    *
    * @param className
    *   Name of the case class (e.g., "CobolAnalysis")
    * @return
    *   Schema string if found, or a default message if not found
    */
  def getSchema(className: String): String =
    schemaMap.getOrElse(
      className,
      s"""Schema for $className not found. Valid schemas: ${schemaMap.keys.mkString(", ")}""",
    )
