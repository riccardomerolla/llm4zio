package cli

import java.nio.file.{ Path, Paths }

import zio.*
import zio.cli.*
import zio.cli.HelpDoc.Span.text
import zio.http.Client
import zio.logging.backend.SLF4J

import board.control.{ BoardRepositoryFS, IssueMarkdownParser }
import board.entity.BoardRepository
import _root_.cli.commands.{ BoardCommand, ConfigCommand, ProjectCommand, RemoteCommand, WorkspaceCommand }
import shared.store.StoreConfig
import workspace.control.GitService

object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  // ── Default paths ────────────────────────────────────────────────────

  private val defaultStateRoot: Path =
    Paths.get(sys.props.getOrElse("user.home", ".")).resolve(".llm4zio").resolve("data")

  private val defaultGatewayUrl: String = "http://localhost:8080"

  // ── Internal routing ADT ──────────────────────────────────────────────

  enum CliCommand:
    case ConfigList(statePath: Option[String])
    case ConfigSet(statePath: Option[String], key: String, value: String)
    case BoardList(statePath: Option[String], workspacePath: String)
    case BoardShow(statePath: Option[String], workspacePath: String, issueId: String)
    case WorkspaceList(statePath: Option[String])
    case ProjectList(statePath: Option[String])
    case RemoteStatus(gatewayUrl: Option[String])
    case RemoteChat(gatewayUrl: Option[String], prompt: String)

  // ── Shared options ───────────────────────────────────────────────────

  private val stateOpt: Options[Option[String]] =
    Options.text("state").optional ??
      s"Store root path (default: ${defaultStateRoot.toAbsolutePath})"

  private val gatewayUrlOpt: Options[Option[String]] =
    Options.text("gateway-url").optional ??
      s"Gateway base URL (default: $defaultGatewayUrl)"

  // ── config subcommands ───────────────────────────────────────────────

  private val configListCmd: Command[CliCommand] =
    Command("config-list", stateOpt)
      .withHelp("List all configuration settings")
      .map(CliCommand.ConfigList.apply)

  private val configSetCmd: Command[CliCommand] =
    Command("config-set", stateOpt, Args.text("key") ++ Args.text("value"))
      .withHelp("Set a configuration key to a value")
      .map { case (statePath, (key, value)) => CliCommand.ConfigSet(statePath, key, value) }

  // ── board subcommands ────────────────────────────────────────────────

  private val boardListCmd: Command[CliCommand] =
    Command("board-list", stateOpt, Args.text("workspace-path"))
      .withHelp("List all issues on the board for a workspace")
      .map { case (statePath, workspacePath) => CliCommand.BoardList(statePath, workspacePath) }

  private val boardShowCmd: Command[CliCommand] =
    Command("board-show", stateOpt, Args.text("workspace-path") ++ Args.text("issue-id"))
      .withHelp("Show details of a specific board issue")
      .map { case (statePath, (workspacePath, issueId)) => CliCommand.BoardShow(statePath, workspacePath, issueId) }

  // ── workspace subcommands ────────────────────────────────────────────

  private val workspaceListCmd: Command[CliCommand] =
    Command("workspace-list", stateOpt)
      .withHelp("List all workspaces")
      .map(CliCommand.WorkspaceList.apply)

  // ── project subcommands ──────────────────────────────────────────────

  private val projectListCmd: Command[CliCommand] =
    Command("project-list", stateOpt)
      .withHelp("List all projects")
      .map(CliCommand.ProjectList.apply)

  // ── remote subcommands ───────────────────────────────────────────────

  private val remoteStatusCmd: Command[CliCommand] =
    Command("remote-status", gatewayUrlOpt)
      .withHelp("Check gateway health status")
      .map(CliCommand.RemoteStatus.apply)

  private val remoteChatCmd: Command[CliCommand] =
    Command("remote-chat", gatewayUrlOpt, Args.text("prompt"))
      .withHelp("Send a chat prompt to the gateway")
      .map { case (gatewayUrl, prompt) => CliCommand.RemoteChat(gatewayUrl, prompt) }

  // ── Top-level command ────────────────────────────────────────────────

  private val rootCmd: Command[CliCommand] =
    Command("llm4zio-cli")
      .subcommands(
        configListCmd,
        configSetCmd,
        boardListCmd,
        boardShowCmd,
        workspaceListCmd,
        projectListCmd,
        remoteStatusCmd,
        remoteChatCmd,
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

  // ── CLI App ──────────────────────────────────────────────────────────

  private val cliApp: CliApp[Any, Any, Unit] = CliApp.make(
    name = "llm4zio-cli",
    version = "1.0.0",
    summary = text("llm4zio command-line interface"),
    command = rootCmd,
  )(handle)

  override def run: ZIO[ZIOAppArgs & Scope, Any, Unit] =
    for
      args <- ZIOAppArgs.getArgs
      _    <- cliApp.run(args.toList).orDie
    yield ()
