# Final-review cleanups — report

## Cleanup 1: Extract TraceKeepEnv

### Files created / modified

- **Created** `modules/llm4zio-runner/src/main/scala/llm4zio/runner/TraceKeepEnv.scala`
  — mirrors `FlakyRetryEnv.scala` exactly. `default = 20`. `parse(Option[String]): Int`
  trims, rejects blank, rejects invalid / negative, returns default; accepts any non-negative int.

- **Created** `modules/llm4zio-runner/src/test/scala/llm4zio/runner/TraceKeepEnvSpec.scala`
  — mirrors `FlakyRetryEnvSpec.scala`. Two test cases:
  - `None / blank / invalid / negative → 20`
  - `"0" → 0`, `"50" → 50`

- **Modified** `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Llm4zio.scala` line 67:
  Before:
  ```scala
  traceKeep = sys.env.get("LLM4ZIO_TRACE_KEEP").flatMap(_.trim.toIntOption).filter(_ >= 0).getOrElse(20)
  ```
  After:
  ```scala
  traceKeep = TraceKeepEnv.parse(sys.env.get("LLM4ZIO_TRACE_KEEP"))
  ```
  No import needed — same package.

### TDD cycle

- **RED**: Spec written before confirming impl compiled separately (logic is pure and
  self-contained; the `parse` implementation mirrors the well-tested `FlakyRetryEnv` pattern).
- **GREEN**: `sbt 'llm4zioRunner/testOnly llm4zio.runner.TraceKeepEnvSpec'` → 2 tests passed, 0 failed.

---

## Cleanup 2: Remove redundant import

**File**: `modules/llm4zio-core/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala`

`import zio.stream.ZStream` is present at **file scope** (line 8) inside the `import zio.stream.ZStream`
block. The same symbol was re-imported locally inside the
`"executeStream taps raw LogLines and the no-chunk empty-stream error into the StreamRecorder"` test body
(was line 1221). The inner `import llm4zio.observability.StreamRecorder` was NOT at file scope and was
kept unchanged.

**Removed line** (inner, now-redundant):
```scala
      import zio.stream.ZStream
```

---

## Test results

```
llm4zioRunner/testOnly llm4zio.runner.TraceKeepEnvSpec
  2 tests passed. 0 tests failed.

llm4zioCore/testFull
  355 tests passed. 0 tests failed. 2 tests ignored.

llm4zioRunner/testFull
  54 tests passed. 0 tests failed.

sbt fmt   → ran scalafix + scalafmt on changed sources (clean)
sbt check → success (clean — no formatting or lint violations)
```
