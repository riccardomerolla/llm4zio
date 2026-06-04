package llm4zio.runner

import java.nio.file.Path

import zio.{ Scope, ZIO }

import llm4zio.core.{ AgentSession, CliProcessExecutor }
import llm4zio.flow.{ ApprovalPolicy, CoderSystem, FlowError, Interaction, McpServer }
import llm4zio.providers.ClaudeAgentSession

/** Wires the interactive runtime for a live `claude` coder: starts the in-process MCP server (exposing `ask_user` +
  * `approve`), writes its `--mcp-config`, and hands back a factory that opens a steerable [[AgentSession]] per task —
  * each a held `claude` process that reaches back through HTTP for human input and tool-call approval.
  *
  * Pair the factory with [[llm4zio.flow.implementTaskLoopLive]]: the MCP server lives for the enclosing scope (one per
  * run), while each task gets its own scoped session.
  */
object InteractiveCoder:

  val serverName: String = "llm4zio"

  /** The claude flags that point a session at this run's MCP server: load the config, route tool approval to the
    * `approve` tool, and allow both MCP tools.
    */
  def sessionFlags(server: String, configPath: Path): List[String] =
    List(
      "--mcp-config",
      configPath.toString,
      "--permission-prompt-tool",
      McpHttpServer.permissionPromptToolName(server),
      "--allowedTools",
      s"${McpHttpServer.allowedToolName(server, "ask_user")},${McpHttpServer.permissionPromptToolName(server)}",
    )

  /** Start the MCP server (scoped) and return a per-task session factory for [[llm4zio.flow.implementTaskLoopLive]]. */
  def openSessions(
    cwd: Path,
    interaction: Interaction,
    policy: ApprovalPolicy,
    model: Option[String] = None,
    executor: CliProcessExecutor = LiveCliProcessExecutor.instance,
  ): ZIO[Scope, FlowError, Any => ZIO[Scope, FlowError, AgentSession]] =
    val tools = List(McpServer.askUserTool(interaction), McpServer.approvalTool(policy))
    for
      port   <- McpHttpServer.serve(tools).mapError(processError("start MCP server"))
      config <- McpHttpServer.writeMcpConfig(serverName, port).mapError(processError("write mcp-config"))
      // The live coder, like the Chat-based one, must leave git to the runtime (CoderSystem.gitOwnership).
      flags   = sessionFlags(serverName, config) ++ List("--append-system-prompt", CoderSystem.gitOwnership)
      argv    = ClaudeAgentSession.sessionArgv(model, flags)
    yield _ =>
      ClaudeAgentSession.open(executor, argv, cwd.toString).mapError(e => FlowError.Llm(e.toString))

  private def processError(step: String)(t: Throwable): FlowError =
    FlowError.Process(step, Option(t.getMessage).getOrElse(t.toString))
