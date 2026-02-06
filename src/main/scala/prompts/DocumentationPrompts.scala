package prompts

import java.time.{ Duration, Instant }

import models.{ CobolAnalysis, DependencyGraph, SpringBootProject, ValidationReport }

/** Prompt templates for migration documentation generation
  *
  * Generates prompts for the DocumentationAgent to create comprehensive migration documentation.
  *
  * Responsibilities:
  *   - Create technical design documents
  *   - Generate API documentation
  *   - Document data model mappings
  *   - Produce migration summary reports
  *   - Create deployment guides
  *
  * Features:
  *   - Markdown-formatted documentation
  *   - Mermaid diagrams for architecture visualization
  *   - COBOL to Java mapping tables
  *   - Deployment instructions and prerequisites
  */
object DocumentationPrompts:

  val version: String = "1.0.0"

  private val systemPrompt =
    """You are an expert technical writer specializing in migration documentation.
      |Your role is to generate clear, comprehensive documentation for COBOL to Java migrations.
      |
      |CRITICAL REQUIREMENTS:
      |- Always respond with valid JSON only, no markdown inside JSON strings
      |- Generate Markdown-formatted documentation (stored in JSON string fields)
      |- Include architecture diagrams in Mermaid format
      |- Document all mappings between COBOL and Java
      |- Provide deployment instructions and prerequisites
      |
      |TEMPLATE_VERSION: 1.0.0
      |""".stripMargin

  /** Generate technical design document
    *
    * @param analyses
    *   List of COBOL analyses
    * @param dependencyGraph
    *   Dependency graph showing program relationships
    * @param projects
    *   List of generated Spring Boot projects
    * @return
    *   Formatted prompt for Gemini CLI
    */
  def generateTechnicalDesign(
    analyses: List[CobolAnalysis],
    dependencyGraph: DependencyGraph,
    projects: List[SpringBootProject],
  ): String =
    val programNames = analyses.map(_.file.name).mkString(", ")
    val serviceCount = projects.size

    s"""$systemPrompt
       |
       |Generate a comprehensive technical design document for this migration.
       |
       |MIGRATION SCOPE:
       |Programs migrated: ${analyses.size} ($programNames)
       |Spring Boot services: $serviceCount
       |Service candidates: ${dependencyGraph.serviceCandidates.mkString(", ")}
       |
       |${PromptHelpers.schemaReference("MigrationDocumentation")}
       |
       |$documentationExamples
       |
       |Technical design should include:
       |1. Executive Summary
       |   - Migration scope and objectives
       |   - Technology stack (COBOL -> Spring Boot 3.x, Java 17)
       |2. Architecture Overview
       |   - Mermaid diagram showing microservices
       |   - Service boundaries and responsibilities
       |   - Shared components (from copybooks)
       |3. Data Model Mappings
       |   - COBOL data structures -> JPA entities
       |   - Table showing field mappings with types
       |4. API Design
       |   - REST endpoints for each service
       |   - Request/response schemas
       |5. Deployment Architecture
       |   - Spring Boot applications
       |   - Database requirements
       |   - Infrastructure needs
       |
       |Output format: Markdown stored in JSON field "technicalDesign"
       |
       |Respond with JSON only.
       |""".stripMargin

  /** Generate migration summary report
    *
    * @param startTime
    *   Migration start timestamp
    * @param endTime
    *   Migration end timestamp
    * @param analyses
    *   List of COBOL analyses performed
    * @param validationReports
    *   List of validation results
    * @return
    *   Formatted prompt for Gemini CLI
    */
  def generateMigrationSummary(
    startTime: Instant,
    endTime: Instant,
    analyses: List[CobolAnalysis],
    validationReports: List[ValidationReport],
  ): String =
    val duration            = Duration.between(startTime, endTime)
    val compileSuccessCount = validationReports.count(_.compileResult.success)
    val failedCount         = validationReports.count(_.overallStatus == models.ValidationStatus.Failed)

    s"""$systemPrompt
       |
       |Generate a migration summary report.
       |
       |MIGRATION METRICS:
       |Duration: ${duration.toMinutes} minutes
       |Programs migrated: ${analyses.size}
       |Compile successes: $compileSuccessCount
       |Validation failures: $failedCount
       |
       |$documentationExamples
       |
       |Summary report should include:
       |1. Migration Overview
       |   - Start/end times
       |   - Total duration
       |   - Programs processed
       |2. Quality Metrics
       |   - Test coverage
       |   - Validation results
       |   - Complexity metrics
       |3. Success Criteria
       |   - All programs migrated: Yes/No
       |   - All tests passing: Yes/No
       |   - Business logic validated: Yes/No
       |4. Next Steps
       |   - Deployment recommendations
       |   - Manual review items
       |   - Performance testing needs
       |
       |Output format: Markdown stored in JSON field "migrationSummary"
       |
       |Respond with JSON only.
       |""".stripMargin

  private val documentationExamples =
    s"""
       |${PromptHelpers.fewShotExample(
        "Technical design structure",
        "3 COBOL programs -> 3 Spring Boot services",
        """# Technical Design Document
         |
         |## Executive Summary
         |This migration converts 3 COBOL mainframe programs to Spring Boot microservices...
         |
         |## Architecture
         |```mermaid
         |graph TD
         |  A[CustomerService] --> D[SharedDataService]
         |  B[OrderService] --> D
         |  C[ValidationService]
         |```
         |
         |## Data Mappings
         || COBOL Field | Java Field | Type Mapping |
         ||-------------|-----------|--------------|
         || WS-CUSTOMER-ID | customerId | PIC 9(5) -> Integer |
         |...""",
      )}
       |
       |${PromptHelpers.fewShotExample(
        "Migration summary report",
        """Duration: 45 minutes
         |Programs: 10
         |Tests: 150 (148 passed)""",
        """# Migration Summary Report
         |
         |## Overview
         |- Start: 2026-02-05 10:00:00
         |- End: 2026-02-05 10:45:00
         |- Duration: 45 minutes
         |- Programs: 10
         |
         |## Quality Metrics
         |- Tests Generated: 150
         |- Tests Passed: 148 (98.7%)
         |- Test Coverage: 87%
         |
         |## Success Criteria
         |✅ All programs migrated
         |✅ Tests passing (98.7%)
         |✅ Business logic validated
         |
         |## Next Steps
         |1. Deploy to staging environment
         |2. Manual review of complex business logic
         |3. Performance testing""",
      )}
       |
       |${PromptHelpers.fewShotExample(
        "API documentation section",
        "CustomerService with 3 endpoints",
        """## API Reference
         |
         |### CustomerController
         |
         |Base path: `/api/customer`
         |
         |#### POST /process
         |Process customer data
         |
         |**Request:**
         |```json
         |{
         |  "customerId": 12345,
         |  "balance": 5000.00
         |}
         |```
         |
         |**Response:**
         |```json
         |{
         |  "status": "APPROVED",
         |  "message": "Customer processed successfully"
         |}
         |```""",
      )}
       |""".stripMargin
