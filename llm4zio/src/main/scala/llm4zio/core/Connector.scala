package llm4zio.core

import zio.*
import zio.json.*
import zio.stream.ZStream

trait Connector:
  def id: ConnectorId
  def kind: ConnectorKind
  def healthCheck: IO[LlmError, HealthStatus]
  def isAvailable: UIO[Boolean]

trait ApiConnector extends Connector, LlmService:
  final def kind: ConnectorKind = ConnectorKind.Api

trait CliConnector extends Connector:
  final def kind: ConnectorKind = ConnectorKind.Cli
  def interactionSupport: InteractionSupport
  def buildArgv(prompt: String, ctx: CliContext): List[String]
  def buildInteractiveArgv(ctx: CliContext): List[String]
  def complete(prompt: String): IO[LlmError, String]
  def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk]

enum Availability derives JsonCodec:
  case Healthy, Degraded, Unhealthy, Unknown

enum AuthStatus derives JsonCodec:
  case Valid, Missing, Invalid, Unknown

final case class HealthStatus(
  availability: Availability,
  authStatus: AuthStatus,
  latency: Option[Duration],
) derives JsonCodec

enum InteractionSupport derives JsonCodec:
  case InteractiveStdin, ContinuationOnly

enum CliSandbox derives JsonCodec:
  case Docker(image: String, mount: Boolean = true, network: Option[String] = None)
  case Podman, SeatbeltMacOS, Runsc, Lxc

final case class CliContext(
  worktreePath: String,
  repoPath: String,
  envVars: Map[String, String] = Map.empty,
  sandbox: Option[CliSandbox] = None,
  turnLimit: Option[Int] = None,
)
