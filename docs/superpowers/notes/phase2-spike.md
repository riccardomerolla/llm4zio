# Phase 2 feasibility spike — findings (2026-06-03, claude 2.1.161)

Gate result: **PASS — Phase 2 is buildable as planned, including approval gates.**

## 1. Claude bidirectional stream-json (foundation for `Conversation`) — ✅
`claude --print --input-format stream-json --output-format stream-json --verbose` reads
newline-delimited user messages on **stdin** and emits stream-json events on stdout.

User-message framing that works:
```json
{"type":"user","message":{"role":"user","content":"<text>"}}
```
A single piped message produced `system/init` → `assistant` (content blocks) → `result success`
(plus hook/rate-limit system events), exit 0, assistant text in `message.content[].text`. Same
event shape as the Phase-1 `claude-stream.jsonl` fixture, so the existing
`ClaudeCliConnector.parseStreamLine` parsing carries over. Multi-turn = keep stdin open and write
another `{"type":"user",...}` line (held process — `runBidirectional` primitive, Task 3).

## 2. Approval routing — ✅ (flag accepted)
`--permission-prompt-tool <mcp-tool>` is **accepted** by 2.1.161 (hidden from `--help`, but not
rejected as an unknown option; the session inits normally with it). `--mcp-config <files...>` and
`--strict-mcp-config` are present. So routing tool-approval to an MCP tool (Task 7) is viable; the
exact request/response protocol gets nailed down when the MCP server is built (Tasks 5/7).
Static fallbacks also exist: `--permission-mode`, `--allowedTools`/`--disallowedTools`.

## 3. `ask_user` MCP — ✅ (mechanism present)
`--mcp-config` loads custom MCP servers; allow the tool via `--allowedTools`. A small
`ask_user(question)->answer` MCP server (Task 5) bridges to the existing `Interaction`.

## 4. codex / gemini — limited (expected)
codex `exec`/gemini headless are one-shot, not held bidirectional sessions with approval routing.
**Interactive mode (B) is claude-only**; codex/gemini stay autonomous-parity. Accepted asymmetry.

## Implication for the plan
No descope. Proceed Task 2 → … → Task 8. The exact stdin framing above + the
`--permission-prompt-tool`/`--mcp-config` flags feed Tasks 4/5/7.
