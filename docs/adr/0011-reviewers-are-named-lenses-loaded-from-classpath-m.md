# 11. Reviewers are named lenses loaded from classpath Markdown, fanned out and converged in a loop

- Status: Accepted

## Context
Code review of an AI-produced diff benefits from multiple independent perspectives (functionality, tests, readability, structure, performance, security, Scala/ZIO idioms) and from feeding findings back to the coder until clean. Hard-coding prompts in Scala would make lenses hard to edit, and a single monolithic review would lose the distinct perspectives. Rate-limited backends also need throttling when many reviews run at once.

## Decision
Model a Reviewer as a named lens — a system prompt plus an optional changed-file regex scope — loaded from classpath Markdown under resources/llm4zio/review/reviewers/*.md (Reviewer.scala), with structured ReviewResult/ReviewIssue/Severity outputs. reviewAndFixLoop (LlmReview.scala:151) runs an optional lint gate first (failure short-circuits and skips LLM reviewers), runs format before each round, selects reviewers via a ReviewerSelector policy (allEveryRound/whileDirty/llmDriven), fans them out with ZIO.foreachPar throttled by parallelism, merges findings, has the coder fix, and re-reviews up to maxRounds. fixLoop is the generic evaluate→fix→re-evaluate primitive it is shaped after.

## Alternatives considered
A single monolithic review prompt covering all concerns — rejected because it dilutes the distinct perspectives and is hard to extend. Prompts hard-coded as Scala string constants — rejected because editing or adding a lens would require a recompile; classpath Markdown makes it a content change. One-shot review with no fix loop — rejected because converging the diff toward clean is the whole value; the loop with maxRounds bounds it. Unbounded parallel fan-out — rejected for rate-limited/local backends, hence the parallelism throttle (e.g. local.sc uses parallelism = 1).

## Consequences
Adding or editing a review perspective is a Markdown change, not a recompile, and the structured outputs let an LLM return findings as data. The loop converges or gives up after maxRounds, returning the final (possibly still-dirty) ReviewResult rather than failing. Parallel fan-out is bounded for rate-limited providers, and building the diff first is mandatory (hence ADR 9).
