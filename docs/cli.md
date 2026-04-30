# llm4zio CLI

`llm4zio-cli` is the command-line front door to llm4zio. It covers the full
developer loop: scaffolding, configuration, running agents locally, evaluating
them, deploying, and talking to a running gateway.

This document is a practical reference. For every command it lists the
required args, the flags, and a copy-pasteable example.

---

## Installation & running

The CLI lives in the `cli` sbt module. During development, invoke it through
sbt:

```bash
sbt 'cli/run <subcommand> [flags] [args]'
```

Once the fat JAR is built (`sbt assembly`), it can also be invoked as
`java -jar llm4zio-cli.jar …`.

### Global flags

Most stateful commands accept:

| Flag            | Default                     | Purpose                                |
| --------------- | --------------------------- | -------------------------------------- |
| `--state`       | `~/.llm4zio/data`           | Root for `config-store` + `data-store` |
| `--gateway-url` | `http://localhost:8080`     | Base URL of a running gateway          |

The state directory is created on first use.

### Top-level command tree

```
llm4zio-cli
├── setup                              one-time bootstrap
├── login     <provider>               store provider API key
├── info                               show state + gateway + provider status
│
├── agent
│   ├── init  <name>                   scaffold an agent definition
│   └── run   <name> <prompt>          run an agent locally (subprocess)
│
├── workspace
│   ├── list
│   └── init  <path>                   scaffold a workspace on disk
│
├── board
│   ├── list  <workspace-path>
│   └── show  <workspace-path> <id>
│
├── config
│   ├── list
│   └── set   <key> <value>
│
├── project  list
│
├── eval
│   ├── run      <dataset>             execute JSONL eval against an agent
│   └── compare  <a> <b>               diff two run files; exits non-zero on regression
│
├── deploy                             package workspace for a runtime target
├── ingest   <source>                  emit an ingest manifest (files → JSONL)
│
└── remote
    ├── status                         probe gateway /api/health
    └── chat    <prompt>                send a one-shot prompt to the gateway
```

> **Argument order matters.** `zio-cli` requires options *before* positional
> args. Write `agent init --workspace /tmp/ws researcher`, not
> `agent init researcher --workspace /tmp/ws`.

---

## Onboarding (three commands)

### `setup`

Creates the state root and seeds `gateway.url`. Idempotent.

```bash
sbt 'cli/run setup --state /tmp/demo/state --gateway-url http://localhost:8080'
```

Output:

```
✓ State root: /tmp/demo/state
✓ Seeded gateway.url = http://localhost:8080

Next steps:
  llm4zio-cli login <provider> --key <api-key>
  llm4zio-cli workspace init <path>
  llm4zio-cli info
```

### `login <provider>`

Stores provider credentials where `ConnectorConfigResolver` reads them:

- Global default: `connector.default.provider`, `connector.default.apiKey`
- Agent-scoped: `agent.<name>.connector.{provider,apiKey}`

```bash
# Global default
sbt 'cli/run login --key sk-... openai'

# Scoped to a specific agent
sbt 'cli/run login --key sk-... --agent researcher anthropic'
```

Required: `--key <value>`. Optional: `--agent <name>`.

### `info`

Dumps state path, gateway reachability, known provider keys (masked), CLI
version.

```bash
sbt 'cli/run info --state /tmp/demo/state'
```

```
llm4zio-cli v1.0.0

State:
  /tmp/demo/state  ✓ exists

Gateway:
  http://localhost:8080  ✗ unreachable

Providers:
  [default] provider=openai  key=sk-t****************mnop
  [agent=researcher] provider=anthropic  key=ant-************4321
```

---

## Workspaces & agents

### `workspace init <path>`

Scaffolds a workspace on disk: kanban board (`.board/`), a default governance
policy, `agents/`, `evals/`, and a README.

```bash
sbt 'cli/run workspace init --name demo /tmp/demo/ws'
```

Optional: `--name`, `--description`.

### `workspace list`

Lists all workspaces registered in the store.

```bash
sbt 'cli/run workspace list --state /tmp/demo/state'
```

### `agent init <name>`

Scaffolds a new agent definition (`<workspace>/agents/<name>.agent.toml`) and
registers it in the event store.

```bash
sbt 'cli/run agent init --workspace /tmp/demo/ws --model claude-opus-4-7 researcher'
```

Optional: `--model`, `--cli-tool` (default `llm4zio`), `--description`.

### `agent run <name> <prompt>`

Runs an agent locally as a one-shot subprocess. Streams stdout/stderr. Exits
with the subprocess's exit code.

```bash
sbt 'cli/run agent run --workspace /tmp/demo/ws researcher "Summarise foo.md"'
```

The underlying runner honours the agent's `cliTool` field (default
`llm4zio`) — set `--cli-tool echo` during scaffolding to dry-run without a
real LLM.

---

## Board & projects

### `board list <workspace-path>`

Print issues across `backlog → todo → in-progress → review → done`.

```bash
sbt 'cli/run board list /tmp/demo/ws'
```

### `board show <workspace-path> <issue-id>`

Show a single issue's Markdown body.

### `project list`

List all projects in the store.

---

## Config

Configuration is a flat key/value store backed by EclipseStore. Hierarchical
keys are dot-separated.

```bash
sbt 'cli/run config list'
sbt 'cli/run config set llm.model gpt-4'
```

Known namespaces:

| Prefix                          | Purpose                                        |
| ------------------------------- | ---------------------------------------------- |
| `connector.default.*`           | Default provider + API key                     |
| `agent.<name>.connector.*`      | Per-agent provider override                    |
| `ai.*`                          | Legacy provider settings (still read)          |
| `gateway.url`                   | Default gateway URL                            |
| `agent.binding.<id>.<channel>`  | Channel bindings (Telegram/Slack/Discord/WS)   |

---

## Eval harness

### JSONL dataset format

One case per line. Comments (`//`) and blank lines are ignored.

```jsonl
// examples/evals/capitals.jsonl
{"prompt": "Capital of France?", "expected": "Paris"}
{"prompt": "Capital of Germany?", "expected": "Berlin", "metadata": {"tag": "eu"}}
```

### `eval run <dataset>`

Executes the dataset against a named agent and writes a run file to
`<workspace>/evals/runs/run-<timestamp>-<runId>.json`.

```bash
sbt 'cli/run eval run --workspace /tmp/demo/ws --agent researcher /tmp/demo/ws/evals/capitals.jsonl'
```

The scorer is substring-case-insensitive by default.

### `eval compare <a> <b>`

Diffs two run files and exits non-zero if the candidate has regressions.

```bash
sbt 'cli/run eval compare /tmp/demo/ws/evals/runs/run-A.json /tmp/demo/ws/evals/runs/run-B.json'
```

Exit code `1` on regression makes this CI-friendly.

---

## Deploy

`deploy` packages a workspace for a target runtime. Artifacts land under the
workspace; commands to invoke (`docker build`, `kubectl apply`, …) are
printed as next-step notes.

```bash
sbt 'cli/run deploy --workspace /tmp/demo/ws --target docker --dry-run'
sbt 'cli/run deploy --workspace /tmp/demo/ws --target kubernetes'
sbt 'cli/run deploy --workspace /tmp/demo/ws --target cloud-run --image-name my-ws --image-tag v1'
sbt 'cli/run deploy --workspace /tmp/demo/ws --target jvm-fatjar --repo-root /path/to/llm4zio'
```

Supported targets:

| Target        | Alias     | Output                                               |
| ------------- | --------- | ---------------------------------------------------- |
| `jvm-fatjar`  | `fatjar`  | runs `sbt assembly`, copies JAR to `<ws>/dist/`      |
| `docker`      | —         | writes `Dockerfile.llm4zio` at repo root; builds it  |
| `kubernetes`  | `k8s`     | writes `<ws>/k8s/deployment.yaml`                    |
| `cloud-run`   | —         | writes `<ws>/cloud-run/service.yaml` (Knative)       |
| `agent-runtime` | —       | not supported (stub, returns error)                  |

Flags: `--dry-run`, `--repo-root`, `--image-name`, `--image-tag`.

---

## Ingest

### `ingest <source>`

Scans a file or directory and writes a portable JSONL manifest under
`<workspace>/ingested/ingest-<timestamp>.jsonl`. One record per matching
file (source, scope, tags, text, sha256, bytes, timestamp).

```bash
sbt 'cli/run ingest --workspace /tmp/demo/ws --tags cli,demo /tmp/demo/src'
sbt 'cli/run ingest --workspace /tmp/demo/ws --extensions md,rst,txt docs/'
```

Optional: `--scope` (default `knowledge`), `--tags` (comma-separated),
`--extensions` (default `md,txt,markdown`).

The CLI does not embed or index — that belongs to the gateway (where the
embedding provider is configured). The manifest is the portable handoff.

---

## Remote

### `remote status`

Probes `GET <gateway>/api/health`.

```bash
sbt 'cli/run remote status'
```

### `remote chat <prompt>`

Creates a new conversation on the gateway and prints the follow-up URL.

```bash
sbt 'cli/run remote chat "What is the status of the board?"'
```

---

## End-to-end smoke test

The full onboarding loop from a clean machine:

```bash
STATE=/tmp/llm4zio/state
WS=/tmp/llm4zio/ws

sbt "cli/run setup --state $STATE"
sbt "cli/run login --state $STATE --key \$OPENAI_API_KEY openai"
sbt "cli/run workspace init --state $STATE --name demo $WS"
sbt "cli/run agent init --state $STATE --workspace $WS --model gpt-4o-mini researcher"
sbt "cli/run info --state $STATE"

# Optional
sbt "cli/run eval run --state $STATE --workspace $WS --agent researcher $WS/evals/example.jsonl"
sbt "cli/run deploy --workspace $WS --target docker --dry-run"
sbt "cli/run ingest --workspace $WS docs/"
```

---

## Troubleshooting

- **`ValidationError: Missing option --workspace`** — put flags *before*
  positional args.
- **`Gateway … ✗ unreachable` in `info`** — expected when no gateway is
  running. Start one with `sbt run` (from the repo root).
- **`Ingest failed: Source does not exist`** — the path you passed does not
  exist or is unreadable.
- **Docker / sbt / kubectl not on PATH** — `deploy` falls back to dry-run
  mode and prints the command you should invoke manually.
