package workspace.control

import java.time.Duration

import zio.*
import zio.test.*

import agent.entity.*
import workspace.entity.{ RunMode, WorkspaceError }

object ExecutionRuntimeSpec extends ZIOSpecDefault:

  private val timeout = Duration.ofSeconds(300)

  private def permissions(trustLevel: TrustLevel): AgentPermissions =
    AgentPermissions.defaults(trustLevel, "claude", timeout, maxEstimatedTokens = None)

  private def minimalContext(
    perms: AgentPermissions = permissions(TrustLevel.Standard)
  ): ExecutionRuntime.Context =
    ExecutionRuntime.Context(
      runId = "run-1",
      cliTool = "claude",
      prompt = "do something",
      worktreePath = "/tmp/wt",
      repoPath = "/tmp/repo",
      runCommand = (_, _, _, _) => ZIO.succeed(0),
      envVars = Map.empty,
      permissions = perms,
    )

  def spec: Spec[Any, Any] =
    suite("ExecutionRuntimeSpec")(
      suite("ExecutionRuntime.resolve")(
        test("resolves RunMode.Host to LocalRuntime") {
          val resolution = ExecutionRuntime.resolve(
            RunMode.Host,
            permissions(TrustLevel.Standard),
            trustLevel = None,
          )
          assertTrue(
            resolution.runtime == LocalRuntime,
            resolution.mode == RunMode.Host,
          )
        },
        test("resolves RunMode.Docker to DockerRuntime") {
          val docker     = RunMode.Docker(image = "my-image:latest")
          val resolution = ExecutionRuntime.resolve(
            docker,
            permissions(TrustLevel.Standard),
            trustLevel = None,
          )
          assertTrue(
            resolution.runtime.isInstanceOf[DockerRuntime],
            resolution.mode == docker,
          )
        },
        test("resolves RunMode.Cloud to CloudRuntime") {
          val cloud      = RunMode.Cloud(provider = "aws", image = "my-image:latest")
          val resolution = ExecutionRuntime.resolve(
            cloud,
            permissions(TrustLevel.Standard),
            trustLevel = None,
          )
          assertTrue(
            resolution.runtime.isInstanceOf[CloudRuntime],
            resolution.mode == cloud,
          )
        },
        test("enforces network=none on Docker when permissions have NetworkAccessScope.Disabled") {
          val docker         = RunMode.Docker(image = "my-image:latest")
          val untrustedPerms = permissions(TrustLevel.Untrusted)
          val resolution     = ExecutionRuntime.resolve(
            docker,
            untrustedPerms,
            trustLevel = None,
          )
          assertTrue(
            resolution.mode == RunMode.Docker(image = "my-image:latest", network = Some("none"))
          )
        },
        test("enforces network=none on Cloud when permissions have NetworkAccessScope.Disabled") {
          val cloud          = RunMode.Cloud(provider = "aws", image = "img")
          val untrustedPerms = permissions(TrustLevel.Untrusted)
          val resolution     = ExecutionRuntime.resolve(
            cloud,
            untrustedPerms,
            trustLevel = None,
          )
          assertTrue(
            resolution.mode == RunMode.Cloud(provider = "aws", image = "img", network = Some("none"))
          )
        },
        test("selectionSource reflects presence of trust level") {
          val withTrust    =
            ExecutionRuntime.resolve(RunMode.Host, permissions(TrustLevel.Standard), Some(TrustLevel.Standard))
          val withoutTrust = ExecutionRuntime.resolve(RunMode.Host, permissions(TrustLevel.Standard), None)
          assertTrue(
            withTrust.selectionSource.contains("agent-trust-hooks"),
            !withoutTrust.selectionSource.contains("agent-trust-hooks"),
          )
        },
      ),
      suite("LocalRuntime")(
        test("preflight always succeeds") {
          for
            result <- LocalRuntime.preflight(minimalContext()).exit
          yield assertTrue(result.isSuccess)
        },
        test("provision returns context worktree path as working directory") {
          for
            provisioned <- LocalRuntime.provision(minimalContext())
          yield assertTrue(provisioned.workingDirectory == "/tmp/wt")
        },
      ),
      suite("CloudRuntime")(
        test("preflight fails with WorkspaceError when provider is empty") {
          val config  = RunMode.Cloud(provider = "  ", image = "my-image")
          val runtime = CloudRuntime(config)
          for
            result <- runtime.preflight(minimalContext()).exit
          yield assertTrue(result.isFailure)
        },
        test("preflight fails with WorkspaceError when image is empty") {
          val config  = RunMode.Cloud(provider = "aws", image = "  ")
          val runtime = CloudRuntime(config)
          for
            result <- runtime.preflight(minimalContext()).exit
          yield assertTrue(result.isFailure)
        },
        test("preflight succeeds when provider and image are provided") {
          val config  = RunMode.Cloud(provider = "aws", image = "my-image:latest")
          val runtime = CloudRuntime(config)
          for
            result <- runtime.preflight(minimalContext()).exit
          yield assertTrue(result.isSuccess)
        },
        test("provision includes provider and image in metadata") {
          val config  = RunMode.Cloud(provider = "aws", image = "my-image:latest", region = Some("us-east-1"))
          val runtime = CloudRuntime(config)
          for
            provisioned <- runtime.provision(minimalContext())
          yield assertTrue(
            provisioned.metadata.get("provider").contains("aws"),
            provisioned.metadata.get("image").contains("my-image:latest"),
            provisioned.metadata.get("region").contains("us-east-1"),
          )
        },
        test("execute always fails with not-implemented error") {
          val config  = RunMode.Cloud(provider = "aws", image = "my-image:latest")
          val runtime = CloudRuntime(config)
          for
            provisioned <- runtime.provision(minimalContext())
            result      <- runtime.execute(minimalContext(), provisioned, _ => ZIO.unit).exit
          yield assertTrue(result.isFailure)
        },
      ),
      suite("AgentPermissions trust levels")(
        test("Untrusted has network Disabled and write scope limited to Worktree") {
          val perms = permissions(TrustLevel.Untrusted)
          assertTrue(
            perms.network == NetworkAccessScope.Disabled,
            perms.fileSystem.writeScopes == List(AgentPathScope.Worktree),
            !perms.git.push,
          )
        },
        test("Elevated has Unrestricted network and CrossWorkspace write scope") {
          val perms = permissions(TrustLevel.Elevated)
          assertTrue(
            perms.network == NetworkAccessScope.Unrestricted,
            perms.fileSystem.writeScopes.contains(AgentPathScope.CrossWorkspace),
            perms.git.push,
          )
        },
      ),
    )
