package deploy.control

import zio.*

import deploy.entity.*

/** Single entry point for CLI + tests — picks the right [[DeployStrategy]] for the spec's target and runs it. */
object DeployDispatcher:

  def deploy(spec: DeploySpec): IO[DeployError, DeployResult] =
    strategyFor(spec.target) match
      case Right(strategy) => strategy.deploy(spec)
      case Left(err)       => ZIO.fail(err)

  private def strategyFor(target: DeployTarget): Either[DeployError, DeployStrategy] =
    target match
      case DeployTarget.Docker       => Right(DockerStrategy)
      case DeployTarget.JvmFatJar    => Right(JvmFatJarStrategy)
      case DeployTarget.Kubernetes   => Right(KubernetesStrategy)
      case DeployTarget.CloudRun     => Right(CloudRunStrategy)
      case DeployTarget.AgentRuntime =>
        Left(DeployError.Unsupported("agent-runtime target is not implemented yet"))
