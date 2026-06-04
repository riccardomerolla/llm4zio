package llm4zio.core

import zio.*
import zio.stream.ZStream

/** A single event from a live agent session (mirrors the CliStreamJson metadata contract). Distinct from the
  * [[ConversationThread]] / [[ConversationStore]] types, which model replayed message history.
  */
enum SessionEvent:
  case TextDelta(text: String)
  case ToolUse(tool: String, rawInput: String, id: String)
  case ToolResult(id: String, status: String, content: String)
  case AskUser(question: String)
  case ApprovalRequest(tool: String, rawInput: String, id: String)
  case Usage(usage: TokenUsage, model: Option[String])
  case Done(result: String)

/** The terminal outcome of an agent session. */
final case class SessionResult(text: String, usage: Option[TokenUsage], model: Option[String])

/** A long-lived, steerable agent session over a held subprocess. Built ALONGSIDE the one-shot `LlmService` surface —
  * autonomous flows keep using `executeStream`; interactive flows drive an `AgentSession`. Feed follow-up user turns
  * over [[sendUserMessage]], answer [[SessionEvent.AskUser]] via [[respondToAsk]], and gate tool calls via
  * [[respondToApproval]].
  */
trait AgentSession:
  def events: ZStream[Any, LlmError, SessionEvent]
  def sendUserMessage(text: String): IO[LlmError, Unit]
  def respondToAsk(answer: String): IO[LlmError, Unit]
  def respondToApproval(id: String, approved: Boolean): IO[LlmError, Unit]
  def awaitResult: IO[LlmError, SessionResult]
  def cancel: UIO[Unit]
