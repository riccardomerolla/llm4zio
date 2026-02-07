# Legacy Modernization Agents: COBOL to Spring Boot Migration

**Scala 3 + ZIO 2.x Effect-Oriented Programming Implementation**

**Version:** 1.0.0
**Author:** Riccardo Merolla
**Date:** February 5, 2026
**Status:** ✅ Production Ready - All Agents Implemented
**Test Coverage:** 417 tests passing

---

## Executive Summary

This project implements an AI-powered legacy modernization framework for migrating COBOL mainframe applications to modern Spring Boot microservices using Scala 3 and ZIO 2.x. The system leverages Google Gemini CLI in non-interactive mode to orchestrate specialized AI agents that perform analysis, transformation, and code generation tasks.

### Key Objectives

- Automate COBOL-to-Java Spring Boot migration with minimal manual intervention
- Preserve business logic integrity through multi-phase validation
- Generate production-ready microservices with modern architectural patterns
- Provide comprehensive documentation and traceability throughout migration
- Enable team collaboration through agent-based task decomposition

### Technology Stack

- **Core Language:** Scala 3 with latest syntax and features
- **Effect System:** ZIO 2.x for functional, composable effects
- **AI Engine:** Multi-provider `AIService` (Gemini CLI/API, OpenAI-compatible, Anthropic-compatible)
- **Target Platform:** Spring Boot microservices (Java 17+)
- **Build Tool:** sbt 1.9+
- **Testing:** ZIO Test framework

### Architecture Principles

This implementation follows Effect-Oriented Programming (EOP) principles, treating all side effects (AI calls, file I/O, logging) as managed effects within the ZIO ecosystem. The system is designed for composability, testability, and observability.

### Implementation Status

**✅ Fully Implemented and Tested:**

- **Discovery Phase** (Issues #19-22): File scanning, metadata extraction, content-based categorization, inventory generation
- **Analysis Phase** (Issues #23-28): AI-powered COBOL parsing via Gemini CLI, division analysis, copybook detection
- **Dependency Mapping Phase** (Issues #29-34): COPY/CALL extraction, graph building, complexity metrics, Mermaid diagrams
- **Transformation Phase** (Issues #35-41): Spring Boot project generation, entity/service/controller creation, JPA entities
- **Validation Phase** (Issues #42-47): Test generation, logic validation, compilation checks, coverage reports
- **Documentation Phase** (Issues #48-53): Technical design, API docs, data mappings, migration summary, deployment guides

**Architecture Highlights:**
- All agents use ZIO 2.x with typed error channels and resource-safe operations
- AI-powered analysis via Google Gemini CLI (ADR-002) for complex parsing tasks
- Schema-validated JSON output with `zio-json` codecs
- Comprehensive test coverage with ZIO Test (417+ passing tests)
- Production-ready reports generated in `reports/` directory

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Architecture and Design](#2-architecture-and-design)
3. [Agent Ecosystem](#3-agent-ecosystem)
4. [Migration Pipeline](#4-migration-pipeline)
5. [Project Structure](#5-project-structure)
6. [Getting Started](#6-getting-started)
7. [Documentation](#7-documentation)
8. [Contributing](#8-contributing)
9. [References](#9-references)

---

## 1. Project Overview

### 1.1 Problem Statement

Legacy COBOL systems represent decades of accumulated business logic in financial, insurance, and government sectors. These systems face critical challenges:

- Shrinking pool of COBOL developers
- High operational costs on mainframe infrastructure
- Difficulty integrating with modern cloud-native ecosystems
- Limited agility for business changes and innovation

### 1.2 Solution Approach

Our framework decomposes the migration into distinct phases, each handled by specialized AI agents orchestrated through ZIO effects:

| Phase | Primary Agent | Output | Status |
|-------|---------------|--------|--------|
| Discovery | CobolDiscoveryAgent | File inventory, dependencies | ✅ Implemented |
| Analysis | CobolAnalyzerAgent | Structured analysis JSON | ✅ Implemented |
| Mapping | DependencyMapperAgent | Dependency graph | ✅ Implemented |
| Transformation | JavaTransformerAgent | Spring Boot code | ✅ Implemented |
| Validation | ValidationAgent | Test results, reports | ✅ Implemented |
| Documentation | DocumentationAgent | Technical docs | ✅ Implemented |

### 1.3 Expected Outcomes

- Functional Spring Boot microservices equivalent to COBOL programs
- Comprehensive dependency mapping and architecture documentation
- Unit and integration tests for generated code
- Migration reports with metrics and quality indicators
- Reusable agent framework for future migrations

---

## 2. Architecture and Design

### 2.1 System Architecture

The system follows a layered architecture:

**Layer 1: Agent Orchestration**
- Main orchestrator built with ZIO workflows
- Agent lifecycle management
- State management and checkpointing

**Layer 2: Agent Implementations**
- Specialized agents for different tasks
- Gemini CLI integration wrapper
- Prompt engineering and context management

**Layer 3: Core Services**
- File I/O services
- Logging and observability
- Configuration management
- State persistence

**Layer 4: External Integrations**
- Gemini CLI non-interactive invocation
- Git integration for version control
- Report generation services

### 2.2 Effect-Oriented Design with ZIO

All system operations are modeled as ZIO effects:

- **ZIO[R, E, A]:** Core effect type representing computation requiring environment R, failing with E, or succeeding with A
- **ZLayer:** Dependency injection for services
- **ZIO Streams:** Processing large COBOL codebases incrementally
- **ZIO Test:** Property-based and effect-based testing
- **Ref and Queue:** Concurrent state management

Example effect signature for COBOL analysis:

```scala
def analyzeCobol(file: CobolFile): ZIO[AIService & Logger, AnalysisError, CobolAnalysis]
```

### 2.3 Gemini CLI Integration Strategy

Google Gemini CLI supports non-interactive mode for automation:

```bash
# Non-interactive invocation
gemini -p "Analyze this COBOL code: $(cat program.cbl)" --json-output
```

Our ZIO wrapper provides:

- Process execution with streaming output
- Timeout handling
- Retry logic with exponential backoff
- Response parsing and validation
- Cost tracking and rate limiting

---

## 3. Agent Ecosystem

### 3.1 Core Agent Types

#### CobolDiscoveryAgent ✅
**Purpose:** Scan and catalog COBOL source files and copybooks.

**Status:** Fully implemented with content-based file type detection

**Responsibilities:**
- Traverse directory structures
- Identify .cbl, .cpy, .jcl files by extension and content
- Extract metadata (file size, last modified, encoding, line count)
- Detect COBOL divisions for accurate categorization
- Build schema-validated JSON file inventory

**Implementation:** [CobolDiscoveryAgent.scala](src/main/scala/agents/CobolDiscoveryAgent.scala)

#### CobolAnalyzerAgent ✅
**Purpose:** Deep structural analysis of COBOL programs using AI.

**Status:** Fully implemented using Gemini CLI with structured prompts (ADR-002)

**Responsibilities:**
- Parse COBOL divisions (IDENTIFICATION, ENVIRONMENT, DATA, PROCEDURE) via AI
- Extract variables, data structures, and types
- Identify control flow (IF, PERFORM, GOTO statements)
- Detect copybook dependencies (COPY statements with REPLACING)
- Generate schema-validated analysis JSON
- Handle large files with division chunking (10K+ characters)

**Implementation:** [CobolAnalyzerAgent.scala](src/main/scala/agents/CobolAnalyzerAgent.scala) + [CobolAnalyzerPrompts.scala](src/main/scala/prompts/CobolAnalyzerPrompts.scala)

**Architecture Note:** Uses AI-powered parsing rather than manual parser implementation for better maintainability and COBOL dialect handling

#### DependencyMapperAgent ✅
**Purpose:** Map relationships between COBOL programs and copybooks.

**Status:** Fully implemented with graph algorithms and visualization

**Responsibilities:**
- Extract COPY statements and CALL targets from analysis
- Build directed dependency graph with nodes and edges
- Calculate cyclomatic complexity metrics
- Detect circular dependencies and strongly connected components
- Generate Mermaid diagrams for visualization
- Identify shared copybooks as service candidates (2+ references)
- Produce migration order recommendations

**Implementation:** [DependencyMapperAgent.scala](src/main/scala/agents/DependencyMapperAgent.scala)

**Outputs:**
- `reports/mapping/dependency-graph.json`
- `reports/mapping/dependency-diagram.md`
- `reports/mapping/migration-order.md`

#### JavaTransformerAgent ✅
**Purpose:** Transform COBOL programs into Spring Boot microservices.

**Status:** Fully implemented with AI-powered code generation

**Responsibilities:**
- Convert COBOL DATA division to Java entities (JPA/records)
- Transform PROCEDURE division to Spring service methods
- Generate REST controllers with @RestController annotations
- Create Spring Data JPA repositories
- Generate Maven project structure (pom.xml)
- Apply Spring Boot annotations (@Service, @Autowired, @Transactional)
- Handle error scenarios with exception classes
- Configure application.yml and OpenAPI documentation

**Implementation:** [JavaTransformerAgent.scala](src/main/scala/agents/JavaTransformerAgent.scala) + [JavaTransformerPrompts.scala](src/main/scala/prompts/JavaTransformerPrompts.scala)

**Outputs:** Complete Spring Boot Maven projects in `java-output/` directory

#### ValidationAgent ✅
**Purpose:** Validate generated Spring Boot code for correctness.

**Status:** Fully implemented with multi-level validation

**Responsibilities:**
- Execute Maven compilation (`mvn -q compile`)
- Calculate coverage metrics (variables, procedures, file sections)
- Run static analysis checks for code quality
- Perform semantic validation via AI (business logic preservation)
- Generate JUnit 5 unit tests via Gemini prompts
- Track unmapped COBOL elements
- Produce comprehensive validation reports

**Implementation:** [ValidationAgent.scala](src/main/scala/agents/ValidationAgent.scala) + [ValidationPrompts.scala](src/main/scala/prompts/ValidationPrompts.scala)

**Outputs:**
- `reports/validation/{project}-validation.json`
- `reports/validation/{project}-validation.md`
- Compilation results, coverage metrics, issue tracking

#### DocumentationAgent ✅
**Purpose:** Generate comprehensive migration documentation.

**Status:** Fully implemented with multi-format output

**Responsibilities:**
- Aggregate data from all migration phases
- Generate technical design document with architecture diagrams
- Create OpenAPI/REST endpoint documentation
- Document COBOL-to-Java data model mappings
- Produce executive migration summary with metrics
- Create deployment guide (Maven, Docker, Kubernetes)
- Generate Mermaid architecture and data flow diagrams
- Output both Markdown and HTML formats

**Implementation:** [DocumentationAgent.scala](src/main/scala/agents/DocumentationAgent.scala)

**Outputs:**
- `reports/documentation/migration-summary.{md,html}`
- `reports/documentation/design-document.{md,html}`
- `reports/documentation/api-documentation.{md,html}`
- `reports/documentation/data-mapping-reference.{md,html}`
- `reports/documentation/deployment-guide.{md,html}`
- `reports/documentation/diagrams/` (Mermaid diagrams)

---

## 4. Migration Pipeline

The migration follows six macro steps executed sequentially:

1. **Discovery and Inventory** (5-10 minutes)
   - Scan directory tree for COBOL files
   - Extract file metadata
   - Generate inventory JSON

2. **Deep Analysis** (30-60 minutes for 100 programs)
   - Invoke CobolAnalyzerAgent for each file
   - Extract structural information using Gemini AI
   - Store analysis results

3. **Dependency Mapping** (10-20 minutes)
   - Extract COPY statements and program calls
   - Build directed dependency graph
   - Identify service boundaries

4. **Code Transformation** (60-120 minutes for 100 programs)
   - Generate Spring Boot project structure
   - Transform data structures and procedures
   - Apply Spring annotations

5. **Validation and Testing** (30-45 minutes)
   - Generate unit and integration tests
   - Validate business logic preservation
   - Run static analysis tools

6. **Documentation Generation** (15-20 minutes)
   - Aggregate data from all phases
   - Generate technical design documents
   - Create deployment guides

---

## 5. Project Structure

```
legacy-modernization-agents/
├── build.sbt
├── project/
├── src/
│   ├── main/
│   │   └── scala/
│   │       ├── agents/          # Agent implementations
│   │       ├── core/            # Core services
│   │       ├── models/          # Domain models
│   │       ├── orchestration/   # Workflow orchestration
│   │       └── Main.scala
│   └── test/
│       └── scala/
├── docs/
│   ├── adr/                    # Architecture Decision Records
│   ├── findings/               # Findings and observations
│   ├── progress/               # Progress tracking
│   ├── deep-dive/              # Detailed task breakdowns
│   └── agent-skills/           # Agent skill definitions
├── cobol-source/               # Input COBOL files
├── java-output/                # Generated Spring Boot code
├── reports/                    # Migration reports
└── README.md
```

---

## 6. Getting Started

### 6.1 Prerequisites

- Scala 3.3+ and sbt 1.9+
- Java 17+ (for running generated Spring Boot code)
- Gemini CLI installed and configured (only required for `gemini-cli` provider)
- Docker (optional, for containerized deployment)

### 6.2 Installation

```bash
# Clone repository
git clone <repository-url>
cd legacy-modernization-agents

# Install dependencies
sbt update

# Configure provider credentials (example)
export MIGRATION_AI_PROVIDER="gemini-cli"
export MIGRATION_AI_API_KEY="your-api-key"

# Verify installation
sbt test
```

### 6.3 Configuration

Create or edit `src/main/resources/application.conf`:

```hocon
migration {
  ai {
    provider = "gemini-cli" # gemini-cli | gemini-api | openai | anthropic
    model = "gemini-2.5-flash"
    base-url = null
    api-key = null
    timeout = 90s
    max-retries = 3
    temperature = 0.1
    max-tokens = 32768
  }

  cobol-source = "cobol-source"
  java-output = "java-output"
  reports-dir = "reports"
}
```

### 6.4 Running a Migration

```bash
# Place COBOL files in cobol-source/
cp /path/to/cobol/* cobol-source/

# Run full migration pipeline
sbt "run migrate --source cobol-source --output java-output"

# Explicit provider selection
sbt "run migrate --source cobol-source --output java-output --ai-provider gemini-cli"

# LM Studio (OpenAI-compatible local endpoint)
sbt "run migrate --source cobol-source --output java-output --ai-provider openai --ai-base-url http://localhost:1234/v1 --ai-model <model-name>"

# Or run specific steps individually
sbt "run step discovery --source cobol-source --output java-output"
sbt "run step analysis --source cobol-source --output java-output"
sbt "run step mapping --source cobol-source --output java-output"
sbt "run step transformation --source cobol-source --output java-output"
sbt "run step validation --source cobol-source --output java-output"
sbt "run step documentation --source cobol-source --output java-output"

# List available migration runs and checkpoints
sbt "run list-runs"

# View help for available options
sbt "run --help"
sbt "run migrate --help"
sbt "run step --help"

# Advanced options
sbt "run migrate --source cobol-source --output java-output --parallelism 8 --verbose"
sbt "run migrate --source cobol-source --output java-output --resume <run-id>"
sbt "run migrate --source cobol-source --output java-output --dry-run"

# Run tests to verify implementation
sbt test                         # Unit tests (417+ tests)
sbt it:test                      # Integration tests
sbt coverage test coverageReport # With coverage

# View generated reports
ls reports/discovery/       # File inventory
ls reports/analysis/        # COBOL analysis
ls reports/mapping/         # Dependency graphs
ls reports/validation/      # Validation results
ls reports/documentation/   # Migration docs

# View progress
cat docs/progress/overall-progress.md
```

### 6.5 Testing Generated Code

```bash
# Navigate to generated Spring Boot project
cd java-output/com/example/customer-service

# Run tests
./mvnw test

# Start application
./mvnw spring-boot:run
```

---

## 7. Documentation

Comprehensive documentation is organized in the following directories:

- **[docs/deep-dive/](docs/deep-dive/)** - Detailed task breakdowns for each migration phase
- **[docs/agent-skills/](docs/agent-skills/)** - Agent skill definitions and specifications
- **[docs/progress/](docs/progress/)** - Progress tracking dashboards
- **[docs/findings/](docs/findings/)** - Technical findings and lessons learned
- **[docs/adr/](docs/adr/)** - Architecture Decision Records (ADRs)

Key documentation files:

- [ADR-001: Scala 3 and ZIO 2.x Adoption](docs/adr/ADR-001-scala3-zio-adoption.md)
- [ADR-002: Gemini CLI Adoption](docs/adr/ADR-002-gemini-cli-adoption.md)
- [ADR-003: Multi-Provider AIService](docs/adr/ADR-003-multi-provider-ai-service.md)
- [Migration Progress Dashboard](docs/progress/overall-progress.md)
- [Findings and Observations](docs/findings/findings-observations.md)

---

## 8. Contributing

This project follows ZIO and Scala 3 best practices. When contributing:

1. Write pure functional code using ZIO effects
2. Use meaningful type signatures
3. Add comprehensive tests using ZIO Test
4. Document complex logic with comments
5. Follow the agent-based architecture pattern

---

## 9. References

[1] Microsoft. (2025). How We Use AI Agents for COBOL Migration and Mainframe Modernization. https://devblogs.microsoft.com/all-things-azure/how-we-use-ai-agents-for-cobol-migration-and-mainframe-modernization/

[2] Azure Samples. (2025). Legacy-Modernization-Agents: AI-powered COBOL to Java Quarkus modernization agents. GitHub. https://github.com/Azure-Samples/Legacy-Modernization-Agents

[3] Microsoft. (2025). AI Agents Are Rewriting the App Modernization Playbook. Microsoft Tech Community. https://techcommunity.microsoft.com/blog/appsonazureblog/ai-agents-are-rewriting-the-app-modernization-playbook/4470162

[4] Google. (2025). Hands-on with Gemini CLI. Google Codelabs. https://codelabs.developers.google.com/gemini-cli-hands-on

[5] Ranjan, R. (2025). Modernizing Legacy: AI-Powered COBOL to Java Migration. LinkedIn. https://www.linkedin.com/pulse/modernizing-legacy-ai-powered-cobol-java-migration-rajesh-ranjan-diode

---

## License

[Specify your license here]

## Contact

For questions or support, please [open an issue](https://github.com/your-repo/issues).
