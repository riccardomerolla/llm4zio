package prompts

/** Unified access point for all prompt templates
  *
  * This object provides a single entry point to access all prompt template generators for the COBOL to Java migration
  * agents. Each agent has its own specialized prompt module with versioned templates.
  *
  * Architecture:
  *   - CobolAnalyzerPrompts: Parse COBOL structure, extract variables, procedures, complexity
  *   - DependencyMapperPrompts: Build dependency graphs from analyses
  *   - JavaTransformerPrompts: Generate Java entities, services, and controllers
  *   - ValidationPrompts: Create tests and validate transformations
  *   - DocumentationPrompts: Generate migration documentation
  *
  * All prompts follow these principles:
  *   - Version tracking for iteration and debugging
  *   - String interpolation for dynamic content
  *   - System prompts for consistent LLM behavior
  *   - JSON schema specifications from OutputSchemas
  *   - Few-shot examples for guidance
  *   - Strict validation requirements
  *
  * Usage example:
  * {{{
  *   val prompt = PromptTemplates.CobolAnalyzer.analyzeStructure(cobolFile, cobolCode)
  *   val response = geminiService.execute(prompt)
  * }}}
  *
  * See individual prompt objects for detailed documentation on each template.
  */
object PromptTemplates:

  /** CobolAnalyzer agent prompts (version: 1.0.0)
    *
    * Generates prompts for deep structural analysis of COBOL programs:
    *   - analyzeStructure: Complete program analysis (auto-chunks large files)
    *
    * Features:
    *   - Smart chunking by COBOL divisions for large files
    *   - Comprehensive few-shot examples
    *   - Parses all four divisions (IDENTIFICATION, ENVIRONMENT, DATA, PROCEDURE)
    *   - Extracts variables, procedures, copybook dependencies
    *   - Calculates cyclomatic complexity metrics
    *
    * Output: CobolAnalysis JSON
    */
  object CobolAnalyzer:
    export CobolAnalyzerPrompts.{ analyzeStructure, version }

  /** DependencyMapper agent prompts (version: 1.0.0)
    *
    * Generates prompts for building dependency graphs:
    *   - extractDependencies: Analyze program relationships and identify service candidates
    *
    * Features:
    *   - Processes multiple COBOL analyses
    *   - Identifies COPY statement dependencies (Include edges)
    *   - Extracts CALL statement dependencies (Call edges)
    *   - Detects shared copybooks (3+ usage = service candidate)
    *   - Builds complete node-edge graph structure
    *
    * Output: DependencyGraph JSON
    */
  object DependencyMapper:
    export DependencyMapperPrompts.{ extractDependencies, version }

  /** JavaTransformer agent prompts (version: 1.0.0)
    *
    * Generates prompts for COBOL to Java transformation:
    *   - generateEntity: COBOL data structures -> JPA entities
    *   - generateService: COBOL procedures -> Spring service methods
    *   - generateController: COBOL entry points -> REST controllers
    *
    * Features:
    *   - Modern Java 17+ patterns (records, switch expressions)
    *   - Spring Boot 3.x annotations
    *   - Preserves COBOL business logic exactly
    *   - Applies Java naming conventions (camelCase)
    *   - Proper error handling with try-catch
    *   - Spring Data JPA for persistence
    *
    * Output: JavaEntity, JavaService, JavaController JSON
    */
  object JavaTransformer:
    export JavaTransformerPrompts.{ generateEntity, generateService, generateController, fixCompilationErrors, version }

  /** Validation agent prompts (version: 1.0.0)
    *
    * Generates prompts for test generation and validation:
    *   - generateUnitTests: Create JUnit 5 tests for services
    *   - validateTransformation: Verify Java matches COBOL business logic
    *
    * Features:
    *   - JUnit 5 with modern patterns (@Test, assertions)
    *   - Covers all business logic paths from COBOL
    *   - Meaningful test names (should_condition pattern)
    *   - Edge cases and boundary conditions
    *   - Mocking with @Mock and @InjectMocks
    *   - Business logic equivalence validation
    *
    * Output: ValidationReport JSON
    */
  object Validation:
    export ValidationPrompts.{ generateUnitTests, validateTransformation, version }

  /** Documentation agent prompts (version: 1.0.0)
    *
    * Generates prompts for migration documentation:
    *   - generateTechnicalDesign: Architecture overview, data mappings, API design
    *   - generateMigrationSummary: Summary report with metrics and next steps
    *
    * Features:
    *   - Markdown-formatted documentation
    *   - Mermaid diagrams for architecture visualization
    *   - COBOL to Java mapping tables
    *   - Deployment instructions and prerequisites
    *   - Success criteria and quality metrics
    *
    * Output: MigrationDocumentation JSON with Markdown content
    */
  object Documentation:
    export DocumentationPrompts.{ generateTechnicalDesign, generateMigrationSummary, version }

  /** Output JSON schemas for all response types
    *
    * Maps case class names to their JSON schema definitions:
    *   - CobolAnalysis: Complete COBOL program structure
    *   - DependencyGraph: Program and copybook relationships
    *   - JavaEntity: JPA entity class
    *   - JavaService: Spring service class
    *   - JavaController: REST controller
    *   - ValidationReport: Test results and quality metrics
    *   - MigrationDocumentation: Complete migration docs
    *
    * Access via:
    *   - OutputSchemas.cobolAnalysis (direct access)
    *   - OutputSchemas.getSchema("CobolAnalysis") (lookup by name)
    */
  object Schemas:
    export OutputSchemas.{
      cobolAnalysis,
      dependencyGraph,
      javaEntity,
      javaService,
      javaController,
      validationReport,
      migrationDocumentation,
      schemaMap,
      getSchema,
    }

  /** Shared prompt utilities
    *
    * Helper functions for prompt construction:
    *   - formatCobolCode: Wrap COBOL code in markdown blocks
    *   - schemaReference: Generate schema reference instructions
    *   - chunkByDivision: Split large COBOL files by division
    *   - validationRules: Generate strict validation requirements
    *   - fewShotExample: Format few-shot learning examples
    *   - estimateTokens: Estimate token count for chunking decisions
    */
  object Helpers:
    export PromptHelpers.{
      formatCobolCode,
      schemaReference,
      chunkByDivision,
      validationRules,
      fewShotExample,
      estimateTokens,
      shouldChunk,
    }

  /** Template version tracking
    *
    * All templates are versioned for iteration and debugging. Returns a map of agent name to version.
    */
  def versions: Map[String, String] = Map(
    "CobolAnalyzer"    -> CobolAnalyzerPrompts.version,
    "DependencyMapper" -> DependencyMapperPrompts.version,
    "JavaTransformer"  -> JavaTransformerPrompts.version,
    "Validation"       -> ValidationPrompts.version,
    "Documentation"    -> DocumentationPrompts.version,
  )

  /** Get all current template versions as formatted string
    *
    * Useful for logging and debugging to track which prompt versions were used for a migration run.
    */
  def versionSummary: String =
    versions
      .map { case (name, version) => s"  $name: $version" }
      .mkString("Prompt Template Versions:\n", "\n", "")
