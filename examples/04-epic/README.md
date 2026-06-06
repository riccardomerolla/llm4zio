# Example 04 — running a resumable epic

A more involved flow than [01-simple](../01-simple/): the epic lives on disk in a
file you can read and edit before running, and after each task lands it goes
through the **full reviewer roster** (`Reviewers.all` — seven lenses) and a
fix loop. Crashes don't lose progress — each task's checkbox is committed before
the next starts, and a re-run picks up from the first incomplete task. When the
epic completes, docs are updated and the epic file is removed.

One CLI agent does everything — planning, coding, and review — selectable with
`LLM4ZIO_CODER=claude|codex|gemini` (default `claude`), just like
[01-simple](../01-simple/). A single login is enough; no API key.

## What it does

1. **Acquire epic** — `PlanStore.recoverOrCreate(.llm4zio/epic.md)(Planner.from…)`:
   reuse the on-disk plan if present, else generate one and persist it.
2. **Branch** — `ctx.git.checkoutOrCreate(plan.epicId)` (re-attaches on resume).
3. **Per task** (`implementTaskLoop`, resumable):
   - `coderChat.ask(task.description)` — the coder edits files.
   - `reviewAndFixLoop(Reviewers.all, ctx.reasoning, …)` — the seven review
     lenses run on the chosen backend, findings merged; the coder fixes.
   - `ctx.git.commitAll(…)`.
4. **Update documentation** — coder updates the README/doc-comments; commit.
5. **Clean up** — `PlanStore.delete(planPath)`.

The flow script is [`plans/epic.scala`](../../plans/epic.scala).

## Prerequisites

- JDK 21+, [scala-cli](https://scala-cli.virtuslab.org/).
- The chosen agent CLI logged in — `claude` by default, or `codex` / `gemini`
  via `LLM4ZIO_CODER`. No API key needed.

## Run

```bash
./examples/04-epic/create-test-project.sh --local --run
# or step by step:
./examples/04-epic/create-test-project.sh --local
cd /tmp/llm4zio-04-epic-…
scala-cli run epic.scala -- "…your epic prompt…"

# pick a different backend:
LLM4ZIO_CODER=gemini scala-cli run epic.scala -- "…your epic prompt…"

# retry transient provider blips (timeouts, 5xx, gemini "API Error") N times;
# default 3, set 0 to fail fast:
LLM4ZIO_RETRIES=5 LLM4ZIO_CODER=gemini scala-cli run epic.scala -- "…"
```

Edit [`test-project/`](test-project/) for a different starter, or
[`plans/epic.scala`](../../plans/epic.scala) for a different flow.
