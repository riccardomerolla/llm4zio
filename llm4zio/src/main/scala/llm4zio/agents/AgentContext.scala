package llm4zio.agents

import zio.*
import zio.json.*
import zio.json.ast.Json
import llm4zio.core.{ Message, MessageRole }
import llm4zio.tools.Tool

case class AgentConstraints(
  maxContextMessages: Int = 40,
  maxEstimatedTokens: Int = 12_000,
  allowedTools: Set[String] = Set.empty,
  enforceAllowedTools: Boolean = false,
) derives JsonCodec

enum ContextTrimStrategy derives JsonCodec:
  case KeepLatest
  case KeepSystemAndLatest

case class AgentContext(
  threadId: String,
  parentThreadId: Option[String] = None,
  history: Vector[Message] = Vector.empty,
  availableTools: List[Tool] = List.empty,
  constraints: AgentConstraints = AgentConstraints(),
  state: Map[String, Json] = Map.empty,
):
  def addMessage(message: Message): AgentContext =
    copy(history = history :+ message)

  def addMessages(messages: Iterable[Message]): AgentContext =
    copy(history = history ++ messages)

  def putState(key: String, value: Json): AgentContext =
    copy(state = state.updated(key, value))

  def getState(key: String): Option[Json] =
    state.get(key)

  def estimatedTokens: Int =
    TokenEstimator.estimate(history)

  def trim(strategy: ContextTrimStrategy = ContextTrimStrategy.KeepSystemAndLatest): AgentContext =
    val maxMessages = constraints.maxContextMessages.max(1)
    val byMessageCount =
      if history.length <= maxMessages then history
      else
        strategy match
          case ContextTrimStrategy.KeepLatest =>
            history.takeRight(maxMessages)
          case ContextTrimStrategy.KeepSystemAndLatest =>
            val systemMessages = history.filter(_.role == MessageRole.System)
            val nonSystem = history.filterNot(_.role == MessageRole.System)
            val slotsForNonSystem = (maxMessages - systemMessages.length).max(0)
            val latestNonSystem = nonSystem.takeRight(slotsForNonSystem)
            (systemMessages ++ latestNonSystem).takeRight(maxMessages)

    val byTokenBudget =
      trimToTokenBudget(
        messages = byMessageCount,
        budget = constraints.maxEstimatedTokens.max(1),
        strategy = strategy,
      )

    copy(history = byTokenBudget)

  def fork(newThreadId: String): AgentContext =
    copy(threadId = newThreadId, parentThreadId = Some(threadId))

  def filteredTools: List[Tool] =
    if !constraints.enforceAllowedTools || constraints.allowedTools.isEmpty then availableTools
    else availableTools.filter(tool => constraints.allowedTools.contains(tool.name))

  private def trimToTokenBudget(
    messages: Vector[Message],
    budget: Int,
    strategy: ContextTrimStrategy,
  ): Vector[Message] =
    def consumeReverse(msgs: Vector[Message]): Vector[Message] =
      val (_, kept) = msgs.reverse.foldLeft((0, Vector.empty[Message])) {
        case ((used, acc), msg) =>
          val cost = TokenEstimator.estimateMessage(msg)
          if used + cost <= budget then (used + cost, msg +: acc)
          else (used, acc)
      }
      kept

    strategy match
      case ContextTrimStrategy.KeepLatest => consumeReverse(messages)
      case ContextTrimStrategy.KeepSystemAndLatest =>
        val systemMessages = messages.filter(_.role == MessageRole.System)
        val systemCost = TokenEstimator.estimate(systemMessages)
        if systemCost >= budget then consumeReverse(systemMessages)
        else
          val nonSystem = messages.filterNot(_.role == MessageRole.System)
          val tail = consumeReverse(nonSystem)
          val combined = (systemMessages ++ tail)
          consumeReverse(combined)

object AgentContext:
  def empty(threadId: String): AgentContext = AgentContext(threadId = threadId)

object TokenEstimator:
  def estimate(messages: Iterable[Message]): Int =
    messages.iterator.map(estimateMessage).sum

  def estimateMessage(message: Message): Int =
    val words = message.content.trim.split("\\s+").count(_.nonEmpty)
    // Approximation for token counting across providers.
    (words * 1.35).toInt + 4
