package llm4zio.core

import zio.*
import zio.json.*
import zio.stream.ZStream

import llm4zio.tools.{ AnyTool, JsonSchema }

/** A `Connector` is the unified abstraction for talking to an LLM. Both
  * API-backed and CLI-backed connectors implement the full `LlmService`
  * surface — there are no "text-only" connectors in this codebase. Having
  * `Connector` extend `LlmService` lets callers depend on the capability
  * statically instead of downcasting at runtime.
  */
trait Connector extends LlmService:
  def id: ConnectorId
  def kind: ConnectorKind
  def healthCheck: IO[LlmError, HealthStatus]
  override def isAvailable: UIO[Boolean]

trait ApiConnector extends Connector:
  final def kind: ConnectorKind = ConnectorKind.Api

/** Every CLI connector is also an `LlmService`. The CLI-specific primitives
  * (`complete`, `completeStream`) are required; the higher-level `LlmService`
  * methods get default implementations derived from those primitives so
  * concrete connectors only override what their CLI can actually do better
  * (e.g. native streaming, tool calling). Structured output is derived from
  * `complete` via the shared `StructuredOutputs` JSON extractor — that's how
  * `executeStructured` works uniformly across every CLI provider.
  */
trait CliConnector extends Connector:
  final def kind: ConnectorKind = ConnectorKind.Cli
  def interactionSupport: InteractionSupport
  def buildArgv(prompt: String, ctx: CliContext): List[String]
  def buildInteractiveArgv(ctx: CliContext): List[String]
  def complete(prompt: String): IO[LlmError, String]
  def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk]

  // ── LlmService defaults — overridable by connectors with richer support ──
  override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
    completeStream(prompt)

  override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
    completeStream(CliConnector.flattenHistory(messages))

  override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
    ZIO.fail(
      LlmError.InvalidRequestError(s"CLI connector ${id.value} does not support tool calling")
    )

  override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
    complete(prompt).flatMap(text => StructuredOutputs.parseFromText[A](text, schema))

object CliConnector:
  /** Default history flattening for CLI connectors without native conversation
    * support: render each turn as a labelled block separated by blank lines.
    * Mirrors the format GeminiCliProvider was already using.
    */
  def flattenHistory(messages: List[Message]): String =
    val systemBlock = messages.collect {
      case msg if msg.role == MessageRole.System && msg.content.nonEmpty => msg.content
    }.mkString("\n\n") match
      case ""    => ""
      case block => block + "\n\n"
    val turns       = messages.collect {
      case msg if msg.role != MessageRole.System =>
        val label = msg.role match
          case MessageRole.User      => "**User:**"
          case MessageRole.Assistant => "**Assistant:**"
          case MessageRole.Tool      => "**Tool:**"
          case MessageRole.System    => "**System:**"
        s"$label ${msg.content}"
    }
    systemBlock + turns.mkString("\n\n")

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
