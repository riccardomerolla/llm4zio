# Gateway / CLI Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Simplify the gateway Main.scala to start with `sbt run` (no subcommand), and create a separate CLI module for standalone and remote operations.

**Architecture:** Remove `zio-cli` from the root project and make Main a standard `ZIOAppDefault`. Create `modules/cli/` as an independent sbt module with its own `Main` using `zio-cli` subcommands. The CLI accesses local EclipseStore for standalone ops (config, board, workspace, project) and HTTP for remote ops (status, stats, activity, chat).

**Tech Stack:** ZIO 2.x, zio-cli 0.7.5, zio-http 3.10.1, EclipseStore, Scalatags (not needed in CLI)

**Key constraint:** `DataStoreModule` and `ConfigStoreModule` live in root (`src/main/scala/shared/store/`) and can't be imported by submodules. The CLI must replicate their store-layer construction. `ConfigRepositoryES` is also in root — the CLI will create a simplified config reader or use the `db.ConfigRepositoryES` directly.

---

## File Structure

### Modified Files

| File | Change |
|------|--------|
| `src/main/scala/app/Main.scala` | Remove zio-cli, simplify to direct WebServer startup |
| `build.sbt` | Remove zio-cli from `rootDeps`, add `cli` module definition, add `zioCliDep` val |

### New Files (CLI Module)

| File | Responsibility |
|------|---------------|
| `modules/cli/src/main/scala/cli/Main.scala` | CLI entry point, zio-cli command tree |
| `modules/cli/src/main/scala/cli/CliStoreModule.scala` | EclipseStore layer construction for CLI |
| `modules/cli/src/main/scala/cli/CliDI.scala` | Lightweight DI wiring for standalone mode |
| `modules/cli/src/main/scala/cli/commands/ConfigCommand.scala` | `config list`, `config set` |
| `modules/cli/src/main/scala/cli/commands/BoardCommand.scala` | `board list`, `board show` |
| `modules/cli/src/main/scala/cli/commands/WorkspaceCommand.scala` | `workspace list` |
| `modules/cli/src/main/scala/cli/commands/ProjectCommand.scala` | `project list` |
| `modules/cli/src/main/scala/cli/commands/RemoteCommand.scala` | `remote status`, `remote stats`, `remote activity`, `remote chat` |
| `modules/cli/src/test/scala/cli/commands/ConfigCommandSpec.scala` | Tests for config commands |
| `modules/cli/src/test/scala/cli/commands/BoardCommandSpec.scala` | Tests for board commands |
| `modules/cli/src/test/scala/cli/commands/WorkspaceCommandSpec.scala` | Tests for workspace commands |
| `modules/cli/src/test/scala/cli/commands/ProjectCommandSpec.scala` | Tests for project commands |

---

## Task 1: Simplify Gateway Main.scala

**Files:**
- Modify: `src/main/scala/app/Main.scala`
- Modify: `build.sbt:56-58` (remove zio-cli from rootDeps)

- [ ] **Step 1: Verify current gateway starts with `sbt run serve`**

Run: `sbt run serve`
Expected: Server starts on http://0.0.0.0:8080, logs show "Starting web server"

- [ ] **Step 2: Remove zio-cli from rootDeps in build.sbt**

In `build.sbt`, remove line 58 (`"dev.zio" %% "zio-cli" % zioCliVersion,`) from `rootDeps`. Extract a reusable `zioCliDep` val for the CLI module. Keep the version constant.

Change `build.sbt` lines 17, 36-38 area — add after `val zioHttpDep`:

```scala
val zioCliDep = "dev.zio" %% "zio-cli" % zioCliVersion
```

Then in `rootDeps` (line 56-77), remove:
```scala
  "dev.zio" %% "zio-cli" % zioCliVersion,
```

- [ ] **Step 3: Rewrite Main.scala to remove zio-cli**

Replace the entire `src/main/scala/app/Main.scala` with:

```scala
package app

import java.nio.file.{ Path, Paths }

import zio.*
import zio.logging.backend.SLF4J

import _root_.config.ConfigLoader
import app.boundary.WebServer
import shared.store.StoreConfig

object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  private val defaultStateRoot: Path =
    Paths.get(sys.props.getOrElse("user.home", ".")).resolve(".llm4zio-gateway").resolve("data")

  override def run: ZIO[ZIOAppArgs & Scope, Any, Unit] =
    for
      port       <- System.envOrElse("GATEWAY_PORT", "8080").map(_.toInt)
      host       <- System.envOrElse("GATEWAY_HOST", "0.0.0.0")
      statePath  <- System.envOrElse("GATEWAY_STATE", defaultStateRoot.toString)
      storeConfig = buildStoreConfig(Paths.get(statePath))
      baseConfig <- loadConfig
      config     <- ConfigLoader.validate(baseConfig).mapError(msg => new IllegalArgumentException(msg))
      _          <- ZIO.logInfo(s"Starting web server on http://$host:$port")
      _          <- ZIO.logInfo(s"Store root: ${Paths.get(storeConfig.dataStorePath).getParent.toAbsolutePath}")
      _          <- ZIO.logInfo(s"Config store: ${Paths.get(storeConfig.configStorePath).toAbsolutePath}")
      _          <- ZIO.logInfo(s"Data store: ${Paths.get(storeConfig.dataStorePath).toAbsolutePath}")
      _          <- WebServer.start(host, port).provide(ApplicationDI.webServerLayer(config, storeConfig))
    yield ()

  private def buildStoreConfig(root: Path): StoreConfig =
    StoreConfig(
      configStorePath = root.resolve("config-store").toString,
      dataStorePath = root.resolve("data-store").toString,
    )

  private def loadConfig =
    ConfigLoader
      .loadWithEnvOverrides
      .orElseSucceed(_root_.config.entity.GatewayConfig())
```

- [ ] **Step 4: Compile and verify**

Run: `sbt compile`
Expected: Compiles without errors. No unused import warnings for zio.cli.

- [ ] **Step 5: Test gateway starts with `sbt run` (no subcommand)**

Run: `sbt run`
Expected: Server starts on http://0.0.0.0:8080 — no "serve" subcommand needed.

Run: `GATEWAY_PORT=9000 sbt run`
Expected: Server starts on port 9000.

- [ ] **Step 6: Run existing tests**

Run: `sbt test`
Expected: All tests pass. No tests reference the CLI/zio-cli code.

- [ ] **Step 7: Commit**

```bash
git add src/main/scala/app/Main.scala build.sbt
git commit -m "refactor: simplify gateway Main to start without CLI subcommand

Remove zio-cli from gateway. Server starts directly with 'sbt run'.
Port/host/state configured via GATEWAY_PORT, GATEWAY_HOST, GATEWAY_STATE
environment variables."
```

---

## Task 2: Add CLI Module to build.sbt

**Files:**
- Modify: `build.sbt` (add cli module definition)

- [ ] **Step 1: Add CLI module definition to build.sbt**

Add the following after the `sdlcDomain` definition (after line 444) and BEFORE `allModules`:

```scala
lazy val cli = (project in file("modules/cli"))
  .dependsOn(
    sharedJson, sharedIds, sharedErrors, sharedStoreCore, sharedServices,
    configDomain,
    boardDomain,
    workspaceDomain,
    projectDomain,
    activityDomain,
    conversationDomain,
    taskrunDomain,
  )
  .settings(foundationSettings)
  .settings(
    name := "llm4zio-cli",
    libraryDependencies ++= zioCoreDeps ++ Seq(
      zioCliDep,
      zioHttpDep,
      zioJsonDep,
      "dev.zio" %% "zio-logging-slf4j2" % zioLoggingVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      "io.github.riccardomerolla" %% "zio-eclipsestore" % zioEclipseStoreVersion,
      "dev.zio" %% "zio-schema"            % zioSchemaVersion,
      "dev.zio" %% "zio-schema-derivation" % zioSchemaVersion,
    ) ++ zioLoggingDeps ++ zioTestDeps,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    run / fork := true,
    run / javaOptions ++= Seq(
      "--enable-native-access=ALL-UNNAMED",
      "--add-opens", "java.base/java.lang=ALL-UNNAMED",
      "--add-opens", "java.base/java.util=ALL-UNNAMED",
      "--add-opens", "java.base/java.nio=ALL-UNNAMED",
    ),
  )
```

**Important:** Do NOT add `cli` to `allModules` — the CLI must not be aggregated by root so `sbt run` doesn't pick it up. But we still want `sbt compile` and `sbt test` to cover it. So add it to the root project's aggregate only:

Actually, we should NOT aggregate it. Run CLI tests with `sbt cli/test`. This keeps `sbt run` clean.

- [ ] **Step 2: Create CLI module directory structure**

```bash
mkdir -p modules/cli/src/main/scala/cli/commands
mkdir -p modules/cli/src/test/scala/cli/commands
```

- [ ] **Step 3: Create a minimal Main.scala placeholder to verify sbt setup**

Create `modules/cli/src/main/scala/cli/Main.scala`:

```scala
package cli

import zio.*

object Main extends ZIOAppDefault:
  override def run: ZIO[ZIOAppArgs & Scope, Any, Unit] =
    ZIO.logInfo("llm4zio-cli placeholder")
```

- [ ] **Step 4: Verify CLI module compiles**

Run: `sbt cli/compile`
Expected: Compiles successfully.

Run: `sbt cli/run`
Expected: Prints "llm4zio-cli placeholder" and exits.

- [ ] **Step 5: Verify root project is unaffected**

Run: `sbt run`
Expected: Gateway starts normally on port 8080 (not the CLI placeholder).

Run: `sbt test`
Expected: All existing tests pass. CLI module tests not included.

- [ ] **Step 6: Commit**

```bash
git add build.sbt modules/cli/
git commit -m "feat: add cli sbt module scaffold

New modules/cli/ module for standalone and remote CLI operations.
Not aggregated by root - run with 'sbt cli/run'."
```

---

## Task 3: CLI Store Module

The CLI needs to access EclipseStore directly. `DataStoreModule` and `ConfigStoreModule` are in root and can't be imported. Replicate the store-layer construction in the CLI module.

**Files:**
- Create: `modules/cli/src/main/scala/cli/CliStoreModule.scala`

- [ ] **Step 1: Create CliStoreModule**

Create `modules/cli/src/main/scala/cli/CliStoreModule.scala`:

```scala
package cli

import java.nio.file.Paths

import zio.*
import zio.schema.Schema

import conversation.entity.{ Conversation, ConversationEvent }
import io.github.riccardomerolla.zio.eclipsestore.config.{ EclipseStoreConfig, StorageTarget }
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.schema.{ SchemaBinaryCodec, TypedStoreLive }
import io.github.riccardomerolla.zio.eclipsestore.service.{ EclipseStoreService, LifecycleCommand }
import shared.store.{ ConfigStoreRef, DataStoreRef, DataStoreService, StoreConfig }
import shared.store.ConfigStoreModule.ConfigStoreService
import taskrun.entity.{ TaskRun, TaskRunEvent }
import _root_.config.entity.{ CustomAgent, Setting, SettingValue, Workflow }

object CliStoreModule:

  // --- Data store (same handlers as root DataStoreModule) ---

  private val dataStoreHandlers =
    SchemaBinaryCodec.handlers(Schema[String])
      ++ SchemaBinaryCodec.handlers(Schema[TaskRun])
      ++ SchemaBinaryCodec.handlers(Schema[TaskRunEvent])
      ++ SchemaBinaryCodec.handlers(Schema[Conversation])
      ++ SchemaBinaryCodec.handlers(Schema[ConversationEvent])

  val dataStoreLive: ZLayer[StoreConfig, EclipseStoreError, DataStoreService] =
    val baseStore: ZLayer[StoreConfig, EclipseStoreError, DataStoreRef] =
      ZLayer.fromZIO(
        ZIO.serviceWith[StoreConfig] { cfg =>
          EclipseStoreConfig(
            storageTarget = StorageTarget.FileSystem(Paths.get(cfg.dataStorePath)),
            autoCheckpointInterval = Some(java.time.Duration.ofSeconds(5L)),
            customTypeHandlers = dataStoreHandlers,
          )
        }
      ) >>> EclipseStoreService.live.fresh >>> ZLayer.fromFunction(DataStoreRef.apply) >>> withShutdownCheckpoint[DataStoreRef](_.raw)

    val dataStore: ZLayer[DataStoreRef, Nothing, DataStoreService] =
      ZLayer.fromFunction { (ref: DataStoreRef) =>
        val esc = ref.raw
        val ts  = TypedStoreLive(esc)
        new DataStoreService:
          export ts.{ store, fetch, remove, fetchAll, streamAll, typedRoot, storePersist }
          override val rawStore: EclipseStoreService = esc
      }

    baseStore >>> dataStore

  // --- Config store (same handlers as root ConfigStoreModule) ---

  private val configStoreHandlers =
    SchemaBinaryCodec.handlers(Schema[String])
      ++ SchemaBinaryCodec.handlers(Schema[SettingValue])
      ++ SchemaBinaryCodec.handlers(Schema[Setting])
      ++ SchemaBinaryCodec.handlers(Schema[Workflow])
      ++ SchemaBinaryCodec.handlers(Schema[CustomAgent])

  val configStoreLive: ZLayer[StoreConfig, EclipseStoreError, ConfigStoreService] =
    val baseStore: ZLayer[StoreConfig, EclipseStoreError, ConfigStoreRef] =
      ZLayer.fromZIO(
        ZIO.serviceWith[StoreConfig] { cfg =>
          EclipseStoreConfig(
            storageTarget = StorageTarget.FileSystem(Paths.get(cfg.configStorePath)),
            autoCheckpointInterval = Some(java.time.Duration.ofSeconds(5L)),
            customTypeHandlers = configStoreHandlers,
          )
        }
      ) >>> EclipseStoreService.live.fresh >>> ZLayer.fromFunction(ConfigStoreRef.apply) >>> withShutdownCheckpoint[ConfigStoreRef](_.raw)

    val configStore: ZLayer[ConfigStoreRef, Nothing, ConfigStoreService] =
      ZLayer.fromFunction { (ref: ConfigStoreRef) =>
        val esc = ref.raw
        val ts  = TypedStoreLive(esc)
        new ConfigStoreService:
          export ts.{ store, fetch, remove, fetchAll, streamAll, typedRoot, storePersist }
          override val rawStore: EclipseStoreService = esc
      }

    baseStore >>> configStore

  // --- Shared shutdown-checkpoint finalizer ---

  private def withShutdownCheckpoint[R: Tag](extract: R => EclipseStoreService): ZLayer[R, EclipseStoreError, R] =
    ZLayer.scoped {
      for
        ref <- ZIO.service[R]
        svc  = extract(ref)
        _   <- svc.reloadRoots
        _   <- ZIO.addFinalizer(
                 svc.maintenance(LifecycleCommand.Checkpoint).ignoreLogged
               )
      yield ref
    }
```

- [ ] **Step 2: Verify it compiles**

Run: `sbt cli/compile`
Expected: Compiles without errors.

- [ ] **Step 3: Commit**

```bash
git add modules/cli/src/main/scala/cli/CliStoreModule.scala
git commit -m "feat(cli): add CliStoreModule for EclipseStore access

Replicates DataStoreModule and ConfigStoreModule layer construction
for use in CLI standalone mode."
```

---

## Task 4: CLI DI Module

Lightweight dependency wiring for CLI standalone operations.

**Files:**
- Create: `modules/cli/src/main/scala/cli/CliDI.scala`

- [ ] **Step 1: Create CliDI**

Create `modules/cli/src/main/scala/cli/CliDI.scala`:

```scala
package cli

import zio.*

import activity.entity.{ ActivityRepository, ActivityRepositoryES }
import board.control.BoardRepositoryFS
import board.entity.BoardRepository
import _root_.config.entity.ConfigRepository
import db.ConfigRepositoryES as DbConfigRepositoryES
import project.entity.{ ProjectRepository, ProjectRepositoryES }
import shared.store.{ DataStoreService, StoreConfig }
import shared.store.ConfigStoreModule.ConfigStoreService
import workspace.entity.{ WorkspaceRepository, WorkspaceRepositoryES }

object CliDI:

  /** Layers for standalone CLI operations that need local store access. */
  def standaloneLayers(storeConfig: StoreConfig): ZLayer[Any, Throwable, StandaloneEnv] =
    ZLayer.make[StandaloneEnv](
      ZLayer.succeed(storeConfig),
      CliStoreModule.dataStoreLive.orDie,
      CliStoreModule.configStoreLive.orDie,
      configRepositoryLayer,
      WorkspaceRepository.live,
      ProjectRepository.live,
      ActivityRepository.live,
    )

  type StandaloneEnv =
    StoreConfig &
    DataStoreService &
    ConfigStoreService &
    ConfigRepository &
    WorkspaceRepository &
    ProjectRepository &
    ActivityRepository

  /** ConfigRepository layer — delegates to DbConfigRepositoryES which uses ConfigStoreService. */
  private val configRepositoryLayer: ZLayer[ConfigStoreService, Nothing, ConfigRepository] =
    ZLayer.fromFunction { (configStore: ConfigStoreService) =>
      _root_.config.entity.ConfigRepositoryES(DbConfigRepositoryES(configStore)): ConfigRepository
    }
```

**Note:** The `BoardRepository` is NOT wired here because `BoardRepositoryFS` needs `IssueMarkdownParser` and `GitService` which are provided inline per-command (they're stateless).

- [ ] **Step 2: Verify it compiles**

Run: `sbt cli/compile`
Expected: Compiles. If `db.ConfigRepositoryES` is not visible from the CLI module (it's in root), we'll need to create a simple inline implementation instead — see Step 3.

- [ ] **Step 3: If `db.ConfigRepositoryES` is not accessible, create inline config repository**

If Step 2 fails because `db.ConfigRepositoryES` is in root and inaccessible, replace the `configRepositoryLayer` with an inline implementation that directly reads from `ConfigStoreService`. Read `src/main/scala/db/ConfigRepositoryES.scala` for the key patterns it uses and replicate the settings-related methods.

In that case, create `modules/cli/src/main/scala/cli/CliConfigRepository.scala`:

```scala
package cli

import zio.*

import _root_.config.entity.*
import shared.errors.PersistenceError
import shared.store.ConfigStoreModule.ConfigStoreService

/** Simplified ConfigRepository for CLI that reads settings directly from ConfigStoreService.
  * Only implements the methods needed by CLI commands (getAllSettings, getSetting, upsertSetting).
  */
final case class CliConfigRepository(store: ConfigStoreService) extends ConfigRepository:

  override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
    store.rawStore
      .streamKeys[String]()
      .filter(_.startsWith("settings:"))
      .mapZIO { key =>
        store.fetch[SettingValue](key).map(_.map(sv => SettingRow(key.stripPrefix("settings:"), sv.value)))
      }
      .collectSome
      .runCollect
      .map(_.toList)
      .mapError(e => PersistenceError.StorageError(e.toString, None))

  override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
    store.fetch[SettingValue](s"settings:$key")
      .map(_.map(sv => SettingRow(key, sv.value)))
      .mapError(e => PersistenceError.StorageError(e.toString, None))

  override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
    store.store(s"settings:$key", SettingValue(value))
      .mapError(e => PersistenceError.StorageError(e.toString, None))

  // CLI doesn't need these — fail with NotImplemented
  override def deleteSetting(key: String): IO[PersistenceError, Unit] = ZIO.unit
  override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] = ZIO.unit
  override def listAgentChannelBindings: IO[PersistenceError, List[AgentChannelBinding]] = ZIO.succeed(Nil)
  override def upsertAgentChannelBinding(binding: AgentChannelBinding): IO[PersistenceError, Unit] = ZIO.unit
  override def deleteAgentChannelBinding(binding: AgentChannelBinding): IO[PersistenceError, Unit] = ZIO.unit
  override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long] = ZIO.succeed(0L)
  override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]] = ZIO.succeed(None)
  override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]] = ZIO.succeed(None)
  override def listWorkflows: IO[PersistenceError, List[WorkflowRow]] = ZIO.succeed(Nil)
  override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit] = ZIO.unit
  override def deleteWorkflow(id: Long): IO[PersistenceError, Unit] = ZIO.unit
  override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long] = ZIO.succeed(0L)
  override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]] = ZIO.succeed(None)
  override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] = ZIO.succeed(None)
  override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]] = ZIO.succeed(Nil)
  override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit] = ZIO.unit
  override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit] = ZIO.unit

object CliConfigRepository:
  val live: ZLayer[ConfigStoreService, Nothing, ConfigRepository] =
    ZLayer.fromFunction(CliConfigRepository.apply(_): ConfigRepository)
```

Then update `CliDI` to use `CliConfigRepository.live` instead of the `configRepositoryLayer`.

- [ ] **Step 4: Verify it compiles**

Run: `sbt cli/compile`
Expected: Compiles without errors.

- [ ] **Step 5: Commit**

```bash
git add modules/cli/src/main/scala/cli/CliDI.scala modules/cli/src/main/scala/cli/CliConfigRepository.scala
git commit -m "feat(cli): add CliDI and CliConfigRepository for standalone mode

Lightweight DI wiring for CLI standalone operations. CliConfigRepository
provides settings access without depending on root db layer."
```

---

## Task 5: Config Command

**Files:**
- Create: `modules/cli/src/main/scala/cli/commands/ConfigCommand.scala`
- Create: `modules/cli/src/test/scala/cli/commands/ConfigCommandSpec.scala`

- [ ] **Step 1: Write ConfigCommand test**

Create `modules/cli/src/test/scala/cli/commands/ConfigCommandSpec.scala`:

```scala
package cli.commands

import zio.*
import zio.test.*

import _root_.config.entity.*
import shared.errors.PersistenceError

object ConfigCommandSpec extends ZIOSpecDefault:

  private final class InMemoryConfigRepo(ref: Ref[Map[String, String]]) extends ConfigRepository:
    override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
      ref.get.map(_.toList.map((k, v) => SettingRow(k, v)))
    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
      ref.get.map(_.get(key).map(v => SettingRow(key, v)))
    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
      ref.update(_ + (key -> value))
    override def deleteSetting(key: String): IO[PersistenceError, Unit] = ref.update(_ - key)
    override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] = ZIO.unit
    override def listAgentChannelBindings: IO[PersistenceError, List[AgentChannelBinding]] = ZIO.succeed(Nil)
    override def upsertAgentChannelBinding(binding: AgentChannelBinding): IO[PersistenceError, Unit] = ZIO.unit
    override def deleteAgentChannelBinding(binding: AgentChannelBinding): IO[PersistenceError, Unit] = ZIO.unit
    override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long] = ZIO.succeed(0L)
    override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]] = ZIO.succeed(None)
    override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]] = ZIO.succeed(None)
    override def listWorkflows: IO[PersistenceError, List[WorkflowRow]] = ZIO.succeed(Nil)
    override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit] = ZIO.unit
    override def deleteWorkflow(id: Long): IO[PersistenceError, Unit] = ZIO.unit
    override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long] = ZIO.succeed(0L)
    override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]] = ZIO.succeed(None)
    override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] = ZIO.succeed(None)
    override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]] = ZIO.succeed(Nil)
    override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit] = ZIO.unit
    override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit] = ZIO.unit

  private def repoLayer(initial: Map[String, String] = Map.empty) =
    ZLayer.fromZIO(Ref.make(initial).map(new InMemoryConfigRepo(_)))

  def spec = suite("ConfigCommand")(
    test("listSettings returns all settings formatted") {
      for
        output <- ConfigCommand.listSettings
      yield assertTrue(
        output.contains("ai.provider"),
        output.contains("openai"),
      )
    }.provide(repoLayer(Map("ai.provider" -> "openai", "ai.model" -> "gpt-4"))),

    test("listSettings returns empty message when no settings") {
      for
        output <- ConfigCommand.listSettings
      yield assertTrue(output.contains("No settings"))
    }.provide(repoLayer()),

    test("setSetting updates a value and confirms") {
      for
        output <- ConfigCommand.setSetting("ai.provider", "anthropic")
        check  <- ZIO.serviceWithZIO[ConfigRepository](_.getSetting("ai.provider"))
      yield assertTrue(
        output.contains("ai.provider"),
        output.contains("anthropic"),
        check.exists(_.value == "anthropic"),
      )
    }.provide(repoLayer()),
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt cli/test`
Expected: Compilation error — `ConfigCommand` doesn't exist yet.

- [ ] **Step 3: Implement ConfigCommand**

Create `modules/cli/src/main/scala/cli/commands/ConfigCommand.scala`:

```scala
package cli.commands

import zio.*

import _root_.config.entity.ConfigRepository
import shared.errors.PersistenceError

object ConfigCommand:

  def listSettings: ZIO[ConfigRepository, PersistenceError, String] =
    for
      settings <- ZIO.serviceWithZIO[ConfigRepository](_.getAllSettings)
    yield
      if settings.isEmpty then "No settings configured."
      else
        val maxKeyLen = settings.map(_.key.length).max
        settings
          .sortBy(_.key)
          .map(s => s"  ${s.key.padTo(maxKeyLen, ' ')}  ${s.value}")
          .mkString("Settings:\n", "\n", "")

  def setSetting(key: String, value: String): ZIO[ConfigRepository, PersistenceError, String] =
    for
      _ <- ZIO.serviceWithZIO[ConfigRepository](_.upsertSetting(key, value))
    yield s"Set $key = $value"
```

- [ ] **Step 4: Run tests**

Run: `sbt cli/test`
Expected: All ConfigCommand tests pass.

- [ ] **Step 5: Commit**

```bash
git add modules/cli/src/main/scala/cli/commands/ConfigCommand.scala modules/cli/src/test/scala/cli/commands/ConfigCommandSpec.scala
git commit -m "feat(cli): add config list and config set commands"
```

---

## Task 6: Board Command

**Files:**
- Create: `modules/cli/src/main/scala/cli/commands/BoardCommand.scala`
- Create: `modules/cli/src/test/scala/cli/commands/BoardCommandSpec.scala`

- [ ] **Step 1: Write BoardCommand test**

Create `modules/cli/src/test/scala/cli/commands/BoardCommandSpec.scala`:

```scala
package cli.commands

import zio.*
import zio.test.*

import board.entity.*
import shared.ids.Ids.BoardIssueId

object BoardCommandSpec extends ZIOSpecDefault:

  private val sampleIssues = List(
    BoardIssue(
      id = BoardIssueId("issue-1"),
      title = "Add login page",
      column = BoardColumn.InProgress,
      frontmatter = IssueFrontmatter.empty,
      body = "Implement OAuth login",
    ),
    BoardIssue(
      id = BoardIssueId("issue-2"),
      title = "Fix header layout",
      column = BoardColumn.Todo,
      frontmatter = IssueFrontmatter.empty,
      body = "Header breaks on mobile",
    ),
  )

  private val sampleBoard = Board(
    columns = Map(
      BoardColumn.Todo -> List(sampleIssues(1)),
      BoardColumn.InProgress -> List(sampleIssues(0)),
    )
  )

  private final class StubBoardRepo(board: Board) extends BoardRepository:
    override def readBoard(workspacePath: String): IO[BoardError, Board] = ZIO.succeed(board)
    override def readIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, BoardIssue] =
      ZIO.fromOption(board.columns.values.flatten.find(_.id == issueId))
        .orElseFail(BoardError.IssueNotFound(issueId.value))
    override def initBoard(workspacePath: String): IO[BoardError, Unit] = ZIO.unit
    override def createIssue(workspacePath: String, column: BoardColumn, issue: BoardIssue): IO[BoardError, BoardIssue] = ZIO.succeed(issue)
    override def moveIssue(workspacePath: String, issueId: BoardIssueId, toColumn: BoardColumn): IO[BoardError, BoardIssue] = ZIO.succeed(sampleIssues.head)
    override def updateIssue(workspacePath: String, issueId: BoardIssueId, update: IssueFrontmatter => IssueFrontmatter): IO[BoardError, BoardIssue] = ZIO.succeed(sampleIssues.head)
    override def deleteIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit] = ZIO.unit
    override def listIssues(workspacePath: String, column: BoardColumn): IO[BoardError, List[BoardIssue]] =
      ZIO.succeed(board.columns.getOrElse(column, Nil))
    override def invalidateWorkspace(workspacePath: String): UIO[Unit] = ZIO.unit

  private def boardLayer = ZLayer.succeed[BoardRepository](new StubBoardRepo(sampleBoard))

  def spec = suite("BoardCommand")(
    test("listBoard shows issues grouped by column") {
      for
        output <- BoardCommand.listBoard("/tmp/workspace")
      yield assertTrue(
        output.contains("Todo"),
        output.contains("InProgress"),
        output.contains("Fix header layout"),
        output.contains("Add login page"),
      )
    }.provide(boardLayer),

    test("showIssue displays issue details") {
      for
        output <- BoardCommand.showIssue("/tmp/workspace", "issue-1")
      yield assertTrue(
        output.contains("Add login page"),
        output.contains("InProgress"),
        output.contains("Implement OAuth login"),
      )
    }.provide(boardLayer),
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt cli/test`
Expected: Compilation error — `BoardCommand` doesn't exist yet.

- [ ] **Step 3: Implement BoardCommand**

Create `modules/cli/src/main/scala/cli/commands/BoardCommand.scala`:

```scala
package cli.commands

import zio.*

import board.entity.{ Board, BoardColumn, BoardError, BoardIssue, BoardRepository }
import shared.ids.Ids.BoardIssueId

object BoardCommand:

  def listBoard(workspacePath: String): ZIO[BoardRepository, BoardError, String] =
    for
      board <- ZIO.serviceWithZIO[BoardRepository](_.readBoard(workspacePath))
    yield formatBoard(board)

  def showIssue(workspacePath: String, issueId: String): ZIO[BoardRepository, BoardError, String] =
    for
      issue <- ZIO.serviceWithZIO[BoardRepository](_.readIssue(workspacePath, BoardIssueId(issueId)))
    yield formatIssue(issue)

  private def formatBoard(board: Board): String =
    val columns = BoardColumn.values.toList
    columns
      .flatMap { col =>
        board.columns.get(col).filter(_.nonEmpty).map { issues =>
          val header = s"${col.toString} (${issues.size})"
          val items = issues.map(i => s"  ${i.id.value}  ${i.title}").mkString("\n")
          s"$header\n$items"
        }
      }
      .mkString("\n\n")

  private def formatIssue(issue: BoardIssue): String =
    s"""${issue.title}
       |Column: ${issue.column}
       |ID: ${issue.id.value}
       |
       |${issue.body}""".stripMargin
```

- [ ] **Step 4: Run tests and adjust**

Run: `sbt cli/test`
Expected: All BoardCommand tests pass. If `Board`, `BoardColumn`, `BoardIssue`, or `IssueFrontmatter` have different shapes than assumed, adjust the test and implementation. Check `modules/board-domain/src/main/scala/board/entity/` for exact types.

- [ ] **Step 5: Commit**

```bash
git add modules/cli/src/main/scala/cli/commands/BoardCommand.scala modules/cli/src/test/scala/cli/commands/BoardCommandSpec.scala
git commit -m "feat(cli): add board list and board show commands"
```

---

## Task 7: Workspace and Project Commands

**Files:**
- Create: `modules/cli/src/main/scala/cli/commands/WorkspaceCommand.scala`
- Create: `modules/cli/src/main/scala/cli/commands/ProjectCommand.scala`
- Create: `modules/cli/src/test/scala/cli/commands/WorkspaceCommandSpec.scala`
- Create: `modules/cli/src/test/scala/cli/commands/ProjectCommandSpec.scala`

- [ ] **Step 1: Write WorkspaceCommand test**

Create `modules/cli/src/test/scala/cli/commands/WorkspaceCommandSpec.scala`:

```scala
package cli.commands

import zio.*
import zio.test.*

import shared.errors.PersistenceError
import shared.ids.Ids.ProjectId
import workspace.entity.*

object WorkspaceCommandSpec extends ZIOSpecDefault:

  private final class StubWorkspaceRepo(workspaces: List[Workspace]) extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit] = ZIO.unit
    override def list: IO[PersistenceError, List[Workspace]] = ZIO.succeed(workspaces)
    override def listByProject(projectId: ProjectId): IO[PersistenceError, List[Workspace]] =
      ZIO.succeed(workspaces.filter(_.projectId.contains(projectId)))
    override def get(id: String): IO[PersistenceError, Option[Workspace]] =
      ZIO.succeed(workspaces.find(_.id == id))
    override def delete(id: String): IO[PersistenceError, Unit] = ZIO.unit
    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit] = ZIO.unit
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]] = ZIO.succeed(Nil)
    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] = ZIO.succeed(Nil)
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]] = ZIO.succeed(None)

  def spec = suite("WorkspaceCommand")(
    test("listWorkspaces shows workspace names and paths") {
      for
        output <- WorkspaceCommand.listWorkspaces
      yield assertTrue(
        output.contains("ws-1"),
        output.contains("/tmp/ws1"),
      )
    }.provide(ZLayer.succeed[WorkspaceRepository](
      new StubWorkspaceRepo(List(
        Workspace(id = "ws-1", name = "Test Workspace", localPath = "/tmp/ws1")
      ))
    )),
  )
```

**Note:** Adjust `Workspace` constructor based on actual entity shape. Read `modules/workspace-domain/src/main/scala/workspace/entity/` for the actual case class fields.

- [ ] **Step 2: Write ProjectCommand test**

Create `modules/cli/src/test/scala/cli/commands/ProjectCommandSpec.scala`:

```scala
package cli.commands

import zio.*
import zio.test.*

import project.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.ProjectId

object ProjectCommandSpec extends ZIOSpecDefault:

  private final class StubProjectRepo(projects: List[Project]) extends ProjectRepository:
    override def append(event: ProjectEvent): IO[PersistenceError, Unit] = ZIO.unit
    override def list: IO[PersistenceError, List[Project]] = ZIO.succeed(projects)
    override def get(id: ProjectId): IO[PersistenceError, Option[Project]] =
      ZIO.succeed(projects.find(_.id == id))
    override def delete(id: ProjectId): IO[PersistenceError, Unit] = ZIO.unit

  def spec = suite("ProjectCommand")(
    test("listProjects shows project names") {
      for
        output <- ProjectCommand.listProjects
      yield assertTrue(
        output.contains("proj-1"),
        output.contains("My Project"),
      )
    }.provide(ZLayer.succeed[ProjectRepository](
      new StubProjectRepo(List(
        Project(id = ProjectId("proj-1"), name = "My Project")
      ))
    )),
  )
```

**Note:** Adjust `Project` constructor based on actual entity shape. Read `modules/project-domain/src/main/scala/project/entity/` for the actual case class fields.

- [ ] **Step 3: Implement WorkspaceCommand**

Create `modules/cli/src/main/scala/cli/commands/WorkspaceCommand.scala`:

```scala
package cli.commands

import zio.*

import shared.errors.PersistenceError
import workspace.entity.{ Workspace, WorkspaceRepository }

object WorkspaceCommand:

  def listWorkspaces: ZIO[WorkspaceRepository, PersistenceError, String] =
    for
      workspaces <- ZIO.serviceWithZIO[WorkspaceRepository](_.list)
    yield
      if workspaces.isEmpty then "No workspaces configured."
      else
        workspaces
          .map(ws => s"  ${ws.id}  ${ws.name}  ${ws.localPath}")
          .mkString("Workspaces:\n", "\n", "")
```

- [ ] **Step 4: Implement ProjectCommand**

Create `modules/cli/src/main/scala/cli/commands/ProjectCommand.scala`:

```scala
package cli.commands

import zio.*

import project.entity.{ Project, ProjectRepository }
import shared.errors.PersistenceError

object ProjectCommand:

  def listProjects: ZIO[ProjectRepository, PersistenceError, String] =
    for
      projects <- ZIO.serviceWithZIO[ProjectRepository](_.list)
    yield
      if projects.isEmpty then "No projects configured."
      else
        projects
          .map(p => s"  ${p.id.value}  ${p.name}")
          .mkString("Projects:\n", "\n", "")
```

- [ ] **Step 5: Run tests and adjust**

Run: `sbt cli/test`
Expected: All tests pass. Adjust entity constructors/fields if they differ from what's assumed.

- [ ] **Step 6: Commit**

```bash
git add modules/cli/src/main/scala/cli/commands/WorkspaceCommand.scala modules/cli/src/main/scala/cli/commands/ProjectCommand.scala modules/cli/src/test/scala/cli/commands/WorkspaceCommandSpec.scala modules/cli/src/test/scala/cli/commands/ProjectCommandSpec.scala
git commit -m "feat(cli): add workspace list and project list commands"
```

---

## Task 8: Remote Command

HTTP client commands for talking to a running gateway.

**Files:**
- Create: `modules/cli/src/main/scala/cli/commands/RemoteCommand.scala`

- [ ] **Step 1: Implement RemoteCommand**

Create `modules/cli/src/main/scala/cli/commands/RemoteCommand.scala`:

```scala
package cli.commands

import zio.*
import zio.http.*
import zio.json.*

object RemoteCommand:

  def status(gatewayUrl: String): ZIO[Client, Throwable, String] =
    for
      url      <- ZIO.fromEither(URL.decode(s"$gatewayUrl/health")).mapError(new RuntimeException(_))
      response <- Client.request(Request.get(url))
      body     <- response.body.asString
    yield
      if response.status.isSuccess then s"Gateway is healthy: $body"
      else s"Gateway returned ${response.status.code}: $body"

  def chat(gatewayUrl: String, prompt: String): ZIO[Client, Throwable, String] =
    for
      url      <- ZIO.fromEither(URL.decode(s"$gatewayUrl/api/chat")).mapError(new RuntimeException(_))
      response <- Client.request(
                    Request.post(url, Body.fromString(s"""{"message":"$prompt"}"""))
                      .addHeader(Header.ContentType(MediaType.application.json))
                  )
      body     <- response.body.asString
    yield body
```

**Note:** Adjust the `/health` and `/api/chat` endpoints to match the actual gateway routes. Check `src/main/scala/app/boundary/CoreRouteModule.scala` and `src/main/scala/app/boundary/GatewayRouteModule.scala` for the real paths.

- [ ] **Step 2: Verify it compiles**

Run: `sbt cli/compile`
Expected: Compiles without errors.

- [ ] **Step 3: Commit**

```bash
git add modules/cli/src/main/scala/cli/commands/RemoteCommand.scala
git commit -m "feat(cli): add remote status and remote chat commands"
```

---

## Task 9: CLI Main with zio-cli Command Tree

Wire all commands together into the CLI entry point.

**Files:**
- Modify: `modules/cli/src/main/scala/cli/Main.scala`

- [ ] **Step 1: Implement the full CLI Main**

Replace `modules/cli/src/main/scala/cli/Main.scala` with:

```scala
package cli

import java.nio.file.{ Path, Paths }

import zio.*
import zio.cli.*
import zio.cli.HelpDoc.Span.text
import zio.http.Client
import zio.logging.backend.SLF4J

import cli.commands.*
import shared.store.StoreConfig

object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  private val defaultStateRoot: Path =
    Paths.get(sys.props.getOrElse("user.home", ".")).resolve(".llm4zio-gateway").resolve("data")

  // --- Global options ---

  private val statePathOpt = Options.text("state").optional ??
    s"Store root path (default: ${defaultStateRoot.toAbsolutePath})"

  private val gatewayUrlOpt = Options.text("gateway-url")
    .withDefault("http://localhost:8080") ?? "Gateway URL for remote commands"

  // --- Config commands ---

  private val configList: Command[Option[String]] =
    Command("list", statePathOpt).withHelp("List all settings")

  private val configSetKey  = Args.text("key")
  private val configSetVal  = Args.text("value")
  private val configSet: Command[(Option[String], (String, String))] =
    Command("set", statePathOpt, configSetKey ++ configSetVal).withHelp("Set a config value")

  private val configCmd: Command[Any] =
    Command("config").subcommands(configList, configSet)

  // --- Board commands ---

  private val workspacePathArg = Args.text("workspace-path")

  private val boardList: Command[(Option[String], String)] =
    Command("list", statePathOpt, workspacePathArg).withHelp("List board issues")

  private val issueIdArg = Args.text("issue-id")
  private val boardShow: Command[(Option[String], (String, String))] =
    Command("show", statePathOpt, workspacePathArg ++ issueIdArg).withHelp("Show issue details")

  private val boardCmd: Command[Any] =
    Command("board").subcommands(boardList, boardShow)

  // --- Workspace command ---

  private val workspaceList: Command[Option[String]] =
    Command("list", statePathOpt).withHelp("List workspaces")

  private val workspaceCmd: Command[Any] =
    Command("workspace").subcommands(workspaceList)

  // --- Project command ---

  private val projectList: Command[Option[String]] =
    Command("list", statePathOpt).withHelp("List projects")

  private val projectCmd: Command[Any] =
    Command("project").subcommands(projectList)

  // --- Remote commands ---

  private val remoteStatus: Command[String] =
    Command("status", gatewayUrlOpt).withHelp("Check gateway health")

  private val promptArg   = Args.text("prompt")
  private val remoteChat: Command[(String, String)] =
    Command("chat", gatewayUrlOpt, promptArg).withHelp("Chat via gateway")

  private val remoteCmd: Command[Any] =
    Command("remote").subcommands(remoteStatus, remoteChat)

  // --- Top-level command ---

  private val topCmd: Command[Any] =
    Command("llm4zio-cli").subcommands(configCmd, boardCmd, workspaceCmd, projectCmd, remoteCmd)

  private val cliApp = CliApp.make(
    name = "llm4zio-cli",
    version = "1.0.0",
    summary = text("CLI for llm4zio gateway"),
    command = topCmd,
  ) {
    case ("config", ("list", statePath: Option[String @unchecked])) =>
      runStandalone(statePath) {
        ConfigCommand.listSettings.tap(s => Console.printLine(s)).unit
      }
    case ("config", ("set", (statePath: Option[String @unchecked], (key: String, value: String)))) =>
      runStandalone(statePath) {
        ConfigCommand.setSetting(key, value).tap(s => Console.printLine(s)).unit
      }
    case ("board", ("list", (statePath: Option[String @unchecked], wsPath: String))) =>
      runWithBoard(statePath, wsPath) {
        BoardCommand.listBoard(wsPath).tap(s => Console.printLine(s)).unit
      }
    case ("board", ("show", (statePath: Option[String @unchecked], (wsPath: String, issueId: String)))) =>
      runWithBoard(statePath, wsPath) {
        BoardCommand.showIssue(wsPath, issueId).tap(s => Console.printLine(s)).unit
      }
    case ("workspace", ("list", statePath: Option[String @unchecked])) =>
      runStandalone(statePath) {
        WorkspaceCommand.listWorkspaces.tap(s => Console.printLine(s)).unit
      }
    case ("project", ("list", statePath: Option[String @unchecked])) =>
      runStandalone(statePath) {
        ProjectCommand.listProjects.tap(s => Console.printLine(s)).unit
      }
    case ("remote", ("status", url: String)) =>
      RemoteCommand.status(url).tap(s => Console.printLine(s)).unit
        .provide(Client.default)
    case ("remote", ("chat", (url: String, prompt: String))) =>
      RemoteCommand.chat(url, prompt).tap(s => Console.printLine(s)).unit
        .provide(Client.default)
    case other =>
      Console.printLine(s"Unknown command: $other")
  }

  private def buildStoreConfig(statePath: Option[String]): StoreConfig =
    val root = Paths.get(statePath.getOrElse(defaultStateRoot.toString))
    StoreConfig(
      configStorePath = root.resolve("config-store").toString,
      dataStorePath = root.resolve("data-store").toString,
    )

  private def runStandalone(statePath: Option[String])(
    effect: ZIO[CliDI.StandaloneEnv, Any, Unit]
  ): ZIO[Any, Any, Unit] =
    effect.provide(CliDI.standaloneLayers(buildStoreConfig(statePath)))

  private def runWithBoard(statePath: Option[String], wsPath: String)(
    effect: ZIO[board.entity.BoardRepository, Any, Unit]
  ): ZIO[Any, Any, Unit] =
    effect.provide(
      board.control.BoardRepositoryFS.live,
      board.control.IssueMarkdownParser.live,
      workspace.control.GitService.live,
    )

  override def run: ZIO[ZIOAppArgs & Scope, Any, Unit] =
    for
      args <- ZIOAppArgs.getArgs
      _    <- cliApp.run(args.toList).orDie
    yield ()
```

**Important notes:**
- The `zio-cli` command matching pattern depends on the exact types `zio-cli 0.7.5` produces. The pattern matching above is approximate — adjust based on what the compiler requires.
- `BoardRepositoryFS.live` needs `IssueMarkdownParser` and `GitService` — check their exact layer signatures in `modules/board-domain/src/main/scala/board/control/`.
- If `GitService` is in a different module (e.g., `shared-services`), add that dependency.

- [ ] **Step 2: Verify it compiles**

Run: `sbt cli/compile`
Expected: Compiles. Fix any type mismatches in the zio-cli pattern matching — the exact tuple shapes depend on how `++` composes `Options` and `Args` in zio-cli.

- [ ] **Step 3: Test basic CLI help**

Run: `sbt "cli/run --help"`
Expected: Shows help with all subcommands listed.

- [ ] **Step 4: Commit**

```bash
git add modules/cli/src/main/scala/cli/Main.scala
git commit -m "feat(cli): wire all commands into zio-cli Main

Supports: config list/set, board list/show, workspace list,
project list, remote status/chat."
```

---

## Deferred: Standalone Chat Command

The spec mentioned a standalone `chat <prompt>` command for direct LLM interaction without a running gateway. This requires `HttpAIClient`, `ConfigAwareLlmService`, and the LLM provider chain — a significant dependency graph. **Deferred to a follow-up plan** once the CLI module is established. In the meantime, use `remote chat` to chat via a running gateway.

---

## Task 10: End-to-End Verification

- [ ] **Step 1: Verify gateway starts with `sbt run`**

Run: `sbt run`
Expected: Gateway starts on http://0.0.0.0:8080. No "serve" subcommand needed.

- [ ] **Step 2: Verify gateway env var overrides**

Run: `GATEWAY_PORT=9000 sbt run`
Expected: Gateway starts on port 9000.

- [ ] **Step 3: Verify CLI help**

Run: `sbt "cli/run --help"`
Expected: Shows CLI help with all subcommands.

- [ ] **Step 4: Verify CLI remote status (requires running gateway)**

Start gateway in one terminal: `sbt run`
In another terminal: `sbt "cli/run remote status"`
Expected: "Gateway is healthy: ..." output.

- [ ] **Step 5: Verify CLI config list (standalone)**

Run: `sbt "cli/run config list"`
Expected: Shows settings from local store (or "No settings configured" if empty).

- [ ] **Step 6: Run all tests**

Run: `sbt test && sbt cli/test`
Expected: All tests pass.

- [ ] **Step 7: Final commit**

```bash
git add -A
git commit -m "feat: complete gateway/CLI split

Gateway starts with 'sbt run' (no subcommand).
CLI module at modules/cli/ with standalone and remote commands."
```
