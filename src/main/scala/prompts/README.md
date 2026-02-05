# Prompt Templates for COBOL Migration Agents

This package contains all prompt templates used by the COBOL to Java migration agents. Each agent has specialized prompts designed to guide Gemini CLI in generating accurate, structured JSON responses.

## Overview

The prompt template system provides:

- **Version tracking** for iteration and debugging
- **String interpolation** for dynamic content
- **System prompts** for consistent LLM behavior
- **JSON schema specifications** from OutputSchemas
- **Few-shot examples** for guidance
- **Strict validation requirements**

## Architecture

```
prompts/
├── PromptTemplates.scala         # Unified entry point for all prompts
├── OutputSchemas.scala            # JSON schema definitions
├── PromptHelpers.scala            # Shared utilities
├── CobolAnalyzerPrompts.scala     # COBOL structure analysis
├── DependencyMapperPrompts.scala  # Dependency graph extraction
├── JavaTransformerPrompts.scala   # COBOL to Java transformation
├── ValidationPrompts.scala        # Test generation and validation
└── DocumentationPrompts.scala     # Migration documentation
```

## Usage

### Quick Start

```scala
import prompts.PromptTemplates

// Analyze COBOL structure
val analysisPrompt = PromptTemplates.CobolAnalyzer.analyzeStructure(cobolFile, cobolCode)
val response = geminiService.execute(analysisPrompt)
val analysis = response.fromJson[CobolAnalysis]

// Extract dependencies
val depPrompt = PromptTemplates.DependencyMapper.extractDependencies(analyses)
val graph = geminiService.execute(depPrompt).fromJson[DependencyGraph]

// Generate Java entity
val entityPrompt = PromptTemplates.JavaTransformer.generateEntity(analysis)
val entity = geminiService.execute(entityPrompt).fromJson[JavaEntity]

// Generate unit tests
val testPrompt = PromptTemplates.Validation.generateUnitTests(serviceName, methods, cobolCode)
val tests = geminiService.execute(testPrompt).fromJson[TestResults]
```

### Check Template Versions

```scala
// Get all versions
val versions = PromptTemplates.versions
// Map("CobolAnalyzer" -> "1.0.0", "DependencyMapper" -> "1.0.0", ...)

// Get formatted summary
println(PromptTemplates.versionSummary)
// Prompt Template Versions:
//   CobolAnalyzer: 1.0.0
//   DependencyMapper: 1.0.0
//   ...
```

## Agent Prompts

### 1. CobolAnalyzerPrompts

**Purpose:** Parse COBOL structure, extract variables, procedures, complexity metrics

**Methods:**
- `analyzeStructure(cobolFile, cobolCode)` - Complete program analysis with auto-chunking

**Features:**
- Smart chunking by COBOL divisions for large files (>10K chars)
- Parses all four divisions (IDENTIFICATION, ENVIRONMENT, DATA, PROCEDURE)
- Extracts variables with PIC clauses and data types
- Identifies procedures, paragraphs, and statements
- Detects COPY statements and copybook dependencies
- Calculates cyclomatic complexity

**Output:** `CobolAnalysis` JSON

**Example:**
```scala
val prompt = CobolAnalyzerPrompts.analyzeStructure(
  cobolFile = CobolFile(...),
  cobolCode = """
    IDENTIFICATION DIVISION.
    PROGRAM-ID. CUSTPROG.
    ...
  """
)
```

### 2. DependencyMapperPrompts

**Purpose:** Build dependency graphs from COBOL analyses

**Methods:**
- `extractDependencies(analyses)` - Analyze program relationships and service candidates

**Features:**
- Processes multiple COBOL analyses
- Identifies COPY statement dependencies (Include edges)
- Extracts CALL statement dependencies (Call edges)
- Detects shared copybooks (3+ usage = service candidate)
- Builds complete node-edge graph structure

**Output:** `DependencyGraph` JSON

**Example:**
```scala
val prompt = DependencyMapperPrompts.extractDependencies(
  analyses = List(analysis1, analysis2, analysis3)
)
```

### 3. JavaTransformerPrompts

**Purpose:** Transform COBOL programs to Spring Boot microservices

**Methods:**
- `generateEntity(analysis)` - COBOL data structures → JPA entities
- `generateService(analysis, dependencies)` - COBOL procedures → Spring service methods
- `generateController(analysis, serviceName)` - COBOL entry points → REST controllers

**Features:**
- Modern Java 17+ patterns (records, switch expressions)
- Spring Boot 3.x annotations (@Entity, @Service, @RestController)
- Preserves COBOL business logic exactly
- Applies Java naming conventions (camelCase)
- Proper error handling with try-catch
- Spring Data JPA for persistence

**Output:** `JavaEntity`, `JavaService`, `JavaController` JSON

**Example:**
```scala
// Generate entity
val entityPrompt = JavaTransformerPrompts.generateEntity(analysis)

// Generate service with dependencies
val servicePrompt = JavaTransformerPrompts.generateService(
  analysis = analysis,
  dependencies = List("ValidationService", "AuditService")
)

// Generate REST controller
val controllerPrompt = JavaTransformerPrompts.generateController(
  analysis = analysis,
  serviceName = "CustomerService"
)
```

### 4. ValidationPrompts

**Purpose:** Generate tests and validate transformations

**Methods:**
- `generateUnitTests(serviceName, methods, originalCobol)` - Create JUnit 5 tests
- `validateTransformation(cobolCode, javaCode, analysis)` - Verify correctness

**Features:**
- JUnit 5 with modern patterns (@Test, assertions)
- Covers all business logic paths from COBOL
- Meaningful test names (should_condition pattern)
- Edge cases and boundary conditions
- Mocking with @Mock and @InjectMocks
- Business logic equivalence validation

**Output:** `ValidationReport` JSON

**Example:**
```scala
// Generate unit tests
val testPrompt = ValidationPrompts.generateUnitTests(
  serviceName = "CustomerService",
  methods = List(creditCheckMethod, processOrderMethod),
  originalCobol = cobolCode
)

// Validate transformation
val validationPrompt = ValidationPrompts.validateTransformation(
  cobolCode = originalCobol,
  javaCode = generatedJava,
  analysis = analysis
)
```

### 5. DocumentationPrompts

**Purpose:** Generate comprehensive migration documentation

**Methods:**
- `generateTechnicalDesign(analyses, graph, projects)` - Architecture docs
- `generateMigrationSummary(startTime, endTime, analyses, reports)` - Summary report

**Features:**
- Markdown-formatted documentation
- Mermaid diagrams for architecture visualization
- COBOL to Java mapping tables
- Deployment instructions and prerequisites
- Success criteria and quality metrics

**Output:** `MigrationDocumentation` JSON with Markdown content

**Example:**
```scala
// Technical design document
val designPrompt = DocumentationPrompts.generateTechnicalDesign(
  analyses = allAnalyses,
  dependencyGraph = graph,
  projects = springBootProjects
)

// Migration summary
val summaryPrompt = DocumentationPrompts.generateMigrationSummary(
  startTime = migrationStartTime,
  endTime = migrationEndTime,
  analyses = allAnalyses,
  validationReports = testReports
)
```

## Output Schemas

All JSON schemas are defined in `OutputSchemas.scala`. They correspond to case classes in the `models` package.

### Available Schemas

| Schema Name | Purpose | Key Fields |
|-------------|---------|------------|
| `CobolAnalysis` | COBOL program structure | file, divisions, variables, procedures, copybooks, complexity |
| `DependencyGraph` | Program relationships | nodes, edges, serviceCandidates |
| `JavaEntity` | JPA entity class | name, fields, annotations |
| `JavaService` | Spring service class | name, methods |
| `JavaController` | REST controller | name, basePath, endpoints |
| `ValidationReport` | Test results | testResults, coverageMetrics, businessLogicValidation |
| `MigrationDocumentation` | Complete docs | technicalDesign, apiReference, migrationSummary |

### Access Schemas

```scala
import prompts.OutputSchemas

// Direct access
val schema = OutputSchemas.cobolAnalysis

// Lookup by name
val schema = OutputSchemas.getSchema("CobolAnalysis")

// Use in prompts (via PromptHelpers)
val schemaRef = PromptHelpers.schemaReference("CobolAnalysis")
```

## Helper Utilities

`PromptHelpers.scala` provides shared utilities:

```scala
import prompts.PromptHelpers

// Format COBOL code in markdown
val formatted = PromptHelpers.formatCobolCode(cobolCode)

// Generate schema reference
val schemaRef = PromptHelpers.schemaReference("CobolAnalysis")

// Chunk large COBOL files by division
val chunks = PromptHelpers.chunkByDivision(cobolCode)
// Map("IDENTIFICATION" -> "...", "DATA" -> "...", "PROCEDURE" -> "...")

// Generate validation rules
val rules = PromptHelpers.validationRules(List("field1", "field2"))

// Create few-shot examples
val example = PromptHelpers.fewShotExample(
  description = "Simple COBOL program",
  input = cobolCode,
  output = expectedJson
)

// Estimate tokens
val tokens = PromptHelpers.estimateTokens(text)

// Check if chunking is needed
if PromptHelpers.shouldChunk(cobolCode) then
  // Use chunked approach
```

## Design Principles

### 1. Type Safety

All prompts use strong typing for inputs and outputs:
- Input parameters use domain models (`CobolFile`, `CobolAnalysis`, etc.)
- Output schemas correspond to case classes with ZIO JSON codecs
- No string-based magic or untyped maps

### 2. Composability

Prompts are composable and reusable:
- Common utilities in `PromptHelpers`
- Schema definitions in `OutputSchemas`
- Each agent has its own module
- Unified access via `PromptTemplates`

### 3. Version Tracking

All templates have explicit versions:
- `version: String = "1.0.0"` in each prompt object
- Accessible via `PromptTemplates.versions`
- Allows debugging and iteration

### 4. Few-Shot Learning

All prompts include examples:
- Concrete COBOL input examples
- Expected JSON output examples
- Edge cases and complex scenarios
- Guides LLM behavior

### 5. Strict Validation

Prompts specify strict requirements:
- Required fields explicitly listed
- Data type expectations clear
- Format specifications (JSON only, no markdown)
- Error handling instructions

## Testing

Comprehensive test suite in `src/test/scala/prompts/PromptTemplatesSpec.scala`:

```bash
# Run all prompt tests
sbt "testOnly prompts.PromptTemplatesSpec"

# Run all tests
sbt test
```

Tests verify:
- ✅ Prompts generate valid output strings
- ✅ Dynamic content is properly interpolated
- ✅ Schema references are included
- ✅ Version tracking works
- ✅ Chunking logic works for large files
- ✅ Helper utilities function correctly

## Extending the System

### Adding a New Prompt

1. Create new prompt object in `prompts/YourAgentPrompts.scala`:

```scala
object YourAgentPrompts:
  val version: String = "1.0.0"

  private val systemPrompt = """
    You are an expert in...
    CRITICAL REQUIREMENTS:
    - Always respond with valid JSON only
    ...
  """.stripMargin

  def yourMethod(params: Type): String =
    s"""$systemPrompt
       |
       |Your task...
       |${PromptHelpers.schemaReference("YourOutputType")}
       |
       |Extract:
       |1. ...
       |""".stripMargin
```

2. Add schema to `OutputSchemas.scala`:

```scala
val yourSchema: String = """
  {
    "field1": "type",
    ...
  }
""".stripMargin
```

3. Export from `PromptTemplates.scala`:

```scala
object YourAgent:
  export YourAgentPrompts.{ yourMethod, version }
```

4. Add tests in `PromptTemplatesSpec.scala`

### Updating Versions

When modifying a prompt:

1. Increment version number:
```scala
val version: String = "1.1.0"  // was 1.0.0
```

2. Document changes in version control commit

3. Test thoroughly with Gemini CLI

4. Update examples if schema changed

## Best Practices

### DO:
- ✅ Use `PromptHelpers` for common operations
- ✅ Include few-shot examples for complex tasks
- ✅ Specify strict validation requirements
- ✅ Version your templates
- ✅ Test prompts with actual Gemini CLI
- ✅ Keep system prompts concise and clear

### DON'T:
- ❌ Include markdown in JSON output instructions
- ❌ Use vague or ambiguous instructions
- ❌ Skip validation requirements
- ❌ Hard-code schemas inline (use OutputSchemas)
- ❌ Forget to update tests when changing prompts
- ❌ Mix multiple concerns in one prompt

## Troubleshooting

### Gemini returns invalid JSON

**Solution:** Check that your prompt includes:
- Clear "valid JSON only" instruction
- Schema reference via `PromptHelpers.schemaReference()`
- Validation rules via `PromptHelpers.validationRules()`
- Few-shot examples with correct JSON

### Prompts too long / Token limit exceeded

**Solution:**
- Use `PromptHelpers.shouldChunk()` to detect large inputs
- Implement chunking strategy (see `CobolAnalyzerPrompts`)
- Reduce few-shot examples for large inputs
- Split complex tasks into multiple prompts

### Output doesn't match schema

**Solution:**
- Verify schema in `OutputSchemas` matches case class
- Update ZIO JSON codec if case class changed
- Add more explicit field descriptions in schema
- Include example output in few-shot learning

### Inconsistent results across runs

**Solution:**
- Make system prompt more specific
- Add more few-shot examples
- Increase validation requirements
- Consider prompt temperature settings in Gemini CLI

## Version History

- **1.0.0** (2026-02-05) - Initial implementation
  - CobolAnalyzerPrompts with chunking support
  - DependencyMapperPrompts with service candidate detection
  - JavaTransformerPrompts for entity/service/controller generation
  - ValidationPrompts for testing and validation
  - DocumentationPrompts for migration docs
  - OutputSchemas for all response types
  - PromptHelpers with common utilities
  - Comprehensive test suite (23 tests)

## References

- [Gemini CLI Documentation](https://ai.google.dev/gemini-api/docs)
- [ZIO JSON](https://zio.dev/zio-json/)
- [Spring Boot 3.x](https://spring.io/projects/spring-boot)
- [COBOL Language Reference](https://www.ibm.com/docs/en/cobol-zos)
