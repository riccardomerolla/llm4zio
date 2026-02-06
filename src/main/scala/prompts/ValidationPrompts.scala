package prompts

import models.{ CobolAnalysis, JavaMethod }

/** Prompt templates for test generation and validation
  *
  * Generates prompts for the ValidationAgent to create tests and verify transformation correctness.
  *
  * Responsibilities:
  *   - Generate unit tests using JUnit 5
  *   - Create integration tests for REST endpoints
  *   - Validate business logic preservation
  *   - Check compilation and static analysis
  *   - Generate test coverage reports
  *
  * Features:
  *   - JUnit 5 with modern patterns (@Test, assertions)
  *   - Cover all business logic paths from original COBOL
  *   - Meaningful test names (should_condition pattern)
  *   - Edge cases and boundary conditions
  */
object ValidationPrompts:

  val version: String = "1.0.0"

  private val systemPrompt =
    """You are an expert in Java test generation and code validation.
      |Your role is to generate comprehensive tests that verify business logic preservation.
      |
      |CRITICAL REQUIREMENTS:
      |- Always respond with valid JSON only, no markdown, no explanations
      |- Generate JUnit 5 tests with modern patterns (@Test, assertions)
      |- Cover all business logic paths from original COBOL
      |- Use meaningful test names (should_returnApproved_when_balanceExceeds1000)
      |- Include edge cases and boundary conditions
      |- Generate both unit tests and integration tests
      |
      |TEMPLATE_VERSION: 1.0.0
      |""".stripMargin

  /** Generate unit tests for Java service
    *
    * @param serviceName
    *   Name of the service class to test
    * @param methods
    *   List of methods in the service
    * @param originalCobol
    *   Original COBOL code for business logic reference
    * @return
    *   Formatted prompt for Gemini CLI
    */
  def generateUnitTests(
    serviceName: String,
    methods: List[JavaMethod],
    originalCobol: String,
  ): String =
    val methodsJson = methods.map(formatMethod).mkString(",\n")

    s"""$systemPrompt
       |
       |Generate comprehensive JUnit 5 unit tests for this Spring service.
       |
       |SERVICE: $serviceName
       |METHODS:
       |[$methodsJson]
       |
       |ORIGINAL COBOL LOGIC (for reference):
       |${PromptHelpers.formatCobolCode(originalCobol)}
       |
       |${PromptHelpers.schemaReference("TestResults")}
       |
       |$validationExamples
       |
       |Generate tests that:
       |1. Verify business logic matches COBOL behavior exactly
       |2. Test all conditional branches (IF, EVALUATE cases)
       |3. Test boundary conditions (min/max values, edge cases)
       |4. Test error scenarios (null inputs, invalid data)
       |5. Use descriptive test names following pattern: methodName_should_expectedBehavior_when_condition
       |6. Mock dependencies with @Mock and @InjectMocks
       |
       |Output format: Return JSON with test class structure and test methods.
       |
       |Respond with JSON only.
       |""".stripMargin

  /** Validate transformation correctness
    *
    * @param cobolCode
    *   Original COBOL source code
    * @param javaCode
    *   Generated Java code
    * @param analysis
    *   CobolAnalysis data
    * @return
    *   Formatted prompt for Gemini CLI
    */
  def validateTransformation(
    cobolCode: String,
    javaCode: String,
    analysis: CobolAnalysis,
  ): String =
    s"""$systemPrompt
       |
       |Validate that this Java code correctly preserves the COBOL business logic.
       |
       |ORIGINAL COBOL:
       |${PromptHelpers.formatCobolCode(cobolCode)}
       |
       |GENERATED JAVA:
       |```java
       |$javaCode
       |```
       |
       |COBOL ANALYSIS:
       |Procedures: ${analysis.procedures.size}
       |Variables: ${analysis.variables.size}
       |Complexity: ${analysis.complexity.cyclomaticComplexity}
       |
       |${PromptHelpers.schemaReference("SemanticValidation")}
       |
       |Validation checklist:
       |1. All COBOL variables have corresponding Java fields
       |2. All COBOL procedures have corresponding Java methods
       |3. Control flow logic is preserved (IF -> if, EVALUATE -> switch)
       |4. Arithmetic operations are correct (COMPUTE, ADD, SUBTRACT)
       |5. Data type conversions are safe (PIC clauses -> Java types)
       |6. Edge cases handled (division by zero, null checks)
       |7. Business logic is functionally equivalent
       |
       |Output: SemanticValidation with businessLogicPreserved true/false and classified issues.
       |
       |Respond with JSON only.
       |""".stripMargin

  /** Format method for prompt inclusion
    *
    * @param method
    *   JavaMethod to format
    * @return
    *   JSON-formatted method signature
    */
  private def formatMethod(method: JavaMethod): String =
    val params = method.parameters.map(p => s"${p.javaType} ${p.name}").mkString(", ")
    s"""{ "name": "${method.name}", "signature": "${method.returnType} ${method.name}($params)" }"""

  private val validationExamples =
    s"""
       |${PromptHelpers.fewShotExample(
        "Unit tests for credit check service",
        """Service method:
         |String creditCheck(BigDecimal balance) {
         |  if (balance.compareTo(new BigDecimal("1000")) > 0) {
         |    return "APPROVED";
         |  }
         |  return "PENDING";
         |}""",
        """Unit tests should cover:
         |1. creditCheck_should_returnApproved_when_balanceExceeds1000
         |2. creditCheck_should_returnPending_when_balanceEquals1000
         |3. creditCheck_should_returnPending_when_balanceLessThan1000
         |4. creditCheck_should_handleZeroBalance
         |5. creditCheck_should_handleNegativeBalance""",
      )}
       |
       |${PromptHelpers.fewShotExample(
        "Validation of COBOL to Java transformation",
        """COBOL: IF WS-BALANCE > 1000 MOVE 'APPROVED' TO WS-STATUS
         |Java: if (balance.compareTo(new BigDecimal("1000")) > 0) { return "APPROVED"; }""",
        """Validation result:
         |{
         |  "businessLogicPreserved": true,
         |  "confidence": 0.92,
         |  "summary": "Control flow and arithmetic mapping are equivalent.",
         |  "issues": []
         |}""",
      )}
       |
       |${PromptHelpers.fewShotExample(
        "Edge case test generation",
        """Method: BigDecimal calculateInterest(BigDecimal principal, double rate) {
         |  return principal.multiply(new BigDecimal(rate));
         |}""",
        """Edge case tests:
         |1. calculateInterest_should_returnZero_when_principalIsZero
         |2. calculateInterest_should_returnZero_when_rateIsZero
         |3. calculateInterest_should_handleNegativeRate
         |4. calculateInterest_should_handleVeryLargePrincipal
         |5. calculateInterest_should_throwException_when_principalIsNull""",
      )}
       |""".stripMargin
