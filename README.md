# Legacy Modernization Agents: COBOL to Spring Boot Migration

**Scala 3 + ZIO 2.x Effect-Oriented Programming Implementation**

**Version:** 1.0.0
**Author:** Engineering Team
**Date:** February 5, 2026
**Status:** Initial Implementation

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
- **AI Engine:** Google Gemini CLI (non-interactive mode)
- **Target Platform:** Spring Boot microservices (Java 17+)
- **Build Tool:** sbt 1.9+
- **Testing:** ZIO Test framework

### Architecture Principles

This implementation follows Effect-Oriented Programming (EOP) principles, treating all side effects (AI calls, file I/O, logging) as managed effects within the ZIO ecosystem. The system is designed for composability, testability, and observability.

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

| Phase | Primary Agent | Output |
|-------|---------------|--------|
| Discovery | CobolDiscoveryAgent | File inventory, dependencies |
| Analysis | CobolAnalyzerAgent | Structured analysis JSON |
| Mapping | DependencyMapperAgent | Dependency graph |
| Transformation | JavaTransformerAgent | Spring Boot code |
| Validation | ValidationAgent | Test results, reports |
| Documentation | DocumentationAgent | Technical docs |

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
def analyzeCobol(file: CobolFile): ZIO[GeminiService & Logger, AnalysisError, CobolAnalysis]
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

#### CobolDiscoveryAgent
**Purpose:** Scan and catalog COBOL source files and copybooks.

**Responsibilities:**
- Traverse directory structures
- Identify .cbl, .cpy, .jcl files
- Extract metadata (file size, last modified, encoding)
- Build initial file inventory

#### CobolAnalyzerAgent
**Purpose:** Deep structural analysis of COBOL programs using AI.

**Responsibilities:**
- Parse COBOL divisions (IDENTIFICATION, ENVIRONMENT, DATA, PROCEDURE)
- Extract variables, data structures, and types
- Identify control flow (IF, PERFORM, GOTO statements)
- Detect copybook dependencies
- Generate structured analysis JSON

#### DependencyMapperAgent
**Purpose:** Map relationships between COBOL programs and copybooks.

**Responsibilities:**
- Analyze COPY statements and program calls
- Build dependency graph
- Calculate complexity metrics
- Generate Mermaid diagrams
- Identify shared copybooks as service candidates

#### JavaTransformerAgent
**Purpose:** Transform COBOL programs into Spring Boot microservices.

**Responsibilities:**
- Convert COBOL data structures to Java classes/records
- Transform PROCEDURE DIVISION to service methods
- Generate Spring Boot annotations and configurations
- Implement REST endpoints for program entry points
- Create Spring Data JPA entities from file definitions
- Handle error scenarios with try-catch blocks

#### ValidationAgent
**Purpose:** Validate generated Spring Boot code for correctness.

**Responsibilities:**
- Generate unit tests using JUnit 5
- Create integration tests for REST endpoints
- Validate business logic preservation
- Check compilation and static analysis
- Generate test coverage reports

#### DocumentationAgent
**Purpose:** Generate comprehensive migration documentation.

**Responsibilities:**
- Create technical design documents
- Generate API documentation
- Document data model mappings
- Produce migration summary reports
- Create deployment guides

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
- Google Gemini CLI installed and configured
- Docker (optional, for containerized deployment)

### 6.2 Installation

```bash
# Clone repository
git clone <repository-url>
cd legacy-modernization-agents

# Install dependencies
sbt update

# Configure Gemini CLI
export GEMINI_API_KEY="your-api-key"

# Verify installation
sbt test
```

### 6.3 Configuration

Create or edit `src/main/resources/application.conf`:

```hocon
gemini {
  model = "gemini-2.5-flash"
  max-tokens = 32768
  temperature = 0.1
  timeout = 300s
}

migration {
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
sbt "run --migrate"

# Or run specific steps
sbt "run --step discovery"
sbt "run --step analysis"
sbt "run --step transformation"

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
