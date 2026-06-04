# Example 05 — interactive live coding

The steerable counterpart of [01-simple](../01-simple/) / [02-interactive](../02-interactive/).
Instead of a one-shot reply per task, each task drives a **held `claude` session**:
it streams its thinking and tool calls to the terminal, can ask you clarifying
questions *mid-task*, and routes every tool call through an **approval gate** —
all over an in-process MCP server the runtime starts for the run.

## What it does

1. **Plan interactively** — `Planner.interactive(ctx.reasoning, prompt, TerminalInteraction.live)`
   loops: the model either asks a question (answered on the terminal) or proposes the `Plan`.
2. **Live per-task coding** — `InteractiveCoder.openSessions(...)` starts the MCP
   server (`ask_user` + `approve` tools) and returns a factory that opens a steerable
   `claude` session per task. `implementTaskLoopLive` drives each one:
   - **streams** the agent's text + tool calls into the terminal tree,
   - bridges the agent's `ask_user` calls to your terminal (`Interaction`),
   - gates each tool call through an `ApprovalPolicy` (the script uses
     `autoApprove`; swap in `ApprovalPolicy.interactive(interaction)` to confirm
     each call yourself),
   - then the **runtime commits** the task. Resumable via `.llm4zio/plan-*.md`.

The flow script is [`plans/implement-live.scala`](../../plans/implement-live.scala).

## How the interactive runtime fits together

```
plan (interactive)
      │
      ▼
implementTaskLoopLive ──drives──▶ Drive.run(session, interaction, task)
      │                                  │  relays SessionEvents ─▶ terminal
      │                                  │  bridges ask_user ─────▶ Interaction (stdin)
      ▼                                  ▼
InteractiveCoder.openSessions      held `claude --input-format stream-json` process
      │  starts ──▶ McpHttpServer (ask_user + approve)  ◀── claude reaches back over HTTP
      │             via --mcp-config / --permission-prompt-tool / --allowedTools
      ▼
   commit (runtime owns git)
```

Interactive mode is **claude-only** — codex/gemini headless modes aren't held
bidirectional sessions (see `docs/superpowers/notes/phase2-spike.md`). The
autonomous flows (01–04) still work with any CLI coder.

## Prerequisites

- JDK 21+, [scala-cli](https://scala-cli.virtuslab.org/), `cargo`.
- `claude` logged in — no API key needed (reasoning *and* coding run over the claude CLI).

## Run

```bash
./examples/05-interactive-live/create-test-project.sh --local --run
# or step by step:
./examples/05-interactive-live/create-test-project.sh --local
cd /tmp/llm4zio-05-interactive-live-…
scala-cli run implement-live.scala -- "Make the calculator crate more useful"
```

The planner asks a clarifying question first; then watch the live session
implement each task, asking you follow-ups as it goes.
