# 1. Build the library on ZIO as the single effect system

- Status: Accepted

## Context
The library must express LLM calls, subprocess execution, streaming, concurrency, and typed errors in one coherent model. It is positioned explicitly as the ZIO counterpart to VirtusLab's orca (which uses Ox/direct-style), so the same values — thin, readable, errors-as-data — had to be re-expressed in an effect system. A consistent async/resource model was needed across HTTP providers, CLI subprocesses, and the orchestration loops.

## Decision
Adopt ZIO 2.1.x (zio, zio-streams) as the pervasive effect system. Every public operation returns ZIO[R,E,A] or Stream[E,A]; the codebase forbids Future and blocking-by-default, requiring blocking work to be wrapped in ZIO.attemptBlocking. State is held in Ref/Queue/Hub rather than var. The single unsafeRun is confined to the script entry point runner/Flow.scala, keeping the rest of the system pure and testable. Supporting libraries are chosen from the ZIO ecosystem: zio-process for subprocesses, zio-json for serialization, zio-http for HTTP and the MCP server, zio-logging, and zio-test.

## Alternatives considered
Ox/direct-style (orca's choice) — rejected because the whole point of llm4zio is to be the ZIO-native counterpart; direct-style would duplicate orca rather than serve the ZIO audience. Cats Effect / Typelevel stack — rejected to keep one ecosystem with first-class streaming, structured concurrency, typed errors, zio-process and zio-test out of the box, avoiding a mixed-stack impedance. Plain Future/Try with manual thread pools — rejected because it lacks interruption, resource safety and a typed error channel, all central to the recoverable-vs-catastrophic discipline.

## Consequences
A uniform, composable model for concurrency (ZIO.foreachPar in the review loop), interruption (Ctrl-C unwinds stages), resources, and streaming, all testable up to one unsafeRun. Contributors must think in ZIO and avoid blocking. A noted gotcha: a wildcard import zio.* pulls in zio.Task, which shadows the flow layer's own Task type, so files naming Task must import specific zio names. The whole stack is coupled to the ZIO ecosystem version set.
