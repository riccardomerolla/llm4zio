package workspace.control

import zio.*

import agent.entity.{ AgentPermissions, TrustLevel }
import workspace.entity.{ RunMode, WorkspaceError }

trait ExecutionRuntime:
  def name: String
  def preflight(context: ExecutionRuntime.Context): IO[WorkspaceError, Unit]                           = ZIO.unit
  def provision(context: ExecutionRuntime.Context): IO[WorkspaceError, ExecutionRuntime.Provisioned]
  def execute(
    context: ExecutionRuntime.Context,
    provisioned: ExecutionRuntime.Provisioned,
    onLine: String => Task[Unit],
  ): IO[WorkspaceError, ExecutionRuntime.ExecutionResult]
  def collectArtifacts(
    context: ExecutionRuntime.Context,
    provisioned: ExecutionRuntime.Provisioned,
  ): IO[WorkspaceError, List[ExecutionRuntime.Artifact]] = ZIO.succeed(Nil)
  def cleanup(context: ExecutionRuntime.Context, provisioned: ExecutionRuntime.Provisioned): UIO[Unit] = ZIO.unit

object ExecutionRuntime:
  final case class Resources(
    dockerMemoryLimit: Option[String] = None,
    dockerCpuLimit: Option[String] = None,
  )

  final case class Context(
    runId: String,
    cliTool: String,
    prompt: String,
    worktreePath: String,
    repoPath: String,
    runCommand: (List[String], String, String => Task[Unit], Map[String, String]) => Task[Int],
    envVars: Map[String, String],
    permissions: AgentPermissions,
    resources: Resources = Resources(),
  )

  final case class Provisioned(
    workingDirectory: String,
    envVars: Map[String, String] = Map.empty,
    metadata: Map[String, String] = Map.empty,
  )

  final case class Artifact(name: String, location: String)

  final case class ExecutionResult(
    exitCode: Int,
    estimatedCostUsd: Double = 0.0,
    metadata: Map[String, String] = Map.empty,
  )

  final case class Resolution(
    mode: RunMode,
    runtime: ExecutionRuntime,
    selectionSource: String,
  )

  def resolve(
    runMode: RunMode,
    permissions: AgentPermissions,
    trustLevel: Option[TrustLevel],
  ): Resolution =
    val enforcedMode = CliAgentRunner.enforceRunMode(runMode, Some(permissions))
    val source       = trustLevel match
      case Some(_) => "workspace-default-with-agent-trust-hooks"
      case None    => "workspace-default"
    val runtime      = enforcedMode match
      case RunMode.Host           => LocalRuntime
      case docker: RunMode.Docker => DockerRuntime(docker)
      case cloud: RunMode.Cloud   => CloudRuntime(cloud)
    Resolution(enforcedMode, runtime, source)

object LocalRuntime extends ExecutionRuntime:
  override val name: String = "local"

  override def provision(context: ExecutionRuntime.Context): IO[WorkspaceError, ExecutionRuntime.Provisioned] =
    ZIO.succeed(ExecutionRuntime.Provisioned(workingDirectory = context.worktreePath, envVars = context.envVars))

  override def execute(
    context: ExecutionRuntime.Context,
    provisioned: ExecutionRuntime.Provisioned,
    onLine: String => Task[Unit],
  ): IO[WorkspaceError, ExecutionRuntime.ExecutionResult] =
    context.runCommand(
      CliAgentRunner.buildArgv(
        cliTool = context.cliTool,
        prompt = context.prompt,
        worktreePath = context.worktreePath,
        runMode = RunMode.Host,
        repoPath = context.repoPath,
        envVars = provisioned.envVars,
        permissions = Some(context.permissions),
      ),
      provisioned.workingDirectory,
      onLine,
      provisioned.envVars,
    )
      .mapBoth(
        {
          case WorkspaceRunServiceLive.WorkspaceExecutionFailure(error) => error
          case err                                                      => WorkspaceError.WorktreeError(err.getMessage)
        },
        exitCode => ExecutionRuntime.ExecutionResult(exitCode = exitCode),
      )

final case class DockerRuntime(config: RunMode.Docker) extends ExecutionRuntime:
  override val name: String = "docker"

  override def preflight(context: ExecutionRuntime.Context): IO[WorkspaceError, Unit] =
    DockerSupport.requireDocker

  override def provision(context: ExecutionRuntime.Context): IO[WorkspaceError, ExecutionRuntime.Provisioned] =
    preflight(context).as(ExecutionRuntime.Provisioned(
      workingDirectory = context.worktreePath,
      envVars = context.envVars,
    ))

  override def execute(
    context: ExecutionRuntime.Context,
    provisioned: ExecutionRuntime.Provisioned,
    onLine: String => Task[Unit],
  ): IO[WorkspaceError, ExecutionRuntime.ExecutionResult] =
    context.runCommand(
      CliAgentRunner.buildArgv(
        cliTool = context.cliTool,
        prompt = context.prompt,
        worktreePath = context.worktreePath,
        runMode = config,
        repoPath = context.repoPath,
        envVars = provisioned.envVars,
        dockerMemoryLimit = context.resources.dockerMemoryLimit,
        dockerCpuLimit = context.resources.dockerCpuLimit,
        permissions = Some(context.permissions),
      ),
      provisioned.workingDirectory,
      onLine,
      provisioned.envVars,
    )
      .mapBoth(
        {
          case WorkspaceRunServiceLive.WorkspaceExecutionFailure(error) => error
          case err                                                      => WorkspaceError.WorktreeError(err.getMessage)
        },
        exitCode => ExecutionRuntime.ExecutionResult(exitCode = exitCode),
      )

final case class CloudRuntime(config: RunMode.Cloud) extends ExecutionRuntime:
  override val name: String = "cloud"

  override def preflight(context: ExecutionRuntime.Context): IO[WorkspaceError, Unit] =
    if config.provider.trim.isEmpty then ZIO.fail(WorkspaceError.WorktreeError("Cloud runtime provider is required"))
    else if config.image.trim.isEmpty then ZIO.fail(WorkspaceError.WorktreeError("Cloud runtime image is required"))
    else ZIO.unit

  override def provision(context: ExecutionRuntime.Context): IO[WorkspaceError, ExecutionRuntime.Provisioned] =
    preflight(context).as(
      ExecutionRuntime.Provisioned(
        workingDirectory = context.worktreePath,
        envVars = context.envVars,
        metadata = Map(
          "provider" -> config.provider,
          "image"    -> config.image,
        ) ++ config.region.map("region" -> _),
      )
    )

  override def execute(
    context: ExecutionRuntime.Context,
    provisioned: ExecutionRuntime.Provisioned,
    onLine: String => Task[Unit],
  ): IO[WorkspaceError, ExecutionRuntime.ExecutionResult] =
    ZIO.fail(
      WorkspaceError.WorktreeError(
        s"Cloud runtime provider '${config.provider}' is not implemented yet; configuration is wired and ready for a provider backend"
      )
    )
