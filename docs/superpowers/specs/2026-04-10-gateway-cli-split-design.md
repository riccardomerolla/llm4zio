# Gateway / CLI Split — Design Spec

**Date:** 2026-04-10  
**Status:** Draft

## Problem

The gateway's `Main.scala` started as a CLI app using `zio-cli` but evolved into a webapp. Running the gateway requires `sbt run serve` instead of a simple `sbt run`. The CLI scaffolding adds unnecessary complexity to the web server entry point, and there is no separate CLI tool for interacting with the gateway or performing standalone operations.

## Goals

1. **Simplify gateway startup** — `sbt run` starts the web server directly, no subcommand needed
2. **Create a CLI module** — separate `modules/cli/` sbt module for command-line operations
3. **CLI dual-mode** — standalone operations (local store) and remote operations (HTTP client to running gateway)

## Non-Goals

- The CLI does NOT include a `serve` command — use `sbt run` or the gateway jar to start the server
- No changes to the gateway's HTTP API, DI wiring, or domain modules
- No new shared modules (e.g., `app-core`) — the CLI depends on existing domain modules directly

---

## Design

### 1. Gateway Main Simplification

**File:** `src/main/scala/app/Main.scala`

Remove `zio-cli` and make Main a standard `ZIOAppDefault`:

```scala
object Main extends ZIOAppDefault:
  override val bootstrap = Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  override def run =
    for
      port        <- System.envOrElse("GATEWAY_PORT", "8080").map(_.toInt)
      host        <- System.envOrElse("GATEWAY_HOST", "0.0.0.0")
      statePath   <- System.envOrElse("GATEWAY_STATE", defaultStateRoot.toString)
      storeConfig  = buildStoreConfig(Paths.get(statePath))
      baseConfig  <- loadConfig
      config      <- ConfigLoader.validate(baseConfig).mapError(...)
      _           <- ZIO.logInfo(s"Starting on http://$host:$port")
      _           <- WebServer.start(host, port).provide(ApplicationDI.webServerLayer(config, storeConfig))
    yield ()
```

**Changes:**
- Remove `zio.cli` imports and `zio-cli` library dependency from root project
- Remove `serveCmd`, `cliApp`, `ServeOpts` type alias
- Config comes from environment variables (already supported via `ConfigLoader.loadWithEnvOverrides`) and the `GATEWAY_PORT` / `GATEWAY_HOST` / `GATEWAY_STATE` env vars for the values previously passed as CLI options
- `run` directly calls the startup logic

### 2. CLI Module

**Location:** `modules/cli/`

#### Module Definition (build.sbt)

```scala
lazy val cli = project.in(file("modules/cli"))
  .dependsOn(
    sharedJson, sharedIds, sharedErrors, sharedStoreCore, sharedServices,
    configDomain,
    boardDomain,
    workspaceDomain,
    projectDomain,
    sdlcDomain,
    activityDomain,
  )
  .settings(
    name := "llm4zio-cli",
    libraryDependencies ++= Seq(zioCliDep, zioHttpDep),
    run / fork := true,
  )
```

The CLI is NOT aggregated by the root project (so `sbt run` doesn't pick it up). Run it with `sbt cli/run`.

#### Source Structure

```
modules/cli/src/main/scala/cli/
  Main.scala                 # Entry point, zio-cli command tree
  CliDI.scala                # Lightweight DI for standalone mode
  commands/
    ConfigCommand.scala      # config list, config set <key> <value>
    BoardCommand.scala       # board list, board show <id>
    ProjectCommand.scala     # project list, workspace list
    StatsCommand.scala       # stats summary
    ChatCommand.scala        # chat <prompt> (standalone LLM)
    RemoteCommand.scala      # remote status, remote chat <prompt>
```

#### Command Tree

```
llm4zio-cli <command> [options]

Commands:
  config list                    Show current configuration
  config set <key> <value>       Update a config value
  board list [--workspace <id>]  List board issues
  board show <issue-id>          Show issue details
  project list                   List projects
  workspace list                 List workspaces
  stats summary                  Show stats report
  chat <prompt>                  Direct LLM chat (standalone)
  remote status                  Check gateway health
  remote chat <prompt>           Chat via running gateway

Global options:
  --state <path>                 Store root path (default: ~/.llm4zio-gateway/data)
  --gateway-url <url>            Gateway URL for remote commands (default: http://localhost:8080)
```

#### CLI DI (CliDI.scala)

A lightweight layer stack for standalone operations — a subset of `ApplicationDI`:

```scala
object CliDI:
  def standaloneLayers(storeConfig: StoreConfig, config: GatewayConfig): ZLayer[...] =
    // Config store + data store (EclipseStore)
    // ConfigRepository, BoardRepository, WorkspaceRepository, ProjectRepository
    // SdlcMetricsService, ActivityRepository
    // NO web server, NO routes, NO channel registry, NO orchestration
```

For `chat` standalone mode, additionally:
- `HttpAIClient` + `ConfigAwareLlmService` (for direct LLM calls)

For `remote` commands:
- Just a ZIO HTTP client, no store access needed

### 3. Removed from Root

After the refactoring:
- `zio-cli` dependency removed from root `build.sbt`
- `zio.cli` imports removed from `Main.scala`
- CLI option parsing code removed from `Main.scala`

### 4. Configuration Strategy

| Setting | Gateway | CLI (standalone) | CLI (remote) |
|---------|---------|-------------------|--------------|
| Port/Host | `GATEWAY_PORT`, `GATEWAY_HOST` env vars | N/A | `--gateway-url` flag |
| State path | `GATEWAY_STATE` env var | `--state` flag | N/A |
| LLM config | `application.conf` + env vars | `application.conf` + env vars | N/A (uses gateway) |

---

## Verification

1. **Gateway starts with `sbt run`** — no arguments needed, binds to port 8080
2. **Gateway accepts env var overrides** — `GATEWAY_PORT=9000 sbt run` works
3. **CLI standalone commands work** — `sbt cli/run board list` reads from local store
4. **CLI remote commands work** — `sbt cli/run remote status` hits running gateway
5. **Existing tests pass** — `sbt test` unchanged
6. **No regressions** — all HTTP routes, SSE, WebSocket endpoints work as before
