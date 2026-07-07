# Java examples

Flows authored in **Java** instead of Scala, run the same way — `scala-cli run`.
They use the `llm4zio-java` facade (`llm4zio.javaapi.*`): a blocking, exception-based
surface over the flow layer, so a Java flow reads as straight-line imperative code.
The single `JavaFlow` handle replaces the `.sc` surface's implicit `FlowContext` and
bare-name accessors (`git`/`gh`/`coder`/`stage`/…).

| Script           | What it shows                                              | Starter        |
| ---------------- | ---------------------------------------------------------- | -------------- |
| `Implement.java` | Autonomous plan → branch → implement → review → commit loop | calculator-rs |
| `ImplementEnhanced.java` | Plan self-review + shared codebase brief on every task prompt | calculator-rs |
| `ImplementEnhancedPr.java` | Enhanced plan → implement → push → open PR (needs a remote + `gh`) | calculator-rs |
| `Epic.java`      | Resumable multi-task epic: full review roster, doc-update stage, plan cleanup | todo-java |
| `IssuePr.java`   | GitHub issue → assess → implement → push → open PR (needs `gh` + a remote) | calculator-scala |
| `IssuePrBugfix.java` | Bug report → triage → failing test → red CI → fix → PR (needs `gh` + CI) | calculator-scala |
| `Sdd.java`       | Spec → tests-first (must go RED) → implement → verify; mvn as the gate | todo-java |
| `Pipeline.java`  | Specify → design → plan → tests-first → implement → verify; TDD-discipline reviewer | todo-java |
| `JudgeGate.java` | LLM-as-a-Judge quality gate: per-task score loop (correctness/scope/safety, bar = 2) replaces the review loop | calculator-rs |
| `JudgeSuite.java`| Offline eval harness: built-in dataset, noPii + judge via `Evals.all`, 3× repeats flag flakes | — (no starter) |
| `ReverseEngineer.java` | Read-only: architecture + domain docs, then structured ADRs under docs/adr/ | todo-java |
| `AdoSpec.java`   | Azure DevOps: card → draft spec onto the work item → Spec Review (needs ADO) | — |
| `AdoImplement.java` | Azure DevOps: card → plan from criteria → implement (mvn gate) → ADO PR linked to the card (needs ADO) | — |
| `Local.java`     | Fully local — reasoning on LM Studio, coding on pi; no cloud/API key | calculator-rs |
| `LocalClaude.java` | Fully local — reasoning on LM Studio, coding on Claude Code routed to LM Studio | calculator-rs |

## Running one

Same zero-effort recipe as the `.sc` examples — seed a starter, then run the Java flow
against it:

```bash
examples/seed.sh implement --java          # seed a starter, use Implement.java
examples/seed.sh implement --java --run    # seed + run
examples/seed.sh implement --java --local  # test against the in-tree build (sbt publishLocal)
```

Or by hand: copy a starter from `examples/starters/`, `git init` + commit it, then run
the flow **from outside the repo** with the repo as the working directory:

```bash
cd /path/to/seeded-repo
scala-cli run /path/to/Implement.java -- "Add a multiply function to the calculator crate"
```

Backend: `LLM4ZIO_CODER=claude|codex|gemini|pi` (default claude). No API key — one CLI
login is enough. The issue-pr flows additionally need `gh` authenticated and a remote.

## Java-surface notes

- **Coordinate**: single-colon (`io.github.riccardomerolla:llm4zio-java:…`) — the module
  is published with `crossPaths := false`, so it reads like an ordinary Maven coordinate.
- **Keep the `//> using scala` directive.** It looks odd in a `.java` file, but without
  it scala-cli builds a Java-only project whose incremental-compile analysis can't load
  the Scala 3 enum types the facade returns (`ClassNotFoundException: scala.reflect.Enum`
  inside the build server — the compile appears to hang). The directive makes the
  project "mixed", which analyses fine; your code stays 100% Java.
- **Errors**: catastrophic failures throw the unchecked `Llm4zioException`, carrying a
  typed `ErrorCategory` (`e.getCategory().name()`, `e.getCategory().isAborted()`).
- **Recoverable outcomes are values with `is*` predicates**, not exceptions:
  `flow.git().commitAll(msg).isCommitted()`, `flow.gh().waitForBuild(pr, t).isSuccess()`,
  `flow.assessThenPlan(p).isBlocked()` (then `.getReason()` / `.getPlan()`). Scala 3
  enum *cases* aren't reachable as `Enum.Case` constants from Java, so the facade
  exposes predicates instead.
- **Multi-case results with payloads** (e.g. `Triage`) work with ordinary Java
  pattern-matching, because payload cases compile to nested classes:
  ```java
  if (verdict instanceof Triage.NotABug notABug) { … notABug.explanation() … }
  if (verdict instanceof Triage.Testable t)      { … t.branchName() … }
  ```
- Baseline Java 21. The interactive/live path (`implement-live.sc`) is Scala-only for now.

Repo maintainers: `scripts/verify-java-examples.sh` compiles every example here against
the in-tree build (publishLocal + scala-cli, with the version pinned from one sbt
session).
