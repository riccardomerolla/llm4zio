# Connectors UI Redesign — Design Spec

## Goal

Replace the single-form Connectors settings page with a two-column layout exposing both a default API connector and a default CLI connector, plus a per-agent assignment table with mode toggle and inline override forms.

## Architecture

The page is split into four vertical sections: two side-by-side cards for system defaults (API left, CLI right), a full-width agent assignment table, and the existing model registry / tools reference section at the bottom. Each card is an independent form. Agents choose API or CLI mode via a segmented toggle, and can override any field via an expandable inline form. All interactions use HTMX — no new JS components.

## Tech Stack

- Scalatags (SSR views in `SettingsView.scala`)
- HTMX for all dynamic interactions (mode toggle, override expand/save/reset)
- Existing `ConfigRepository` for settings persistence
- Existing `ConnectorConfigResolver` (extended for mode-aware default resolution)
- Tailwind CSS (existing styling system)

---

## 1. Page Layout

The `/settings/connectors` page renders three sections:

### 1.1 Default API Connector (left card)

A card form posting to `POST /settings/connectors/api` with fields:

| Field | Input Type | Settings Key |
|-------|-----------|--------------|
| Provider | Dropdown: `gemini-api` (Gemini API), `openai` (OpenAI), `anthropic` (Anthropic), `lm-studio` (LM Studio), `ollama` (Ollama). Values are `ConnectorId` strings. | `connector.default.api.id` |
| Model | Text | `connector.default.api.model` |
| Base URL | Text (placeholder shows provider default) | `connector.default.api.baseUrl` |
| API Key | Password | `connector.default.api.apiKey` |
| Timeout (seconds) | Number | `connector.default.api.timeout` |
| Max Retries | Number | `connector.default.api.maxRetries` |
| Requests/min | Number | `connector.default.api.requestsPerMinute` |
| Burst Size | Number | `connector.default.api.burstSize` |
| Acquire Timeout (seconds) | Number | `connector.default.api.acquireTimeout` |
| Temperature | Number (step 0.1) | `connector.default.api.temperature` |
| Max Tokens | Number | `connector.default.api.maxTokens` |
| Fallback Chain | Text (comma-separated `provider:model` pairs) | `connector.default.api.fallbackChain` |

Layout: Timeout/Max Retries in a two-column row. Requests-min/Burst Size in a two-column row. Temperature/Max Tokens in a two-column row. All others full-width.

### 1.2 Default CLI Connector (right card)

A card form posting to `POST /settings/connectors/cli` with fields:

| Field | Input Type | Settings Key |
|-------|-----------|--------------|
| Connector | Dropdown: `claude-cli` (Claude CLI), `gemini-cli` (Gemini CLI), `opencode` (OpenCode), `codex` (Codex), `copilot` (Copilot). Values are `ConnectorId` strings. | `connector.default.cli.id` |
| Model | Text (optional) | `connector.default.cli.model` |
| Timeout (seconds) | Number | `connector.default.cli.timeout` |
| Max Retries | Number | `connector.default.cli.maxRetries` |
| Turn Limit | Number | `connector.default.cli.turnLimit` |
| Sandbox | Dropdown (None, Docker, Podman, SeatbeltMacOS, Runsc, Lxc) | `connector.default.cli.sandbox` |
| Flags | Textarea (one flag per line) | `connector.default.cli.flags` |
| Environment Variables | Key-value pair list with add/remove | `connector.default.cli.envVars` |

Layout: Timeout/Max Retries in a two-column row. All others full-width. Env vars uses rows of two text inputs (key/value) + remove button, with an "Add" button that appends a row.

### 1.3 Agent Connector Assignments (full-width table)

A table with columns:

| Column | Content |
|--------|---------|
| Agent Name | Display name of the agent |
| Mode | Segmented control toggle: API / CLI |
| Connector | Effective connector ID (from default or override) |
| Model | Effective model (from default or override) |
| Status | Health indicator dot (green/yellow/red) |
| Actions | "Override" button + "Reset" link (when overridden) |

- Rows with active overrides show a "customized" badge next to the connector/model.
- Clicking "Override" expands an inline form below the row.
- Clicking "Reset" (with confirm dialog) clears the agent's override settings.

### 1.4 Override Inline Form

When expanded below an agent row, shows the full field set matching the agent's current mode:
- If mode = API: same fields as the API default card
- If mode = CLI: same fields as the CLI default card

All fields pre-populated from the system default values. Form posts to `POST /agents/{name}/connector`. On save, the row collapses and shows the "customized" badge.

---

### 1.5 Model Registry & Tools Reference (existing, kept as-is)

The existing read-only model registry table (grouped by provider with health status badges) and the lazy-loaded tools list remain at the bottom of the page, below the agent table. No changes to these sections.

---

## 2. Settings Key Schema

### System defaults

```
connector.default.api.id              = gemini-api
connector.default.api.model           = gemini-2.5-flash
connector.default.api.baseUrl         = (optional)
connector.default.api.apiKey          = (optional)
connector.default.api.timeout         = 300
connector.default.api.maxRetries      = 3
connector.default.api.requestsPerMinute = 60
connector.default.api.burstSize       = 10
connector.default.api.acquireTimeout  = 30
connector.default.api.temperature     = (optional)
connector.default.api.maxTokens       = (optional)
connector.default.api.fallbackChain   = (optional, comma-separated provider:model pairs)

connector.default.cli.id              = claude-cli
connector.default.cli.model           = (optional)
connector.default.cli.timeout         = 300
connector.default.cli.maxRetries      = 3
connector.default.cli.turnLimit       = 50
connector.default.cli.sandbox         = none
connector.default.cli.flags           = (optional, newline-separated)
connector.default.cli.envVars         = (optional, comma-separated key=value pairs)
```

### Per-agent overrides

```
agent.<name>.connector.mode           = api | cli
agent.<name>.connector.api.*          = (same leaf keys as connector.default.api.*)
agent.<name>.connector.cli.*          = (same leaf keys as connector.default.cli.*)
```

### Backward compatibility

The `POST /settings/connectors/api` handler dual-writes `ai.*` keys alongside `connector.default.api.*` keys so the legacy `AgentConfigResolver` continues to work during the transition period.

---

## 3. Controller Routes

### New/modified routes in `SettingsController`

| Method | Path | Behavior |
|--------|------|----------|
| `GET` | `/settings/connectors` | Load all defaults + agent list + overrides, render full page |
| `POST` | `/settings/connectors/api` | Save API default card, dual-write `ai.*`, update `Ref[GatewayConfig]`, HTMX swap card |
| `POST` | `/settings/connectors/cli` | Save CLI default card, HTMX swap card |
| `PUT` | `/agents/{name}/connector/mode` | Save mode toggle, HTMX swap row |
| `GET` | `/agents/{name}/connector/edit` | Load override form pre-filled from defaults, HTMX swap into panel |
| `POST` | `/agents/{name}/connector` | Save agent override, HTMX swap collapsed row with badge |
| `DELETE` | `/agents/{name}/connector` | Delete all `agent.<name>.connector.*` keys, HTMX swap row |

### Removed routes

- `POST /settings/connectors` (old single-form handler) — replaced by `/api` and `/cli` variants

### Kept routes (backward compat)

- `GET /settings/ai` — redirects to `/settings/connectors`
- `GET /settings/advanced` — redirects to `/settings/connectors`

---

## 4. ConnectorConfigResolver Changes

The existing `ConnectorConfigResolver` has 3-tier resolution: `agent.<name>.connector.*` > `connector.default.*` > `ai.*` (legacy).

**Change needed:** Split the default tier into `connector.default.api.*` and `connector.default.cli.*`. The resolver reads `agent.<name>.connector.mode` (defaulting to `api` if absent) to decide which default branch to consult.

Resolution order becomes:
1. `agent.<name>.connector.{api|cli}.*` (agent override for the active mode)
2. `connector.default.{api|cli}.*` (system default for the active mode)
3. `ai.*` (legacy fallback, API-only)

---

## 5. View Architecture

### Modified files

**`SettingsView.scala`** — Replace `connectorsTab()` entirely with:
- `defaultConnectorsSection()` — flex row container for the two cards
- `apiDefaultCard(settings: Map[String, String])` — API card form
- `cliDefaultCard(settings: Map[String, String])` — CLI card form
- `agentConnectorTable(agents: List[Agent], overrides: Map[String, Map[String, String]])` — assignments table
- `agentRow(agent, mode, overrides, defaults)` — single table row
- `agentOverrideForm(agent, mode, defaults)` — expandable inline form

**`AgentsView.scala`** — Remove the `agentConfigPage` AI provider override section. Replace with a read-only summary that says "Connector settings configured on the Connectors page" with a link to `/settings/connectors`.

**`SettingsController.scala`** — Add new routes (Section 3). Remove old single-form `POST /settings/connectors`.

### HTMX interactions

| Interaction | HTMX Attributes |
|------------|-----------------|
| Mode toggle | `hx-put="/agents/{name}/connector/mode"` `hx-target="closest tr"` `hx-swap="outerHTML"` |
| Override expand | `hx-get="/agents/{name}/connector/edit"` `hx-target="next .override-panel"` `hx-swap="innerHTML"` |
| Override save | `hx-post="/agents/{name}/connector"` `hx-target="closest tr"` `hx-swap="outerHTML"` |
| Override reset | `hx-delete="/agents/{name}/connector"` `hx-target="closest tr"` `hx-swap="outerHTML"` `hx-confirm="Reset to defaults?"` |
| Save API default | `hx-post="/settings/connectors/api"` `hx-target="#api-card"` `hx-swap="outerHTML"` |
| Save CLI default | `hx-post="/settings/connectors/cli"` `hx-target="#cli-card"` `hx-swap="outerHTML"` |

### Env vars key-value editor

Server-rendered rows of two text inputs (key/value) + a remove button. "Add" button fetches an empty row from the server via `hx-get="/settings/connectors/cli/env-row"` and appends it. Form submission serializes as `envVars.key.0=FOO&envVars.value.0=bar&envVars.key.1=BAZ&envVars.value.1=qux`. The controller joins them into the comma-separated `key=value` storage format.

---

## 6. Testing

### Unit tests

**`ConnectorConfigResolverSpec`** (extend existing):
- API mode agent inherits `connector.default.api.*` defaults
- CLI mode agent inherits `connector.default.cli.*` defaults
- Agent override takes precedence over system default
- Reset (no agent keys) falls back to system default
- Missing mode defaults to `api`
- Legacy `ai.*` fallback still works when no `connector.default.api.*` keys exist

**`SettingsControllerSpec`** (new or extend):
- `POST /settings/connectors/api` persists `connector.default.api.*` and dual-writes `ai.*`
- `POST /settings/connectors/cli` persists `connector.default.cli.*`
- `PUT /agents/{name}/connector/mode` saves mode key
- `POST /agents/{name}/connector` saves override keys in correct namespace
- `DELETE /agents/{name}/connector` removes all agent connector keys
- `GET /settings/connectors` renders both cards and agent table

**`SettingsViewSpec`** (new):
- Rendered HTML contains both card forms with correct `action` attributes
- Field names match expected settings key patterns
- Agent table renders rows with mode toggle and actions
- Override form pre-fills values from defaults

### No new integration tests

Settings persistence is already covered by existing `ConfigRepository` integration tests. The new code is routing + view rendering on top of existing infrastructure.
