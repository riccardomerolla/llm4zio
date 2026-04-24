package deploy.entity

import zio.json.*

/** Which runtime to package/ship an llm4zio workspace to. */
enum DeployTarget derives JsonCodec:
  case JvmFatJar
  case Docker
  case CloudRun
  case Kubernetes
  case AgentRuntime

object DeployTarget:
  def parse(s: String): Either[String, DeployTarget] =
    s.trim.toLowerCase match
      case "jvm-fatjar" | "fatjar" | "jar" => Right(JvmFatJar)
      case "docker"                        => Right(Docker)
      case "cloud-run" | "cloudrun"        => Right(CloudRun)
      case "k8s" | "kubernetes"            => Right(Kubernetes)
      case "agent-runtime"                 => Right(AgentRuntime)
      case other                           => Left(s"unknown deploy target '$other'")

  val supportedValues: String =
    "jvm-fatjar | docker | cloud-run | kubernetes | agent-runtime"
