# Prompt Templates Design Document

**Date:** 2026-02-05
**Issue:** #8 - [P2-03] Implement prompt templates for COBOL analysis
**Version:** 1.0.0
**Status:** Approved for Implementation

---

## Executive Summary

This design document specifies the prompt template library for COBOL to Java migration agents. The templates provide structured prompts for Gemini CLI to perform code analysis, dependency mapping, Java transformation, validation, and documentation generation.

### Key Design Decisions

1. **One template object per agent** - Separate objects for each specialized agent
2. **Agent-specific system prompts** - Each agent has tailored instructions
3. **Reference existing case classes** - Single source of truth for data structures
4. **Comprehensive few-shot examples** - Multiple examples covering edge cases
5. **Strict validation instructions** - Explicit required fields and format rules
6. **Smart division-based chunking** - Handle large COBOL files efficiently
7. **Version tracking** - Each template includes version marker

---

## Architecture

### File Structure

```
src/main/scala/prompts/
├── PromptHelpers.scala              # Shared utilities
├── CobolAnalyzerPrompts.scala       # Structure analysis prompts
├── DependencyMapperPrompts.scala    # Dependency graph extraction
├── JavaTransformerPrompts.scala     # Java code generation
├── ValidationPrompts.scala          # Test generation & validation
└── DocumentationPrompts.scala       # Documentation generation
```

### Component Overview

| Component | Responsibility | Methods |
|-----------|---------------|---------|
| PromptHelpers | Shared utilities, COBOL parsing, formatting | formatCobolCode, chunkByDivision, schemaReference, validationRules, fewShotExample |
| CobolAnalyzerPrompts | Deep COBOL structure analysis | analyzeStructure, analyzeComplete, analyzeChunked |
| DependencyMapperPrompts | Dependency graph construction | extractDependencies |
| JavaTransformerPrompts | COBOL to Java transformation | generateEntity, generateService, generateController |
| ValidationPrompts | Test generation and validation | generateUnitTests, validateTransformation |
| DocumentationPrompts | Migration documentation | generateTechnicalDesign, generateMigrationSummary |

---

## Design Patterns

### 1. Template Structure Pattern

All prompt template objects follow this pattern:

```scala
object SomeAgentPrompts:
  val version = "1.0.0"

  private val systemPrompt = """
    |You are a specialized [agent role]...
    |TEMPLATE_VERSION: 1.0.0
    |...
  """.stripMargin

  def someTask(input: Data): String =
    s"""$systemPrompt
       |
       |[Task description]
       |
       |${PromptHelpers.schemaReference("OutputType")}
       |${PromptHelpers.validationRules(requiredFields)}
       |${fewShotExamples}
       |
       |[Detailed instructions]
       |""".stripMargin
```

### 2. Chunking Strategy

For large COBOL files (>8000 tokens estimated):

```scala
def analyzeStructure(cobolFile: CobolFile, cobolCode: String): String =
  val chunks = PromptHelpers.chunkByDivision(cobolCode)

  if chunks.size == 1 || cobolCode.length < 10000 then
    analyzeComplete(cobolFile, cobolCode)  // Single prompt
  else
    analyzeChunked(cobolFile, chunks)      // Division-based chunking
```

### 3. Schema Reference Pattern

Instead of inline JSON schemas, reference existing Scala case classes:

```scala
${PromptHelpers.schemaReference("CobolAnalysis")}
// Generates:
// "Your response must be valid JSON matching the CobolAnalysis
//  case class from our Scala codebase..."
```

This ensures:
- Single source of truth (case classes in Models.scala)
- No schema drift between prompts and code
- Easier maintenance

### 4. Few-Shot Learning Pattern

Each template includes 3 comprehensive examples:

1. **Simple case** - Basic scenario
2. **Intermediate case** - Common patterns
3. **Complex case** - Edge cases and advanced features

Examples use `PromptHelpers.fewShotExample()` for consistent formatting.

---

## Component Details

### PromptHelpers.scala

**Purpose:** Shared utilities for all prompt templates

**Key Functions:**

- `formatCobolCode(code: String)`: Wraps COBOL in markdown code blocks
- `schemaReference(className: String)`: Generates schema reference text
- `chunkByDivision(cobolCode: String)`: Splits COBOL by divisions
- `extractDivision(code: String, division: String)`: Extracts specific division
- `validationRules(fields: List[String])`: Generates strict field requirements
- `fewShotExample(description, input, output)`: Formats examples
- `estimateTokens(text: String)`: Rough token count for chunking
- `shouldChunk(cobolCode: String)`: Determines if chunking needed

**Division Extraction Algorithm:**
1. Find line with "{DIVISION_NAME} DIVISION."
2. Extract until next "DIVISION." or EOF
3. Filter out comment lines (starting with *)
4. Return division content

### CobolAnalyzerPrompts.scala

**Purpose:** Generate prompts for COBOL structure analysis

**System Prompt Focus:**
- Expert COBOL parser
- Extract ALL variables, procedures, copybooks
- Calculate accurate complexity metrics
- Parse PIC clauses correctly

**Methods:**

1. `analyzeStructure(cobolFile, cobolCode)` - Entry point, handles chunking decision
2. `analyzeComplete(cobolFile, cobolCode)` - Single-shot analysis for small files
3. `analyzeChunked(cobolFile, chunks)` - Division-by-division for large files

**Examples Cover:**
- Simple program with file I/O (3 variables, 2 procedures)
- Program with copybooks and file operations (COPY statements, FD)
- Complex program with EVALUATE and nested IFs (high cyclomatic complexity)

**Output:** CobolAnalysis JSON matching case class

### DependencyMapperPrompts.scala

**Purpose:** Generate prompts for dependency graph extraction

**System Prompt Focus:**
- Build accurate dependency graphs
- Identify ALL COPY and CALL relationships
- Find shared copybooks (3+ usage = service candidate)
- Create unique node IDs

**Methods:**

1. `extractDependencies(analyses: List[CobolAnalysis])` - Build graph from analyses
2. `formatAnalysisSummary(analysis)` - Helper to format analysis data
3. `extractCallStatements(analysis)` - Extract CALL targets from procedures

**Graph Construction Rules:**
- **Nodes**: One per program (Program type) and copybook (Copybook type)
- **Edges**: COPY → Includes edge, CALL → Calls edge
- **Service Candidates**: Copybooks used by 3+ programs

**Examples Cover:**
- Simple graph with shared copybook (2 programs, 1 shared)
- Complex multi-level calls (4 programs with nested calls)
- Isolated programs (no shared dependencies)

**Output:** DependencyGraph JSON

### JavaTransformerPrompts.scala

**Purpose:** Generate prompts for COBOL to Java transformation

**System Prompt Focus:**
- Modern Java 17+ patterns
- Spring Boot 3.x annotations
- Preserve COBOL business logic exactly
- Use Java naming conventions (camelCase)
- Proper error handling

**Methods:**

1. `generateEntity(analysis)` - COBOL data → JPA entity
2. `generateService(analysis, dependencies)` - COBOL procedures → Spring service
3. `generateController(analysis, serviceName)` - Entry points → REST controller

**Transformation Rules:**

**Entities:**
- PIC 9(n) → Integer/Long
- PIC 9(n)V9(m) → BigDecimal
- PIC X(n) → String
- COBOL-NAMES → camelCase
- @Entity, @Table, @Id, @Column annotations

**Services:**
- Program name + "Service"
- MOVE → assignment
- IF/ELSE → if-else
- PERFORM → method call
- EVALUATE → switch expression
- DISPLAY → logger.info()
- READ/WRITE → repository calls

**Controllers:**
- Program name + "Controller"
- @RestController, @RequestMapping
- POST for main procedure
- GET for read operations
- Base path: /api/{program-name}

**Examples Cover:**
- Simple COBOL record → JPA entity
- COBOL group item → nested entity
- COBOL paragraph → service method
- EVALUATE → switch expression
- Program → REST controller

**Output:** JavaEntity, JavaService, or JavaController JSON

### ValidationPrompts.scala

**Purpose:** Generate prompts for test generation and validation

**System Prompt Focus:**
- JUnit 5 with modern patterns
- Verify business logic preservation
- Cover all conditional branches
- Meaningful test names
- Edge cases and boundaries

**Methods:**

1. `generateUnitTests(serviceName, methods, originalCobol)` - Generate JUnit tests
2. `validateTransformation(cobolCode, javaCode, analysis)` - Verify correctness

**Test Generation Rules:**
- Test name pattern: `methodName_should_expectedBehavior_when_condition`
- Use @Mock and @InjectMocks
- Cover all IF/EVALUATE branches
- Test boundary conditions
- Test error scenarios (null, invalid)

**Validation Checklist:**
- All COBOL variables → Java fields
- All COBOL procedures → Java methods
- Control flow preserved
- Arithmetic operations correct
- Data type conversions safe
- Edge cases handled
- Business logic equivalent

**Output:** TestResults or ValidationReport JSON

### DocumentationPrompts.scala

**Purpose:** Generate prompts for migration documentation

**System Prompt Focus:**
- Technical writing expertise
- Clear, comprehensive documentation
- Mermaid diagrams for architecture
- COBOL to Java mappings
- Deployment instructions

**Methods:**

1. `generateTechnicalDesign(analyses, graph, projects)` - Full technical design doc
2. `generateMigrationSummary(startTime, endTime, analyses, reports)` - Summary report

**Technical Design Includes:**
- Executive summary (scope, tech stack)
- Architecture overview (Mermaid diagrams)
- Data model mappings (COBOL → JPA)
- API design (REST endpoints)
- Deployment architecture

**Migration Summary Includes:**
- Migration overview (duration, files processed)
- Quality metrics (test coverage, validation)
- Success criteria (all tests passing, etc.)
- Next steps (deployment, manual review)

**Output:** MigrationDocumentation JSON (with Markdown in string fields)

---

## Integration with Agents

### Agent Integration Pattern

Agents will use prompt templates like this:

```scala
// In CobolAnalyzerAgent.scala
override def analyze(cobolFile: CobolFile): ZIO[GeminiService, Throwable, CobolAnalysis] =
  for
    cobolCode <- FileService.readFile(cobolFile.path)
    prompt     = CobolAnalyzerPrompts.analyzeStructure(cobolFile, cobolCode)
    response  <- GeminiService.execute(prompt)
    analysis  <- parseJsonResponse[CobolAnalysis](response.output)
  yield analysis
```

### JSON Parsing

Use ZIO JSON to parse Gemini responses:

```scala
import zio.json.*

def parseJsonResponse[A: JsonDecoder](json: String): ZIO[Any, Throwable, A] =
  ZIO.fromEither(json.fromJson[A])
    .mapError(err => new RuntimeException(s"JSON parse error: $err"))
```

---

## Versioning Strategy

### Template Versioning

Each template object includes:

```scala
val version = "1.0.0"

private val systemPrompt = """
  |...
  |TEMPLATE_VERSION: 1.0.0
  |...
""".stripMargin
```

### Version Tracking in Output

Gemini responses will include the template version, allowing:
- Traceability: Know which prompt version generated which output
- A/B testing: Compare different prompt versions
- Debugging: Identify issues with specific prompt versions

### Version Evolution

When refining prompts:
1. Update `version` constant
2. Update `TEMPLATE_VERSION` in system prompt
3. Document changes in commit message
4. Keep version consistent across object and system prompt

---

## Quality Assurance

### Prompt Quality Checklist

- [ ] System prompt is agent-specific and clear
- [ ] Schema reference points to correct case class
- [ ] Validation rules list all required fields
- [ ] 3 comprehensive examples provided
- [ ] Examples cover simple, intermediate, complex cases
- [ ] Version number included and consistent
- [ ] Chunking logic for large files
- [ ] JSON-only output requirement explicit
- [ ] Business logic preservation emphasized

### Testing Strategy

1. **Unit tests**: Test helper functions (extractDivision, formatCobolCode)
2. **Integration tests**: Test with real Gemini CLI
3. **Manual validation**: Review generated outputs for quality
4. **Example coverage**: Ensure examples match real COBOL patterns

---

## Non-Functional Requirements

### Performance

- **Token efficiency**: Chunking prevents token limit issues
- **Prompt size**: Keep prompts under 10K tokens when possible
- **Example size**: Examples should be minimal but comprehensive

### Maintainability

- **DRY principle**: PromptHelpers eliminates duplication
- **Single source of truth**: Reference case classes, don't duplicate schemas
- **Consistent structure**: All templates follow same pattern

### Reliability

- **Strict validation**: Explicit required fields prevent missing data
- **JSON-only output**: Clear instructions reduce parsing errors
- **Error recovery**: Agents can retry with modified prompts if parsing fails

---

## Future Enhancements

1. **Dynamic example selection**: Choose examples based on COBOL complexity
2. **Prompt optimization**: A/B test different phrasings for better results
3. **Token usage tracking**: Monitor and optimize token consumption
4. **Multi-language support**: Extend beyond COBOL (PL/I, Assembler)
5. **Custom validation rules**: Allow per-project validation customization

---

## Appendix: Example Prompt Output

### CobolAnalyzerPrompts.analyzeStructure() Output

```
You are an expert COBOL program analyzer with deep knowledge of mainframe COBOL syntax.
Your role is to perform precise structural analysis of COBOL programs.

CRITICAL REQUIREMENTS:
- Always respond with valid JSON only, no markdown, no explanations
- Extract ALL variables, procedures, and copybook references
- Calculate accurate complexity metrics
- Identify all control flow statements (IF, PERFORM, GOTO, EVALUATE)
- Parse PIC clauses correctly for data types

TEMPLATE_VERSION: 1.0.0

Analyze this COBOL program and extract its complete structure.

FILE: CUSTPROG.cbl
```cobol
IDENTIFICATION DIVISION.
PROGRAM-ID. CUSTPROG.
...
```

OUTPUT FORMAT:
Your response must be valid JSON matching the CobolAnalysis case class from our Scala codebase.
Do not include markdown formatting, explanations, or any text outside the JSON structure.
Ensure all required fields are present and types are correct.

STRICT VALIDATION REQUIREMENTS:
  - divisions is REQUIRED and cannot be null or empty
  - variables is REQUIRED and cannot be null or empty
  - procedures is REQUIRED and cannot be null or empty
  - copybooks is REQUIRED and cannot be null or empty
  - complexity is REQUIRED and cannot be null or empty

If any required field is missing or invalid, the response will be rejected.

[... few-shot examples ...]

Extract:
1. IDENTIFICATION DIVISION - program-id, author, date-written
2. ENVIRONMENT DIVISION - file controls, special-names
3. DATA DIVISION:
   - WORKING-STORAGE: All 01-49 level variables with PIC clauses
   ...
```

---

## Conclusion

This design provides a comprehensive, maintainable prompt template library for COBOL to Java migration. The templates balance specificity (agent-focused system prompts, strict validation) with flexibility (chunking, comprehensive examples) to produce reliable, high-quality Gemini CLI outputs.

By following these patterns, the migration agents can consistently extract accurate COBOL analysis, build correct dependency graphs, generate clean Java code, and produce thorough documentation.
