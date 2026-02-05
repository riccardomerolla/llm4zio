# Legacy Modernization Agents: COBOL to Spring Boot Migration

**Scala 3 + ZIO 2.x Effect-Oriented Programming Implementation**

**Version:** 1.0.0  
**Author:** Engineering Team  
**Date:** February 5, 2026  
**Status:** Initial Design

---

## Executive Summary

This project implements an AI-powered legacy modernization framework for migrating COBOL mainframe applications to modern Spring Boot microservices using Scala 3 and ZIO 2.x. The system leverages Google Gemini CLI in non-interactive mode to orchestrate specialized AI agents that perform analysis, transformation, and code generation tasks[1].

### Key Objectives

\begin{itemize}
\item Automate COBOL-to-Java Spring Boot migration with minimal manual intervention
\item Preserve business logic integrity through multi-phase validation
\item Generate production-ready microservices with modern architectural patterns
\item Provide comprehensive documentation and traceability throughout migration
\item Enable team collaboration through agent-based task decomposition
\end{itemize}

### Technology Stack

\begin{itemize}
\item \textbf{Core Language:} Scala 3 with latest syntax and features
\item \textbf{Effect System:} ZIO 2.x for functional, composable effects
\item \textbf{AI Engine:} Google Gemini CLI (non-interactive mode)
\item \textbf{Target Platform:} Spring Boot microservices (Java 17+)
\item \textbf{Build Tool:} sbt 1.9+
\item \textbf{Testing:} ZIO Test framework
\end{itemize}

### Architecture Principles

This implementation follows Effect-Oriented Programming (EOP) principles, treating all side effects (AI calls, file I/O, logging) as managed effects within the ZIO ecosystem. The system is designed for composability, testability, and observability.

---

## Table of Contents

\begin{enumerate}
\item Project Overview
\item Architecture and Design
\item Agent Ecosystem
\item Macro Steps and Workflows
\item Deep-Dive Task Breakdown
\item Agent Skill Definitions
\item Progress Tracking Framework
\item ADRs (Architecture Decision Records)
\item Findings and Lessons Learned
\item Getting Started
\item References
\end{enumerate}

---

## 1. Project Overview

### 1.1 Problem Statement

Legacy COBOL systems represent decades of accumulated business logic in financial, insurance, and government sectors. These systems face critical challenges[5]:

\begin{itemize}
\item Shrinking pool of COBOL developers
\item High operational costs on mainframe infrastructure
\item Difficulty integrating with modern cloud-native ecosystems
\item Limited agility for business changes and innovation
\end{itemize}

### 1.2 Solution Approach

Our framework decomposes the migration into distinct phases, each handled by specialized AI agents orchestrated through ZIO effects:

\begin{table}
\begin{tabular}{|l|l|l|}
\hline
\textbf{Phase} & \textbf{Primary Agent} & \textbf{Output} \\
\hline
Discovery & CobolDiscoveryAgent & File inventory, dependencies \\
Analysis & CobolAnalyzerAgent & Structured analysis JSON \\
Mapping & DependencyMapperAgent & Dependency graph \\
Transformation & JavaTransformerAgent & Spring Boot code \\
Validation & ValidationAgent & Test results, reports \\
Documentation & DocumentationAgent & Technical docs \\
\hline
\end{tabular}
\caption{Migration phases and responsible agents}
\end{table}

### 1.3 Expected Outcomes

\begin{itemize}
\item Functional Spring Boot microservices equivalent to COBOL programs
\item Comprehensive dependency mapping and architecture documentation
\item Unit and integration tests for generated code
\item Migration reports with metrics and quality indicators
\item Reusable agent framework for future migrations
\end{itemize}

---

## 2. Architecture and Design

### 2.1 System Architecture

\begin{figure}
\centering
\includegraphics[width=0.9\textwidth]{architecture-diagram.png}
\caption{High-level system architecture showing agent orchestration}
\label{fig:architecture}
\end{figure}

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

\begin{itemize}
\item \textbf{ZIO[R, E, A]:} Core effect type representing computation requiring environment R, failing with E, or succeeding with A
\item \textbf{ZLayer:} Dependency injection for services
\item \textbf{ZIO Streams:} Processing large COBOL codebases incrementally
\item \textbf{ZIO Test:} Property-based and effect-based testing
\item \textbf{Ref and Queue:} Concurrent state management
\end{itemize}

Example effect signature for COBOL analysis:

def analyzeCobol(file: CobolFile): ZIO[GeminiService & Logger, AnalysisError, CobolAnalysis]

### 2.3 Gemini CLI Integration Strategy

Google Gemini CLI supports non-interactive mode for automation[6][10]:

# Non-interactive invocation
gemini -p "Analyze this COBOL code: $(cat program.cbl)" --json-output

Our ZIO wrapper provides:

\begin{itemize}
\item Process execution with streaming output
\item Timeout handling
\item Retry logic with exponential backoff
\item Response parsing and validation
\item Cost tracking and rate limiting
\end{itemize}

### 2.4 Project Structure

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
│   └── deep-dive/              # Detailed task breakdowns
├── cobol-source/               # Input COBOL files
├── java-output/                # Generated Spring Boot code
├── reports/                    # Migration reports
└── README.md

---

## 3. Agent Ecosystem

### 3.1 Agent Architecture

Each agent is a self-contained ZIO service with:

\begin{itemize}
\item Defined input/output contracts
\item Specialized prompt templates
\item Context management capabilities
\item Error handling strategies
\item Performance metrics collection
\end{itemize}

### 3.2 Core Agent Types

#### 3.2.1 CobolDiscoveryAgent

**Purpose:** Scan and catalog COBOL source files and copybooks.

**Responsibilities:**
\begin{itemize}
\item Traverse directory structures
\item Identify .cbl, .cpy, .jcl files
\item Extract metadata (file size, last modified, encoding)
\item Build initial file inventory
\end{itemize}

**Interactions:**
- Output consumed by: CobolAnalyzerAgent, DependencyMapperAgent

#### 3.2.2 CobolAnalyzerAgent

**Purpose:** Deep structural analysis of COBOL programs using AI.

**Responsibilities:**
\begin{itemize}
\item Parse COBOL divisions (IDENTIFICATION, ENVIRONMENT, DATA, PROCEDURE)
\item Extract variables, data structures, and types
\item Identify control flow (IF, PERFORM, GOTO statements)
\item Detect copybook dependencies
\item Generate structured analysis JSON
\end{itemize}

**Interactions:**
- Input from: CobolDiscoveryAgent
- Output consumed by: JavaTransformerAgent, DependencyMapperAgent

#### 3.2.3 DependencyMapperAgent

**Purpose:** Map relationships between COBOL programs and copybooks.

**Responsibilities:**
\begin{itemize}
\item Analyze COPY statements and program calls
\item Build dependency graph
\item Calculate complexity metrics
\item Generate Mermaid diagrams
\item Identify shared copybooks as service candidates
\end{itemize}

**Interactions:**
- Input from: CobolDiscoveryAgent, CobolAnalyzerAgent
- Output consumed by: JavaTransformerAgent, DocumentationAgent

#### 3.2.4 JavaTransformerAgent

**Purpose:** Transform COBOL programs into Spring Boot microservices.

**Responsibilities:**
\begin{itemize}
\item Convert COBOL data structures to Java classes/records
\item Transform PROCEDURE DIVISION to service methods
\item Generate Spring Boot annotations and configurations
\item Implement REST endpoints for program entry points
\item Create Spring Data JPA entities from file definitions
\item Handle error scenarios with try-catch blocks
\end{itemize}

**Interactions:**
- Input from: CobolAnalyzerAgent, DependencyMapperAgent
- Output consumed by: ValidationAgent, DocumentationAgent

#### 3.2.5 ValidationAgent

**Purpose:** Validate generated Spring Boot code for correctness.

**Responsibilities:**
\begin{itemize}
\item Generate unit tests using JUnit 5
\item Create integration tests for REST endpoints
\item Validate business logic preservation
\item Check compilation and static analysis
\item Generate test coverage reports
\end{itemize}

**Interactions:**
- Input from: JavaTransformerAgent
- Output consumed by: DocumentationAgent

#### 3.2.6 DocumentationAgent

**Purpose:** Generate comprehensive migration documentation.

**Responsibilities:**
\begin{itemize}
\item Create technical design documents
\item Generate API documentation
\item Document data model mappings
\item Produce migration summary reports
\item Create deployment guides
\end{itemize}

**Interactions:**
- Input from: All agents
- Output: Final documentation deliverables

### 3.3 Agent Interaction Patterns

\begin{figure}
\centering
\includegraphics[width=0.9\textwidth]{agent-interactions.png}
\caption{Agent interaction and data flow diagram}
\label{fig:interactions}
\end{figure}

Agents communicate through typed messages and shared state managed by ZIO Ref and Queue:

case class AgentMessage(
  id: String,
  sourceAgent: AgentType,
  targetAgent: AgentType,
  payload: Json,
  timestamp: Instant
)

---

## 4. Macro Steps and Workflows

### 4.1 Migration Pipeline Overview

The migration follows six macro steps executed sequentially:

\begin{enumerate}
\item \textbf{Step 1: Discovery and Inventory}
\item \textbf{Step 2: Deep Analysis}
\item \textbf{Step 3: Dependency Mapping}
\item \textbf{Step 4: Code Transformation}
\item \textbf{Step 5: Validation and Testing}
\item \textbf{Step 6: Documentation Generation}
\end{enumerate}

### 4.2 Step 1: Discovery and Inventory

**Duration Estimate:** 5-10 minutes for typical codebase

**Inputs:**
\begin{itemize}
\item COBOL source directory path
\item Include/exclude patterns
\end{itemize}

**Process:**
\begin{enumerate}
\item Scan directory tree for COBOL files
\item Extract file metadata
\item Categorize files (programs vs copybooks vs JCL)
\item Generate inventory JSON
\end{enumerate}

**Outputs:**
\begin{itemize}
\item \texttt{inventory.json} - Complete file catalog
\item \texttt{discovery-report.md} - Human-readable summary
\end{itemize}

**Success Criteria:**
- All COBOL files discovered
- No permission errors
- Inventory contains accurate metadata

### 4.3 Step 2: Deep Analysis

**Duration Estimate:** 30-60 minutes for 100 programs

**Inputs:**
\begin{itemize}
\item File inventory from Step 1
\item COBOL source files
\end{itemize}

**Process:**
\begin{enumerate}
\item For each COBOL file, invoke CobolAnalyzerAgent
\item Extract structural information using Gemini AI
\item Parse AI response into structured JSON
\item Store analysis results
\end{enumerate}

**Outputs:**
\begin{itemize}
\item \texttt{analysis/<filename>.json} - Per-file analysis
\item \texttt{analysis-summary.json} - Aggregated statistics
\end{itemize}

**Success Criteria:**
- All files analyzed successfully
- Structured data validated against schema
- No AI invocation failures

### 4.4 Step 3: Dependency Mapping

**Duration Estimate:** 10-20 minutes

**Inputs:**
\begin{itemize}
\item File inventory
\item Analysis results from Step 2
\end{itemize}

**Process:**
\begin{enumerate}
\item Extract COPY statements and program calls
\item Build directed dependency graph
\item Calculate complexity metrics
\item Generate Mermaid diagram
\item Identify service boundaries
\end{enumerate}

**Outputs:**
\begin{itemize}
\item \texttt{dependency-map.json} - Graph representation
\item \texttt{dependency-diagram.md} - Mermaid visualization
\item \texttt{service-candidates.json} - Recommended microservice boundaries
\end{itemize}

**Success Criteria:**
- Complete dependency graph
- No orphaned nodes
- Service boundaries identified

### 4.5 Step 4: Code Transformation

**Duration Estimate:** 60-120 minutes for 100 programs

**Inputs:**
\begin{itemize}
\item Analysis results
\item Dependency map
\item Transformation templates
\end{itemize}

**Process:**
\begin{enumerate}
\item For each COBOL program, invoke JavaTransformerAgent
\item Generate Spring Boot project structure
\item Create domain models from DATA DIVISION
\item Transform procedures to service methods
\item Generate REST controllers and configurations
\item Apply Spring annotations
\end{enumerate}

**Outputs:**
\begin{itemize}
\item \texttt{java-output/<package>/} - Spring Boot projects
\item \texttt{transformation-report.json} - Transformation metrics
\end{itemize}

**Success Criteria:**
- All programs transformed
- Generated code compiles
- Spring Boot conventions followed

### 4.6 Step 5: Validation and Testing

**Duration Estimate:** 30-45 minutes

**Inputs:**
\begin{itemize}
\item Generated Spring Boot code
\item Original COBOL analysis
\end{itemize}

**Process:**
\begin{enumerate}
\item Generate unit tests for each service
\item Create integration tests for REST endpoints
\item Validate business logic preservation
\item Run static analysis tools
\item Generate coverage reports
\end{enumerate}

**Outputs:**
\begin{itemize}
\item \texttt{tests/<package>/} - Generated test suites
\item \texttt{validation-report.json} - Test results and coverage
\end{itemize}

**Success Criteria:**
- All tests generated and passing
- Minimum 70\% code coverage
- No critical static analysis violations

### 4.7 Step 6: Documentation Generation

**Duration Estimate:** 15-20 minutes

**Inputs:**
\begin{itemize}
\item All previous outputs
\item Migration metadata
\end{itemize}

**Process:**
\begin{enumerate}
\item Aggregate data from all phases
\item Generate technical design documents
\item Create API documentation
\item Produce migration summary
\item Generate deployment guides
\end{enumerate}

**Outputs:**
\begin{itemize}
\item \texttt{docs/technical-design.md}
\item \texttt{docs/api-reference.md}
\item \texttt{docs/migration-summary.md}
\item \texttt{docs/deployment-guide.md}
\end{itemize}

**Success Criteria:**
- Complete documentation set
- All diagrams rendered
- No broken references

---

## 5. Deep-Dive Task Breakdown

This section outlines the micro-tasks for each macro step, structured for assignment to AI coding agents (Claude, GitHub Copilot, OpenAI Codex).

### 5.1 Task Format

Each task follows this structure:

\begin{itemize}
\item \textbf{Task ID:} Unique identifier
\item \textbf{Title:} Brief description
\item \textbf{Agent Type:} Recommended AI agent
\item \textbf{Dependencies:} Required prior tasks
\item \textbf{Inputs:} Required artifacts/context
\item \textbf{Outputs:} Expected deliverables
\item \textbf{Acceptance Criteria:} Definition of done
\item \textbf{Complexity:} Low/Medium/High
\item \textbf{Estimated Effort:} Time estimate
\end{itemize}

### 5.2 Deep-Dive Folders

Detailed task breakdowns are organized in separate markdown files:

\begin{itemize}
\item \texttt{docs/deep-dive/01-discovery-tasks.md}
\item \texttt{docs/deep-dive/02-analysis-tasks.md}
\item \texttt{docs/deep-dive/03-dependency-mapping-tasks.md}
\item \texttt{docs/deep-dive/04-transformation-tasks.md}
\item \texttt{docs/deep-dive/05-validation-tasks.md}
\item \texttt{docs/deep-dive/06-documentation-tasks.md}
\end{itemize}

See Appendix A for complete task listings.

---

## 6. Agent Skill Definitions

### 6.1 Skill Definition Format

Each agent type has a corresponding skill definition markdown file specifying:

\begin{itemize}
\item Core competencies
\item Knowledge domains
\item Interaction protocols
\item Error handling strategies
\item Performance requirements
\end{itemize}

### 6.2 Agent Skill Files

\begin{itemize}
\item \texttt{docs/agent-skills/cobol-discovery-agent-skill.md}
\item \texttt{docs/agent-skills/cobol-analyzer-agent-skill.md}
\item \texttt{docs/agent-skills/dependency-mapper-agent-skill.md}
\item \texttt{docs/agent-skills/java-transformer-agent-skill.md}
\item \texttt{docs/agent-skills/validation-agent-skill.md}
\item \texttt{docs/agent-skills/documentation-agent-skill.md}
\end{itemize}

See Appendix B for complete skill definitions.

---

## 7. Progress Tracking Framework

### 7.1 Progress Tracking Files

The project includes structured progress tracking:

\begin{itemize}
\item \texttt{docs/progress/overall-progress.md} - High-level status dashboard
\item \texttt{docs/progress/step-01-discovery-progress.md}
\item \texttt{docs/progress/step-02-analysis-progress.md}
\item \texttt{docs/progress/step-03-dependency-mapping-progress.md}
\item \texttt{docs/progress/step-04-transformation-progress.md}
\item \texttt{docs/progress/step-05-validation-progress.md}
\item \texttt{docs/progress/step-06-documentation-progress.md}
\end{itemize}

### 7.2 Progress Metrics

Each step tracks:

\begin{table}
\begin{tabular}{|l|l|}
\hline
\textbf{Metric} & \textbf{Description} \\
\hline
Status & Not Started / In Progress / Complete / Blocked \\
Completion \% & 0-100\% progress indicator \\
Files Processed & Count of files handled \\
Success Rate & Percentage of successful operations \\
Duration & Actual time spent \\
Blockers & Current impediments \\
\hline
\end{tabular}
\caption{Progress tracking metrics}
\end{table}

### 7.3 Automated Progress Updates

Progress tracking integrates with the ZIO effect system:

def trackProgress(step: MigrationStep, status: Status): ZIO[ProgressTracker, Nothing, Unit]

Progress updates are automatically written to markdown files using ZIO Streams.

---

## 8. Architecture Decision Records (ADRs)

### 8.1 ADR Overview

ADRs document significant architectural decisions and their rationale. Each ADR follows the format:

\begin{itemize}
\item Title
\item Status (Proposed / Accepted / Deprecated / Superseded)
\item Context
\item Decision
\item Consequences
\item Alternatives Considered
\end{itemize}

### 8.2 Key ADRs

\begin{enumerate}
\item \textbf{ADR-001:} Use Scala 3 + ZIO 2.x for Implementation
\item \textbf{ADR-002:} Adopt Google Gemini CLI for AI Operations
\item \textbf{ADR-003:} Target Spring Boot for Microservice Generation
\item \textbf{ADR-004:} Use Effect-Oriented Programming Pattern
\item \textbf{ADR-005:} Implement Agent-Based Architecture
\item \textbf{ADR-006:} Store State in JSON Files vs Database
\item \textbf{ADR-007:} Generate Mermaid Diagrams for Visualization
\item \textbf{ADR-008:} Use ZIO Test for Testing Framework
\end{enumerate}

See \texttt{docs/adr/} directory for complete ADR documents.

---

## 9. Findings and Lessons Learned

### 9.1 Technical Findings

**Finding 1: COBOL Complexity Varies Significantly**

Analysis of legacy codebases reveals 3 complexity tiers[5]:
\begin{itemize}
\item Tier 1 (30\%): Simple batch programs, straightforward transformation
\item Tier 2 (50\%): Moderate complexity with file I/O and business rules
\item Tier 3 (20\%): High complexity with embedded SQL, CICS transactions, extensive copybook dependencies
\end{itemize}

**Finding 2: Gemini CLI Performance**

Non-interactive Gemini CLI provides excellent throughput[6][10]:
\begin{itemize}
\item Average response time: 3-8 seconds per COBOL program analysis
\item Token efficiency: Better context utilization than REST API
\item Cost effectiveness: Reduced API overhead
\end{itemize}

**Finding 3: ZIO Benefits for Agent Orchestration**

Effect-oriented programming with ZIO delivers measurable advantages:
\begin{itemize}
\item Type-safe error handling reduces runtime failures
\item Composable effects enable clean separation of concerns
\item Built-in retry and timeout mechanisms improve reliability
\item ZIO Test simplifies testing of effectful code
\end{itemize}

### 9.2 Migration Patterns

**Pattern 1: COBOL to Java Mappings**

Common transformation patterns identified[7][9]:

\begin{table}
\begin{tabular}{|l|l|}
\hline
\textbf{COBOL Construct} & \textbf{Spring Boot Equivalent} \\
\hline
DATA DIVISION & Java records / POJOs \\
PROCEDURE DIVISION & Service methods \\
COPY statements & Shared DTOs / Spring beans \\
FILE section & Spring Data JPA entities \\
DB2 EXEC SQL & Spring Data repositories \\
PERFORM loops & Java for/while loops \\
CALL programs & Service method invocations \\
\hline
\end{tabular}
\caption{COBOL to Spring Boot transformation mappings}
\end{table}

**Pattern 2: Microservice Boundary Identification**

Effective service boundaries correlate with[9]:
\begin{itemize}
\item COBOL program cohesion
\item Copybook sharing patterns
\item Transaction boundaries
\item Business domain alignment
\end{itemize}

### 9.3 Lessons Learned

**Lesson 1: Iterative Validation is Critical**

Continuous validation after each transformation step prevents error accumulation. Implement checkpoint-based recovery.

**Lesson 2: Prompt Engineering Matters**

Agent effectiveness depends heavily on prompt quality. Invest time in prompt templates with examples.

**Lesson 3: Human Review Checkpoints Required**

Fully automated migration is aspirational. Plan for human review at key decision points.

---

## 10. Getting Started

### 10.1 Prerequisites

\begin{itemize}
\item Scala 3.3+ and sbt 1.9+
\item Java 17+ (for running generated Spring Boot code)
\item Google Gemini CLI installed and configured
\item Docker (optional, for containerized deployment)
\end{itemize}

### 10.2 Installation

# Clone repository
git clone <repository-url>
cd legacy-modernization-agents

# Install dependencies
sbt update

# Configure Gemini CLI
export GEMINI_API_KEY="your-api-key"

# Verify installation
sbt test

### 10.3 Configuration

Edit \texttt{src/main/resources/application.conf}:

gemini {
  model = "gemini-2.0-flash"
  max-tokens = 32768
  temperature = 0.1
  timeout = 300s
}

migration {
  cobol-source = "cobol-source"
  java-output = "java-output"
  reports-dir = "reports"
}

### 10.4 Running a Migration

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

### 10.5 Testing Generated Code

# Navigate to generated Spring Boot project
cd java-output/com/example/customer-service

# Run tests
./mvnw test

# Start application
./mvnw spring-boot:run

---

## 11. References

[1] Microsoft. (2025). How We Use AI Agents for COBOL Migration and Mainframe Modernization. https://devblogs.microsoft.com/all-things-azure/how-we-use-ai-agents-for-cobol-migration-and-mainframe-modernization/

[2] Azure Samples. (2025). Legacy-Modernization-Agents: AI-powered COBOL to Java Quarkus modernization agents. GitHub. https://github.com/Azure-Samples/Legacy-Modernization-Agents

[3] Microsoft. (2025). AI Agents Are Rewriting the App Modernization Playbook. Microsoft Tech Community. https://techcommunity.microsoft.com/blog/appsonazureblog/ai-agents-are-rewriting-the-app-modernization-playbook/4470162

[4] Google. (2025). Hands-on with Gemini CLI. Google Codelabs. https://codelabs.developers.google.com/gemini-cli-hands-on

[5] Ranjan, R. (2025). Modernizing Legacy: AI-Powered COBOL to Java Migration. LinkedIn. https://www.linkedin.com/pulse/modernizing-legacy-ai-powered-cobol-java-migration-rajesh-ranjan-diode

[6] Schmid, P. (2025). Google Gemini CLI Cheatsheet. https://www.philschmid.de/gemini-cli-cheatsheet

[7] Pal, N. (2025). From COBOL to Java Spring Boot: My Modernization Journey. LinkedIn. https://www.linkedin.com/posts/neha-pal-98372520a_cobol-java-springboot-activity-7378860036376317952-ec0O

[8] YouTube. (2025). Legacy Code Modernization with Multi AI Agents. https://www.youtube.com/watch?v=iDGWqkLotOs

[9] Azure. (2025). Modernize your mainframe and midrange workloads. https://azure.microsoft.com/en-us/solutions/migration/mainframe

[10] GitHub. (2025). Add @file command support for non-interactive cli input. google-gemini/gemini-cli. https://github.com/google-gemini/gemini-cli/issues/3311

[11] In-Com. (2025). Top COBOL Modernization Vendors in 2025 - 2026. https://www.in-com.com/blog/top-cobol-modernization-vendors-in-2025-2026-from-legacy-to-cloud/

---

## Appendices

### Appendix A: Complete Task Listings

See \texttt{docs/deep-dive/} directory for complete micro-task breakdowns for each macro step.

### Appendix B: Agent Skill Definitions

See \texttt{docs/agent-skills/} directory for detailed skill specifications for each agent type.

### Appendix C: Sample COBOL Programs

See \texttt{cobol-source/samples/} directory for example COBOL programs used in testing.

### Appendix D: Generated Code Examples

See \texttt{java-output/examples/} directory for sample Spring Boot microservices generated by the framework.

# Legacy Modernization Agents: COBOL to Spring Boot Migration

**Scala 3 + ZIO 2.x Effect-Oriented Programming Implementation**

**Version:** 1.0.0  
**Author:** Engineering Team  
**Date:** February 5, 2026  
**Status:** Initial Design

---

## Executive Summary

This project implements an AI-powered legacy modernization framework for migrating COBOL mainframe applications to modern Spring Boot microservices using Scala 3 and ZIO 2.x. The system leverages Google Gemini CLI in non-interactive mode to orchestrate specialized AI agents that perform analysis, transformation, and code generation tasks[1].

### Key Objectives

\begin{itemize}
\item Automate COBOL-to-Java Spring Boot migration with minimal manual intervention
\item Preserve business logic integrity through multi-phase validation
\item Generate production-ready microservices with modern architectural patterns
\item Provide comprehensive documentation and traceability throughout migration
\item Enable team collaboration through agent-based task decomposition
\end{itemize}

### Technology Stack

\begin{itemize}
\item \textbf{Core Language:} Scala 3 with latest syntax and features
\item \textbf{Effect System:} ZIO 2.x for functional, composable effects
\item \textbf{AI Engine:} Google Gemini CLI (non-interactive mode)
\item \textbf{Target Platform:} Spring Boot microservices (Java 17+)
\item \textbf{Build Tool:} sbt 1.9+
\item \textbf{Testing:} ZIO Test framework
\end{itemize}

### Architecture Principles

This implementation follows Effect-Oriented Programming (EOP) principles, treating all side effects (AI calls, file I/O, logging) as managed effects within the ZIO ecosystem. The system is designed for composability, testability, and observability.

---

## Table of Contents

\begin{enumerate}
\item Project Overview
\item Architecture and Design
\item Agent Ecosystem
\item Macro Steps and Workflows
\item Deep-Dive Task Breakdown
\item Agent Skill Definitions
\item Progress Tracking Framework
\item ADRs (Architecture Decision Records)
\item Findings and Lessons Learned
\item Getting Started
\item References
\end{enumerate}

---

## 1. Project Overview

### 1.1 Problem Statement

Legacy COBOL systems represent decades of accumulated business logic in financial, insurance, and government sectors. These systems face critical challenges[5]:

\begin{itemize}
\item Shrinking pool of COBOL developers
\item High operational costs on mainframe infrastructure
\item Difficulty integrating with modern cloud-native ecosystems
\item Limited agility for business changes and innovation
\end{itemize}

### 1.2 Solution Approach

Our framework decomposes the migration into distinct phases, each handled by specialized AI agents orchestrated through ZIO effects:

\begin{table}
\begin{tabular}{|l|l|l|}
\hline
\textbf{Phase} & \textbf{Primary Agent} & \textbf{Output} \\
\hline
Discovery & CobolDiscoveryAgent & File inventory, dependencies \\
Analysis & CobolAnalyzerAgent & Structured analysis JSON \\
Mapping & DependencyMapperAgent & Dependency graph \\
Transformation & JavaTransformerAgent & Spring Boot code \\
Validation & ValidationAgent & Test results, reports \\
Documentation & DocumentationAgent & Technical docs \\
\hline
\end{tabular}
\caption{Migration phases and responsible agents}
\end{table}

### 1.3 Expected Outcomes

\begin{itemize}
\item Functional Spring Boot microservices equivalent to COBOL programs
\item Comprehensive dependency mapping and architecture documentation
\item Unit and integration tests for generated code
\item Migration reports with metrics and quality indicators
\item Reusable agent framework for future migrations
\end{itemize}

---

## 2. Architecture and Design

### 2.1 System Architecture

\begin{figure}
\centering
\includegraphics[width=0.9\textwidth]{architecture-diagram.png}
\caption{High-level system architecture showing agent orchestration}
\label{fig:architecture}
\end{figure}

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

\begin{itemize}
\item \textbf{ZIO[R, E, A]:} Core effect type representing computation requiring environment R, failing with E, or succeeding with A
\item \textbf{ZLayer:} Dependency injection for services
\item \textbf{ZIO Streams:} Processing large COBOL codebases incrementally
\item \textbf{ZIO Test:} Property-based and effect-based testing
\item \textbf{Ref and Queue:} Concurrent state management
\end{itemize}

Example effect signature for COBOL analysis:

def analyzeCobol(file: CobolFile): ZIO[GeminiService & Logger, AnalysisError, CobolAnalysis]

### 2.3 Gemini CLI Integration Strategy

Google Gemini CLI supports non-interactive mode for automation[6][10]:

# Non-interactive invocation
gemini -p "Analyze this COBOL code: $(cat program.cbl)" --json-output

Our ZIO wrapper provides:

\begin{itemize}
\item Process execution with streaming output
\item Timeout handling
\item Retry logic with exponential backoff
\item Response parsing and validation
\item Cost tracking and rate limiting
\end{itemize}

### 2.4 Project Structure

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
│   └── deep-dive/              # Detailed task breakdowns
├── cobol-source/               # Input COBOL files
├── java-output/                # Generated Spring Boot code
├── reports/                    # Migration reports
└── README.md

---

## 3. Agent Ecosystem

### 3.1 Agent Architecture

Each agent is a self-contained ZIO service with:

\begin{itemize}
\item Defined input/output contracts
\item Specialized prompt templates
\item Context management capabilities
\item Error handling strategies
\item Performance metrics collection
\end{itemize}

### 3.2 Core Agent Types

#### 3.2.1 CobolDiscoveryAgent

**Purpose:** Scan and catalog COBOL source files and copybooks.

**Responsibilities:**
\begin{itemize}
\item Traverse directory structures
\item Identify .cbl, .cpy, .jcl files
\item Extract metadata (file size, last modified, encoding)
\item Build initial file inventory
\end{itemize}

**Interactions:**
- Output consumed by: CobolAnalyzerAgent, DependencyMapperAgent

#### 3.2.2 CobolAnalyzerAgent

**Purpose:** Deep structural analysis of COBOL programs using AI.

**Responsibilities:**
\begin{itemize}
\item Parse COBOL divisions (IDENTIFICATION, ENVIRONMENT, DATA, PROCEDURE)
\item Extract variables, data structures, and types
\item Identify control flow (IF, PERFORM, GOTO statements)
\item Detect copybook dependencies
\item Generate structured analysis JSON
\end{itemize}

**Interactions:**
- Input from: CobolDiscoveryAgent
- Output consumed by: JavaTransformerAgent, DependencyMapperAgent

#### 3.2.3 DependencyMapperAgent

**Purpose:** Map relationships between COBOL programs and copybooks.

**Responsibilities:**
\begin{itemize}
\item Analyze COPY statements and program calls
\item Build dependency graph
\item Calculate complexity metrics
\item Generate Mermaid diagrams
\item Identify shared copybooks as service candidates
\end{itemize}

**Interactions:**
- Input from: CobolDiscoveryAgent, CobolAnalyzerAgent
- Output consumed by: JavaTransformerAgent, DocumentationAgent

#### 3.2.4 JavaTransformerAgent

**Purpose:** Transform COBOL programs into Spring Boot microservices.

**Responsibilities:**
\begin{itemize}
\item Convert COBOL data structures to Java classes/records
\item Transform PROCEDURE DIVISION to service methods
\item Generate Spring Boot annotations and configurations
\item Implement REST endpoints for program entry points
\item Create Spring Data JPA entities from file definitions
\item Handle error scenarios with try-catch blocks
\end{itemize}

**Interactions:**
- Input from: CobolAnalyzerAgent, DependencyMapperAgent
- Output consumed by: ValidationAgent, DocumentationAgent

#### 3.2.5 ValidationAgent

**Purpose:** Validate generated Spring Boot code for correctness.

**Responsibilities:**
\begin{itemize}
\item Generate unit tests using JUnit 5
\item Create integration tests for REST endpoints
\item Validate business logic preservation
\item Check compilation and static analysis
\item Generate test coverage reports
\end{itemize}

**Interactions:**
- Input from: JavaTransformerAgent
- Output consumed by: DocumentationAgent

#### 3.2.6 DocumentationAgent

**Purpose:** Generate comprehensive migration documentation.

**Responsibilities:**
\begin{itemize}
\item Create technical design documents
\item Generate API documentation
\item Document data model mappings
\item Produce migration summary reports
\item Create deployment guides
\end{itemize}

**Interactions:**
- Input from: All agents
- Output: Final documentation deliverables

### 3.3 Agent Interaction Patterns

\begin{figure}
\centering
\includegraphics[width=0.9\textwidth]{agent-interactions.png}
\caption{Agent interaction and data flow diagram}
\label{fig:interactions}
\end{figure}

Agents communicate through typed messages and shared state managed by ZIO Ref and Queue:

case class AgentMessage(
  id: String,
  sourceAgent: AgentType,
  targetAgent: AgentType,
  payload: Json,
  timestamp: Instant
)

---

## 4. Macro Steps and Workflows

### 4.1 Migration Pipeline Overview

The migration follows six macro steps executed sequentially:

\begin{enumerate}
\item \textbf{Step 1: Discovery and Inventory}
\item \textbf{Step 2: Deep Analysis}
\item \textbf{Step 3: Dependency Mapping}
\item \textbf{Step 4: Code Transformation}
\item \textbf{Step 5: Validation and Testing}
\item \textbf{Step 6: Documentation Generation}
\end{enumerate}

### 4.2 Step 1: Discovery and Inventory

**Duration Estimate:** 5-10 minutes for typical codebase

**Inputs:**
\begin{itemize}
\item COBOL source directory path
\item Include/exclude patterns
\end{itemize}

**Process:**
\begin{enumerate}
\item Scan directory tree for COBOL files
\item Extract file metadata
\item Categorize files (programs vs copybooks vs JCL)
\item Generate inventory JSON
\end{enumerate}

**Outputs:**
\begin{itemize}
\item \texttt{inventory.json} - Complete file catalog
\item \texttt{discovery-report.md} - Human-readable summary
\end{itemize}

**Success Criteria:**
- All COBOL files discovered
- No permission errors
- Inventory contains accurate metadata

### 4.3 Step 2: Deep Analysis

**Duration Estimate:** 30-60 minutes for 100 programs

**Inputs:**
\begin{itemize}
\item File inventory from Step 1
\item COBOL source files
\end{itemize}

**Process:**
\begin{enumerate}
\item For each COBOL file, invoke CobolAnalyzerAgent
\item Extract structural information using Gemini AI
\item Parse AI response into structured JSON
\item Store analysis results
\end{enumerate}

**Outputs:**
\begin{itemize}
\item \texttt{analysis/<filename>.json} - Per-file analysis
\item \texttt{analysis-summary.json} - Aggregated statistics
\end{itemize}

**Success Criteria:**
- All files analyzed successfully
- Structured data validated against schema
- No AI invocation failures

### 4.4 Step 3: Dependency Mapping

**Duration Estimate:** 10-20 minutes

**Inputs:**
\begin{itemize}
\item File inventory
\item Analysis results from Step 2
\end{itemize}

**Process:**
\begin{enumerate}
\item Extract COPY statements and program calls
\item Build directed dependency graph
\item Calculate complexity metrics
\item Generate Mermaid diagram
\item Identify service boundaries
\end{enumerate}

**Outputs:**
\begin{itemize}
\item \texttt{dependency-map.json} - Graph representation
\item \texttt{dependency-diagram.md} - Mermaid visualization
\item \texttt{service-candidates.json} - Recommended microservice boundaries
\end{itemize}

**Success Criteria:**
- Complete dependency graph
- No orphaned nodes
- Service boundaries identified

### 4.5 Step 4: Code Transformation

**Duration Estimate:** 60-120 minutes for 100 programs

**Inputs:**
\begin{itemize}
\item Analysis results
\item Dependency map
\item Transformation templates
\end{itemize}

**Process:**
\begin{enumerate}
\item For each COBOL program, invoke JavaTransformerAgent
\item Generate Spring Boot project structure
\item Create domain models from DATA DIVISION
\item Transform procedures to service methods
\item Generate REST controllers and configurations
\item Apply Spring annotations
\end{enumerate}

**Outputs:**
\begin{itemize}
\item \texttt{java-output/<package>/} - Spring Boot projects
\item \texttt{transformation-report.json} - Transformation metrics
\end{itemize}

**Success Criteria:**
- All programs transformed
- Generated code compiles
- Spring Boot conventions followed

### 4.6 Step 5: Validation and Testing

**Duration Estimate:** 30-45 minutes

**Inputs:**
\begin{itemize}
\item Generated Spring Boot code
\item Original COBOL analysis
\end{itemize}

**Process:**
\begin{enumerate}
\item Generate unit tests for each service
\item Create integration tests for REST endpoints
\item Validate business logic preservation
\item Run static analysis tools
\item Generate coverage reports
\end{enumerate}

**Outputs:**
\begin{itemize}
\item \texttt{tests/<package>/} - Generated test suites
\item \texttt{validation-report.json} - Test results and coverage
\end{itemize}

**Success Criteria:**
- All tests generated and passing
- Minimum 70\% code coverage
- No critical static analysis violations

### 4.7 Step 6: Documentation Generation

**Duration Estimate:** 15-20 minutes

**Inputs:**
\begin{itemize}
\item All previous outputs
\item Migration metadata
\end{itemize}

**Process:**
\begin{enumerate}
\item Aggregate data from all phases
\item Generate technical design documents
\item Create API documentation
\item Produce migration summary
\item Generate deployment guides
\end{enumerate}

**Outputs:**
\begin{itemize}
\item \texttt{docs/technical-design.md}
\item \texttt{docs/api-reference.md}
\item \texttt{docs/migration-summary.md}
\item \texttt{docs/deployment-guide.md}
\end{itemize}

**Success Criteria:**
- Complete documentation set
- All diagrams rendered
- No broken references

---

## 5. Deep-Dive Task Breakdown

This section outlines the micro-tasks for each macro step, structured for assignment to AI coding agents (Claude, GitHub Copilot, OpenAI Codex).

### 5.1 Task Format

Each task follows this structure:

\begin{itemize}
\item \textbf{Task ID:} Unique identifier
\item \textbf{Title:} Brief description
\item \textbf{Agent Type:} Recommended AI agent
\item \textbf{Dependencies:} Required prior tasks
\item \textbf{Inputs:} Required artifacts/context
\item \textbf{Outputs:} Expected deliverables
\item \textbf{Acceptance Criteria:} Definition of done
\item \textbf{Complexity:} Low/Medium/High
\item \textbf{Estimated Effort:} Time estimate
\end{itemize}

### 5.2 Deep-Dive Folders

Detailed task breakdowns are organized in separate markdown files:

\begin{itemize}
\item \texttt{docs/deep-dive/01-discovery-tasks.md}
\item \texttt{docs/deep-dive/02-analysis-tasks.md}
\item \texttt{docs/deep-dive/03-dependency-mapping-tasks.md}
\item \texttt{docs/deep-dive/04-transformation-tasks.md}
\item \texttt{docs/deep-dive/05-validation-tasks.md}
\item \texttt{docs/deep-dive/06-documentation-tasks.md}
\end{itemize}

See Appendix A for complete task listings.

---

## 6. Agent Skill Definitions

### 6.1 Skill Definition Format

Each agent type has a corresponding skill definition markdown file specifying:

\begin{itemize}
\item Core competencies
\item Knowledge domains
\item Interaction protocols
\item Error handling strategies
\item Performance requirements
\end{itemize}

### 6.2 Agent Skill Files

\begin{itemize}
\item \texttt{docs/agent-skills/cobol-discovery-agent-skill.md}
\item \texttt{docs/agent-skills/cobol-analyzer-agent-skill.md}
\item \texttt{docs/agent-skills/dependency-mapper-agent-skill.md}
\item \texttt{docs/agent-skills/java-transformer-agent-skill.md}
\item \texttt{docs/agent-skills/validation-agent-skill.md}
\item \texttt{docs/agent-skills/documentation-agent-skill.md}
\end{itemize}

See Appendix B for complete skill definitions.

---

## 7. Progress Tracking Framework

### 7.1 Progress Tracking Files

The project includes structured progress tracking:

\begin{itemize}
\item \texttt{docs/progress/overall-progress.md} - High-level status dashboard
\item \texttt{docs/progress/step-01-discovery-progress.md}
\item \texttt{docs/progress/step-02-analysis-progress.md}
\item \texttt{docs/progress/step-03-dependency-mapping-progress.md}
\item \texttt{docs/progress/step-04-transformation-progress.md}
\item \texttt{docs/progress/step-05-validation-progress.md}
\item \texttt{docs/progress/step-06-documentation-progress.md}
\end{itemize}

### 7.2 Progress Metrics

Each step tracks:

\begin{table}
\begin{tabular}{|l|l|}
\hline
\textbf{Metric} & \textbf{Description} \\
\hline
Status & Not Started / In Progress / Complete / Blocked \\
Completion \% & 0-100\% progress indicator \\
Files Processed & Count of files handled \\
Success Rate & Percentage of successful operations \\
Duration & Actual time spent \\
Blockers & Current impediments \\
\hline
\end{tabular}
\caption{Progress tracking metrics}
\end{table}

### 7.3 Automated Progress Updates

Progress tracking integrates with the ZIO effect system:

def trackProgress(step: MigrationStep, status: Status): ZIO[ProgressTracker, Nothing, Unit]

Progress updates are automatically written to markdown files using ZIO Streams.

---

## 8. Architecture Decision Records (ADRs)

### 8.1 ADR Overview

ADRs document significant architectural decisions and their rationale. Each ADR follows the format:

\begin{itemize}
\item Title
\item Status (Proposed / Accepted / Deprecated / Superseded)
\item Context
\item Decision
\item Consequences
\item Alternatives Considered
\end{itemize}

### 8.2 Key ADRs

\begin{enumerate}
\item \textbf{ADR-001:} Use Scala 3 + ZIO 2.x for Implementation
\item \textbf{ADR-002:} Adopt Google Gemini CLI for AI Operations
\item \textbf{ADR-003:} Target Spring Boot for Microservice Generation
\item \textbf{ADR-004:} Use Effect-Oriented Programming Pattern
\item \textbf{ADR-005:} Implement Agent-Based Architecture
\item \textbf{ADR-006:} Store State in JSON Files vs Database
\item \textbf{ADR-007:} Generate Mermaid Diagrams for Visualization
\item \textbf{ADR-008:} Use ZIO Test for Testing Framework
\end{enumerate}

See \texttt{docs/adr/} directory for complete ADR documents.

---

## 9. Findings and Lessons Learned

### 9.1 Technical Findings

**Finding 1: COBOL Complexity Varies Significantly**

Analysis of legacy codebases reveals 3 complexity tiers[5]:
\begin{itemize}
\item Tier 1 (30\%): Simple batch programs, straightforward transformation
\item Tier 2 (50\%): Moderate complexity with file I/O and business rules
\item Tier 3 (20\%): High complexity with embedded SQL, CICS transactions, extensive copybook dependencies
\end{itemize}

**Finding 2: Gemini CLI Performance**

Non-interactive Gemini CLI provides excellent throughput[6][10]:
\begin{itemize}
\item Average response time: 3-8 seconds per COBOL program analysis
\item Token efficiency: Better context utilization than REST API
\item Cost effectiveness: Reduced API overhead
\end{itemize}

**Finding 3: ZIO Benefits for Agent Orchestration**

Effect-oriented programming with ZIO delivers measurable advantages:
\begin{itemize}
\item Type-safe error handling reduces runtime failures
\item Composable effects enable clean separation of concerns
\item Built-in retry and timeout mechanisms improve reliability
\item ZIO Test simplifies testing of effectful code
\end{itemize}

### 9.2 Migration Patterns

**Pattern 1: COBOL to Java Mappings**

Common transformation patterns identified[7][9]:

\begin{table}
\begin{tabular}{|l|l|}
\hline
\textbf{COBOL Construct} & \textbf{Spring Boot Equivalent} \\
\hline
DATA DIVISION & Java records / POJOs \\
PROCEDURE DIVISION & Service methods \\
COPY statements & Shared DTOs / Spring beans \\
FILE section & Spring Data JPA entities \\
DB2 EXEC SQL & Spring Data repositories \\
PERFORM loops & Java for/while loops \\
CALL programs & Service method invocations \\
\hline
\end{tabular}
\caption{COBOL to Spring Boot transformation mappings}
\end{table}

**Pattern 2: Microservice Boundary Identification**

Effective service boundaries correlate with[9]:
\begin{itemize}
\item COBOL program cohesion
\item Copybook sharing patterns
\item Transaction boundaries
\item Business domain alignment
\end{itemize}

### 9.3 Lessons Learned

**Lesson 1: Iterative Validation is Critical**

Continuous validation after each transformation step prevents error accumulation. Implement checkpoint-based recovery.

**Lesson 2: Prompt Engineering Matters**

Agent effectiveness depends heavily on prompt quality. Invest time in prompt templates with examples.

**Lesson 3: Human Review Checkpoints Required**

Fully automated migration is aspirational. Plan for human review at key decision points.

---

## 10. Getting Started

### 10.1 Prerequisites

\begin{itemize}
\item Scala 3.3+ and sbt 1.9+
\item Java 17+ (for running generated Spring Boot code)
\item Google Gemini CLI installed and configured
\item Docker (optional, for containerized deployment)
\end{itemize}

### 10.2 Installation

# Clone repository
git clone <repository-url>
cd legacy-modernization-agents

# Install dependencies
sbt update

# Configure Gemini CLI
export GEMINI_API_KEY="your-api-key"

# Verify installation
sbt test

### 10.3 Configuration

Edit \texttt{src/main/resources/application.conf}:

gemini {
  model = "gemini-2.0-flash"
  max-tokens = 32768
  temperature = 0.1
  timeout = 300s
}

migration {
  cobol-source = "cobol-source"
  java-output = "java-output"
  reports-dir = "reports"
}

### 10.4 Running a Migration

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

### 10.5 Testing Generated Code

# Navigate to generated Spring Boot project
cd java-output/com/example/customer-service

# Run tests
./mvnw test

# Start application
./mvnw spring-boot:run

---

## 11. References

[1] Microsoft. (2025). How We Use AI Agents for COBOL Migration and Mainframe Modernization. https://devblogs.microsoft.com/all-things-azure/how-we-use-ai-agents-for-cobol-migration-and-mainframe-modernization/

[2] Azure Samples. (2025). Legacy-Modernization-Agents: AI-powered COBOL to Java Quarkus modernization agents. GitHub. https://github.com/Azure-Samples/Legacy-Modernization-Agents

[3] Microsoft. (2025). AI Agents Are Rewriting the App Modernization Playbook. Microsoft Tech Community. https://techcommunity.microsoft.com/blog/appsonazureblog/ai-agents-are-rewriting-the-app-modernization-playbook/4470162

[4] Google. (2025). Hands-on with Gemini CLI. Google Codelabs. https://codelabs.developers.google.com/gemini-cli-hands-on

[5] Ranjan, R. (2025). Modernizing Legacy: AI-Powered COBOL to Java Migration. LinkedIn. https://www.linkedin.com/pulse/modernizing-legacy-ai-powered-cobol-java-migration-rajesh-ranjan-diode

[6] Schmid, P. (2025). Google Gemini CLI Cheatsheet. https://www.philschmid.de/gemini-cli-cheatsheet

[7] Pal, N. (2025). From COBOL to Java Spring Boot: My Modernization Journey. LinkedIn. https://www.linkedin.com/posts/neha-pal-98372520a_cobol-java-springboot-activity-7378860036376317952-ec0O

[8] YouTube. (2025). Legacy Code Modernization with Multi AI Agents. https://www.youtube.com/watch?v=iDGWqkLotOs

[9] Azure. (2025). Modernize your mainframe and midrange workloads. https://azure.microsoft.com/en-us/solutions/migration/mainframe

[10] GitHub. (2025). Add @file command support for non-interactive cli input. google-gemini/gemini-cli. https://github.com/google-gemini/gemini-cli/issues/3311

[11] In-Com. (2025). Top COBOL Modernization Vendors in 2025 - 2026. https://www.in-com.com/blog/top-cobol-modernization-vendors-in-2025-2026-from-legacy-to-cloud/

---

## Appendices

### Appendix A: Complete Task Listings

See \texttt{docs/deep-dive/} directory for complete micro-task breakdowns for each macro step.

### Appendix B: Agent Skill Definitions

See \texttt{docs/agent-skills/} directory for detailed skill specifications for each agent type.

### Appendix C: Sample COBOL Programs

See \texttt{cobol-source/samples/} directory for example COBOL programs used in testing.

### Appendix D: Generated Code Examples

See \texttt{java-output/examples/} directory for sample Spring Boot microservices generated by the framework.
