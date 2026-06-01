# Examples

End-to-end llm4zio flows, each a single scala-cli script under [`plans/`](../plans/)
plus a seeder that drops a starter project next to it. The ZIO-native counterpart
to [orca's examples](https://github.com/VirtusLab/orca/tree/main/examples).

| Example | What it shows |
| ------- | ------------- |
| [01-simple](01-simple/) | Autonomous planning + coding for a small task: plan → per-task implement (claude CLI) → LLM review-and-fix → commit. Plan persists to `.llm4zio/plan-*.md`, so a re-run resumes. |
| [04-epic](04-epic/) | A multi-task epic in a resumable on-disk file, with **cross-agent review** (claude implements; the API model + codex review in parallel), a final doc-update stage, and epic-file cleanup. |

(02-interactive, 03-bugfix are planned — see `.claude/plans/orca-examples-parity.md`.)

## Prerequisites

- **JDK 21+** and [scala-cli](https://scala-cli.virtuslab.org/).
- `claude` CLI logged in (the coder backend edits files in the repo).
- A reasoning API key in the environment (e.g. `ANTHROPIC_API_KEY`).
- The starter's toolchain (01-simple ships a Rust crate, so `cargo` on PATH).

## Seeding and running

Each example has a `create-test-project.sh` that copies its starter + the flow
script into a temp dir and inits git:

```bash
./examples/01-simple/create-test-project.sh            # seed only, prints next step
./examples/01-simple/create-test-project.sh --run      # seed, then run the flow
./examples/01-simple/create-test-project.sh /tmp/demo  # explicit destination
```

### Running against a local build (`--local`)

llm4zio isn't on Maven Central yet, so until it is, pass `--local` to resolve the
flow script against your in-tree build:

```bash
./examples/01-simple/create-test-project.sh --local --run
```

`--local` runs `sbt publishLocal`, discovers the published version, and rewrites
the script's `//> using dep` to pin it with `//> using repository ivy2Local`.
