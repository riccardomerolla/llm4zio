# 3. Recoverable outcomes are typed values; only catastrophes fail the effect

- Status: Accepted

## Context
Many operations in agentic flows have expected, handleable outcomes that are not errors — a branch may already exist, a commit may have nothing to commit, a CI run may still be pending. Conflating these with genuine failures (network down, process crash) would force defensive try/catch around normal control flow and obscure real problems. This is orca's recoverable-vs-catastrophic split, re-expressed in ZIO.

## Decision
Model expected outcomes as values in the success channel using small enums, and reserve the error channel for genuine failures. GitTool.createBranch returns CreateBranch.{Created,AlreadyExists}; GitTool.commitAll returns Commit.{Committed,NothingToCommit} (GitTool.scala:121-125); GhTool exposes BuildOutcome.{Success,Failure,Pending,TimedOut}. Catastrophic problems become FlowError.Process. The same discipline appears in the LLM layer's structured judgements (Verdict.{Proceed,Blocked}, Triage.{NotABug,Untestable,Testable}).

## Alternatives considered
Put every non-success in the error channel and recover with catchSome/catchAll — rejected because it forces defensive error handling around ordinary control flow and makes normal outcomes indistinguishable from real failures. Return raw booleans or exit codes — rejected as untyped and self-documenting only by convention; the enum names (AlreadyExists, NothingToCommit) carry the meaning. Throw exceptions for these cases — rejected outright as it both violates the typed-error rule and hides the expected nature of the outcome.

## Consequences
Callers pattern-match on outcomes as ordinary data, and the type system documents which situations are normal. Real failures stay loud and rare in the error channel. This is a spine convention the whole codebase must follow; adding a new tool means deciding deliberately which outcomes are values and which are failures.
