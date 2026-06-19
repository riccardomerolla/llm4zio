# 4. Three-module layering with a strict one-directional dependency

- Status: accepted

## Context
The system spans generic LLM plumbing, the differentiating agentic-flow model, and the application concerns of entry points, presets, and terminal rendering. These have different rates of change and reuse value (the LLM layer is a generic supporting subdomain; the flow layer is the core domain). The history of a 39-module product collapsing into a focused library motivated a deliberately small, clean module graph.

## Decision
Split into three published modules under modules/ — llm4zio-core (LLM access), llm4zio-flow (agentic flow, orca-shaped), llm4zio-runner (entry/wiring/UI) — with the dependency direction runner → flow → core and arrows never reversing. A non-published root aggregates them (publish/skip := true); all three publish to Maven Central under io.github.riccardomerolla.

## Consequences
Clear separation of the core domain from its supporting subdomain and from driving concerns; core can be consumed without the flow or runner. The constraint that arrows never reverse must be defended in review. Packages mirror the modules (llm4zio.core/providers/tools/observability, llm4zio.flow, llm4zio.runner).
