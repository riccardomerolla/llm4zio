package cli.commands

import java.nio.file.Paths

import zio.*

import agent.entity.AgentRepository
import workspace.control.CliAgentRunner
import workspace.entity.RunMode

/** Runs an agent locally as a one-shot subprocess: looks the agent up by name in the local event store, builds the
  * CLI-tool argv for its `cliTool` / `runMode`, executes it in the workspace directory, and streams output to stdout.
  *
  * Exit code of the spawned process is returned so the caller can propagate it.
  */
object AgentRunCommand:

  def run(
    workspaceRaw: String,
    agentName: String,
    prompt: String,
  ): ZIO[AgentRepository, String, Int] =
    val workspace = Paths.get(workspaceRaw).toAbsolutePath.normalize.toString
    val lookup    = slugify(agentName)

    for
      maybeAgent <- AgentRepository
                      .findByName(lookup)
                      .mapError(e => s"AgentRepository.findByName failed: $e")
      agent      <- ZIO
                      .fromOption(maybeAgent)
                      .orElseFail(s"No agent '$lookup' registered. Run: llm4zio-cli agent init ...")
      argv        = CliAgentRunner.buildArgv(
                      cliTool = agent.cliTool,
                      prompt = prompt,
                      worktreePath = workspace,
                      runMode = RunMode.Host,
                      repoPath = workspace,
                      envVars = agent.envVars,
                      dockerMemoryLimit = agent.dockerMemoryLimit,
                      dockerCpuLimit = agent.dockerCpuLimit,
                      permissions = Some(agent.permissions),
                    )
      _          <- Console
                      .printLine(s"▶ ${argv.mkString(" ")}")
                      .mapError(e => s"print failed: ${e.getMessage}")
      exit       <- CliAgentRunner
                      .runProcessStreaming(
                        argv = argv,
                        cwd = workspace,
                        onLine = line => Console.printLine(line).orDie,
                        envVars = agent.envVars,
                      )
                      .mapError(e => s"agent subprocess failed: ${e.getMessage}")
    yield exit

  private def slugify(name: String): String =
    name.trim.toLowerCase
      .replaceAll("[^a-z0-9-]+", "-")
      .replaceAll("^-+|-+$", "")
      .replaceAll("-{2,}", "-")
