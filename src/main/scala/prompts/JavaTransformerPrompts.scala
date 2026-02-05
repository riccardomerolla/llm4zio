package prompts

import models.{ CobolAnalysis, Procedure, Variable }

/** Prompt templates for COBOL to Java transformation
  *
  * Generates prompts for the JavaTransformerAgent to transform COBOL programs into Spring Boot microservices.
  *
  * Responsibilities:
  *   - Convert COBOL data structures to Java classes/records
  *   - Transform PROCEDURE DIVISION to service methods
  *   - Generate Spring Boot annotations and configurations
  *   - Implement REST endpoints for program entry points
  *   - Create Spring Data JPA entities from file definitions
  *   - Handle error scenarios with try-catch blocks
  *
  * Features:
  *   - Modern Java 17+ patterns (records, switch expressions)
  *   - Spring Boot 3.x annotations
  *   - Preserves COBOL business logic exactly
  *   - Java naming conventions (camelCase)
  */
object JavaTransformerPrompts:

  val version: String = "1.0.0"

  private val systemPrompt =
    """You are an expert in COBOL to Java Spring Boot transformation.
      |Your role is to generate clean, idiomatic, production-ready Java code.
      |
      |CRITICAL REQUIREMENTS:
      |- Always respond with valid JSON only, no markdown, no explanations
      |- Generate Java 17+ code with modern patterns (records, switch expressions)
      |- Use Spring Boot 3.x annotations (@Service, @RestController, @Entity)
      |- Preserve COBOL business logic exactly - no functional changes
      |- Use meaningful Java naming conventions (camelCase, not COBOL-STYLE)
      |- Generate proper error handling with try-catch blocks
      |- Include Spring Data JPA for file persistence
      |
      |TEMPLATE_VERSION: 1.0.0
      |""".stripMargin

  /** Generate Java entity from COBOL data structures
    *
    * @param analysis
    *   CobolAnalysis containing variables and data structures
    * @return
    *   Formatted prompt for Gemini CLI
    */
  def generateEntity(analysis: CobolAnalysis): String =
    val variablesJson = formatVariables(analysis.variables)

    s"""$systemPrompt
       |
       |Generate a Java entity (JPA) from this COBOL data structure.
       |
       |COBOL PROGRAM: ${analysis.file.name}
       |
       |VARIABLES:
       |$variablesJson
       |
       |${PromptHelpers.schemaReference("JavaEntity")}
       |
       |${PromptHelpers.validationRules(
        List(
          "name (Java class name, CamelCase)",
          "fields (all COBOL variables converted)",
          "annotations (must include @Entity, @Table)",
        )
      )}
       |
       |$entityExamples
       |
       |Transformation rules:
       |1. Entity name: Convert COBOL program name to CamelCase (e.g., CUST-PROG -> CustomerProgram)
       |2. Fields: Convert each COBOL variable:
       |   - PIC 9(n): Integer or Long (depending on size)
       |   - PIC 9(n)V9(m): BigDecimal
       |   - PIC X(n): String
       |   - PIC S9(n): Signed integer
       |   - Convert COBOL-NAMES to camelCase (WS-CUSTOMER-ID -> customerId)
       |3. Annotations:
       |   - @Entity on class
       |   - @Table(name = "cobol_program_name")
       |   - @Id on first field (or field with "ID" in name)
       |   - @Column(length = n) from PIC clause
       |4. Level numbers:
       |   - 01 level: Top-level entity
       |   - 05-49 levels: Nested fields or separate embedded classes
       |
       |Respond with JSON only.
       |""".stripMargin

  /** Generate Spring service from COBOL procedures
    *
    * @param analysis
    *   CobolAnalysis containing procedures
    * @param dependencies
    *   List of dependent service names
    * @return
    *   Formatted prompt for Gemini CLI
    */
  def generateService(analysis: CobolAnalysis, dependencies: List[String]): String =
    val proceduresJson = formatProcedures(analysis.procedures)
    val depsJson       = dependencies.map(d => s""""$d"""").mkString(", ")

    s"""$systemPrompt
       |
       |Generate a Spring Boot service from this COBOL program logic.
       |
       |COBOL PROGRAM: ${analysis.file.name}
       |
       |PROCEDURES:
       |$proceduresJson
       |
       |DEPENDENCIES: [$depsJson]
       |
       |${PromptHelpers.schemaReference("JavaService")}
       |
       |${PromptHelpers.validationRules(
        List(
          "name (Service class name)",
          "methods (one per COBOL paragraph/procedure)",
        )
      )}
       |
       |$serviceExamples
       |
       |Transformation rules:
       |1. Service name: COBOL program name + "Service" (CUSTPROG -> CustomerProgramService)
       |2. Class annotations: @Service
       |3. Methods: Convert each COBOL paragraph to a method:
       |   - Method name: Convert paragraph name to camelCase (CREDIT-CHECK -> creditCheck)
       |   - Parameters: Extract from LINKAGE SECTION or called program parameters
       |   - Return type: Determine from logic (void if no return value)
       |4. Statement conversion:
       |   - MOVE: variable assignment (MOVE X TO Y -> y = x)
       |   - IF/ELSE: if-else blocks
       |   - PERFORM: method calls
       |   - EVALUATE: switch expressions (Java 17+)
       |   - COMPUTE: arithmetic expressions
       |   - DISPLAY: logger.info()
       |   - READ/WRITE: repository calls
       |5. Dependencies: @Autowired for each dependency service
       |
       |Respond with JSON only.
       |""".stripMargin

  /** Generate REST controller from COBOL entry points
    *
    * @param analysis
    *   CobolAnalysis containing entry point information
    * @param serviceName
    *   Name of the generated service class
    * @return
    *   Formatted prompt for Gemini CLI
    */
  def generateController(analysis: CobolAnalysis, serviceName: String): String =
    val mainProcedure = analysis.procedures.headOption.map(_.name).getOrElse("MAIN")

    s"""$systemPrompt
       |
       |Generate a Spring Boot REST controller for this COBOL program.
       |
       |COBOL PROGRAM: ${analysis.file.name}
       |SERVICE: $serviceName
       |ENTRY POINT: $mainProcedure
       |
       |${PromptHelpers.schemaReference("JavaController")}
       |
       |${PromptHelpers.validationRules(
        List(
          "name (Controller class name)",
          "basePath (REST API base path)",
          "endpoints (at least one endpoint)",
        )
      )}
       |
       |$controllerExamples
       |
       |Transformation rules:
       |1. Controller name: COBOL program + "Controller" (CUSTPROG -> CustomerProgramController)
       |2. Annotations: @RestController, @RequestMapping
       |3. Base path: /api/{program-name-kebab-case}
       |4. Endpoints: Create REST endpoint for main entry point
       |   - POST endpoint for main procedure (business logic execution)
       |   - GET endpoint if program reads data
       |   - Parameters: Extract from LINKAGE SECTION
       |5. Inject service: @Autowired {ServiceName}
       |
       |Respond with JSON only.
       |""".stripMargin

  /** Format variables for prompt inclusion
    *
    * @param variables
    *   List of COBOL variables
    * @return
    *   JSON-formatted variable array
    */
  private def formatVariables(variables: List[Variable]): String =
    variables
      .map { v =>
        s"""{ "name": "${v.name}", "level": ${v.level}, "dataType": "${v.dataType}", "picture": "${v.picture.getOrElse(
            ""
          )}" }"""
      }
      .mkString("[\n", ",\n", "\n]")

  /** Format procedures for prompt inclusion
    *
    * @param procedures
    *   List of COBOL procedures
    * @return
    *   JSON-formatted procedure array
    */
  private def formatProcedures(procedures: List[Procedure]): String =
    procedures
      .map { p =>
        val stmts = p.statements.map(s => s""""${s.statementType}: ${s.content}"""").mkString(", ")
        s"""{ "name": "${p.name}", "statements": [$stmts] }"""
      }
      .mkString("[\n", ",\n", "\n]")

  private val entityExamples =
    s"""
       |${PromptHelpers.fewShotExample(
        "Simple COBOL record to JPA entity",
        """[
         |  { "name": "WS-CUSTOMER-ID", "level": 1, "dataType": "numeric", "picture": "9(5)" },
         |  { "name": "WS-CUSTOMER-NAME", "level": 1, "dataType": "alphanumeric", "picture": "X(30)" },
         |  { "name": "WS-BALANCE", "level": 1, "dataType": "numeric", "picture": "9(7)V99" }
         |]""",
        """{
         |  "name": "Customer",
         |  "fields": [
         |    { "name": "customerId", "javaType": "Integer", "annotations": ["@Id", "@Column(length = 5)"] },
         |    { "name": "customerName", "javaType": "String", "annotations": ["@Column(length = 30)"] },
         |    { "name": "balance", "javaType": "BigDecimal", "annotations": ["@Column(precision = 9, scale = 2)"] }
         |  ],
         |  "annotations": ["@Entity", "@Table(name = \\"customer\\")"]
         |}""",
      )}
       |
       |${PromptHelpers.fewShotExample(
        "COBOL group item to nested entity",
        """[
         |  { "name": "CUSTOMER-RECORD", "level": 1, "dataType": "group", "picture": "" },
         |  { "name": "CUST-ID", "level": 5, "dataType": "numeric", "picture": "9(8)" },
         |  { "name": "CUST-NAME", "level": 5, "dataType": "alphanumeric", "picture": "X(50)" },
         |  { "name": "ADDRESS-INFO", "level": 5, "dataType": "group", "picture": "" },
         |  { "name": "STREET", "level": 10, "dataType": "alphanumeric", "picture": "X(40)" },
         |  { "name": "CITY", "level": 10, "dataType": "alphanumeric", "picture": "X(30)" }
         |]""",
        """{
         |  "name": "CustomerRecord",
         |  "fields": [
         |    { "name": "custId", "javaType": "Long", "annotations": ["@Id", "@Column(length = 8)"] },
         |    { "name": "custName", "javaType": "String", "annotations": ["@Column(length = 50)"] },
         |    { "name": "street", "javaType": "String", "annotations": ["@Column(length = 40)"] },
         |    { "name": "city", "javaType": "String", "annotations": ["@Column(length = 30)"] }
         |  ],
         |  "annotations": ["@Entity", "@Table(name = \\"customer_record\\")"]
         |}""",
      )}
       |""".stripMargin

  private val serviceExamples =
    s"""
       |${PromptHelpers.fewShotExample(
        "COBOL paragraph to service method",
        """[
         |  {
         |    "name": "CREDIT-CHECK",
         |    "statements": [
         |      "IF: IF WS-BALANCE > 1000",
         |      "DISPLAY: DISPLAY 'High balance customer'",
         |      "MOVE: MOVE 'APPROVED' TO WS-STATUS"
         |    ]
         |  }
         |]""",
        """{
         |  "name": "CustomerService",
         |  "methods": [
         |    {
         |      "name": "creditCheck",
         |      "returnType": "String",
         |      "parameters": [
         |        { "name": "balance", "javaType": "BigDecimal" }
         |      ],
         |      "body": "if (balance.compareTo(new BigDecimal(\\"1000\\")) > 0) {\\n  logger.info(\\"High balance customer\\");\\n  return \\"APPROVED\\";\\n}\\nreturn \\"PENDING\\";"
         |    }
         |  ]
         |}""",
      )}
       |
       |${PromptHelpers.fewShotExample(
        "COBOL EVALUATE to Java switch expression",
        """[
         |  {
         |    "name": "PROCESS-STATUS",
         |    "statements": [
         |      "EVALUATE: EVALUATE WS-STATUS-CODE",
         |      "WHEN: WHEN 10",
         |      "MOVE: MOVE 'ACTIVE' TO WS-RESULT",
         |      "WHEN: WHEN 20",
         |      "MOVE: MOVE 'PENDING' TO WS-RESULT",
         |      "WHEN: WHEN OTHER",
         |      "MOVE: MOVE 'UNKNOWN' TO WS-RESULT"
         |    ]
         |  }
         |]""",
        """{
         |  "name": "StatusService",
         |  "methods": [
         |    {
         |      "name": "processStatus",
         |      "returnType": "String",
         |      "parameters": [
         |        { "name": "statusCode", "javaType": "Integer" }
         |      ],
         |      "body": "return switch (statusCode) {\\n  case 10 -> \\"ACTIVE\\";\\n  case 20 -> \\"PENDING\\";\\n  default -> \\"UNKNOWN\\";\\n};"
         |    }
         |  ]
         |}""",
      )}
       |""".stripMargin

  private val controllerExamples =
    s"""
       |${PromptHelpers.fewShotExample(
        "COBOL program to REST controller",
        """COBOL PROGRAM: CUSTPROG
         |SERVICE: CustomerService
         |ENTRY POINT: MAIN-PROCESS""",
        """{
         |  "name": "CustomerController",
         |  "basePath": "/api/customer",
         |  "endpoints": [
         |    {
         |      "path": "/process",
         |      "method": "POST",
         |      "methodName": "processCustomer"
         |    },
         |    {
         |      "path": "/{id}",
         |      "method": "GET",
         |      "methodName": "getCustomer"
         |    }
         |  ]
         |}""",
      )}
       |""".stripMargin
