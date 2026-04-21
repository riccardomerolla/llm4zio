# bankmod MVP — Friendly-Architect Demo Script

**Length:** ~30 minutes, five acts plus Q&A.

**Audience:** One bank architect (EU-regulated, Tier-1 preferred). Familiar with
MRM review processes; skeptical of "AI architects" but open to *typed-model*
tooling.

**Thesis we are testing:** the validator's output, rendered as a diff against the
current living blueprint, is the kind of artifact the MRM committee would accept
as change-control evidence.

---

## Setup (before the call — 10 min)

1. `sbt bankmodApp/assembly` — produce `target/bankmod-app.jar`.
2. In one terminal: `BANKMOD_GRAPH_FILE=examples/bankmod/sample-graph.json java -jar target/bankmod-app.jar`
   - Seeds the store with the 8-service sample, watches the JSON file for edits.
3. Configure Claude Desktop per `../mcp-integration.md`:
   ```json
   {
     "mcpServers": {
       "bankmod": {
         "command": "curl",
         "args": ["-N", "http://localhost:8090/mcp"]
       }
     }
   }
   ```
4. Pre-render the baseline Mermaid SVG and have it open in a browser tab.
5. Have `examples/bankmod/demo-scenarios/` open in the editor for reference.

---

## Act 1 — Setup & framing (2 min)

- "Before we start: I'm not going to show you an AI that writes code. I'm going
  to show you a *validator* that rejects bad architecture proposals in typed
  language, with reasons precise enough to paste into a change-control ticket."
- "The MRM angle: can the outputs here be part of your review artifact pack?"

---

## Act 2 — Graph exploration (5 min)

- Prompt Claude: *"Read `graph://full` and summarise the baseline architecture."*
- Claude reads the resource, produces a short summary by tier with the key cross-
  tier edges called out.
- Prompt: *"Render the full graph as Mermaid and show me the PII-sensitive path."*
- Open the rendered diagram alongside the pre-rendered baseline to show parity.
- Architect question checkpoint: "Is this a reasonable model shape for your
  estate?"

---

## Act 3 — Invariant-violating evolution (8 min)

- Execute **Scenario 1** (`scenario-01-pii-boundary-violation.md`).
- Execute **Scenario 2** (`scenario-02-tier-violation.md`).
- After each, pause on the error string and ask:
  "If this were on a pull request, would it close the loop with your reviewer?"
- Emphasize: no code was written, no deployment was attempted, no test failed at
  runtime. The rejection is purely at the model level.

---

## Act 4 — Valid evolution + live update (8 min)

- Execute **Scenario 3** (`scenario-03-valid-event-introduction.md`).
- After `proposeService` commits, open the Mermaid rendering — show the new
  edge is drawn.
- Then: edit `sample-graph.json` in the editor (add an `ownership` change on one
  service). Show Claude Desktop receive the `notifications/resources/updated`
  event within ~1 second. Re-query `graph://full` — reflect the change.
- Narrate: "This is the 'living blueprint' — the model stays in lockstep with
  the source of truth."

---

## Act 5 — Regulator-style audit recap (5 min)

- Show the validator's invariant catalog via `tools/call listInvariants`.
- Highlight the banking-specific invariants: `PiiBoundaryCrossed`,
  `WeakConsistencyOnFinancialEdge`, `MissingPackedDecimalGuard`.
- Walk through how these map to MRM concerns: data-boundary controls, settlement
  integrity, representation fidelity for COBOL-era numerics.
- Close: "The model is the artifact. The validator is the gate. The diagram is
  just a visualisation of both."

---

## Q&A checklist

Capture structured feedback per the plan's §M9 dimensions:

- [ ] "Would the MRM committee accept this artifact?"
- [ ] "Is the invariant set rich enough for your bank?"
- [ ] "Does the typed evolution gate fit your change-control process?"
- [ ] "What's missing before v0.5?"
- [ ] "What would make this a paid-pilot conversation?"

Write notes in `docs/bankmod/feedback/2026-XX-<bank-name>-session-notes.md`.

---

## Known rough edges (self-disclose early)

- Graph is held in-memory. A restart returns the sample state (v0.5 persists via
  event store).
- Live watcher is on a JSON export, not a compiled Scala graph (v0.5 ties the
  Scala ADT + compiler plugin).
- No COBOL parsing or codegen in MVP (v0.5 scope).
- Only one friendly-bank fixture — your own estate would need onboarding work.

---

## Dry-run checklist

- [ ] Fresh machine clone → `sbt bankmodApp/assembly` succeeds.
- [ ] Claude Desktop connects to MCP endpoint on first try.
- [ ] All three scenarios reproduce per their script.
- [ ] File-edit → resource-update latency is under 2 seconds.
- [ ] Mermaid rendering opens in browser (`npx @mermaid-js/mermaid-cli`).
- [ ] Demo completes in under 32 minutes end-to-end.
