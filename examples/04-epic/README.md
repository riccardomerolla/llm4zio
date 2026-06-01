# Example 04 — running an epic with cross-agent review

A more involved flow than [01-simple](../01-simple/): the epic lives on disk in a
file you can read and edit before running, and after each task lands it is
reviewed by *different* backends than the one that implemented it. `claude`
implements; the API reasoning model and `codex` review in parallel. Crashes
don't lose progress — each task's checkbox is committed before the next starts,
and a re-run picks up from the first incomplete task. When the epic completes,
docs are updated and the epic file is removed.

## What it does

1. **Acquire epic** — `PlanStore.recoverOrCreate(.llm4zio/epic.md)(Planner.from…)`:
   reuse the on-disk plan if present, else generate one and persist it.
2. **Branch** — `ctx.git.checkoutOrCreate(plan.epicId)` (re-attaches on resume).
3. **Per task** (`implementTaskLoop`, resumable):
   - `coderChat.ask(task.description)` — `claude` edits files.
   - `reviewAndFixLoop(ctx.reasoning :: ctx.reviewers, …)` — reviewers run in
     **parallel** (API model + `codex`), findings merged; the coder fixes.
   - `ctx.git.commitAll(…)`.
4. **Update documentation** — coder updates the README/doc-comments; commit.
5. **Clean up** — `PlanStore.delete(planPath)`.

The flow script is [`plans/epic.scala`](../../plans/epic.scala).

## Prerequisites

- JDK 21+, [scala-cli](https://scala-cli.virtuslab.org/).
- **Both** `claude` and `codex` logged in (claude implements; codex reviews).
- A reasoning API key in the environment (`ANTHROPIC_API_KEY`).

## Run

```bash
./examples/04-epic/create-test-project.sh --local --run
# or step by step:
./examples/04-epic/create-test-project.sh --local
cd /tmp/llm4zio-04-epic-…
scala-cli run epic.scala -- "…your epic prompt…"
```

Edit [`test-project/`](test-project/) for a different starter, or
[`plans/epic.scala`](../../plans/epic.scala) for a different flow.
