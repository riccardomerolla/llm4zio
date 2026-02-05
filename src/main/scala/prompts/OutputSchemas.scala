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
      |  "name": "string (CamelCase class name, e.g., CustomerRecord)",
      |  "fields": [
      |    {
      |      "name": "string (camelCase field name, e.g., customerId)",
      |      "javaType": "string (Integer | Long | String | BigDecimal | etc.)",
      |      "annotations": [
      |        "string (e.g., @Id, @Column(length = 30), @NotNull)"
      |      ]
      |    }
      |  ],
      |  "annotations": [
      |    "string (class-level annotations: @Entity, @Table(name = \"...\"))"
      |  ]
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

  /** Schema for ValidationReport - test results and quality metrics
    *
    * Returned by: ValidationAgent
    *
    * Contains test execution results, coverage metrics, and business logic validation
    */
  val validationReport: String =
    """
      |{
      |  "testResults": {
      |    "totalTests": "number (count of all tests)",
      |    "passed": "number (count of passing tests)",
      |    "failed": "number (count of failing tests)"
      |  },
      |  "coverageMetrics": {
      |    "lineCoverage": "number (percentage 0.0-100.0)",
      |    "branchCoverage": "number (percentage 0.0-100.0)",
      |    "methodCoverage": "number (percentage 0.0-100.0)"
      |  },
      |  "staticAnalysisIssues": [
      |    "string (description of issue found by static analysis)"
      |  ],
      |  "businessLogicValidation": "boolean (true if Java logic matches COBOL)"
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
