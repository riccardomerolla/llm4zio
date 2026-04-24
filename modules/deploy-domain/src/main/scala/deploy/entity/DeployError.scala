package deploy.entity

sealed trait DeployError:
  def message: String

object DeployError:
  final case class IOFailure(message: String)             extends DeployError
  final case class ToolMissing(tool: String)              extends DeployError:
    def message: String = s"required tool '$tool' is not on PATH"
  final case class BuildFailed(tool: String, exitCode: Int, log: String) extends DeployError:
    def message: String = s"$tool exited with code $exitCode\n$log"
  final case class Unsupported(message: String)           extends DeployError
