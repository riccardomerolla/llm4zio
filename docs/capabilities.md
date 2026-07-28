# Tracked capabilities

llm4zio 4.2 implements the safety model from Odersky's *Tracked Capabilities for
Safer Agents* (and [lampepfl/tacit](https://github.com/lampepfl/tacit)) in
stable Scala 3 + ZIO: **agent safety as a property of the infrastructure, not a
bet on how the model behaves**. Design record: ADR 0021; issue
[#716](https://github.com/riccardomerolla/llm4zio/issues/716).

## The 60-second version

```scala
import llm4zio.flow.*
import llm4zio.runner.*

// A survey flow that provably cannot commit, push, or open a PR:
flow.restricted[Caps.GitRead & Caps.Reasoning](args):
  for
    base <- git.defaultBase
    diff <- git.diffVsBase(base)
    _    <- stage("survey")(summarize(diff))   // reasoning is granted; coder is not
  yield ()

// git.commitAll("…")   ← compile error: no Caps.GitWrite witness in scope
// gh.createPr("…","…") ← compile error: no Caps.GhWrite witness in scope
```

Plain `flow()` is unchanged: it grants every capability, exactly as before
capabilities existed. Safety is opt-in per flow.

## The model: two layers that back each other up

**Compile time — witnesses.** Each capability is a sealed trait in
[`Caps`](../modules/llm4zio-flow/src/main/scala/llm4zio/flow/Caps.scala) with no
public implementations: flow code can *receive* a witness (as an entry-point
context argument) but never construct one. Tool methods require the witness for
their grade — `git.diff` takes `using Caps.GitRead`, `git.commitAll` takes
`Caps.GitWrite`, `git.push` takes `Caps.GitPush` — and subtyping encodes the
hierarchy (`GitPush <: GitWrite <: GitRead`), so declaring `Caps.GitPush` grants
all three. The vocabulary: `GitRead/GitWrite/GitPush`, `GhRead/GhWrite`,
`AdoRead/AdoWrite`, `Exec`, `Coder`, `Reasoning`, `Declassify`, and `All`.

**Runtime — the ambient gate.** A `FiberRef[Grants]` (the same app-wide pattern
ZIO 2 uses for its default services) carries the powers in force. Every tool
method checks it before spawning anything; the model-facing `ToolRegistry` checks
each tool call's declared `requires`. The gate catches what a lexical token
can't: a witness captured before a narrowing, mid-flow `Grants.restricted`
blocks, forked fibers (copy-on-fork covers `zipPar`/`race`; a parent-wins join
means a child can never widen its parent), and the model's own choices.

`flow.restricted[C]` is where the two layers meet: the type parameter puts
exactly the declared witnesses in scope **and** derives the runtime `Grants`
(via `Caps.grantsFor`), so the compile-time and runtime layers cannot drift.

## Denials

- **Flow code** hitting a runtime denial is a bug in the flow (the code
  contradicts its own narrowing): it fails fast with
  `FlowError.CapabilityDenied`, before any subprocess is spawned.
- **The model** asking for an ungranted tool is expected behavior: the denial
  goes back to it in the value channel (a `ToolResult` error) and the loop
  continues. `ToolLoopConfig.maxDenials` optionally aborts a probe loop.
- Every denial is audited: `CapabilityDenied` events always render in the
  terminal (at every verbosity) and land in the trace, alongside
  `CapabilityUsed` for publish-grade operations (push, PR, comment, exec).

## Exec, refinement, and narrowing

`Caps.Exec` is compile-time coarse; which commands may run is value-level data.
The entry's `refine` intersects the derived grants, so it can only narrow:

```scala
flow.restricted[Caps.GitRead & Caps.Exec](
  args,
  refine = _.copy(exec = Grants.ExecGrant.Allow(Set("ls", "cat"))),
)(body)
```

**`Caps.Exec` unrefined grants the full allowlist — always pair it with a
`refine`.** Mid-flow, `Grants.restricted(caps)(zio)` narrows further for a
sub-tree (intersection, restored afterwards on every exit).

## Classified values

`Classified[A]` seals a sensitive value: redacted `toString`, no codec, no
implicit widening — a token cannot *accidentally* reach a prompt, log, or JSON
payload. The only exit is `declassify(label)`, which needs the
`Caps.Declassify` witness, passes the runtime gate, and always emits a
`Declassified` audit event. Prefer sealing at the source:
`Classified.env("API_TOKEN")`.

Honesty note: the paper's version proves purity via capture checking; stable
Scala cannot, so this is **accident-proof, not adversary-proof**.

## The trust boundary, stated plainly

- Witnesses + the FiberRef gate are **guarantees** for flow code and for
  API-provider tool calls (`ToolRegistry`).
- The coder CLI is an external process. `CoderPolicy` **translates** the grants
  into the connector's own vocabulary (claude: `--disallowed-tools` deny
  patterns for ungranted push/PR/board writes). A connector that cannot express
  a restriction emits `CapabilityUnenforceable` events — or refuses to start
  under `strictPolicy = true`.
- `CliSandbox` (Docker/Seatbelt) is the hard opt-in boundary. (Currently wired
  for gemini; claude/codex container wrapping is a fast-follow.)

## Escape hatches — loud by design

Inside the library, entry points mint witnesses via the package-private
`Caps.grantAll`. The one public hatch mirrors ZIO's `Unsafe` marker:

```scala
given Caps.All = Unsafe.unsafe(implicit u => Caps.unsafe.all)
```

Every bypass is greppable and glaring in review — airtight against accident and
against generated flow code sneaking powers past a reviewer; bypassable only by
code that visibly announces it.

## Worked example

[`examples/restricted-flow.sc`](../examples/restricted-flow.sc) — the read-only
survey flow above, runnable end to end.
