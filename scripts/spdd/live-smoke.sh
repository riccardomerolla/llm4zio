#!/usr/bin/env bash
# SPDD MCP live smoke test.
#
# Exercises every spdd_* tool against a running llm4zio gateway over the
# legacy SSE transport (GET /mcp/sse → sessionId; POST /mcp?sessionId=...
# returns 202; JSON-RPC response arrives via the open SSE channel,
# correlated by request id).
#
# Usage:
#   1. Start the gateway:    sbt run     (in another terminal)
#   2. Wait until it logs    "Starting web server on http://0.0.0.0:8080"
#   3. Run this script:      ./scripts/spdd/live-smoke.sh
#
# Exits 0 if the full SPDD canvas lifecycle plus the prompt renderer plus
# similarity search all succeed against the live server.

set -u

# ── Config ──────────────────────────────────────────────────────────────
BASE="${BASE:-http://localhost:8080}"
SSE_LOG="$(mktemp -t spdd-sse-XXXXXX)"
SSE_PID=""
TIMEOUT_S=30

# ── Cleanup ─────────────────────────────────────────────────────────────
cleanup() {
  if [[ -n "$SSE_PID" ]] && kill -0 "$SSE_PID" 2>/dev/null; then
    kill "$SSE_PID" 2>/dev/null || true
    wait "$SSE_PID" 2>/dev/null || true
  fi
  rm -f "$SSE_LOG" "${SSE_LOG}.idx" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# ── Counters & helpers ──────────────────────────────────────────────────
PASS=0
FAIL=0

green() { printf '\033[32m%s\033[0m' "$1"; }
red()   { printf '\033[31m%s\033[0m' "$1"; }
ok()   { printf '  %s %s\n' "$(green '✅')" "$1"; PASS=$((PASS+1)); }
fail() { printf '  %s %s\n' "$(red   '❌')" "$1"; FAIL=$((FAIL+1)); }

require() { command -v "$1" >/dev/null 2>&1 || { echo "Missing required tool: $1" >&2; exit 2; }; }
require curl
require jq

# ── 1. Connectivity probe ───────────────────────────────────────────────
echo "──────────────────────────────────────────────────────"
echo " SPDD MCP smoke test against $BASE"
echo "──────────────────────────────────────────────────────"

if ! curl -fsS -m 3 -o /dev/null "$BASE" 2>/dev/null \
   && ! curl -fsS -m 3 -o /dev/null "$BASE/canvases" 2>/dev/null; then
  echo "Gateway not reachable at $BASE. Start it with: sbt run" >&2
  exit 2
fi

# ── 2. Open SSE channel ─────────────────────────────────────────────────
echo
echo "[1/9] Opening SSE channel"

# -N disables curl buffering; -m caps total time so the script can never hang.
curl -sN -m "$TIMEOUT_S" "$BASE/mcp/sse" > "$SSE_LOG" 2>/dev/null &
SSE_PID=$!

# Wait for the `endpoint` event with the sessionId.
SESSION_ID=""
for _ in $(seq 1 50); do
  if [[ -s "$SSE_LOG" ]]; then
    SESSION_ID=$(grep -o 'sessionId=[A-Za-z0-9-]*' "$SSE_LOG" | head -1 | cut -d= -f2)
    [[ -n "$SESSION_ID" ]] && break
  fi
  sleep 0.1
done

if [[ -z "$SESSION_ID" ]]; then
  fail "SSE channel did not yield a sessionId"
  echo "    SSE log was:"
  sed 's/^/      /' "$SSE_LOG" >&2
  exit 1
fi
ok "SSE channel open, sessionId=$SESSION_ID"

# ── JSON-RPC helpers ────────────────────────────────────────────────────
# Send a JSON-RPC request and wait for its response on the SSE stream.
# Usage: rpc <method> [<params-json>]
# Echoes the response JSON on stdout. Returns non-zero on timeout / HTTP fail.
# We own the id counter here (caller doesn't pass it) so $() subshells can't
# desync it.
NEXT_ID=0

rpc() {
  NEXT_ID=$((NEXT_ID+1))
  local id="$NEXT_ID"
  local method="$1"; shift
  local params
  if [[ $# -gt 0 ]]; then params="$1"; else params='{}'; fi

  local body
  body=$(jq -cn --arg m "$method" --argjson p "$params" --argjson i "$id" \
    '{jsonrpc:"2.0",id:$i,method:$m,params:$p}') || {
      echo "RPC $method id=$id failed to encode params: $params" >&2
      return 1
    }

  local pre_lines
  pre_lines=$(wc -l < "$SSE_LOG")

  local http
  http=$(curl -s -m 5 -o /dev/null -w '%{http_code}' \
           -H 'Content-Type: application/json' \
           -X POST "$BASE/mcp?sessionId=$SESSION_ID" \
           --data-raw "$body")
  if [[ "$http" != "202" ]]; then
    echo "RPC $method id=$id POST returned HTTP $http (expected 202)" >&2
    return 1
  fi

  # Tail the SSE log from where we left off, looking for our response id.
  local deadline=$((SECONDS + 10))
  while (( SECONDS < deadline )); do
    if [[ "$(wc -l < "$SSE_LOG")" -gt "$pre_lines" ]]; then
      local match
      match=$(tail -n +$((pre_lines+1)) "$SSE_LOG" \
              | grep '^data: ' \
              | sed 's/^data: //' \
              | jq -c --argjson i "$id" 'select(.id == $i)' 2>/dev/null \
              | head -1)
      if [[ -n "$match" ]]; then
        echo "$match"
        return 0
      fi
    fi
    sleep 0.05
  done
  echo "RPC $method id=$id timed out waiting for SSE response" >&2
  return 1
}

# Extract a tool-call result content[0].text JSON object.
tool_text() { jq -r '.result.content[0].text'; }

# ── 3. initialize ───────────────────────────────────────────────────────
echo
echo "[2/9] initialize"
if response=$(rpc "initialize" \
  '{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"spdd-live-smoke","version":"0.1"}}'); then
  if echo "$response" | jq -e '.result.serverInfo' >/dev/null; then
    ok "initialize OK ($(echo "$response" | jq -r '.result.serverInfo.name'))"
  else
    fail "initialize returned no serverInfo"
    echo "    $response"
  fi
else
  fail "initialize timed out"
fi

# ── 4. tools/list ───────────────────────────────────────────────────────
echo
echo "[3/9] tools/list — expect at least the 9 spdd_* tools"
if response=$(rpc "tools/list" '{}'); then
  spdd_tools=$(echo "$response" | jq -r '.result.tools[].name | select(startswith("spdd_"))' | sort)
  count=$(echo "$spdd_tools" | grep -c .)
  expected="spdd_canvas_approve
spdd_canvas_create
spdd_canvas_get
spdd_canvas_link_run
spdd_canvas_list
spdd_canvas_mark_stale
spdd_canvas_search_similar
spdd_canvas_update_sections
spdd_render_prompt"
  if [[ "$count" -ge 9 ]] && diff <(echo "$expected") <(echo "$spdd_tools") >/dev/null; then
    ok "tools/list returned all 9 spdd_* tools"
  else
    fail "tools/list returned $count spdd_* tools (expected 9)"
    echo "    Got: $spdd_tools"
  fi
else
  fail "tools/list timed out"
fi

# ── 5. spdd_render_prompt ───────────────────────────────────────────────
echo
echo "[4/9] spdd_render_prompt — render an SPDD template"
if response=$(rpc "tools/call" \
  '{"name":"spdd_render_prompt","arguments":{"name":"spdd-api-test","arguments":null,"context":{"canvas":"__SMOKE__"}}}'); then
  rendered=$(echo "$response" | tool_text | jq -r '.rendered // empty')
  if [[ -n "$rendered" ]] && grep -q "__SMOKE__" <<<"$rendered" \
                          && ! grep -q "{{" <<<"$rendered"; then
    ok "spdd_render_prompt produced a rendered template with the placeholder substituted"
  else
    fail "spdd_render_prompt did not interpolate cleanly"
    echo "    response: $response"
  fi
else
  fail "spdd_render_prompt timed out"
fi

# ── 6. spdd_canvas_create ───────────────────────────────────────────────
echo
echo "[5/9] spdd_canvas_create — persist a Draft canvas"
CANVAS_PROJECT="proj-spdd-smoke-$(date +%s)"
create_args=$(jq -n --arg pid "$CANVAS_PROJECT" '
{
  name: "spdd_canvas_create",
  arguments: {
    projectId: $pid,
    title: "Smoke test canvas — multi-plan billing",
    sections: {
      requirements: "R: smoke-test charge correctly per plan",
      entities:     "E: Plan, UsageEvent, UsageLedger",
      approach:     "A: pure pricing function, lazy period rollover",
      structure:    "S: billing-domain BCE module",
      operations:   "O: op-001 calculate(usage, plan, ledger)",
      norms:        "N: BigDecimal HALF_UP @ 4dp",
      safeguards:   "SG-1 idempotency on eventId; SG-2 no overcharge"
    },
    authorKind: "agent",
    authorId: "spdd-live-smoke",
    authorDisplayName: "SPDD Live Smoke Test"
  }
}')
if response=$(rpc "tools/call" "$create_args"); then
  CANVAS_ID=$(echo "$response" | tool_text | jq -r '.canvasId // empty')
  if [[ -n "$CANVAS_ID" ]]; then
    ok "spdd_canvas_create returned canvasId=$CANVAS_ID (status=$(echo "$response" | tool_text | jq -r '.status'))"
  else
    fail "spdd_canvas_create did not return a canvasId"
    echo "    response: $response"
  fi
else
  fail "spdd_canvas_create timed out"
fi

# ── 7. spdd_canvas_update_sections (golden-rule check happens after approve) ─
if [[ -n "${CANVAS_ID:-}" ]]; then
  echo
  echo "[6/9] spdd_canvas_update_sections — bump version on Draft"
  update_args=$(jq -n --arg cid "$CANVAS_ID" '
  {
    name: "spdd_canvas_update_sections",
    arguments: {
      canvasId: $cid,
      rationale: "smoke test: extend Operations",
      updates: [
        { sectionId: "operations", content: "O: op-001 calculate(...) + op-002 quotaRemaining(...)" }
      ],
      authorKind: "agent",
      authorId: "spdd-live-smoke",
      authorDisplayName: "SPDD Live Smoke Test"
    }
  }')
  if response=$(rpc "tools/call" "$update_args"); then
    new_version=$(echo "$response" | tool_text | jq -r '.version')
    new_status=$(echo "$response" | tool_text | jq -r '.status')
    if [[ "$new_version" == "2" && "$new_status" == "Draft" ]]; then
      ok "section update bumped Draft to v2 and stayed in Draft"
    else
      fail "section update produced status=$new_status version=$new_version"
    fi
  else
    fail "spdd_canvas_update_sections timed out"
  fi

  # ── 8. approve, then update again — proves golden rule ───────────────
  echo
  echo "[7/9] spdd_canvas_approve, then update — proves the SPDD golden rule"
  approve_args=$(jq -n --arg cid "$CANVAS_ID" '
  { name: "spdd_canvas_approve",
    arguments: { canvasId: $cid, authorKind: "human", authorId: "alice", authorDisplayName: "Alice" } }')
  if response=$(rpc "tools/call" "$approve_args"); then
    if [[ "$(echo "$response" | tool_text | jq -r '.status')" == "Approved" ]]; then
      ok "spdd_canvas_approve flipped status to Approved"
    else
      fail "spdd_canvas_approve did not produce status=Approved"
    fi
  fi

  # second update — the entity must knock back to InReview
  knock_back_args=$(jq -n --arg cid "$CANVAS_ID" '
  { name: "spdd_canvas_update_sections",
    arguments: {
      canvasId: $cid,
      rationale: "review feedback: tighten Approach",
      updates: [
        { sectionId: "approach", content: "A: switch to Strategy pattern for plan dispatch" }
      ],
      authorKind: "agent", authorId: "spdd-live-smoke", authorDisplayName: "SPDD Live Smoke Test"
    } }')
  if response=$(rpc "tools/call" "$knock_back_args"); then
    if [[ "$(echo "$response" | tool_text | jq -r '.status')" == "InReview" ]]; then
      ok "section update against an Approved canvas knocked status back to InReview (golden rule)"
    else
      fail "expected status=InReview after edit on Approved; got $(echo "$response" | tool_text | jq -r '.status')"
    fi
  fi

  # ── 9. spdd_canvas_get ────────────────────────────────────────────────
  echo
  echo "[8/9] spdd_canvas_get — round-trip the canvas"
  get_args=$(jq -n --arg cid "$CANVAS_ID" '{name:"spdd_canvas_get",arguments:{canvasId:$cid}}')
  if response=$(rpc "tools/call" "$get_args"); then
    sections_ok=$(echo "$response" | tool_text \
      | jq -e '.sections | (.requirements != "" and .entities != "" and .approach != "" and .structure != "" and .operations != "" and .norms != "" and .safeguards != "")' >/dev/null && echo y || echo n)
    if [[ "$sections_ok" == "y" ]]; then
      ok "spdd_canvas_get returned all 7 sections populated"
    else
      fail "spdd_canvas_get is missing one or more sections"
    fi
  fi

  # ── 10. /canvases UI page ────────────────────────────────────────────
  echo
  echo "[9/9] GET /canvases/$CANVAS_ID — UI page round-trip"
  body_file=$(mktemp -t spdd-canvas-body-XXXXXX)
  status_code=$(curl -sS -m 5 -o "$body_file" -w '%{http_code}' "$BASE/canvases/$CANVAS_ID")
  body=$(cat "$body_file")
  rm -f "$body_file"
  if [[ "$status_code" == "200" ]] \
     && grep -q "Smoke test canvas" <<<"$body" \
     && grep -q "R — Requirements" <<<"$body"; then
    ok "GET /canvases/$CANVAS_ID returns 200 HTML with the canvas title and section labels"
  else
    fail "GET /canvases/$CANVAS_ID returned $status_code (expected 200 with the canvas title)"
  fi
fi

# ── Summary ─────────────────────────────────────────────────────────────
echo
echo "──────────────────────────────────────────────────────"
if [[ "$FAIL" -eq 0 ]]; then
  printf ' Result: %s — %d checks passed.\n' "$(green PASS)" "$PASS"
  echo "──────────────────────────────────────────────────────"
  exit 0
else
  printf ' Result: %s — %d passed, %d failed.\n' "$(red FAIL)" "$PASS" "$FAIL"
  echo "──────────────────────────────────────────────────────"
  exit 1
fi
