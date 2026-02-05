# ADR-001: Adoption of Scala 3 and ZIO 2.x for Legacy Modernization Framework

**Status:** Accepted  
**Date:** 2026-02-05  
**Decision Makers:** Architecture Team, Riccardo Merolla  
**Context:** COBOL to Spring Boot Migration Framework Design

---

## Context and Problem Statement

We need to build a legacy modernization framework that:
- Orchestrates multiple AI agents for COBOL analysis and transformation
- Handles complex workflows with side effects (file I/O, AI API calls, logging)
- Ensures type safety, composability, and testability
- Supports concurrent operations and resource management
- Provides observability and error handling

The choice of programming language and effect system is foundational to the project's success.

---

## Decision Drivers

### Functional Requirements
- Manage complex workflows with multiple sequential and parallel steps
- Handle side effects (AI calls, file operations) in a controlled manner
- Support dependency injection and service composition
- Enable comprehensive testing including effect-based tests
- Provide structured concurrency and resource safety

### Non-Functional Requirements
- **Type Safety:** Catch errors at compile time
- **Composability:** Build complex workflows from simple components
- **Observability:** Track execution, metrics, and errors
- **Performance:** Efficient concurrent operations
- **Maintainability:** Clear, expressive code

---

## Considered Options

### Option 1: Scala 3 + ZIO 2.x (Effect-Oriented Programming)
**Description:** Use Scala 3 with ZIO 2.x as the core effect system

**Pros:**
- ✅ **Strong Type System:** Scala 3's improved type inference and union types
- ✅ **Effect Management:** ZIO provides `ZIO[R, E, A]` for typed effects
- ✅ **Dependency Injection:** ZLayer for compile-time-checked DI
- ✅ **Concurrency:** Fiber-based concurrency with structured concurrency
- ✅ **Resource Safety:** Automatic resource management with `ZIO.acquireRelease`
- ✅ **Testing:** ZIO Test framework with property-based testing
- ✅ **Ecosystem:** Rich ecosystem (ZIO HTTP, ZIO JSON, ZIO Logging)

**Cons:**
- ⚠️ Learning curve for developers unfamiliar with functional programming
- ⚠️ Requires understanding of effect systems

### Option 2: Java 17+ with Spring Framework
**Description:** Use Java with Spring Boot for orchestration

**Pros:**
- ✅ Large developer pool familiar with Java/Spring
- ✅ Extensive Spring ecosystem
- ✅ Good IDE support

**Cons:**
- ❌ Limited functional programming support
- ❌ No built-in effect system for managing side effects
- ❌ Less composable than functional approaches
- ❌ Error handling through exceptions (less type-safe)

### Option 3: Kotlin with Coroutines and Arrow
**Description:** Use Kotlin with coroutines for async and Arrow for FP

**Pros:**
- ✅ Modern language with good Java interop
- ✅ Coroutines for structured concurrency
- ✅ Arrow provides functional programming primitives

**Cons:**
- ❌ Arrow's effect system less mature than ZIO
- ❌ Less integrated ecosystem
- ❌ Type inference not as powerful as Scala 3

### Option 4: TypeScript with Effect-TS
**Description:** Use TypeScript with Effect library

**Pros:**
- ✅ Wide developer familiarity
- ✅ Effect-TS provides similar capabilities to ZIO

**Cons:**
- ❌ Weaker type system than Scala
- ❌ Runtime type errors more common
- ❌ Less suitable for complex enterprise systems

---

## Decision Outcome

**Chosen Option:** Scala 3 + ZIO 2.x

### Rationale

1. **Effect-Oriented Programming Alignment:**
   - ZIO's `ZIO[R, E, A]` perfectly models our needs:
     - `R`: Environment (dependencies like GeminiService, Logger)
     - `E`: Error type (typed errors for different failure modes)
     - `A`: Success value (analysis results, generated code)

2. **Composability:**
   ```scala
   val migrationPipeline = for {
     inventory <- discoveryAgent.scan(path)
     analyses  <- ZIO.foreachPar(inventory)(analyzerAgent.analyze)
     graph     <- dependencyMapper.buildGraph(analyses)
     code      <- transformerAgent.generate(analyses, graph)
   } yield code
   ```

3. **Type Safety:**
   - Compile-time verification of dependencies
   - Exhaustive pattern matching
   - No null pointer exceptions

4. **Testability:**
   ```scala
   test("COBOL analysis extracts data structures") {
     for {
       result <- analyzerAgent.analyze(sampleCobol)
     } yield assertTrue(result.dataStructures.nonEmpty)
   }.provide(MockGeminiService.layer)
   ```

5. **Resource Management:**
   ```scala
   ZIO.acquireReleaseWith(
     acquire = openFile(path)
   )(
     release = file => closeFile(file)
   )(
     use = file => processFile(file)
   )
   ```

### Positive Consequences

- ✅ **Reliability:** Type-safe effects prevent many runtime errors
- ✅ **Maintainability:** Clear separation of business logic and effects
- ✅ **Testability:** Easy to test with mock services
- ✅ **Performance:** Efficient fiber-based concurrency
- ✅ **Observability:** Built-in logging and metrics

### Negative Consequences

- ⚠️ **Learning Curve:** Team needs training in functional programming
- ⚠️ **Hiring:** Smaller talent pool compared to Java/Spring
- ⚠️ **Compile Times:** Scala compilation can be slower

### Mitigation Strategies

1. **Training:** Provide ZIO workshops and documentation
2. **Patterns:** Establish common patterns and code examples
3. **Tooling:** Use Metals/IntelliJ with proper configuration
4. **Incremental Adoption:** Start with simple agents, gradually increase complexity

---

## Technical Implementation

### Core Effect Signatures

```scala
// Agent trait
trait Agent[Input, Output] {
  def execute(input: Input): ZIO[GeminiService & Logger, AgentError, Output]
}

// Gemini service
trait GeminiService {
  def query(prompt: String): ZIO[Any, GeminiError, String]
}

// File service
trait FileService {
  def readFile(path: Path): ZIO[Any, FileError, String]
  def writeFile(path: Path, content: String): ZIO[Any, FileError, Unit]
}
```

### Dependency Injection with ZLayer

```scala
object GeminiServiceLive {
  val layer: ZLayer[Any, Nothing, GeminiService] =
    ZLayer {
      for {
        config <- ZIO.config[GeminiConfig]
      } yield GeminiServiceLive(config)
    }
}

// Composition
val appLayer = GeminiServiceLive.layer ++ FileServiceLive.layer ++ LoggerLive.layer
```

---

## Validation and Metrics

### Success Criteria
- ✅ All agents implemented as pure functions with ZIO effects
- ✅ No runtime exceptions from effect mismanagement
- ✅ Test coverage > 80% using ZIO Test
- ✅ Clear error messages from typed errors

### Performance Targets
- Process 100 COBOL files in parallel: < 10 minutes
- Memory usage: < 2GB for standard migration
- Fiber overhead: < 1ms per agent invocation

---

## Related Decisions

- **ADR-002:** Gemini CLI Non-Interactive Mode
- **ADR-004:** Effect-Oriented Programming Principles
- **ADR-008:** ZIO Test Framework Adoption

---

## References

1. [ZIO Official Documentation](https://zio.dev/)
2. [Scala 3 Book](https://docs.scala-lang.org/scala3/book/introduction.html)
3. [Effect-Oriented Programming](https://www.youtube.com/watch?v=gcqWdNI3Qs0)
4. Merolla, R. (2026). "Building AI Agent Systems with ZIO". Personal Blog.

---

**Last Updated:** 2026-02-05  
**Supersedes:** None  
**Superseded By:** None
