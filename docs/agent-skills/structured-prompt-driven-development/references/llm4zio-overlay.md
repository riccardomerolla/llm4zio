# SPDD on llm4zio — Overlay

llm4zio (https://github.com/riccardomerolla/llm4zio) is an SPDD-shaped agent platform. It already provides event-sourced storage, governance gates, and an MCP gateway, so SPDD on llm4zio uses **MCP tools instead of plain markdown files** — every artefact is persisted, versioned, and gate-evaluated automatically.

## Tool ↔ stage map

| SPDD stage | Generic action | llm4zio MCP tool | Persisted entity |
|---|---|---|---|
| 1. Story | Write `.md` | `spdd_story(workspaceId, enhancementText)` | `BoardIssue` (tagged `spdd:story`) |
| 2. Analysis | Write `.md` | `spdd_analysis(storyId)` | `AnalysisDoc` with `AnalysisType.SpddContext` |
| 3. Canvas | Write `.md` | `spdd_reasons_canvas(analysisId, normProfileId?, safeguardProfileId?)` | `ReasonsCanvas` (Draft) in `canvas-domain` |
| 4. Generate | Code by hand | `spdd_generate(canvasId)` | `TaskRun` with `canvasId` + per-artifact `canvasSection` |
| 5. API test | Write `.sh` | `spdd_api_test(canvasId)` | `TestScenarioDoc` + `scripts/spdd/test-<canvasId>.sh` |
| 6a. Prompt-first | Edit `.md` by hand | `spdd_prompt_update(canvasId, deltaIntent, sectionsHint?)` | New `CanvasEvent.SectionUpdated`; downstream code marked `Stale` |
| 6b. Code-first | Edit `.md` by hand | `spdd_sync(canvasId, codeDiff?)` | `CanvasEvent.SectionUpdated` (text-only changes); behaviour-change heuristic rejects misuse |
| 7. Unit tests | Write tests | `spdd_unit_test(canvasId)` | `TestPromptDoc` + new test files |

## BCE mapping

llm4zio enforces Boundary / Control / Entity per CLAUDE.md. SPDD artefacts map onto BCE:

```
canvas-domain/
  entity/
    ReasonsCanvas.scala          # the Canvas (R/E/A/S/O + N/S refs)
    ReasonsSections.scala        # the 7 fields
    CanvasStatus.scala           # Draft | InReview | Approved | Stale | Superseded
    CanvasEvent.scala            # Created | SectionUpdated | Approved | MarkedStale | Superseded | LinkedToTaskRun
    NormProfile.scala            # cross-feature N
    SafeguardProfile.scala       # cross-feature S
    TestScenarioDoc.scala        # test scenarios as a versioned artefact
  control/
    ReasonsCanvasRepository.scala     # event-sourced (mirrors SpecificationRepositoryES)
    ReasonsCanvasRepositoryES.scala
    ReasonsCanvasService.scala        # section-level edits; stale-flagging
    NormProfileRepository.scala
    SafeguardProfileRepository.scala
    CanvasSimilarityIndex.scala       # asset reuse across features
  boundary/
    CanvasView.scala                  # Scalatags page; 7 sections side-by-side; revision history
```

The Operations section of a Canvas — the only one that drives code generation — maps onto an existing `Plan.entity.Plan` whose tasks are the Operations. **Do not duplicate**: a Canvas Operation generates a `PlanTaskDraft`, and the existing `AutoDispatcher` picks it up.

## Governance gates

llm4zio's `GovernanceGate` is extended to recognise the SPDD gates from `references/quality-gates-checklist.md`:

```scala
enum GovernanceGate:
  case SpecReview, PlanningReview, HumanApproval, CodeReview, CiPassed, ProofOfWork
  // SPDD additions:
  case CanvasReview            // Gate 3
  case NormsCompliance         // part of Gate 3
  case SafeguardsCompliance    // part of Gate 3 + Gate 7
  case ApiTestPassed           // Gate 4 / Gate 8
```

When `AutoDispatcher` evaluates a transition (e.g. `Todo → InProgress`), it consults `GovernancePolicyEngine.evaluateTransition`, which reads the Canvas status and TestScenarioDoc state. A `Draft` Canvas blocks dispatch; a `Stale` downstream blocks merge.

## Prompt templates

Every SPDD MCP tool is backed by a markdown template under `src/main/resources/prompts/spdd-*.md`, loaded via the existing `PromptLoader`:

- `prompts/spdd-story.md` — interpolates `{{enhancement}}`, `{{repoContext}}`.
- `prompts/spdd-analysis.md` — interpolates `{{story}}`, `{{repoContext}}`, `{{similarCanvases}}`.
- `prompts/spdd-reasons-canvas.md` — interpolates `{{analysis}}`, `{{normProfile}}`, `{{safeguardProfile}}`.
- `prompts/spdd-generate.md` — interpolates `{{canvas}}`, `{{operationId}}`.
- `prompts/spdd-api-test.md` — interpolates `{{canvas}}`.
- `prompts/spdd-prompt-update.md` — interpolates `{{currentCanvas}}`, `{{deltaIntent}}`, `{{sectionsHint}}`.
- `prompts/spdd-sync.md` — interpolates `{{currentCanvas}}`, `{{codeDiff}}`.
- `prompts/spdd-unit-test.md` — interpolates `{{canvas}}`, `{{existingTestInventory}}`.

## Asset reuse (across features, across iterations)

This is what makes SPDD compound over time on llm4zio:

- Approved Canvases are ingested by `KnowledgeExtractionService` into `DecisionLog`.
- `spdd_analysis` opens with a similarity lookup over approved Canvases for the same domain area, so the LLM has prior context.
- `NormProfile` and `SafeguardProfile` are project-scoped and shared across features — change them once, every new Canvas inherits.

## When NOT to use the MCP tools

- **You're editing a Canvas Norm rule that applies to all features**: edit the `NormProfile` directly, not via the per-Canvas tools.
- **You're outside llm4zio**: fall back to plain markdown per the generic skill; the workflow is identical, the persistence is just less rich.
- **You're prototyping a Canvas template change itself**: edit `src/main/resources/prompts/spdd-*.md` directly, then run a dry generation to verify.

## Verification on llm4zio

```bash
sbt canvasDomain/test                  # unit tests for ReasonsCanvasRepositoryES + status transitions
sbt 'testOnly *SpddE2ESpec'            # integration: story → sync end-to-end against EclipseStore + stub LLM
sbt run                                # then: call MCP tool spdd_story from Claude Code via the gateway
```

A working SPDD pass produces, in order: a `BoardIssue`, an `AnalysisDoc`, a `ReasonsCanvas` (Draft → Approved), one `TaskRun`, a `TestScenarioDoc` + script, optionally a `Decision` for prompt-first review, and the resulting code. Every artefact is event-sourced and replayable; the audit trail is the test.
