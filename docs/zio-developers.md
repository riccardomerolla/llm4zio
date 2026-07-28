# llm4zio for ZIO developers

You know ZIO. You've seen llm4zio's flow scripts — bare names, no `ZLayer` in
sight — and wondered whether this library speaks your language. It does; this
page maps its idioms onto the ones you already use, and shows the embedding path
for a real ZIO application. (Why the flow surface looks the way it does is
recorded in ADR 0021.)

## The concept map

| llm4zio | The ZIO idiom it corresponds to |
|---|---|
| `FlowContext` | A pre-wired service bundle — what you'd assemble with layers, handed over as one value at the entry point |
| bare names (`git`, `coder`, `reasoning`, …) | Accessor sugar: one-line summons from the in-scope `FlowContext`, like `ZIO.serviceWithZIO` without the environment |
| `Grants.restricted { }` | The `FiberRef.locally` pattern you know from `ZIO.logAnnotate` / `Runtime.setConfigProvider` — ambient, scoped, restored on exit |
| `Caps` witnesses | `using`-passed capability tokens (tacit-style) — deliberately *not* in `R`; see below |
| `flow()` / `flow.restricted[C]` | The `ZIOAppDefault` boundary: wiring + the single `unsafeRun` live here, everything inside is ordinary effects |
| `LlmService`, `HttpClient`, `ToolRegistry` | Plain service traits with `ZLayer` constructors — use them in your graphs directly |

## Why capabilities aren't in `R`

The environment is for *services consumed by business logic* (the Three Laws);
ZIO 2's flagship change removed the fine-grained, ambient things
(Clock/Console/System/Random) **from** `R` onto runtime FiberRefs, because they
"polluted the environment type". Capability grants have exactly that profile, so
llm4zio follows ZIO 2's own architecture: coarse services at the edges, a
FiberRef for the ambient cross-cutting state, context parameters for the
compile-time tokens (the mechanism the source paper itself uses). Full
rationale: ADR 0021; the model itself: [capabilities.md](capabilities.md).

## Embedding in a ZIO application

`llm4zio-core` slots into a layer graph as ordinary services:

```scala
val llm: ZLayer[Any, Throwable, HttpClient] =
  HttpClient.reliableClient >>> HttpClient.live   // idle-timeout-safe zio-http client

// LlmService instances come from ConnectorFactories / Connectors presets;
// ToolRegistry.layer wires the tool executor.
```

For flows, `Llm4zio.run` is the embedder entry — an ordinary
`ZIO[Any, Throwable, Unit]` you place wherever your app needs it (see
`ExampleFlow` for the worked `ZIOAppDefault` variant). Tool methods require
capability witnesses; an embedder that drives tools outside a flow entry mints
one explicitly, in the loud `Unsafe` style:

```scala
given Caps.All = Unsafe.unsafe(implicit u => Caps.unsafe.all)
```

## Coming in 4.3 (embedder bridge B2)

First-class `FlowContext.layer` assembly from `LlmService` seats + tools +
config, a `Grants`-accepting `Llm4zio.run` variant (witnesses minted for the
embedded body, no `unsafe` needed), and a Java-facade `Grants` builder. Tracked
in the repo plan; the concept map above is stable.
