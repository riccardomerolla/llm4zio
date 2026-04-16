# Connectors UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single-form Connectors settings page with a two-column API/CLI default layout plus per-agent mode toggle and inline override forms.

**Architecture:** The page splits into: two side-by-side card forms (API default left, CLI default right), a full-width agent assignment table with HTMX-driven mode toggle and expandable override forms, and the existing model registry / tools sections at the bottom. The `ConnectorConfigResolver` is extended to resolve `connector.default.api.*` or `connector.default.cli.*` based on agent mode. All interactions use HTMX — no new JS components.

**Tech Stack:** Scala 3 + ZIO 2, Scalatags (SSR), HTMX, Tailwind CSS, existing `ConfigRepository` for persistence.

---

## File Structure

### Modified files

| File | Responsibility |
|------|---------------|
| `modules/shared-web/src/main/scala/shared/web/SettingsView.scala` | Replace `connectorsTab()` with two-card layout + agent table + override forms |
| `modules/shared-web/src/main/scala/shared/web/HtmlViews.scala` | Update `settingsConnectorsTab` signature to accept agent list + overrides |
| `src/main/scala/config/boundary/SettingsController.scala` | Add new routes for split API/CLI save, agent mode toggle, override CRUD |
| `src/main/scala/config/control/ConnectorConfigResolver.scala` | Mode-aware default resolution (`connector.default.api.*` vs `connector.default.cli.*`) |
| `modules/agent-domain/src/main/scala/agent/boundary/AgentsView.scala` | Replace `agentConfigPage` AI section with link to Connectors tab |

### Test files

| File | What it tests |
|------|--------------|
| `src/test/scala/config/control/ConnectorConfigResolverSpec.scala` | Mode-aware resolution, API/CLI default branches |
| `src/test/scala/config/boundary/SettingsControllerSpec.scala` (new) | New routes: split save, mode toggle, override CRUD |

---

### Task 1: Extend ConnectorConfigResolver for mode-aware defaults

**Files:**
- Modify: `src/main/scala/config/control/ConnectorConfigResolver.scala`
- Test: `src/test/scala/config/control/ConnectorConfigResolverSpec.scala`

- [ ] **Step 1: Write failing tests for mode-aware resolution**

Add these tests to `ConnectorConfigResolverSpec.scala`:

```scala
test("resolves connector.default.api.* when agent mode is api") {
  val repo     = new StubConfigRepository(
    Map(
      "connector.default.api.id"     -> "openai",
      "connector.default.api.model"  -> "gpt-4o",
      "connector.default.api.apiKey" -> "sk-test",
      "connector.default.cli.id"     -> "claude-cli",
      "connector.default.cli.model"  -> "sonnet",
    )
  )
  val resolver = ConnectorConfigResolverLive(repo)
  for config <- resolver.resolve(agentName = None)
  yield assertTrue(
    config.connectorId == ConnectorId.OpenAI,
    config.model == Some("gpt-4o"),
    config.isInstanceOf[ApiConnectorConfig],
  )
},
test("resolves connector.default.cli.* when agent mode is cli") {
  val repo     = new StubConfigRepository(
    Map(
      "connector.default.api.id"         -> "openai",
      "connector.default.api.model"      -> "gpt-4o",
      "connector.default.cli.id"         -> "claude-cli",
      "connector.default.cli.model"      -> "sonnet",
      "agent.coder.connector.mode"       -> "cli",
    )
  )
  val resolver = ConnectorConfigResolverLive(repo)
  for config <- resolver.resolve(agentName = Some("coder"))
  yield assertTrue(
    config.connectorId == ConnectorId.ClaudeCli,
    config.model == Some("sonnet"),
    config.isInstanceOf[CliConnectorConfig],
  )
},
test("agent mode defaults to api when not set") {
  val repo     = new StubConfigRepository(
    Map(
      "connector.default.api.id"    -> "anthropic",
      "connector.default.api.model" -> "claude-sonnet-4",
      "connector.default.cli.id"    -> "claude-cli",
    )
  )
  val resolver = ConnectorConfigResolverLive(repo)
  for config <- resolver.resolve(agentName = Some("reviewer"))
  yield assertTrue(
    config.connectorId == ConnectorId.Anthropic,
    config.model == Some("claude-sonnet-4"),
    config.isInstanceOf[ApiConnectorConfig],
  )
},
test("agent api override takes precedence over api defaults") {
  val repo     = new StubConfigRepository(
    Map(
      "connector.default.api.id"          -> "openai",
      "connector.default.api.model"       -> "gpt-4o",
      "agent.planner.connector.mode"      -> "api",
      "agent.planner.connector.api.id"    -> "anthropic",
      "agent.planner.connector.api.model" -> "claude-sonnet-4",
    )
  )
  val resolver = ConnectorConfigResolverLive(repo)
  for config <- resolver.resolve(agentName = Some("planner"))
  yield assertTrue(
    config.connectorId == ConnectorId.Anthropic,
    config.model == Some("claude-sonnet-4"),
  )
},
test("agent cli override takes precedence over cli defaults") {
  val repo     = new StubConfigRepository(
    Map(
      "connector.default.cli.id"        -> "claude-cli",
      "connector.default.cli.model"     -> "sonnet",
      "agent.coder.connector.mode"      -> "cli",
      "agent.coder.connector.cli.id"    -> "gemini-cli",
      "agent.coder.connector.cli.model" -> "gemini-2.5-pro",
    )
  )
  val resolver = ConnectorConfigResolverLive(repo)
  for config <- resolver.resolve(agentName = Some("coder"))
  yield assertTrue(
    config.connectorId == ConnectorId.GeminiCli,
    config.model == Some("gemini-2.5-pro"),
  )
},
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `sbt 'testOnly config.control.ConnectorConfigResolverSpec'`
Expected: New tests FAIL because the resolver doesn't understand `connector.default.api.*` / `connector.default.cli.*` split or `agent.<name>.connector.mode`.

- [ ] **Step 3: Implement mode-aware resolution**

Replace the `buildConfig` method in `ConnectorConfigResolverLive`:

```scala
private def buildConfig(
  agentSettings: Map[String, String],
  globalSettings: Map[String, String],
  legacySettings: Map[String, String],
  agentName: Option[String],
): ConnectorConfig =
  // Strip prefixes for uniform key access
  val agentRaw = agentName.fold(Map.empty[String, String])(name =>
    agentSettings.map { case (k, v) => k.stripPrefix(s"agent.$name.connector.") -> v }
  )
  val global   = globalSettings.map { case (k, v) => k.stripPrefix("connector.default.") -> v }
  val legacy   = legacySettings.map { case (k, v) => k.stripPrefix("ai.") -> v }

  // Determine agent mode: api or cli (default: api)
  val mode = agentRaw.get("mode").map(_.trim.toLowerCase).getOrElse("api")

  // Build mode-scoped lookup: agent.X.connector.{api|cli}.* > connector.default.{api|cli}.* > flat fallback
  val agentScoped = agentRaw.collect { case (k, v) if k.startsWith(s"$mode.") => k.stripPrefix(s"$mode.") -> v }
  val globalScoped = global.collect { case (k, v) if k.startsWith(s"$mode.") => k.stripPrefix(s"$mode.") -> v }
  // Flat keys (no api./cli. prefix) serve as additional fallback for backward compat
  val agentFlat = agentRaw.filterNot(kv => kv._1.startsWith("api.") || kv._1.startsWith("cli.") || kv._1 == "mode")
  val globalFlat = global.filterNot(kv => kv._1.startsWith("api.") || kv._1.startsWith("cli."))

  def get(key: String): Option[String] =
    agentScoped.get(key).filter(_.nonEmpty)
      .orElse(agentFlat.get(key).filter(_.nonEmpty))
      .orElse(globalScoped.get(key).filter(_.nonEmpty))
      .orElse(globalFlat.get(key).filter(_.nonEmpty))
      .orElse(legacy.get(key).filter(_.nonEmpty))

  val connectorId = get("id")
    .flatMap(parseConnectorId)
    .orElse(get("provider").flatMap(parseLegacyProvider))
    .getOrElse(if mode == "cli" then ConnectorId.ClaudeCli else ConnectorId.GeminiCli)

  val model   = get("model")
  val timeout = get("timeout").flatMap(s => scala.util.Try(s.toLong).toOption).map(_.seconds).getOrElse(300.seconds)
  val retries = get("maxRetries").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(3)

  if ConnectorId.allCli.contains(connectorId) then
    CliConnectorConfig(
      connectorId = connectorId,
      model = model,
      timeout = timeout,
      maxRetries = retries,
      flags = extractFlags(agentScoped ++ agentFlat, globalScoped ++ globalFlat),
      sandbox = get("sandbox").flatMap(parseSandbox),
      turnLimit = get("turnLimit").flatMap(s => scala.util.Try(s.toInt).toOption),
      envVars = extractEnvVars(agentScoped ++ agentFlat, globalScoped ++ globalFlat),
    )
  else
    ApiConnectorConfig(
      connectorId = connectorId,
      model = model,
      baseUrl = get("baseUrl"),
      apiKey = get("apiKey"),
      timeout = timeout,
      maxRetries = retries,
      requestsPerMinute =
        get("requestsPerMinute").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(60),
      burstSize = get("burstSize").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(10),
      acquireTimeout =
        get("acquireTimeout").flatMap(s => scala.util.Try(s.toLong).toOption).map(_.seconds).getOrElse(30.seconds),
      temperature = get("temperature").flatMap(s => scala.util.Try(s.toDouble).toOption),
      maxTokens = get("maxTokens").flatMap(s => scala.util.Try(s.toInt).toOption),
    )

private def parseSandbox(value: String): Option[CliSandbox] =
  value.trim.toLowerCase match
    case "docker"        => Some(CliSandbox.Docker)
    case "podman"        => Some(CliSandbox.Podman)
    case "seatbeltmacos" => Some(CliSandbox.SeatbeltMacOS)
    case "runsc"         => Some(CliSandbox.Runsc)
    case "lxc"           => Some(CliSandbox.Lxc)
    case _               => None
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `sbt 'testOnly config.control.ConnectorConfigResolverSpec'`
Expected: All tests PASS (old + new).

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/config/control/ConnectorConfigResolver.scala src/test/scala/config/control/ConnectorConfigResolverSpec.scala
git commit -m "feat: add mode-aware default resolution (api/cli split) to ConnectorConfigResolver"
```

---

### Task 2: Rewrite SettingsView with two-column layout

**Files:**
- Modify: `modules/shared-web/src/main/scala/shared/web/SettingsView.scala`
- Modify: `modules/shared-web/src/main/scala/shared/web/HtmlViews.scala`

- [ ] **Step 1: Update HtmlViews.settingsConnectorsTab signature**

In `HtmlViews.scala`, update the `settingsConnectorsTab` method to accept agent data:

```scala
def settingsConnectorsTab(
  settings: Map[String, String],
  registry: ModelRegistryResponse,
  statuses: List[ProviderProbeStatus],
  agents: List[AgentInfo] = Nil,
  agentOverrides: Map[String, Map[String, String]] = Map.empty,
  flash: Option[String] = None,
  errors: Map[String, String] = Map.empty,
): String =
  SettingsView.connectorsTab(settings, registry, statuses, agents, agentOverrides, flash, errors)
```

Add import for `AgentInfo` if not already present (`config.entity.AgentInfo`).

- [ ] **Step 2: Rewrite SettingsView.connectorsTab**

Replace the `connectorsTab` method in `SettingsView.scala` with the new signature and body. The new method delegates to helper methods for each section:

```scala
def connectorsTab(
  settings: Map[String, String],
  registry: config.entity.ModelRegistryResponse,
  statuses: List[config.entity.ProviderProbeStatus],
  agents: List[config.entity.AgentInfo] = Nil,
  agentOverrides: Map[String, Map[String, String]] = Map.empty,
  flash: Option[String] = None,
  errors: Map[String, String] = Map.empty,
): String =
  val statusMap = statuses.map(ps => ps.provider -> ps).toMap
  settingsShell("connectors", "Settings — Connectors")(
    flash.map { msg =>
      div(cls := "mb-6 rounded-md bg-green-500/10 border border-green-500/30 p-4")(
        p(cls := "text-sm text-green-400")(msg)
      )
    },
    if errors.nonEmpty then
      div(cls := "mb-6 rounded-md bg-red-500/10 border border-red-500/30 p-4")(
        p(cls := "text-sm font-semibold text-red-400")("Validation Errors"),
        ul(cls := "text-xs text-red-300 mt-2 space-y-1")(
          errors.map { case (key, msg) => li(s"$key: $msg") }.toSeq*
        ),
      )
    else (),
    // Section 1: Two-column default connector cards
    defaultConnectorsSection(settings, errors),
    // Section 2: Agent connector assignments
    agentConnectorTable(agents, agentOverrides, settings),
    // Section 3: Model registry (existing)
    modelRegistrySection(registry, statusMap),
    // Section 4: Tools list (existing)
    toolsSection,
    // Inline JS for env-var add-row
    envVarsScript,
  )
```

- [ ] **Step 3: Add defaultConnectorsSection helper**

```scala
private def defaultConnectorsSection(s: Map[String, String], errors: Map[String, String]): Frag =
  div(cls := "grid grid-cols-1 lg:grid-cols-2 gap-6 mb-8")(
    apiDefaultCard(s, errors),
    cliDefaultCard(s, errors),
  )
```

- [ ] **Step 4: Add apiDefaultCard helper**

```scala
def apiDefaultCard(s: Map[String, String], errors: Map[String, String]): Frag =
  div(id := "api-card", cls := sectionCls)(
    h2(cls := "text-lg font-semibold text-white mb-4")("Default API Connector"),
    tag("form")(
      attr("hx-post") := "/settings/connectors/api",
      attr("hx-target") := "#api-card",
      attr("hx-swap") := "outerHTML",
      cls := "space-y-4",
    )(
      div(
        label(cls := labelCls, `for` := "connector.default.api.id")("Provider"),
        tag("select")(
          name := "connector.default.api.id",
          id := "connector.default.api.id",
          cls := selectCls,
        )(
          connectorOption("gemini-api", "Gemini API", s.get("connector.default.api.id")),
          connectorOption("openai", "OpenAI", s.get("connector.default.api.id")),
          connectorOption("anthropic", "Anthropic", s.get("connector.default.api.id")),
          connectorOption("lm-studio", "LM Studio (Local)", s.get("connector.default.api.id")),
          connectorOption("ollama", "Ollama (Local)", s.get("connector.default.api.id")),
        ),
        p(cls := "text-xs text-gray-400 mt-1")(
          "LM Studio and Ollama run locally. Cloud providers require API keys."
        ),
      ),
      textField("connector.default.api.model", "Model", s, placeholder = "gemini-2.5-flash"),
      textField(
        "connector.default.api.baseUrl",
        "Base URL",
        s,
        placeholder = "Optional: http://localhost:1234 (LM Studio), http://localhost:11434 (Ollama)",
      ),
      div(
        label(cls := labelCls, `for` := "connector.default.api.apiKey")("API Key"),
        input(
          `type` := "password",
          name := "connector.default.api.apiKey",
          id := "connector.default.api.apiKey",
          value := s.getOrElse("connector.default.api.apiKey", ""),
          attr("placeholder") := "Enter API key (optional)",
          cls := inputCls,
        ),
      ),
      div(cls := "grid grid-cols-2 gap-4")(
        numberField("connector.default.api.timeout", "Timeout (s)", s, default = "300", min = "10", max = "900"),
        numberField("connector.default.api.maxRetries", "Max Retries", s, default = "3", min = "0", max = "10"),
      ),
      div(cls := "grid grid-cols-2 gap-4")(
        numberField("connector.default.api.requestsPerMinute", "Requests/min", s, default = "60", min = "1", max = "600"),
        numberField("connector.default.api.burstSize", "Burst Size", s, default = "10", min = "1", max = "100"),
      ),
      numberField("connector.default.api.acquireTimeout", "Acquire Timeout (s)", s, default = "30", min = "1", max = "300"),
      div(cls := "grid grid-cols-2 gap-4")(
        numberField("connector.default.api.temperature", "Temperature", s, default = "", min = "0", max = "2", step = "0.1", placeholder = "Optional (0.0 - 2.0)"),
        numberField("connector.default.api.maxTokens", "Max Tokens", s, default = "", min = "1", max = "1048576", placeholder = "Optional"),
      ),
      textField("connector.default.api.fallbackChain", "Fallback Chain", s, placeholder = "anthropic:claude-sonnet-4, openai:gpt-4o-mini"),
      p(cls := "text-xs text-gray-400 -mt-2")(
        "Comma-separated fallback models. Use provider:model format."
      ),
      div(cls := "flex gap-3 pt-4 border-t border-white/10")(
        button(
          `type` := "submit",
          cls := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-400",
        )("Save API Defaults"),
        button(
          `type` := "button",
          cls := "rounded-md bg-emerald-600 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-emerald-500 disabled:opacity-50 disabled:cursor-not-allowed",
          attr("hx-post") := "/api/settings/test-ai",
          attr("hx-include") := "[name^='connector.default.api.']",
          attr("hx-target") := "#api-test-result",
          attr("hx-swap") := "innerHTML",
        )("Test Connection"),
      ),
      div(id := "api-test-result", cls := "mt-3")(),
    ),
  )
```

- [ ] **Step 5: Add cliDefaultCard helper**

```scala
def cliDefaultCard(s: Map[String, String], errors: Map[String, String]): Frag =
  div(id := "cli-card", cls := sectionCls)(
    h2(cls := "text-lg font-semibold text-white mb-4")("Default CLI Connector"),
    tag("form")(
      attr("hx-post") := "/settings/connectors/cli",
      attr("hx-target") := "#cli-card",
      attr("hx-swap") := "outerHTML",
      cls := "space-y-4",
    )(
      div(
        label(cls := labelCls, `for` := "connector.default.cli.id")("Connector"),
        tag("select")(
          name := "connector.default.cli.id",
          id := "connector.default.cli.id",
          cls := selectCls,
        )(
          connectorOption("claude-cli", "Claude CLI", s.get("connector.default.cli.id")),
          connectorOption("gemini-cli", "Gemini CLI", s.get("connector.default.cli.id")),
          connectorOption("opencode", "OpenCode", s.get("connector.default.cli.id")),
          connectorOption("codex", "Codex", s.get("connector.default.cli.id")),
          connectorOption("copilot", "Copilot", s.get("connector.default.cli.id")),
        ),
      ),
      textField("connector.default.cli.model", "Model", s, placeholder = "Optional model override"),
      div(cls := "grid grid-cols-2 gap-4")(
        numberField("connector.default.cli.timeout", "Timeout (s)", s, default = "300", min = "10", max = "900"),
        numberField("connector.default.cli.maxRetries", "Max Retries", s, default = "3", min = "0", max = "10"),
      ),
      numberField("connector.default.cli.turnLimit", "Turn Limit", s, default = "50", min = "1", max = "500", placeholder = "Max conversation turns"),
      div(
        label(cls := labelCls, `for` := "connector.default.cli.sandbox")("Sandbox"),
        tag("select")(
          name := "connector.default.cli.sandbox",
          id := "connector.default.cli.sandbox",
          cls := selectCls,
        )(
          connectorOption("none", "None", s.get("connector.default.cli.sandbox")),
          connectorOption("docker", "Docker", s.get("connector.default.cli.sandbox")),
          connectorOption("podman", "Podman", s.get("connector.default.cli.sandbox")),
          connectorOption("seatbeltmacos", "Seatbelt (macOS)", s.get("connector.default.cli.sandbox")),
          connectorOption("runsc", "gVisor (runsc)", s.get("connector.default.cli.sandbox")),
          connectorOption("lxc", "LXC", s.get("connector.default.cli.sandbox")),
        ),
      ),
      div(
        label(cls := labelCls, `for` := "connector.default.cli.flags")("Flags"),
        textarea(
          name := "connector.default.cli.flags",
          id := "connector.default.cli.flags",
          rows := 3,
          cls := "block w-full rounded-md bg-white/5 border-0 py-2 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6 px-3",
          attr("placeholder") := "One flag per line, e.g.\n--dangerously-skip-permissions\n--verbose",
        )(s.getOrElse("connector.default.cli.flags", "")),
        p(cls := "text-xs text-gray-400 mt-1")("One CLI flag per line."),
      ),
      envVarsEditor("connector.default.cli.envVars", s.getOrElse("connector.default.cli.envVars", "")),
      div(cls := "flex gap-3 pt-4 border-t border-white/10")(
        button(
          `type` := "submit",
          cls := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-400",
        )("Save CLI Defaults"),
      ),
    ),
  )
```

- [ ] **Step 6: Add connectorOption and envVarsEditor helpers**

```scala
private def connectorOption(value: String, labelText: String, current: Option[String]): Frag =
  val isSelected = current.exists(_.equalsIgnoreCase(value))
  tag("option")(
    attr("value") := value,
    if isSelected then attr("selected") := "selected" else (),
  )(labelText)

private def envVarsEditor(fieldPrefix: String, rawEnvVars: String): Frag =
  // Parse comma-separated key=value pairs
  val pairs = rawEnvVars.split(",").toList.map(_.trim).filter(_.nonEmpty).flatMap { kv =>
    kv.split("=", 2).toList match
      case k :: v :: Nil => Some((k, v))
      case k :: Nil      => Some((k, ""))
      case _             => None
  }
  val indexed = if pairs.isEmpty then List(("", "")) else pairs
  val sanitizedPrefix = fieldPrefix.replace(".", "-")
  div(
    label(cls := labelCls)("Environment Variables"),
    div(id := s"$sanitizedPrefix-rows", cls := "space-y-2")(
      indexed.zipWithIndex.map { case ((k, v), i) =>
        envVarRow(fieldPrefix, i, k, v)
      }
    ),
    button(
      `type` := "button",
      cls := "mt-2 rounded-md border border-white/20 px-3 py-1.5 text-xs font-semibold text-slate-200 hover:bg-white/10",
      attr("onclick") := s"addEnvVarRow('$sanitizedPrefix-rows', '$fieldPrefix')",
    )("+ Add Variable"),
  )

private def envVarRow(fieldPrefix: String, index: Int, key: String, value: String): Frag =
  div(cls := "flex items-center gap-2 env-var-row")(
    input(
      `type` := "text",
      name := s"$fieldPrefix.key.$index",
      attr("placeholder") := "KEY",
      cls := inputCls + " flex-1",
      attr("value") := key,
    ),
    span(cls := "text-gray-500")("="),
    input(
      `type` := "text",
      name := s"$fieldPrefix.value.$index",
      attr("placeholder") := "value",
      cls := inputCls + " flex-1",
      attr("value") := value,
    ),
    button(
      `type` := "button",
      cls := "text-red-400 hover:text-red-300 text-sm",
      attr("onclick") := "this.closest('.env-var-row').remove()",
    )("Remove"),
  )
```

- [ ] **Step 7: Add agentConnectorTable helper**

```scala
private def agentConnectorTable(
  agents: List[config.entity.AgentInfo],
  overrides: Map[String, Map[String, String]],
  defaults: Map[String, String],
): Frag =
  if agents.filter(_.usesAI).isEmpty then frag()
  else
    div(cls := "mb-8")(
      h2(cls := "text-lg font-semibold text-white mb-4")("Agent Connector Assignments"),
      p(cls := "text-sm text-slate-300 mb-4")(
        "Assign API or CLI mode per agent. Override defaults with custom settings."
      ),
      div(cls := "overflow-x-auto")(
        table(cls := "min-w-full text-left text-sm text-slate-200")(
          thead(
            tr(cls := "border-b border-white/10")(
              th(cls := "py-3 pr-4 text-xs font-semibold uppercase text-slate-400")("Agent"),
              th(cls := "py-3 pr-4 text-xs font-semibold uppercase text-slate-400")("Mode"),
              th(cls := "py-3 pr-4 text-xs font-semibold uppercase text-slate-400")("Connector"),
              th(cls := "py-3 pr-4 text-xs font-semibold uppercase text-slate-400")("Model"),
              th(cls := "py-3 text-xs font-semibold uppercase text-slate-400")("Actions"),
            ),
          ),
          tbody(
            agents.filter(_.usesAI).map { agent =>
              val agentOvr    = overrides.getOrElse(agent.name, Map.empty)
              val mode        = agentOvr.getOrElse("mode", "api")
              val hasOverride = agentOvr.exists { case (k, _) => k.startsWith("api.") || k.startsWith("cli.") }
              val effectiveId =
                agentOvr.get(s"$mode.id")
                  .orElse(defaults.get(s"connector.default.$mode.id"))
                  .getOrElse(if mode == "cli" then "claude-cli" else "gemini-api")
              val effectiveModel =
                agentOvr.get(s"$mode.model")
                  .orElse(defaults.get(s"connector.default.$mode.model"))
                  .getOrElse("")
              frag(
                agentRow(agent, mode, hasOverride, effectiveId, effectiveModel),
                tr(td(attr("colspan") := "5", cls := "p-0")(
                  div(id := s"override-panel-${agent.name}")()
                )),
              )
            }
          ),
        ),
      ),
    )

def agentRow(
  agent: config.entity.AgentInfo,
  mode: String,
  hasOverride: Boolean,
  connectorId: String,
  model: String,
): Frag =
  tr(cls := "border-b border-white/5", id := s"agent-row-${agent.name}")(
    td(cls := "py-3 pr-4 font-medium")(
      agent.displayName,
      if hasOverride then
        span(cls := "ml-2 inline-flex items-center rounded-md bg-indigo-500/10 px-2 py-0.5 text-[11px] font-semibold text-indigo-300 ring-1 ring-inset ring-indigo-400/30")("customized")
      else (),
    ),
    td(cls := "py-3 pr-4")(
      div(cls := "inline-flex rounded-md shadow-sm")(
        button(
          `type` := "button",
          cls := (if mode == "api" then "rounded-l-md bg-indigo-500 px-3 py-1.5 text-xs font-semibold text-white"
                  else "rounded-l-md bg-white/5 px-3 py-1.5 text-xs font-semibold text-gray-400 hover:bg-white/10 ring-1 ring-inset ring-white/10"),
          attr("hx-put") := s"/agents/${agent.name}/connector/mode",
          attr("hx-vals") := """{"mode": "api"}""",
          attr("hx-target") := s"#agent-row-${agent.name}",
          attr("hx-swap") := "outerHTML",
        )("API"),
        button(
          `type` := "button",
          cls := (if mode == "cli" then "rounded-r-md bg-indigo-500 px-3 py-1.5 text-xs font-semibold text-white"
                  else "rounded-r-md bg-white/5 px-3 py-1.5 text-xs font-semibold text-gray-400 hover:bg-white/10 ring-1 ring-inset ring-white/10"),
          attr("hx-put") := s"/agents/${agent.name}/connector/mode",
          attr("hx-vals") := """{"mode": "cli"}""",
          attr("hx-target") := s"#agent-row-${agent.name}",
          attr("hx-swap") := "outerHTML",
        )("CLI"),
      ),
    ),
    td(cls := "py-3 pr-4 font-mono text-xs")(connectorId),
    td(cls := "py-3 pr-4 text-xs")(if model.nonEmpty then model else "\u2014"),
    td(cls := "py-3")(
      div(cls := "flex gap-2")(
        button(
          `type` := "button",
          cls := "rounded-md bg-white/5 px-3 py-1.5 text-xs font-semibold text-gray-300 ring-1 ring-white/10 hover:bg-white/10",
          attr("hx-get") := s"/agents/${agent.name}/connector/edit",
          attr("hx-target") := s"#override-panel-${agent.name}",
          attr("hx-swap") := "innerHTML",
        )("Override"),
        if hasOverride then
          button(
            `type` := "button",
            cls := "rounded-md bg-red-500/10 px-3 py-1.5 text-xs font-semibold text-red-300 ring-1 ring-red-400/30 hover:bg-red-500/20",
            attr("hx-delete") := s"/agents/${agent.name}/connector",
            attr("hx-target") := s"#agent-row-${agent.name}",
            attr("hx-swap") := "outerHTML",
            attr("hx-confirm") := "Reset this agent to use system defaults?",
          )("Reset")
        else (),
      ),
    ),
  )
```

- [ ] **Step 8: Add agentOverrideForm helper**

```scala
def agentOverrideForm(
  agentName: String,
  mode: String,
  defaults: Map[String, String],
): String =
  val prefix = s"agent.$agentName.connector.$mode"
  val formContent = if mode == "cli" then cliOverrideFields(prefix, defaults) else apiOverrideFields(prefix, defaults)
  div(cls := "rounded-lg border border-indigo-500/30 bg-slate-900/80 p-4 mt-2")(
    h3(cls := "text-sm font-semibold text-white mb-3")(
      s"Override ${mode.toUpperCase} settings for $agentName"
    ),
    tag("form")(
      attr("hx-post") := s"/agents/$agentName/connector",
      attr("hx-target") := s"#agent-row-$agentName",
      attr("hx-swap") := "outerHTML",
      cls := "space-y-3",
    )(
      formContent,
      div(cls := "flex gap-3 pt-3 border-t border-white/10")(
        button(
          `type` := "submit",
          cls := "rounded-md bg-indigo-500 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-400",
        )("Save Override"),
        button(
          `type` := "button",
          cls := "rounded-md border border-white/20 px-3 py-2 text-sm font-semibold text-slate-200 hover:bg-white/10",
          attr("hx-get") := "about:blank",
          attr("hx-target") := s"#override-panel-$agentName",
          attr("hx-swap") := "innerHTML",
        )("Cancel"),
      ),
    ),
  ).render

private def apiOverrideFields(prefix: String, s: Map[String, String]): Frag =
  frag(
    div(
      label(cls := labelCls, `for` := s"$prefix.id")("Provider"),
      tag("select")(name := s"$prefix.id", id := s"$prefix.id", cls := selectCls)(
        connectorOption("gemini-api", "Gemini API", s.get(s"$prefix.id").orElse(s.get("connector.default.api.id"))),
        connectorOption("openai", "OpenAI", s.get(s"$prefix.id").orElse(s.get("connector.default.api.id"))),
        connectorOption("anthropic", "Anthropic", s.get(s"$prefix.id").orElse(s.get("connector.default.api.id"))),
        connectorOption("lm-studio", "LM Studio (Local)", s.get(s"$prefix.id").orElse(s.get("connector.default.api.id"))),
        connectorOption("ollama", "Ollama (Local)", s.get(s"$prefix.id").orElse(s.get("connector.default.api.id"))),
      ),
    ),
    textField(s"$prefix.model", "Model", s, placeholder = s.getOrElse("connector.default.api.model", "gemini-2.5-flash")),
    textField(s"$prefix.baseUrl", "Base URL", s, placeholder = s.getOrElse("connector.default.api.baseUrl", "Optional")),
    div(
      label(cls := labelCls, `for` := s"$prefix.apiKey")("API Key"),
      input(
        `type` := "password",
        name := s"$prefix.apiKey",
        id := s"$prefix.apiKey",
        value := s.getOrElse(s"$prefix.apiKey", ""),
        attr("placeholder") := "Use system default",
        cls := inputCls,
      ),
    ),
    div(cls := "grid grid-cols-2 gap-4")(
      numberField(s"$prefix.timeout", "Timeout (s)", s, default = s.getOrElse("connector.default.api.timeout", "300"), min = "10", max = "900"),
      numberField(s"$prefix.maxRetries", "Max Retries", s, default = s.getOrElse("connector.default.api.maxRetries", "3"), min = "0", max = "10"),
    ),
    div(cls := "grid grid-cols-2 gap-4")(
      numberField(s"$prefix.requestsPerMinute", "Requests/min", s, default = s.getOrElse("connector.default.api.requestsPerMinute", "60"), min = "1", max = "600"),
      numberField(s"$prefix.burstSize", "Burst Size", s, default = s.getOrElse("connector.default.api.burstSize", "10"), min = "1", max = "100"),
    ),
    div(cls := "grid grid-cols-2 gap-4")(
      numberField(s"$prefix.temperature", "Temperature", s, default = s.getOrElse("connector.default.api.temperature", ""), min = "0", max = "2", step = "0.1", placeholder = "Optional"),
      numberField(s"$prefix.maxTokens", "Max Tokens", s, default = s.getOrElse("connector.default.api.maxTokens", ""), min = "1", max = "1048576", placeholder = "Optional"),
    ),
  )

private def cliOverrideFields(prefix: String, s: Map[String, String]): Frag =
  frag(
    div(
      label(cls := labelCls, `for` := s"$prefix.id")("Connector"),
      tag("select")(name := s"$prefix.id", id := s"$prefix.id", cls := selectCls)(
        connectorOption("claude-cli", "Claude CLI", s.get(s"$prefix.id").orElse(s.get("connector.default.cli.id"))),
        connectorOption("gemini-cli", "Gemini CLI", s.get(s"$prefix.id").orElse(s.get("connector.default.cli.id"))),
        connectorOption("opencode", "OpenCode", s.get(s"$prefix.id").orElse(s.get("connector.default.cli.id"))),
        connectorOption("codex", "Codex", s.get(s"$prefix.id").orElse(s.get("connector.default.cli.id"))),
        connectorOption("copilot", "Copilot", s.get(s"$prefix.id").orElse(s.get("connector.default.cli.id"))),
      ),
    ),
    textField(s"$prefix.model", "Model", s, placeholder = s.getOrElse("connector.default.cli.model", "Optional")),
    div(cls := "grid grid-cols-2 gap-4")(
      numberField(s"$prefix.timeout", "Timeout (s)", s, default = s.getOrElse("connector.default.cli.timeout", "300"), min = "10", max = "900"),
      numberField(s"$prefix.maxRetries", "Max Retries", s, default = s.getOrElse("connector.default.cli.maxRetries", "3"), min = "0", max = "10"),
    ),
    numberField(s"$prefix.turnLimit", "Turn Limit", s, default = s.getOrElse("connector.default.cli.turnLimit", "50"), min = "1", max = "500"),
    div(
      label(cls := labelCls, `for` := s"$prefix.sandbox")("Sandbox"),
      tag("select")(name := s"$prefix.sandbox", id := s"$prefix.sandbox", cls := selectCls)(
        connectorOption("none", "None", s.get(s"$prefix.sandbox").orElse(s.get("connector.default.cli.sandbox"))),
        connectorOption("docker", "Docker", s.get(s"$prefix.sandbox").orElse(s.get("connector.default.cli.sandbox"))),
        connectorOption("podman", "Podman", s.get(s"$prefix.sandbox").orElse(s.get("connector.default.cli.sandbox"))),
        connectorOption("seatbeltmacos", "Seatbelt (macOS)", s.get(s"$prefix.sandbox").orElse(s.get("connector.default.cli.sandbox"))),
        connectorOption("runsc", "gVisor (runsc)", s.get(s"$prefix.sandbox").orElse(s.get("connector.default.cli.sandbox"))),
        connectorOption("lxc", "LXC", s.get(s"$prefix.sandbox").orElse(s.get("connector.default.cli.sandbox"))),
      ),
    ),
    div(
      label(cls := labelCls, `for` := s"$prefix.flags")("Flags"),
      textarea(
        name := s"$prefix.flags",
        id := s"$prefix.flags",
        rows := 3,
        cls := "block w-full rounded-md bg-white/5 border-0 py-2 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6 px-3",
        attr("placeholder") := s.getOrElse("connector.default.cli.flags", "One flag per line"),
      )(s.getOrElse(s"$prefix.flags", "")),
    ),
    envVarsEditor(s"$prefix.envVars", s.getOrElse(s"$prefix.envVars", s.getOrElse("connector.default.cli.envVars", ""))),
  )
```

- [ ] **Step 9: Add modelRegistrySection and toolsSection helpers**

Extract the existing model registry and tools rendering code into helper methods:

```scala
private def modelRegistrySection(
  registry: config.entity.ModelRegistryResponse,
  statusMap: Map[llm4zio.core.LlmProvider, config.entity.ProviderProbeStatus],
): Frag =
  frag(
    h2(cls := "text-lg font-semibold text-white mb-4")("Available Models"),
    p(cls := "text-sm text-slate-300 mb-4")(
      "Models grouped by provider. Configure primary model and fallback chain above."
    ),
    div(cls := "space-y-4")(
      registry.providers.map { group =>
        val status = statusMap.get(group.provider)
        div(cls := "rounded-lg border border-white/10 bg-slate-900/70 p-5")(
          div(cls := "mb-3 flex items-center justify-between")(
            h3(cls := "text-lg font-semibold text-white")(group.provider.toString),
            ModelsView.statusBadge(status),
          ),
          p(cls := "mb-3 text-xs text-slate-400")(status.map(_.statusMessage).getOrElse("No health probe available")),
          table(cls := "min-w-full text-left text-sm text-slate-200")(
            thead(
              tr(
                th(cls := "py-2 pr-4 text-xs font-semibold uppercase text-slate-400")("Model"),
                th(cls := "py-2 pr-4 text-xs font-semibold uppercase text-slate-400")("Context"),
                th(cls := "py-2 pr-4 text-xs font-semibold uppercase text-slate-400")("Capabilities"),
              )
            ),
            tbody(
              group.models.map { model =>
                tr(cls := "border-t border-white/5")(
                  td(cls := "py-2 pr-4 font-mono text-xs")(model.modelId),
                  td(cls := "py-2 pr-4")(model.contextWindow.toString),
                  td(cls := "py-2 pr-4")(model.capabilities.toList.map(_.toString).sorted.mkString(", ")),
                )
              }
            ),
          ),
        )
      }
    ),
  )

private def toolsSection: Frag =
  div(cls := "mt-10")(
    h2(cls := "text-lg font-semibold text-white mb-4")("Available Tools"),
    p(cls := "text-sm text-slate-300 mb-4")("Built-in tools registered in the tool registry."),
    div(
      id := "tools-list",
      attr("hx-get") := "/settings/connectors/tools-fragment",
      attr("hx-trigger") := "load",
      attr("hx-swap") := "innerHTML",
    )(
      div(cls := "text-sm text-gray-400")("Loading tools...")
    ),
  )
```

- [ ] **Step 10: Add envVarsScript helper for add-row JS**

```scala
private def envVarsScript: Frag =
  tag("script")(raw(
    """function addEnvVarRow(containerId, fieldPrefix) {
      |  var container = document.getElementById(containerId);
      |  var rows = container.querySelectorAll('.env-var-row');
      |  var idx = rows.length;
      |  var row = document.createElement('div');
      |  row.className = 'flex items-center gap-2 env-var-row';
      |  var keyInput = document.createElement('input');
      |  keyInput.type = 'text';
      |  keyInput.name = fieldPrefix + '.key.' + idx;
      |  keyInput.placeholder = 'KEY';
      |  keyInput.className = 'block w-full rounded-md bg-white/5 border-0 py-1.5 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6 px-3 flex-1';
      |  var eqSpan = document.createElement('span');
      |  eqSpan.className = 'text-gray-500';
      |  eqSpan.textContent = '=';
      |  var valInput = document.createElement('input');
      |  valInput.type = 'text';
      |  valInput.name = fieldPrefix + '.value.' + idx;
      |  valInput.placeholder = 'value';
      |  valInput.className = keyInput.className;
      |  var removeBtn = document.createElement('button');
      |  removeBtn.type = 'button';
      |  removeBtn.className = 'text-red-400 hover:text-red-300 text-sm';
      |  removeBtn.textContent = 'Remove';
      |  removeBtn.addEventListener('click', function() { row.remove(); });
      |  row.appendChild(keyInput);
      |  row.appendChild(eqSpan);
      |  row.appendChild(valInput);
      |  row.appendChild(removeBtn);
      |  container.appendChild(row);
      |}""".stripMargin
  ))
```

- [ ] **Step 11: Compile and fix any issues**

Run: `sbt compile`
Expected: PASS. Fix any compile errors (unused imports, type mismatches).

- [ ] **Step 12: Commit**

```bash
git add modules/shared-web/src/main/scala/shared/web/SettingsView.scala modules/shared-web/src/main/scala/shared/web/HtmlViews.scala
git commit -m "feat: rewrite SettingsView with two-column API/CLI cards and agent assignment table"
```

---

### Task 3: Add new controller routes

**Files:**
- Modify: `src/main/scala/config/boundary/SettingsController.scala`

- [ ] **Step 1: Add AgentRegistry dependency**

Add `AgentRegistry` to the constructor and layer dependencies of `SettingsControllerLive`. The `AgentRegistry` is at `orchestration.entity.AgentRegistry`. Add the import and constructor parameter:

```scala
// Add to imports:
import orchestration.entity.AgentRegistry

// Add to SettingsControllerLive constructor:
final case class SettingsControllerLive(
  repository: ConfigRepository,
  activityHub: ActivityHub,
  configRef: Ref[GatewayConfig],
  llmService: LlmService,
  modelService: ModelService,
  configStoreService: ConfigStoreModule.ConfigStoreService,
  dataStoreService: DataStoreService,
  storeConfig: StoreConfig,
  memoryEntriesStore: MemoryStoreModule.MemoryEntriesStore,
  toolRegistry: ToolRegistry,
  agentRegistry: AgentRegistry,
) extends SettingsController

// Update the live layer type signature to include AgentRegistry:
val live: ZLayer[
  ConfigRepository & ActivityHub & Ref[GatewayConfig] & LlmService & ModelService & ConfigStoreModule.ConfigStoreService &
    DataStoreService & StoreConfig &
    MemoryStoreModule.MemoryEntriesStore & ToolRegistry & AgentRegistry,
  Nothing,
  SettingsController,
] = ZLayer.fromFunction(SettingsControllerLive.apply)
```

- [ ] **Step 2: Add connector-specific settings key lists**

```scala
private val apiConnectorKeys: List[String] = List(
  "connector.default.api.id",
  "connector.default.api.model",
  "connector.default.api.baseUrl",
  "connector.default.api.apiKey",
  "connector.default.api.timeout",
  "connector.default.api.maxRetries",
  "connector.default.api.requestsPerMinute",
  "connector.default.api.burstSize",
  "connector.default.api.acquireTimeout",
  "connector.default.api.temperature",
  "connector.default.api.maxTokens",
  "connector.default.api.fallbackChain",
)

private val cliConnectorKeys: List[String] = List(
  "connector.default.cli.id",
  "connector.default.cli.model",
  "connector.default.cli.timeout",
  "connector.default.cli.maxRetries",
  "connector.default.cli.turnLimit",
  "connector.default.cli.sandbox",
  "connector.default.cli.flags",
  "connector.default.cli.envVars",
)
```

- [ ] **Step 3: Add loadConnectorsPage helper**

```scala
private def loadConnectorsPage(flash: Option[String] = None): IO[PersistenceError, Response] =
  for
    rows          <- repository.getAllSettings
    settings       = rows.map(r => r.key -> r.value).toMap
    models        <- modelService.listAvailableModels
    status        <- modelService.probeProviders
    agents        <- agentRegistry.listAgentInfo.mapError(e =>
                       PersistenceError.QueryFailed("agents", e.toString))
    agentNames     = agents.filter(_.usesAI).map(_.name)
    agentOverrides <- ZIO.foreach(agentNames) { name =>
                        repository.getSettingsByPrefix(s"agent.$name.connector.")
                          .map(rows => name -> rows.map(r => r.key.stripPrefix(s"agent.$name.connector.") -> r.value).toMap)
                      }
  yield html(HtmlViews.settingsConnectorsTab(
    settings, models, status,
    agents = agents,
    agentOverrides = agentOverrides.toMap,
    flash = flash,
  ))
```

- [ ] **Step 4: Add parseEnvVars helper**

```scala
private def parseEnvVars(form: Map[String, String], prefix: String): String =
  val keys = form.toList.filter(_._1.startsWith(s"$prefix.key.")).sortBy(_._1)
  keys.flatMap { case (keyField, keyValue) =>
    val index = keyField.stripPrefix(s"$prefix.key.")
    val valField = s"$prefix.value.$index"
    form.get(valField).filter(_.nonEmpty).map(v => s"${keyValue.trim}=$v")
  }.mkString(",")
```

- [ ] **Step 5: Replace GET /settings/connectors route**

Replace the existing `GET /settings/connectors` handler:

```scala
Method.GET / "settings" / "connectors" -> handler {
  ErrorHandlingMiddleware.fromPersistence(loadConnectorsPage())
},
```

- [ ] **Step 6: Replace POST /settings/connectors with POST /settings/connectors/api**

Remove the old `POST /settings/connectors` route and add:

```scala
Method.POST / "settings" / "connectors" / "api" -> handler { (req: Request) =>
  ErrorHandlingMiddleware.fromPersistence {
    for
      form     <- parseForm(req)
      _        <- ZIO.foreachDiscard(apiConnectorKeys) { key =>
                    val value = form.getOrElse(key, "")
                    repository.upsertSetting(key, value)
                  }
      // Dual-write ai.* keys for backward compat
      _        <- ZIO.foreachDiscard(apiConnectorKeys) { key =>
                    val legacyKey = key.replace("connector.default.api.", "ai.")
                    val value     = form.getOrElse(key, "")
                    repository.upsertSetting(legacyKey, value)
                  }
      _        <- checkpointConfigStore
      rows     <- repository.getAllSettings
      saved     = rows.map(r => r.key -> r.value).toMap
      newConfig = SettingsApplier.toGatewayConfig(saved)
      _        <- configRef.set(newConfig)
      _        <- writeSettingsSnapshot(saved)
      now      <- Clock.instant
      _        <- activityHub.publish(
                    ActivityEvent(
                      id = EventId.generate,
                      eventType = ActivityEventType.ConfigChanged,
                      source = "settings.connectors.api",
                      summary = "API connector defaults updated",
                      createdAt = now,
                    )
                  )
    yield html(SettingsView.apiDefaultCard(saved, Map.empty).render)
  }
},
```

- [ ] **Step 7: Add POST /settings/connectors/cli route**

```scala
Method.POST / "settings" / "connectors" / "cli" -> handler { (req: Request) =>
  ErrorHandlingMiddleware.fromPersistence {
    for
      form     <- parseForm(req)
      envVars   = parseEnvVars(form, "connector.default.cli.envVars")
      _        <- ZIO.foreachDiscard(cliConnectorKeys) { key =>
                    val value = if key == "connector.default.cli.envVars" then envVars
                                else form.getOrElse(key, "")
                    repository.upsertSetting(key, value)
                  }
      _        <- checkpointConfigStore
      rows     <- repository.getAllSettings
      saved     = rows.map(r => r.key -> r.value).toMap
      _        <- writeSettingsSnapshot(saved)
      now      <- Clock.instant
      _        <- activityHub.publish(
                    ActivityEvent(
                      id = EventId.generate,
                      eventType = ActivityEventType.ConfigChanged,
                      source = "settings.connectors.cli",
                      summary = "CLI connector defaults updated",
                      createdAt = now,
                    )
                  )
    yield html(SettingsView.cliDefaultCard(saved, Map.empty).render)
  }
},
```

- [ ] **Step 8: Add PUT /agents/{name}/connector/mode route**

```scala
Method.PUT / "agents" / string("name") / "connector" / "mode" -> handler {
  (name: String, req: Request) =>
    ErrorHandlingMiddleware.fromPersistence {
      for
        form     <- parseForm(req)
        mode      = form.getOrElse("mode", "api")
        _        <- repository.upsertSetting(s"agent.$name.connector.mode", mode)
        _        <- checkpointConfigStore
        rows     <- repository.getAllSettings
        settings  = rows.map(r => r.key -> r.value).toMap
        agents   <- agentRegistry.listAgentInfo.mapError(e =>
                      PersistenceError.QueryFailed("agents", e.toString))
        agent     = agents.find(_.name == name)
        agentOvr <- repository.getSettingsByPrefix(s"agent.$name.connector.")
                      .map(_.map(r => r.key.stripPrefix(s"agent.$name.connector.") -> r.value).toMap)
        hasOverride = agentOvr.exists { case (k, _) => k.startsWith("api.") || k.startsWith("cli.") }
        effectiveId = agentOvr.get(s"$mode.id")
                        .orElse(settings.get(s"connector.default.$mode.id"))
                        .getOrElse(if mode == "cli" then "claude-cli" else "gemini-api")
        effectiveModel = agentOvr.get(s"$mode.model")
                           .orElse(settings.get(s"connector.default.$mode.model"))
                           .getOrElse("")
      yield agent match
        case Some(a) => htmlFragment(SettingsView.agentRow(a, mode, hasOverride, effectiveId, effectiveModel).render)
        case None    => Response.notFound
    }
},
```

- [ ] **Step 9: Add GET /agents/{name}/connector/edit route**

```scala
Method.GET / "agents" / string("name") / "connector" / "edit" -> handler {
  (name: String, _: Request) =>
    ErrorHandlingMiddleware.fromPersistence {
      for
        agentOvr <- repository.getSettingsByPrefix(s"agent.$name.connector.")
                      .map(_.map(r => r.key.stripPrefix(s"agent.$name.connector.") -> r.value).toMap)
        mode      = agentOvr.getOrElse("mode", "api")
        rows     <- repository.getAllSettings
        defaults  = rows.map(r => r.key -> r.value).toMap
        // Merge agent overrides into defaults for pre-filling
        merged    = defaults ++ agentOvr.map { case (k, v) => s"agent.$name.connector.$k" -> v }
      yield htmlFragment(SettingsView.agentOverrideForm(name, mode, merged))
    }
},
```

- [ ] **Step 10: Add POST /agents/{name}/connector route**

```scala
Method.POST / "agents" / string("name") / "connector" -> handler {
  (name: String, req: Request) =>
    ErrorHandlingMiddleware.fromPersistence {
      for
        form     <- parseForm(req)
        agentOvr <- repository.getSettingsByPrefix(s"agent.$name.connector.")
                      .map(_.map(r => r.key.stripPrefix(s"agent.$name.connector.") -> r.value).toMap)
        mode      = agentOvr.getOrElse("mode", "api")
        prefix    = s"agent.$name.connector.$mode"
        _        <- ZIO.foreachDiscard(form.toList.filter(_._1.startsWith(prefix))) { case (key, value) =>
                      repository.upsertSetting(key, value)
                    }
        // Handle envVars specially for CLI mode
        _        <- if mode == "cli" then
                      val envVars = parseEnvVars(form, s"$prefix.envVars")
                      repository.upsertSetting(s"$prefix.envVars", envVars)
                    else ZIO.unit
        _        <- checkpointConfigStore
        rows     <- repository.getAllSettings
        settings  = rows.map(r => r.key -> r.value).toMap
        agents   <- agentRegistry.listAgentInfo.mapError(e =>
                      PersistenceError.QueryFailed("agents", e.toString))
        agent     = agents.find(_.name == name)
        updOvr   <- repository.getSettingsByPrefix(s"agent.$name.connector.")
                      .map(_.map(r => r.key.stripPrefix(s"agent.$name.connector.") -> r.value).toMap)
        hasOverride = updOvr.exists { case (k, _) => k.startsWith("api.") || k.startsWith("cli.") }
        effectiveId = updOvr.get(s"$mode.id")
                        .orElse(settings.get(s"connector.default.$mode.id"))
                        .getOrElse(if mode == "cli" then "claude-cli" else "gemini-api")
        effectiveModel = updOvr.get(s"$mode.model")
                           .orElse(settings.get(s"connector.default.$mode.model"))
                           .getOrElse("")
      yield agent match
        case Some(a) => htmlFragment(SettingsView.agentRow(a, mode, hasOverride, effectiveId, effectiveModel).render)
        case None    => Response.notFound
    }
},
```

- [ ] **Step 11: Add DELETE /agents/{name}/connector route**

```scala
Method.DELETE / "agents" / string("name") / "connector" -> handler {
  (name: String, _: Request) =>
    ErrorHandlingMiddleware.fromPersistence {
      for
        agentRows <- repository.getSettingsByPrefix(s"agent.$name.connector.")
        _         <- ZIO.foreachDiscard(agentRows) { row =>
                       repository.upsertSetting(row.key, "") // Soft-delete by clearing value
                     }
        _         <- checkpointConfigStore
        rows      <- repository.getAllSettings
        settings   = rows.map(r => r.key -> r.value).toMap
        agents    <- agentRegistry.listAgentInfo.mapError(e =>
                       PersistenceError.QueryFailed("agents", e.toString))
        agent      = agents.find(_.name == name)
        effectiveId = settings.get("connector.default.api.id").getOrElse("gemini-api")
        effectiveModel = settings.get("connector.default.api.model").getOrElse("")
      yield agent match
        case Some(a) => htmlFragment(SettingsView.agentRow(a, "api", false, effectiveId, effectiveModel).render)
        case None    => Response.notFound
    }
},
```

- [ ] **Step 12: Keep backward-compat POST /settings/connectors**

Replace the old `POST /settings/connectors` with a redirect:

```scala
Method.POST / "settings" / "connectors" -> handler { (req: Request) =>
  // Backward compat: redirect old form POST
  ZIO.succeed(Response(
    status = Status.Found,
    headers = Headers(Header.Location(URL.decode("/settings/connectors").getOrElse(URL.root))),
  ))
},
```

- [ ] **Step 13: Compile**

Run: `sbt compile`
Expected: PASS.

- [ ] **Step 14: Commit**

```bash
git add src/main/scala/config/boundary/SettingsController.scala
git commit -m "feat: add split API/CLI save routes and agent mode toggle/override CRUD to SettingsController"
```

---

### Task 4: Update AgentsView to link to Connectors tab

**Files:**
- Modify: `modules/agent-domain/src/main/scala/agent/boundary/AgentsView.scala`

- [ ] **Step 1: Replace agentConfigPage AI override section**

Find the `agentConfigPage` method in `AgentsView.scala` (around line 743). Replace the AI provider override form body with a read-only notice linking to the Connectors tab. Keep the method signature the same for backward compatibility:

```scala
def agentConfigPage(
  agent: AgentInfo,
  overrideSettings: Map[String, String],
  globalSettings: Map[String, String],
  flash: Option[String],
): String =
  Layout.page(s"${agent.displayName} Config", "/agents")(
    div(cls := "max-w-3xl space-y-6")(
      div(
        a(
          href := "/agents",
          cls  := "text-sm font-medium text-indigo-300 hover:text-indigo-200",
        )("Back to agents"),
      ),
      div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
        h1(cls := "text-2xl font-bold text-white")(s"${agent.displayName} Configuration"),
        p(cls := "mt-2 text-sm text-slate-300")(agent.description),
      ),
      flash.map { msg =>
        div(cls := "rounded-md bg-green-500/10 border border-green-500/30 p-4")(
          p(cls := "text-sm text-green-400")(msg)
        )
      },
      // Connector settings notice
      div(cls := "rounded-lg border border-indigo-400/20 bg-indigo-500/10 p-4")(
        p(cls := "text-sm text-indigo-100")(
          "Connector settings (API/CLI mode, provider, model, etc.) are configured on the ",
          a(
            href := "/settings/connectors",
            cls  := "font-semibold underline decoration-indigo-300/50 hover:text-white",
          )("Connectors settings page"),
          ".",
        ),
      ),
    ),
  )
```

- [ ] **Step 2: Compile**

Run: `sbt compile`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add modules/agent-domain/src/main/scala/agent/boundary/AgentsView.scala
git commit -m "feat: replace AgentsView AI override form with link to Connectors settings page"
```

---

### Task 5: End-to-end compile and test

**Files:**
- All modified files from Tasks 1-4

- [ ] **Step 1: Full compile**

Run: `sbt compile`
Expected: PASS with no errors or warnings under `-Werror`.

- [ ] **Step 2: Run full test suite**

Run: `sbt test`
Expected: All tests pass (1099+).

- [ ] **Step 3: Fix any failures**

If there are compile errors or test failures, fix them and re-run.

- [ ] **Step 4: Manual browser verification**

Run: `sbt run`
Navigate to `http://localhost:8080/settings/connectors` and verify:
1. Two cards render side by side (API left, CLI right)
2. API card has provider dropdown with ConnectorId values
3. CLI card has connector dropdown, flags textarea, sandbox dropdown, env vars editor
4. Agent table shows below with mode toggle buttons
5. Clicking Override expands inline form
6. Saving a card updates without full page reload (HTMX swap)

- [ ] **Step 5: Commit any fixes**

```bash
git add -A
git commit -m "fix: address compile and test issues from connectors UI redesign"
```
