# 15. Two consumption surfaces: single-file flow scripts and an embedding API, with one unsafeRun

- Status: accepted

## Context
The library targets both quick, readable one-file flows (the orca aesthetic of a program that reads top-to-bottom) and integration inside larger ZIO applications. Both need the same FlowContext wiring, terminal feedback, and lifecycle handling, but the script surface also needs argument/prompt handling, a Ctrl-C shutdown hook, and process exit codes.

## Decision
Provide flow(args){ body } (runner/Flow.scala:29) as the script surface — the library's only unsafeRun — which resolves args/prompt, installs the Ctrl-C hook (interrupt fiber → stages unwind → ✖ banner → exit 130), maps results to exit codes (2 usage, 1 failure), and simply forks Llm4zio.script. Llm4zio.run/script (runner/Llm4zio.scala) is the embedding surface for ZIOAppDefault apps: it builds the FlowContext, streams progress, provides http/process layers, wraps the body in usage-limit retry, and renders a final banner. script is the pure-ZIO core, testable up to the single unsafe run. Scripts receive the context as a FlowContext ?=> context function so bare names (git, coder, reasoning, userPrompt, workDir) resolve.

## Consequences
Both audiences share one wiring and lifecycle, and almost everything is testable because unsafeRun is isolated to one place (ExampleFlow has an end-to-end integration test through the embedding path). The context-function/bare-name ergonomics make scripts terse but depend on Scala 3 context functions and given resolution. Presets (Connectors.scala) let scripts reference claude/codex/gemini/pi/lmStudio bare, with LLM4ZIO_CODER selecting the coder from the environment.
