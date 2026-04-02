package llm4zio.providers

import zio.*
import zio.json.*
import zio.stream.ZStream

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

/** Mock AI Provider for demo mode
  *
  * Returns pre-canned deterministic responses for all LlmService methods. No network calls are made. The
  * `executeStructured` method returns a valid `GeneratedIssueBatch`-compatible JSON so the IssueCreationWizard works
  * seamlessly in demo mode.
  */
object MockProvider:

  def make(config: LlmConfig): LlmService =
    new LlmService:

      override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        val response = mockResponse(prompt)
        val words    = response.split(" ").toList
        ZStream
          .fromIterable(words.zipWithIndex.map {
            case (word, idx) =>
              val separator = if idx == 0 then "" else " "
              LlmChunk(
                delta = s"$separator$word",
                finishReason = if idx == words.size - 1 then Some("stop") else None,
                usage =
                  if idx == words.size - 1 then
                    Some(TokenUsage(prompt = 10, completion = words.size, total = 10 + words.size))
                  else None,
                metadata = Map("provider" -> "mock", "model" -> config.model),
              )
          })
          .schedule(Schedule.spaced(50.millis))

      override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
        val lastUserMsg = messages.reverseIterator.find(_.role == MessageRole.User).map(_.content).getOrElse("")
        executeStream(lastUserMsg)

      override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
        ZIO.succeed(
          ToolCallResponse(
            content = Some(mockResponse(prompt)),
            toolCalls = Nil,
            finishReason = "stop",
          )
        )

      override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
        val json = mockStructuredResponse(prompt, schema)
        ZIO
          .fromEither(json.fromJson[A])
          .mapError(err => LlmError.ParseError(s"Mock structured parse error: $err", json))

      override def isAvailable: UIO[Boolean] = ZIO.succeed(true)

  private def mockResponse(prompt: String): String =
    "This is a mock response from the demo AI provider. " +
      "The mock provider simulates LLM output without making any real API calls. " +
      "All features work normally except AI-generated content is replaced with pre-canned responses."

  /** Generate a structured JSON response matching the expected schema.
    *
    * Detects GeneratedIssueBatch schema (has "summary" + "issues" properties) and returns pre-canned issues. For other
    * schemas, returns a minimal valid JSON object.
    */
  private def mockStructuredResponse(prompt: String, schema: JsonSchema): String =
    val schemaStr = schema.toJson
    if schemaStr.contains("\"summary\"") && schemaStr.contains("\"issues\"") then mockIssueBatchJson(prompt)
    else """{"result": "mock structured response"}"""

  private def mockIssueBatchJson(prompt: String): String =
    // Extract desired count from prompt if present, default to 10
    val countPattern = """(\d+)\s*issue""".r
    val count        = countPattern.findFirstMatchIn(prompt.toLowerCase).map(_.group(1).toInt).getOrElse(10).min(100)
    val issues       = (1 to count).map(i => mockIssueJson(i)).mkString(",\n    ")
    s"""{
       |  "summary": "Generated $count issue drafts for Spring Boot microservice demo.",
       |  "issues": [
       |    $issues
       |  ]
       |}""".stripMargin

  private def mockIssueJson(index: Int): String =
    val (title, tags, capabilities, criteria, body) = issueTemplates((index - 1) % issueTemplates.size)
    val priority                                    = index match
      case i if i <= 3 => "high"
      case i if i <= 7 => "medium"
      case _           => "low"
    s"""{
       |      "id": "demo-issue-$index",
       |      "title": "$title",
       |      "priority": "$priority",
       |      "assignedAgent": null,
       |      "requiredCapabilities": [${capabilities.map(c => s""""$c"""").mkString(", ")}],
       |      "blockedBy": [],
       |      "tags": [${tags.map(t => s""""$t"""").mkString(", ")}],
       |      "acceptanceCriteria": [${criteria.map(c => s""""$c"""").mkString(", ")}],
       |      "estimate": "2h",
       |      "proofOfWork": ["diff-stat"],
       |      "body": "$body"
       |    }""".stripMargin

  /** Compact issue templates: (title, tags, capabilities, acceptanceCriteria, body) */
  private val issueTemplates: IndexedSeq[(String, List[String], List[String], List[String], String)] = IndexedSeq(
    (
      "Set up Spring Boot project scaffold",
      List("setup", "bootstrap"),
      List("java", "spring-boot"),
      List("Maven/Gradle build compiles", "Application starts on port 8080", "Health endpoint returns 200"),
      "Initialize the Spring Boot 3.x project with Java 21, configure build tool, and add Actuator health endpoint.",
    ),
    (
      "Implement REST controller for orders",
      List("rest", "api"),
      List("java", "spring-boot", "rest"),
      List("GET /api/orders returns list", "POST /api/orders creates order", "Validation errors return 400"),
      "Create OrderController with CRUD endpoints, request validation, and proper HTTP status codes.",
    ),
    (
      "Add JPA entity and repository for Order",
      List("database", "jpa"),
      List("java", "spring-data"),
      List("Order entity maps to orders table", "Repository supports findByStatus", "Flyway migration creates schema"),
      "Define Order JPA entity with audit fields, OrderRepository interface, and initial Flyway migration script.",
    ),
    (
      "Configure Spring Security with JWT authentication",
      List("security", "auth"),
      List("java", "spring-security"),
      List("Unauthenticated requests return 401", "Valid JWT grants access", "Tokens expire after configured TTL"),
      "Set up Spring Security filter chain with JWT validation, configure public and protected endpoints.",
    ),
    (
      "Write unit tests for OrderService",
      List("testing", "unit-test"),
      List("java", "testing"),
      List("Service methods have test coverage", "Edge cases tested", "Mocks used for repository layer"),
      "Create JUnit 5 tests for OrderService using Mockito for repository dependencies.",
    ),
    (
      "Add pagination and sorting to list endpoints",
      List("rest", "api"),
      List("java", "spring-boot"),
      List(
        "GET /api/orders supports page and size params",
        "Sort by createdAt default",
        "Response includes total count",
      ),
      "Implement Spring Data Pageable support in OrderController with proper response envelope.",
    ),
    (
      "Set up Docker container and Compose file",
      List("devops", "docker"),
      List("docker", "devops"),
      List(
        "Dockerfile builds multi-stage image",
        "docker-compose starts app with PostgreSQL",
        "Container health check passes",
      ),
      "Create optimized multi-stage Dockerfile and docker-compose.yml with PostgreSQL service.",
    ),
    (
      "Implement global exception handler",
      List("rest", "error-handling"),
      List("java", "spring-boot"),
      List("Validation errors return structured response", "404 returns proper body", "500 errors are logged"),
      "Create @ControllerAdvice with handlers for common exceptions, returning consistent error response format.",
    ),
    (
      "Add integration tests with TestContainers",
      List("testing", "integration"),
      List("java", "testing", "docker"),
      List("Tests run against real PostgreSQL", "Database is reset between tests", "CI pipeline can run tests"),
      "Set up TestContainers with PostgreSQL for integration testing, create base test class with shared container.",
    ),
    (
      "Configure structured logging with correlation IDs",
      List("observability", "logging"),
      List("java", "observability"),
      List("Logs include correlationId header", "JSON log format in production", "Request/response logged at DEBUG"),
      "Set up Logback with JSON encoder, MDC-based correlation ID propagation via servlet filter.",
    ),
    (
      "Create OpenAPI specification",
      List("documentation", "api"),
      List("java", "documentation"),
      List("Swagger UI accessible at /swagger-ui", "All endpoints documented", "Schema examples included"),
      "Add springdoc-openapi dependency, annotate controllers with OpenAPI annotations.",
    ),
    (
      "Implement rate limiting middleware",
      List("security", "performance"),
      List("java", "spring-boot"),
      List("Rate limit headers in response", "429 returned when exceeded", "Configurable per-endpoint limits"),
      "Add Bucket4j-based rate limiting filter with configurable limits per endpoint.",
    ),
    (
      "Set up GitHub Actions CI pipeline",
      List("devops", "ci"),
      List("devops", "ci"),
      List("Build runs on push to main", "Tests execute in CI", "Docker image pushed on release"),
      "Create GitHub Actions workflow for build, test, and Docker image publishing.",
    ),
    (
      "Add Actuator metrics and custom health indicators",
      List("observability", "monitoring"),
      List("java", "observability"),
      List("Custom health check for database", "Business metrics exposed", "Prometheus endpoint available"),
      "Configure Actuator with Prometheus exporter, add custom HealthIndicator and MeterBinder implementations.",
    ),
    (
      "Implement order status workflow",
      List("business-logic", "workflow"),
      List("java", "spring-boot"),
      List("Status transitions are validated", "Invalid transitions return 422", "Status history is tracked"),
      "Create OrderStatusMachine with allowed transitions, persist status change events.",
    ),
  )
