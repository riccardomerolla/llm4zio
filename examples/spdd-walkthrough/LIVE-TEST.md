# Live Test — SPDD MCP Server + Skill

How to verify the new SPDD machinery against a real running gateway, in two paths:

- **Path A — shell smoke test** (no Claude Code needed). Drives every `spdd_*` MCP tool over the actual SSE transport and verifies the `/canvases` UI round-trip. Ground truth that the gateway is correct. ~30 seconds.
- **Path B — Claude Code interactive test**. Registers the gateway with Claude Code so the SPDD skill + the `spdd_*` tools are usable in a real session. ~5 minutes.

Run **A first**. If it fails, B will fail too and the shell output is easier to debug.

---

## Prerequisites

- `sbt run` works in this repo (check `application.conf` if not).
- `curl` and `jq` on your `PATH` (smoke test requires both).
- Port `8080` free (or set `GATEWAY_PORT=… sbt run` and `BASE=http://localhost:<port>` for the smoke test).

---

## Path A — shell smoke test

In **terminal 1**, start the gateway:

```bash
sbt run
# wait for: "Starting web server on http://0.0.0.0:8080"
```

In **terminal 2**, run the smoke test:

```bash
./scripts/spdd/live-smoke.sh
```

Expected output (~30 s):

```
[1/9] Opening SSE channel
  ✅ SSE channel open, sessionId=…
[2/9] initialize
  ✅ initialize OK (llm4zio-gateway)
[3/9] tools/list — expect at least the 9 spdd_* tools
  ✅ tools/list returned all 9 spdd_* tools
[4/9] spdd_render_prompt — render an SPDD template
  ✅ spdd_render_prompt produced a rendered template with the placeholder substituted
[5/9] spdd_canvas_create — persist a Draft canvas
  ✅ spdd_canvas_create returned canvasId=… (status=Draft)
[6/9] spdd_canvas_update_sections — bump version on Draft
  ✅ section update bumped Draft to v2 and stayed in Draft
[7/9] spdd_canvas_approve, then update — proves the SPDD golden rule
  ✅ spdd_canvas_approve flipped status to Approved
  ✅ section update against an Approved canvas knocked status back to InReview (golden rule)
[8/9] spdd_canvas_get — round-trip the canvas
  ✅ spdd_canvas_get returned all 7 sections populated
[9/9] GET /canvases/<id> — UI page round-trip
  ✅ GET /canvases/<id> returns 200 HTML with the canvas title and section labels

 Result: PASS — 9 checks passed.
```

A **PASS** here means: the SSE transport works, JSON-RPC dispatch reaches the right tool, every tool persists into the event-sourced repository, the `Approved → InReview` knock-back fires at the entity layer, and the rendered HTML page reads the same canvas back. All ten of the SPDD commits are wired correctly.

If any step fails, the script prints the failing JSON-RPC response and exits 1.

### Optional: cross-check via `/canvases` in your browser

```bash
open http://localhost:8080/canvases
```

The smoke test creates one canvas per run with project id `proj-spdd-smoke-<unix-ts>` — the project filter shows only that one. Click through to see all 7 sections plus the revision timeline (you'll see Draft v1 → v2 → Approved → v3 InReview).

---

## Path B — Claude Code interactive test

This proves the **skill ↔ MCP** integration: the user-global SPDD skill triggers on a feature request, and the agent can call the `spdd_*` tools on the gateway as part of its response.

### Setup (one-time)

The repo already contains [`.mcp.json`](../../.mcp.json):

```json
{
  "mcpServers": {
    "llm4zio-gateway": {
      "type": "sse",
      "url": "http://localhost:8080/mcp/sse"
    }
  }
}
```

When Claude Code opens the repo, it picks this file up automatically. The first time, it will ask you to approve the new MCP server — accept it.

The user-global SPDD skill is at `~/.claude/skills/structured-prompt-driven-development/`. It's already loaded; no extra setup.

### Pre-flight

In a terminal, with the gateway running (`sbt run`):

```bash
./scripts/spdd/live-smoke.sh   # must PASS first
```

### The session

1. Start a fresh Claude Code session in this repo.
2. Verify MCP connection — type `/mcp` in the Claude Code session. You should see `llm4zio-gateway` connected with the nine `spdd_*` tools listed.
3. Drive an SPDD pass. A useful first prompt:

   > "Let's add a feature to our token-billing service: Standard customers get a 100K monthly token quota; tokens beyond quota are billed at $0.01/1K for fast-model. Walk me through your approach before any code — and if you have access to the SPDD MCP tools on this gateway, use them."

   Expected behaviour:
   - The SPDD skill triggers (you'll see `Skill(structured-prompt-driven-development)` invoked).
   - The agent describes the closed loop and the seven REASONS dimensions explicitly.
   - When you give the green light, the agent calls `spdd_render_prompt` for the analysis template, `spdd_canvas_search_similar` for asset reuse, and `spdd_canvas_create` to persist the resulting canvas.
   - You can see the persisted canvas at `http://localhost:8080/canvases`.

4. **Negative trigger** (~30 s): start *another* fresh session and ask:

   > "There's a typo in the README — `recieve` should be `receive`."

   The SPDD skill must NOT trigger for typo fixes. If it does, the description in `~/.claude/skills/structured-prompt-driven-development/SKILL.md` needs tightening.

5. **Prompt-first loop** (~2 min): in the original session, after a canvas exists, say:

   > "Actually, change the fast-model overage to $0.012/1K instead of $0.01/1K."

   The agent should:
   - Recognise this is a *behaviour change* (so prompt-first, not code-first).
   - Call `spdd_canvas_update_sections` with the Operations + worked-example sections.
   - Notice the canvas is now `InReview` (entity-enforced knock-back) and explain that re-approval is needed before the gate clears.

   Cross-check at `http://localhost:8080/canvases/<canvasId>` — version should bump to 2 and status be `InReview`.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Gateway not reachable at http://localhost:8080` | `sbt run` not started, or different port | Start it; or `BASE=http://localhost:<port> ./scripts/spdd/live-smoke.sh` |
| `SSE channel did not yield a sessionId` | Gateway started but `/mcp/sse` route not bound | Confirm `McpService.live` is in the layer (it is, in `ApplicationDI`) and look at gateway logs |
| `tools/list returned 0 spdd_* tools` | `SpddMcpTools` not wired into `McpService` (regression) | Run `sbt 'testOnly mcp.SpddMcpToolsSpec'` — should still pass |
| Claude Code does not show `llm4zio-gateway` | `.mcp.json` not picked up | Restart the Claude Code session; the file is read at session start |
| "Approved → still Approved after edit" | Entity rule regressed | Run `sbt 'testOnly canvas.entity.ReasonsCanvasSpec'` — should be 10/10 green |
| `spdd_canvas_search_similar` returns empty hits | Project has no Approved canvases yet | The smoke test deliberately leaves canvas in InReview; create + approve one first |

If the smoke test passes but Path B fails, the regression is in the Claude Code integration layer (skill triggering, MCP discovery), not in the gateway itself.
