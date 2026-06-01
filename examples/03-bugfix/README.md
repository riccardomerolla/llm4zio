# Example 03 — issue-driven bugfix (touches GitHub)

Given a `owner/repo#number` issue, the flow reads it, triages it, and — for a
real, testable bug — writes a failing test on a fresh branch, opens a PR, waits
for CI to go red (proving the reproduction), then implements the fix and
regenerates the PR description from the full diff.

Unlike 01/04 this needs a real GitHub repo + issue + CI, so there's no `--run`.

## What it does

1. **Read issue** — `ctx.gh.readIssue(ref)`.
2. **Triage** — `Planner.triage(...)` → `NotABug` / `Untestable` / `Testable`.
   The first two comment the verdict and stop.
3. **Testable** → branch (`checkoutOrCreate`), coder writes the failing test,
   commit + push, `summarisePr` (tentative) + `createPr`.
4. **Wait for CI to fail** — `gh.waitForBuild`; fail loudly if it passes.
5. **Fix** — `Planner.from` + `implementTaskLoop` with `reviewAndFixLoop`.
6. **Finish** — push, regenerate the PR title/body from the full diff, `updatePr`.

The flow script is [`plans/issue-pr-bugfix.sc`](../../plans/issue-pr-bugfix.sc).
The starter ships an sbt Calculator whose `average` divides by zero on an empty
list, plus a CI workflow that runs `sbt test`.

## Prerequisites

- JDK 21+, scala-cli, sbt; `claude` logged in; `gh` authenticated; a reasoning
  API key (`ANTHROPIC_API_KEY`).

## Run

```bash
./examples/03-bugfix/create-test-project.sh --local
cd /tmp/llm4zio-03-bugfix-…
gh repo create <you>/calculator-demo --private --source=. --push
gh issue create --title "average([]) throws" --body "Divide by zero on empty list."
scala-cli run issue-pr-bugfix.sc -- "<you>/calculator-demo#1"
```

> Simplifications vs orca: the failure comment is a fixed note (orca has sonnet
> inspect the failed run via `gh` and verify the failure matches the report);
> local edits aren't stashed before branching.
