package cli

import java.nio.file.{ Path, Paths }

import zio.*
import zio.cli.*
import zio.cli.HelpDoc.Span.text
import zio.http.Client
import zio.logging.backend.SLF4J

import board.control.{ BoardRepositoryFS, IssueMarkdownParser }
import board.entity.BoardRepository
import _root_.cli.commands.{
  AgentRunCommand,
  BoardCommand,
  ConfigCommand,
  DeployCommand,
  EvalCommand,
  InfoCommand,
  IngestCommand,
  LoginCommand,
  ProjectCommand,
  RemoteCommand,
  ScaffoldCommand,
  SetupCommand,
  WorkspaceCommand,
}
import shared.services.FileService
import shared.store.StoreConfig
import workspace.control.GitService

object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  // ── Default paths ────────────────────────────────────────────────────

  private val defaultStateRoot: Path =
    Paths.get(sys.props.getOrElse("user.home", ".")).resolve(".llm4zio").resolve("data")

  private val defaultGatewayUrl: String = "http://localhost:8080"

  private val cliVersion: String = "1.0.0"

  // ── Internal routing ADT ──────────────────────────────────────────────

  enum CliCommand:
    case ConfigList(statePath: Option[String])
    case ConfigSet(statePath: Option[String], key: String, value: String)
    case BoardList(statePath: Option[String], workspacePath: String)
    case BoardShow(statePath: Option[String], workspacePath: String, issueId: String)
    case WorkspaceList(statePath: Option[String])
    case WorkspaceInit(
      statePath: Option[String],
      path: String,
      name: Option[String],
      description: Option[String],
    )
    case ProjectList(statePath: Option[String])
    case RemoteStatus(gatewayUrl: Option[String])
    case RemoteChat(gatewayUrl: Option[String], prompt: String)
    case AgentInit(
      statePath: Option[String],
      name: String,
      workspace: String,
      model: Option[String],
      cliTool: Option[String],
      description: Option[String],
    )
    case AgentRun(
      statePath: Option[String],
      workspace: String,
      name: String,
      prompt: String,
    )
    case EvalRun(
      statePath: Option[String],
      workspace: String,
      agent: String,
      dataset: String,
    )
    case EvalCompare(baseline: String, candidate: String)
    case Deploy(
      workspace: String,
      target: String,
      repoRoot: Option[String],
      imageName: Option[String],
      imageTag: Option[String],
      dryRun: Boolean,
    )
    case Setup(statePath: Option[String], gatewayUrl: Option[String])
    case Login(statePath: Option[String], provider: String, apiKey: String, agent: Option[String])
    case Info(statePath: Option[String], gatewayUrl: Option[String])
    case Ingest(
      workspace: String,
      source: String,
      scope: Option[String],
      tags: Option[String],
      extensions: Option[String],
    )

  // ── Shared options ───────────────────────────────────────────────────

  private val stateOpt: Options[Option[String]] =
    Options.text("state").optional ??
      s"Store root path (default: ${defaultStateRoot.toAbsolutePath})"

  private val gatewayUrlOpt: Options[Option[String]] =
    Options.text("gateway-url").optional ??
      s"Gateway base URL (default: $defaultGatewayUrl)"

  private val wsNameOpt: Options[Option[String]] =
    Options.text("name").optional ?? "Workspace name (default: directory name)"

  private val wsDescriptionOpt: Options[Option[String]] =
    Options.text("description").optional ?? "Human-readable workspace description"

  private val agentWorkspaceOpt: Options[String] =
    Options.text("workspace") ?? "Target workspace path"

  private val agentModelOpt: Options[Option[String]] =
    Options.text("model").optional ?? "Default model id (e.g. claude-opus-4-7, gpt-4)"

  private val agentCliToolOpt: Options[Option[String]] =
    Options.text("cli-tool").optional ?? "Underlying CLI tool (default: llm4zio)"

  private val agentDescriptionOpt: Options[Option[String]] =
    Options.text("description").optional ?? "Human-readable agent description"

  private val evalAgentOpt: Options[String] =
    Options.text("agent") ?? "Agent name to evaluate"

  private val deployTargetOpt: Options[String] =
    Options.text("target") ?? s"Deploy target: ${deploy.entity.DeployTarget.supportedValues}"

  private val deployRepoRootOpt: Options[Option[String]] =
    Options.text("repo-root").optional ??
      "Path to the llm4zio source repo (default: current dir) — used for `sbt assembly` and Docker build context"

  private val deployImageNameOpt: Options[Option[String]] =
    Options.text("image-name").optional ?? "Container image name (default: llm4zio-<workspace-name>)"

  private val deployImageTagOpt: Options[Option[String]] =
    Options.text("image-tag").optional ?? "Container image tag (default: dev)"

  private val deployDryRunOpt: Options[Boolean] =
    Options.boolean("dry-run") ?? "Generate artifacts but do not invoke docker/sbt/gcloud/kubectl"

  private val loginKeyOpt: Options[String] =
    Options.text("key") ?? "API key to store"

  private val loginAgentOpt: Options[Option[String]] =
    Options.text("agent").optional ?? "Scope the key to a named agent (default: global connector.default.*)"

  private val ingestScopeOpt: Options[Option[String]] =
    Options.text("scope").optional ?? "Memory scope tag (default: knowledge)"

  private val ingestTagsOpt: Options[Option[String]] =
    Options.text("tags").optional ?? "Comma-separated tags to attach to each record"

  private val ingestExtensionsOpt: Options[Option[String]] =
    Options.text("extensions").optional ?? s"Comma-separated extensions to include (default: md,txt,markdown)"

  // ── config subcommands ───────────────────────────────────────────────

  private val configListSub: Command[CliCommand] =
    Command("list", stateOpt)
      .withHelp("List all configuration settings")
      .map(CliCommand.ConfigList.apply)

  private val configSetSub: Command[CliCommand] =
    Command("set", stateOpt, Args.text("key") ++ Args.text("value"))
      .withHelp("Set a configuration key to a value")
      .map { case (statePath, (key, value)) => CliCommand.ConfigSet(statePath, key, value) }

  private val configCmd: Command[CliCommand] =
    Command("config")
      .withHelp("Manage configuration settings")
      .subcommands(configListSub, configSetSub)

  // ── board subcommands ────────────────────────────────────────────────

  private val boardListSub: Command[CliCommand] =
    Command("list", stateOpt, Args.text("workspace-path"))
      .withHelp("List all issues on the board for a workspace")
      .map { case (statePath, workspacePath) => CliCommand.BoardList(statePath, workspacePath) }

  private val boardShowSub: Command[CliCommand] =
    Command("show", stateOpt, Args.text("workspace-path") ++ Args.text("issue-id"))
      .withHelp("Show details of a specific board issue")
      .map { case (statePath, (workspacePath, issueId)) => CliCommand.BoardShow(statePath, workspacePath, issueId) }

  private val boardCmd: Command[CliCommand] =
    Command("board")
      .withHelp("Inspect the kanban board")
      .subcommands(boardListSub, boardShowSub)

  // ── workspace subcommands ────────────────────────────────────────────

  private val workspaceListSub: Command[CliCommand] =
    Command("list", stateOpt)
      .withHelp("List all workspaces")
      .map(CliCommand.WorkspaceList.apply)

  private val workspaceInitSub: Command[CliCommand] =
    Command(
      "init",
      stateOpt ++ wsNameOpt ++ wsDescriptionOpt,
      Args.text("path"),
    )
      .withHelp("Scaffold a new workspace (board, agents/, evals/, policy) at <path>")
      .map { case ((statePath, name, description), path) =>
        CliCommand.WorkspaceInit(statePath, path, name, description)
      }

  private val workspaceCmd: Command[CliCommand] =
    Command("workspace")
      .withHelp("Manage workspaces")
      .subcommands(workspaceListSub, workspaceInitSub)

  // ── agent subcommands ────────────────────────────────────────────────

  private val agentInitSub: Command[CliCommand] =
    Command(
      "init",
      stateOpt ++ agentWorkspaceOpt ++ agentModelOpt ++ agentCliToolOpt ++ agentDescriptionOpt,
      Args.text("name"),
    )
      .withHelp("Scaffold a new agent definition under <workspace>/agents/")
      .map { case ((statePath, workspace, model, cliTool, description), name) =>
        CliCommand.AgentInit(statePath, name, workspace, model, cliTool, description)
      }

  private val agentRunSub: Command[CliCommand] =
    Command(
      "run",
      stateOpt ++ agentWorkspaceOpt,
      Args.text("name") ++ Args.text("prompt"),
    )
      .withHelp("Run an agent locally (one-shot subprocess) against the given prompt")
      .map { case ((statePath, workspace), (name, prompt)) =>
        CliCommand.AgentRun(statePath, workspace, name, prompt)
      }

  private val agentCmd: Command[CliCommand] =
    Command("agent")
      .withHelp("Manage agent definitions")
      .subcommands(agentInitSub, agentRunSub)

  // ── eval subcommands ─────────────────────────────────────────────────

  private val evalRunSub: Command[CliCommand] =
    Command(
      "run",
      stateOpt ++ agentWorkspaceOpt ++ evalAgentOpt,
      Args.text("dataset"),
    )
      .withHelp("Run a JSONL eval dataset against an agent and write a run file")
      .map { case ((statePath, workspace, agent), dataset) =>
        CliCommand.EvalRun(statePath, workspace, agent, dataset)
      }

  private val evalCompareSub: Command[CliCommand] =
    Command("compare", Args.text("baseline") ++ Args.text("candidate"))
      .withHelp("Compare two run files; exits non-zero on regression")
      .map { case (baseline, candidate) => CliCommand.EvalCompare(baseline, candidate) }

  private val evalCmd: Command[CliCommand] =
    Command("eval")
      .withHelp("Run and compare eval datasets")
      .subcommands(evalRunSub, evalCompareSub)

  // ── deploy command ───────────────────────────────────────────────────

  private val deployCmd: Command[CliCommand] =
    Command(
      "deploy",
      agentWorkspaceOpt ++ deployTargetOpt ++ deployRepoRootOpt ++
        deployImageNameOpt ++ deployImageTagOpt ++ deployDryRunOpt,
    )
      .withHelp("Package a workspace for a runtime target (jvm-fatjar | docker | cloud-run | kubernetes)")
      .map { case (workspace, target, repoRoot, imageName, imageTag, dryRun) =>
        CliCommand.Deploy(workspace, target, repoRoot, imageName, imageTag, dryRun)
      }

  // ── setup / login / info ─────────────────────────────────────────────

  private val setupCmd: Command[CliCommand] =
    Command("setup", stateOpt ++ gatewayUrlOpt)
      .withHelp("Initialise the state dir and seed default settings")
      .map { case (statePath, gatewayUrl) => CliCommand.Setup(statePath, gatewayUrl) }

  private val loginCmd: Command[CliCommand] =
    Command("login", stateOpt ++ loginKeyOpt ++ loginAgentOpt, Args.text("provider"))
      .withHelp("Store provider credentials for openai|anthropic|gemini|...")
      .map { case ((statePath, key, agent), provider) => CliCommand.Login(statePath, provider, key, agent) }

  private val infoCmd: Command[CliCommand] =
    Command("info", stateOpt ++ gatewayUrlOpt)
      .withHelp("Show state path, gateway reachability, provider-key status, CLI version")
      .map { case (statePath, gatewayUrl) => CliCommand.Info(statePath, gatewayUrl) }

  private val ingestCmd: Command[CliCommand] =
    Command(
      "ingest",
      agentWorkspaceOpt ++ ingestScopeOpt ++ ingestTagsOpt ++ ingestExtensionsOpt,
      Args.text("source"),
    )
      .withHelp("Scan a file or directory and emit an ingest manifest under <workspace>/ingested/")
      .map { case ((workspace, scope, tags, extensions), source) =>
        CliCommand.Ingest(workspace, source, scope, tags, extensions)
      }

  // ── project subcommands ──────────────────────────────────────────────

  private val projectListSub: Command[CliCommand] =
    Command("list", stateOpt)
      .withHelp("List all projects")
      .map(CliCommand.ProjectList.apply)

  private val projectCmd: Command[CliCommand] =
    Command("project")
      .withHelp("Manage projects")
      .subcommands(projectListSub)

  // ── remote subcommands ───────────────────────────────────────────────

  private val remoteStatusSub: Command[CliCommand] =
    Command("status", gatewayUrlOpt)
      .withHelp("Check gateway health status")
      .map(CliCommand.RemoteStatus.apply)

  private val remoteChatSub: Command[CliCommand] =
    Command("chat", gatewayUrlOpt, Args.text("prompt"))
      .withHelp("Send a chat prompt to the gateway")
      .map { case (gatewayUrl, prompt) => CliCommand.RemoteChat(gatewayUrl, prompt) }

  private val remoteCmd: Command[CliCommand] =
    Command("remote")
      .withHelp("Interact with a running gateway")
      .subcommands(remoteStatusSub, remoteChatSub)

  // ── Top-level command ────────────────────────────────────────────────

  private val rootCmd: Command[CliCommand] =
    Command("llm4zio-cli")
      .subcommands(
        agentCmd,
        configCmd,
        boardCmd,
        workspaceCmd,
        projectCmd,
        remoteCmd,
        evalCmd,
        deployCmd,
        setupCmd,
        loginCmd,
        infoCmd,
        ingestCmd,
      )

  // ── Helpers ──────────────────────────────────────────────────────────

  private def buildStoreConfig(statePath: Option[String]): StoreConfig =
    val root = Paths.get(statePath.getOrElse(defaultStateRoot.toString))
    StoreConfig(
      configStorePath = root.resolve("config-store").toString,
      dataStorePath = root.resolve("data-store").toString,
    )

  private def boardLayer: ZLayer[Any, Nothing, BoardRepository] =
    GitService.live ++ IssueMarkdownParser.live >>> BoardRepositoryFS.live

  // ── Handler ──────────────────────────────────────────────────────────

  private def handle(cmd: CliCommand): ZIO[Any, Any, Unit] =
    cmd match

      case CliCommand.ConfigList(statePath) =>
        val storeConfig = buildStoreConfig(statePath)
        ConfigCommand.listSettings
          .provide(CliDI.standaloneLayers(storeConfig))
          .flatMap(Console.printLine(_))
          .catchAll(err => Console.printLineError(s"Error: $err"))

      case CliCommand.ConfigSet(statePath, key, value) =>
        val storeConfig = buildStoreConfig(statePath)
        ConfigCommand.setSetting(key, value)
          .provide(CliDI.standaloneLayers(storeConfig))
          .flatMap(Console.printLine(_))
          .catchAll(err => Console.printLineError(s"Error: $err"))

      case CliCommand.BoardList(_, workspacePath) =>
        BoardCommand.listBoard(workspacePath)
          .provide(boardLayer)
          .flatMap(Console.printLine(_))
          .catchAll(err => Console.printLineError(s"Board error: $err"))

      case CliCommand.BoardShow(_, workspacePath, issueId) =>
        BoardCommand.showIssue(workspacePath, issueId)
          .provide(boardLayer)
          .flatMap(Console.printLine(_))
          .catchAll(err => Console.printLineError(s"Board error: $err"))

      case CliCommand.WorkspaceList(statePath) =>
        val storeConfig = buildStoreConfig(statePath)
        WorkspaceCommand.listWorkspaces
          .provide(CliDI.standaloneLayers(storeConfig))
          .flatMap(Console.printLine(_))
          .catchAll(err => Console.printLineError(s"Error: $err"))

      case CliCommand.ProjectList(statePath) =>
        val storeConfig = buildStoreConfig(statePath)
        ProjectCommand.listProjects
          .provide(CliDI.standaloneLayers(storeConfig))
          .flatMap(Console.printLine(_))
          .catchAll(err => Console.printLineError(s"Error: $err"))

      case CliCommand.RemoteStatus(gatewayUrl) =>
        RemoteCommand.status(gatewayUrl.getOrElse(defaultGatewayUrl))
          .provide(Client.default)
          .flatMap(Console.printLine(_))
          .catchAll(err => Console.printLineError(s"Remote error: $err"))

      case CliCommand.RemoteChat(gatewayUrl, prompt) =>
        RemoteCommand.chat(gatewayUrl.getOrElse(defaultGatewayUrl), prompt)
          .provide(Client.default)
          .flatMap(Console.printLine(_))
          .catchAll(err => Console.printLineError(s"Remote error: $err"))

      case CliCommand.WorkspaceInit(statePath, path, name, description) =>
        val storeConfig = buildStoreConfig(statePath)
        ScaffoldCommand.initWorkspace(path, name, description)
          .provide(FileService.live, boardLayer, CliDI.standaloneLayers(storeConfig))
          .flatMap(Console.printLine(_))
          .catchAll(err => Console.printLineError(s"Error: $err"))

      case CliCommand.AgentInit(statePath, name, workspace, model, cliTool, description) =>
        val storeConfig = buildStoreConfig(statePath)
        ScaffoldCommand
          .initAgent(workspace, name, model, cliTool.getOrElse("llm4zio"), description)
          .provide(FileService.live, CliDI.standaloneLayers(storeConfig))
          .flatMap(Console.printLine(_))
          .catchAll(err => Console.printLineError(s"Error: $err"))

      case CliCommand.AgentRun(statePath, workspace, name, prompt) =>
        val storeConfig = buildStoreConfig(statePath)
        AgentRunCommand
          .run(workspace, name, prompt)
          .provide(CliDI.standaloneLayers(storeConfig))
          .flatMap { exit =>
            if exit == 0 then ZIO.unit
            else Console.printLineError(s"Agent exited with code $exit")
          }
          .catchAll(err => Console.printLineError(s"Error: $err"))

      case CliCommand.EvalRun(statePath, workspace, agent, dataset) =>
        val storeConfig = buildStoreConfig(statePath)
        EvalCommand
          .runDataset(workspace, agent, dataset)
          .provide(CliDI.standaloneLayers(storeConfig))
          .unit
          .catchAll(err => Console.printLineError(s"Eval failed: $err"))

      case CliCommand.EvalCompare(baseline, candidate) =>
        EvalCommand
          .compare(baseline, candidate)
          .unit
          .catchAll(err => Console.printLineError(s"Compare failed: $err"))

      case CliCommand.Deploy(workspace, target, repoRoot, imageName, imageTag, dryRun) =>
        DeployCommand
          .deploy(workspace, target, repoRoot, imageName, imageTag, dryRun)
          .unit
          .catchAll(err => Console.printLineError(s"Deploy failed: $err"))

      case CliCommand.Setup(statePath, gatewayUrl) =>
        val storeConfig = buildStoreConfig(statePath)
        SetupCommand
          .setup(statePath, defaultStateRoot, gatewayUrl.getOrElse(defaultGatewayUrl))
          .provide(CliDI.standaloneLayers(storeConfig))
          .flatMap(Console.printLine(_))
          .catchAll(err => Console.printLineError(s"Setup failed: $err"))

      case CliCommand.Login(statePath, provider, apiKey, agent) =>
        val storeConfig = buildStoreConfig(statePath)
        LoginCommand
          .login(provider, apiKey, agent)
          .provide(CliDI.standaloneLayers(storeConfig))
          .flatMap(Console.printLine(_))
          .catchAll(err => Console.printLineError(s"Login failed: $err"))

      case CliCommand.Ingest(workspace, source, scope, tags, extensions) =>
        val scopeVal = scope.map(_.trim).filter(_.nonEmpty).getOrElse("knowledge")
        val tagList  = tags.toList.flatMap(_.split(",").toList.map(_.trim).filter(_.nonEmpty))
        val exts     =
          extensions.map(_.split(",").toList.map(_.trim.stripPrefix(".").toLowerCase).filter(_.nonEmpty).toSet)
            .getOrElse(IngestCommand.defaultExtensions)
        IngestCommand
          .ingest(workspace, source, scopeVal, tagList, exts)
          .map(IngestCommand.render)
          .provide(FileService.live)
          .flatMap(Console.printLine(_))
          .catchAll(err => Console.printLineError(s"Ingest failed: $err"))

      case CliCommand.Info(statePath, gatewayUrl) =>
        val storeConfig = buildStoreConfig(statePath)
        val stateRoot   = statePath.map(Paths.get(_)).getOrElse(defaultStateRoot).toAbsolutePath.normalize
        InfoCommand
          .info(stateRoot, gatewayUrl.getOrElse(defaultGatewayUrl), cliVersion)
          .map(InfoCommand.render)
          .provide(CliDI.standaloneLayers(storeConfig), Client.default)
          .flatMap(Console.printLine(_))
          .catchAll(err => Console.printLineError(s"Info failed: $err"))

  // ── CLI App ──────────────────────────────────────────────────────────

  private val cliApp: CliApp[Any, Any, Unit] = CliApp.make(
    name = "llm4zio-cli",
    version = cliVersion,
    summary = text("llm4zio command-line interface"),
    command = rootCmd,
  )(handle)

  override def run: ZIO[ZIOAppArgs & Scope, Any, Unit] =
    for
      args <- ZIOAppArgs.getArgs
      _    <- cliApp.run(args.toList).orDie
    yield ()
