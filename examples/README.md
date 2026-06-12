# Examples

Flat, orca-shaped flow scripts. Each is a single `.sc` file: `//> using dep` pins
llm4zio, `flow(args)` opens the flow, and the body is an ordinary ZIO
for-comprehension with `git`/`gh`/`coder`/`reasoning`/`userPrompt` available bare.
Documentation lives in each script's header comment.

| Script                    | What it shows                                                | Starter           |
| ------------------------- | ------------------------------------------------------------ | ----------------- |
| `implement.sc`            | Autonomous plan → implement → review loop                    | calculator-rs     |
| `implement-interactive.sc`| Planner asks clarifying questions first                      | calculator-rs-open|
| `implement-enhanced.sc`   | Plan self-review + shared codebase brief (`.reviewed/.briefed`) | calculator-rs  |
| `implement-live.sc`       | Held, steerable claude session, streaming + ask_user over MCP | calculator-rs-open|
| `epic.sc`                 | Multi-task epic, full reviewer roster, doc update at the end | todo-java         |
| `issue-pr.sc`             | GitHub issue → assess → implement → PR                       | calculator-scala  |
| `issue-pr-bugfix.sc`      | Bug report → failing test → red CI → fix → PR                | calculator-scala  |

## Running one

```bash
examples/seed.sh implement          # seed a starter into a temp dir
examples/seed.sh implement --run    # seed + run
examples/seed.sh implement --local  # test against the in-tree build (sbt publishLocal)
```

Or by hand: copy a starter from `examples/starters/`, drop the script next to it,
`git init`, then

```bash
scala-cli run implement.sc -- "Add a multiply function to the calculator crate"
```

Backend: `LLM4ZIO_CODER=claude|codex|gemini` (default claude). No API key —
one CLI login is enough. The issue-pr flows additionally need `gh` authenticated
and a repo with a remote.
