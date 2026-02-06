# ADR-002: Adoption of Google Gemini CLI in Non-Interactive Mode

**Status:** Accepted  
**Date:** 2026-02-05  
**Decision Makers:** Architecture Team, Riccardo Merolla  
**Context:** AI Engine Selection for COBOL Analysis and Transformation

---

## Context and Problem Statement

The legacy modernization framework requires an AI engine to:
- Analyze COBOL source code structure and semantics
- Generate transformation plans
- Produce Spring Boot Java code
- Validate business logic preservation
- Generate technical documentation

We need to select an AI provider and integration method that balances:
- **Accuracy:** High-quality code analysis and generation
- **Cost:** Reasonable pricing for enterprise-scale migrations
- **Reliability:** Stable API with good uptime
- **Automation:** Support for non-interactive batch processing
- **Context Window:** Large enough to process entire COBOL programs

---

## Decision Drivers

### Functional Requirements
- Process COBOL files ranging from 500-5000 lines
- Handle batch operations (100+ files)
- Parse structured output (JSON preferred)
- Support long-running operations (minutes per file)
- Enable prompt engineering and fine-tuning

### Non-Functional Requirements
- **Cost Efficiency:** Minimize token usage costs
- **Latency:** Response time < 30 seconds per file
- **Reliability:** 99%+ uptime SLA
- **Observability:** Track token usage and costs
- **Security:** Secure handling of proprietary COBOL code

---

## Considered Options

### Option 1: Google Gemini CLI (Non-Interactive Mode) ✅

**Description:** Use Google Gemini CLI with `--json-output` flag for automation

**Pros:**
- ✅ **Large Context Window:** 32K-128K tokens (sufficient for most COBOL programs)
- ✅ **Non-Interactive Mode:** Full automation support with CLI flags
- ✅ **Cost-Effective:** Competitive pricing vs OpenAI
- ✅ **JSON Output:** Structured responses with `--json-output`
- ✅ **Local Installation:** No REST API overhead
- ✅ **Streaming Support:** Process large outputs incrementally
- ✅ **Prompt Caching:** Reduce costs for repeated patterns

**Cons:**
- ⚠️ Requires local CLI installation
- ⚠️ Less ecosystem maturity than OpenAI

**Implementation:**
```bash
gemini -p "Analyze this COBOL: $(cat program.cbl)" \
  --model gemini-2.5-flash \
  --json-output \
  --max-tokens 32768
```

Option 2: OpenAI API (GPT-4)
Description: Use OpenAI REST API with GPT-4 or GPT-4-turbo

Pros:

- ✅ Mature ecosystem and libraries
- ✅ Strong code generation capabilities
- ✅ Good documentation

Cons:

- ❌ Higher costs (2-3x Gemini for similar quality)
- ❌ REST API overhead for each request
- ❌ Smaller context window (8K-32K base, 128K turbo)
- ❌ Rate limiting more restrictive

Option 3: Anthropic Claude (REST API)
Description: Use Claude 3 via REST API

Pros:

- ✅ Very large context window (200K tokens)
- ✅ Strong reasoning capabilities
- ✅ Good for code analysis

Cons:

- ❌ No CLI tool - REST API only
- ❌ Higher pricing tier
- ❌ Limited availability in some regions

Option 4: Local LLMs (Llama 3, Code Llama)
Description: Self-hosted open-source models

Pros:

- ✅ No API costs
- ✅ Full data privacy
- ✅ No rate limits

Cons:

- ❌ Requires expensive GPU infrastructure
- ❌ Lower quality than commercial models
-❌ Operational complexity

Decision Outcome
Chosen Option: Google Gemini CLI (Non-Interactive Mode)

Rationale
Cost-Effectiveness:

Gemini 2.0 Flash: $0.075/1M input tokens, $0.30/1M output tokens

OpenAI GPT-4 Turbo: $10/1M input, $30/1M output

40x cheaper for production workloads

Non-Interactive Automation:

bash
# Perfect for ZIO process execution
gemini -p "$PROMPT" --json-output --model gemini-2.5-flash
Context Window:

Gemini 2.0: 32,768 tokens standard, up to 128K extended

Sufficient for 90% of COBOL programs

Larger programs can be chunked

JSON Structured Output:

json
{
  "analysis": {
    "divisions": [...],
    "dataStructures": [...],
    "dependencies": [...]
  }
}
Local CLI Benefits:

No REST API latency

Streaming output for large responses

Easy integration with ZIO Process

ZIO Integration Pattern
scala
trait GeminiService {
  def query(
    prompt: String,
    model: String = "gemini-2.5-flash",
    maxTokens: Int = 32768
  ): ZIO[Any, GeminiError, String]
}

object GeminiServiceLive {
  val layer: ZLayer[Any, Nothing, GeminiService] = 
    ZLayer.succeed {
      new GeminiService {
        def query(prompt: String, model: String, maxTokens: Int) =
          for {
            process <- ZIO.attempt {
              Process(
                "gemini",
                "-p", prompt,
                "--model", model,
                "--json-output",
                "--max-tokens", maxTokens.toString
              )
            }.mapError(GeminiError.ProcessError.apply)
            output <- ZIO.attemptBlocking(process.!!.trim)
              .mapError(GeminiError.ExecutionError.apply)
              .timeoutFail(GeminiError.Timeout)(300.seconds)
          } yield output
      }
    }
}
Positive Consequences
- ✅ 40x cost savings compared to OpenAI
- ✅ Simple integration with ZIO Process
- ✅ High throughput for batch operations
- ✅ Reliable structured output with JSON mode
- ✅ Minimal infrastructure (CLI only)

Negative Consequences
- ⚠️ CLI Dependency: Requires Gemini CLI installation
- ⚠️ Version Management: Need to track CLI versions
- ⚠️ Limited to Google Cloud: Vendor lock-in to some degree

Mitigation Strategies
CLI Installation: Docker image with pre-installed Gemini CLI

Version Pinning: Lock to specific Gemini CLI version

Abstraction Layer: Agent interface allows swapping AI providers

Fallback Strategy: Option to use REST API if CLI fails

Technical Implementation
CLI Invocation from Scala/ZIO
scala
case class GeminiConfig(
  model: String = "gemini-2.5-flash",
  maxTokens: Int = 32768,
  temperature: Double = 0.1,
  timeout: Duration = 300.seconds
)

def executeGeminiCLI(
  prompt: String,
  config: GeminiConfig
): ZIO[Any, GeminiError, String] = {
  val command = Seq(
    "gemini",
    "-p", prompt,
    "--model", config.model,
    "--max-tokens", config.maxTokens.toString,
    "--temperature", config.temperature.toString,
    "--json-output"
  )
  
  ZIO.attemptBlocking {
    import scala.sys.process._
    command.!!
  }.timeout(config.timeout)
    .someOrFail(GeminiError.Timeout)
    .mapError {
      case e: GeminiError => e
      case e: Throwable => GeminiError.ExecutionError(e.getMessage)
    }
}
Prompt Engineering for COBOL Analysis
scala
def buildCobolAnalysisPrompt(cobolCode: String): String = s"""
Analyze the following COBOL program and return a JSON object with this structure:

{
  "program_name": "string",
  "divisions": {
    "identification": {...},
    "environment": {...},
    "data": {...},
    "procedure": {...}
  },
  "data_structures": [...],
  "dependencies": [...],
  "complexity": "LOW|MEDIUM|HIGH"
}

COBOL Code:
```cobol
$cobolCode
Respond ONLY with valid JSON, no markdown formatting.
"""

text

---

## Performance Metrics

### Expected Performance (Gemini 2.0 Flash)
- **Average Latency:** 3-8 seconds per COBOL file
- **Throughput:** 10-15 files/minute sequential
- **Token Usage:** ~2000 tokens input, ~1500 tokens output per file
- **Cost per File:** ~$0.0006 (less than 1 cent)

### Cost Projection for 1000 COBOL Programs
- **Input Tokens:** 2M tokens × $0.075/1M = $0.15
- **Output Tokens:** 1.5M tokens × $0.30/1M = $0.45
- **Total Cost:** ~$0.60 for complete migration

---

## Related Decisions

- **ADR-001:** Scala 3 + ZIO 2.x (provides Process integration)
- **ADR-006:** JSON for State Storage (matches Gemini JSON output)

---

## References

1. [Google Gemini CLI Documentation](https://cloud.google.com/gemini/docs/cli)
2. [Gemini Pricing](https://cloud.google.com/gemini/pricing)
3. [ZIO Process Integration](https://zio.dev/reference/core/process/)

---

**Last Updated:** 2026-02-05  
**Supersedes:** None  
**Superseded By:** None