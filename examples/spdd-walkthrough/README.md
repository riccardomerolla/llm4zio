# SPDD Walkthrough — Multi-plan token billing

A small, end-to-end SPDD pass driven through every stage of the loop. Each file is the actual artefact a real run would produce — copy them as templates if useful.

The example feature: a hypothetical `token-billing` service gains tiered plans (Standard 100K quota, Pro 500K quota, plus per-model overage rates). It is small enough to read in one sitting but contains a normal case, a boundary case, an error case, the prompt-first loop (a behaviour change at review), and the code-first loop (a refactor with no behaviour change).

## Pipeline at a glance

```
01-enhancement-idea.md
        │
        ▼ /spdd-story
02-user-story.md                (BILL-1, INVEST + Given/When/Then)
        │
        ▼ /spdd-analysis
03-analysis.md                  (domain concepts, strategy, risks, AC coverage)
        │
        ▼ /spdd-reasons-canvas
04-canvas.md                    (R/E/A/S/O/N/S — the executable blueprint)
        │
        ▼ /spdd-generate
[code lives in your repo, not in this walkthrough]
        │
        ▼ /spdd-api-test
05-api-test-scenarios.md        (cURL scenarios per Operation)
        │
        ▼  Review
        ├── behaviour change   →  06-prompt-update-example.md  (prompt-first)
        └── pure refactor      →  07-sync-example.md           (code-first)
```

## How to drive this on llm4zio

Each `/spdd-*` step in the diagram corresponds to MCP tools on the gateway — see the "SPDD on llm4zio" section in `CLAUDE.md`. The calling agent (e.g. Claude Code with the user-global SPDD skill loaded) renders the system prompt via `spdd_render_prompt`, runs it itself, then commits the result via the matching persistence verb.

The minimum viable session is:

```text
1. spdd_render_prompt("spdd-story", {enhancement, repoContext})        → produce 02
2. (board) create BoardIssue from 02
3. spdd_render_prompt("spdd-analysis", {story, repoContext, similarCanvases}) → produce 03
4. spdd_canvas_search_similar("multi-plan token billing", projectId)   → asset reuse
5. spdd_render_prompt("spdd-reasons-canvas", {analysis, normProfile, safeguardProfile}) → produce 04
6. spdd_canvas_create(...)                                             → returns canvasId, status=Draft
7. (review)
8. spdd_canvas_approve(canvasId)                                       → status=Approved
9. spdd_render_prompt("spdd-api-test", {canvas})                       → produce 05
10. (run the generated bash script — outside SPDD's scope)
```

If at step 7 review finds a *behaviour* problem, follow `06-prompt-update-example.md` (prompt-first). If review only asks for a refactor, follow `07-sync-example.md` (code-first).

## File index

| # | File | What it is |
|---|---|---|
| 01 | [enhancement-idea.md](01-enhancement-idea.md) | Raw business request — the input to `/spdd-story` |
| 02 | [user-story.md](02-user-story.md) | INVEST story BILL-1 with three Given/When/Then ACs (normal / boundary / error) |
| 03 | [analysis.md](03-analysis.md) | Domain concepts, strategy, risks, edge cases, AC coverage matrix |
| 04 | [canvas.md](04-canvas.md) | The full REASONS Canvas with worked example |
| 05 | [api-test-scenarios.md](05-api-test-scenarios.md) | Five cURL scenarios mapped to op-001 |
| 06 | [prompt-update-example.md](06-prompt-update-example.md) | Reviewer changes Pro overage from 50% to 60%-cheaper — prompt-first loop |
| 07 | [sync-example.md](07-sync-example.md) | Reviewer requests Strategy-pattern refactor — code-first loop |

All artefacts use the file-naming convention from the SPDD skill: `[ID]-[YYYYMMDDHHMM]-[Type]-Title.md`. The numeric prefixes here (01, 02, …) are added only for reading order in this directory.
