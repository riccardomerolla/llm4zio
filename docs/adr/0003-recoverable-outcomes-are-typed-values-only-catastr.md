# 3. Recoverable outcomes are typed values; only catastrophes fail the effect

- Status: accepted

## Context
Many operations in agentic flows have expected, handleable outcomes that are not errors — a branch may already exist, a commit may have nothing to commit, a CI run may still be pending. Conflating these with genuine failures (network down, process crash) would force defensive try/catch around normal control flow and obscure real problems. This is orca's recoverable-vs-catastrophic split, re-expressed in ZIO.

## Decision
Model expected outcomes as values in the success channel using small enums, and reserve the error channel for genuine failures. GitTool.createBranch returns CreateBranch.{Created,AlreadyExists}; GitTool.commitAll returns Commit.{Committed,NothingToCommit} (GitTool.scala:121-125); GhTool exposes BuildOutcome.{Success,Failure,Pending,TimedOut}. Catastrophic problems become FlowError.Process. The same discipline appears in the LLM layer's structured judgements (Verdict.{Proceed,Blocked}, Triage.{NotABug,Untestable,Testable}).

## Consequences
Callers pattern-match on outcomes as ordinary data, and the type system documents which situations are normal. Real failures stay loud and rare in the error channel. This is a spine convention the whole codebase must follow; adding a new tool means deciding deliberately which outcomes are values and which are failures.
