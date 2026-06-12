package llm4zio.flow

import zio.*

import llm4zio.core.{ LlmService, Message, MessageRole, Streaming }

/** A stateful conversation over an [[LlmService]].
  *
  * llm4zio-core continues a conversation by replaying message history (there is no backend session token), so
  * continuity is just an accumulating `List[Message]` threaded through `executeStreamWithHistory`. One `Chat` gives an
  * agent memory across the tasks of a flow.
  */
final class Chat private (service: LlmService, history: Ref[List[Message]]):

  /** Send `prompt`, append both the user turn and the assistant reply to the running history, and return the
    * assistant's text. Fails with [[FlowError.Llm]] carrying the typed [[llm4zio.core.LlmError]] cause — Chat is
    * flow-layer API, so it speaks the flow-layer error.
    */
  def ask(prompt: String): IO[FlowError, String] =
    (for
      msgs  <- history.updateAndGet(_ :+ Message(MessageRole.User, prompt))
      reply <- Streaming.collect(service.executeStreamWithHistory(msgs))
      _     <- history.update(_ :+ Message(MessageRole.Assistant, reply.content))
    yield reply.content).mapError(e => FlowError.Llm(e.message, Some(e)))

  /** The full conversation so far. */
  def messages: UIO[List[Message]] = history.get

object Chat:
  /** Start a fresh chat, optionally seeded with a system prompt. By default the runtime-owns-git instruction
    * ([[CoderSystem.gitOwnership]]) is prepended to the system prompt so coders don't manage git themselves; pass
    * `manageGit = true` for the rare flow that wants agent-driven git.
    */
  def start(service: LlmService, system: Option[String] = None, manageGit: Boolean = false): UIO[Chat] =
    val sys =
      if manageGit then system
      else Some(List(Some(CoderSystem.gitOwnership), system).flatten.mkString("\n\n"))
    Ref.make(sys.map(Message(MessageRole.System, _)).toList).map(new Chat(service, _))
