package llm4zio.core

import zio.*
import zio.json.*

import llm4zio.tools.{ Tool, ToolRegistry }

import java.time.Instant

enum ContextError derives JsonCodec:
  case InvalidInput(message: String)
  case ParseFailed(message: String)
  case SummarizationFailed(message: String)
  case ToolLoopFailed(message: String)

case class ContextLimits(
  maxTokens: Int,
  warningThresholdPct: Double = 0.85,
) derives JsonCodec

case class ContextWindow(
  messages: Vector[ConversationMessage],
  totalTokens: Int,
  approachingLimit: Boolean,
  trimmed: Boolean,
) derives JsonCodec

enum ContextTrimmingStrategy derives JsonCodec:
  case DropOldestFifo
  case SlidingWindow
  case PriorityBased
  case SummarizeOldMessages(summaryTargetTokens: Int = 256)

trait TokenCounter:
  def countText(provider: LlmProvider, text: String): Int
  def countMessage(provider: LlmProvider, message: ConversationMessage): Int
  def countMessages(provider: LlmProvider, messages: Iterable[ConversationMessage]): Int

object TokenCounter:
  val default: TokenCounter =
    new TokenCounter:
      override def countText(provider: LlmProvider, text: String): Int =
        val chars = text.length.max(1)
        provider match
          case LlmProvider.OpenAI | LlmProvider.OpenCode     => math.ceil(chars.toDouble / 4.0).toInt.max(1)
          case LlmProvider.Anthropic => math.ceil(chars.toDouble / 3.8).toInt.max(1)
          case LlmProvider.GeminiApi | LlmProvider.GeminiCli => math.ceil(chars.toDouble / 4.2).toInt.max(1)
          case LlmProvider.LmStudio | LlmProvider.Ollama     => math.ceil(chars.toDouble / 4.5).toInt.max(1)

      override def countMessage(provider: LlmProvider, message: ConversationMessage): Int =
        val roleOverhead = message.role match
          case PromptRole.System    => 6
          case PromptRole.User      => 5
          case PromptRole.Assistant => 5
          case PromptRole.Tool      => 8

        countText(provider, message.content) + roleOverhead

      override def countMessages(provider: LlmProvider, messages: Iterable[ConversationMessage]): Int =
        messages.iterator.map(countMessage(provider, _)).sum

trait MessageSummarizer:
  def summarize(messages: List[ConversationMessage], targetTokens: Int): IO[ContextError, ConversationMessage]

object MessageSummarizer:
  val simple: MessageSummarizer =
    new MessageSummarizer:
      override def summarize(messages: List[ConversationMessage], targetTokens: Int): IO[ContextError, ConversationMessage] =
        if messages.isEmpty then ZIO.fail(ContextError.InvalidInput("Cannot summarize empty messages"))
        else
          val summaryText = messages
            .take(8)
            .map(message => s"${message.role.toString.toLowerCase}: ${message.content.take(160)}")
            .mkString("\n")

          ZIO.succeed(
            ConversationMessage(
              id = java.util.UUID.randomUUID().toString,
              role = PromptRole.Assistant,
              content = s"Summary of previous context:\n$summaryText",
              timestamp = Instant.now(),
              tokens = targetTokens.max(1),
              metadata = Map("summary" -> "true"),
              important = true,
            )
          )

object ContextManagement:
  def applyWindow(
    messages: Vector[ConversationMessage],
    provider: LlmProvider,
    limits: ContextLimits,
    strategy: ContextTrimmingStrategy,
    tokenCounter: TokenCounter = TokenCounter.default,
    summarizer: MessageSummarizer = MessageSummarizer.simple,
  ): IO[ContextError, ContextWindow] =
    if limits.maxTokens <= 0 then ZIO.fail(ContextError.InvalidInput("maxTokens must be > 0"))
    else
      for
        withTokens <- annotateTokens(messages, provider, tokenCounter)
        totalBefore = withTokens.iterator.map(_.tokens).sum
        trimmed <-
          if totalBefore <= limits.maxTokens then ZIO.succeed((withTokens, false))
          else strategy match
            case ContextTrimmingStrategy.DropOldestFifo =>
              ZIO.succeed(dropOldest(withTokens, limits.maxTokens) -> true)
            case ContextTrimmingStrategy.SlidingWindow  =>
              ZIO.succeed(slidingWindow(withTokens, limits.maxTokens) -> true)
            case ContextTrimmingStrategy.PriorityBased  =>
              ZIO.succeed(priorityWindow(withTokens, limits.maxTokens) -> true)
            case ContextTrimmingStrategy.SummarizeOldMessages(summaryTarget) =>
              summarizeOldMessages(withTokens, limits.maxTokens, summaryTarget, provider, tokenCounter, summarizer).map(_ -> true)
        (finalMessages, wasTrimmed) = trimmed
        totalAfter = finalMessages.iterator.map(_.tokens).sum
        approaching = totalAfter.toDouble >= limits.maxTokens.toDouble * limits.warningThresholdPct
      yield ContextWindow(
        messages = finalMessages,
        totalTokens = totalAfter,
        approachingLimit = approaching,
        trimmed = wasTrimmed,
      )

  private def annotateTokens(
    messages: Vector[ConversationMessage],
    provider: LlmProvider,
    tokenCounter: TokenCounter,
  ): IO[ContextError, Vector[ConversationMessage]] =
    ZIO.foreach(messages) { message =>
      val tokens = if message.tokens > 0 then message.tokens else tokenCounter.countMessage(provider, message)
      ZIO.succeed(message.copy(tokens = tokens))
    }.map(_.toVector)

  private def dropOldest(messages: Vector[ConversationMessage], maxTokens: Int): Vector[ConversationMessage] =
    val (_, kept) = messages.reverse.foldLeft((0, Vector.empty[ConversationMessage])) {
      case ((used, acc), message) =>
        if used + message.tokens <= maxTokens then (used + message.tokens, message +: acc)
        else (used, acc)
    }
    kept

  private def slidingWindow(messages: Vector[ConversationMessage], maxTokens: Int): Vector[ConversationMessage] =
    val system = messages.filter(_.role == PromptRole.System)
    val recentNonSystem = messages.filterNot(_.role == PromptRole.System)
    val budgetAfterSystem = (maxTokens - system.iterator.map(_.tokens).sum).max(0)

    val (_, tail) = recentNonSystem.reverse.foldLeft((0, Vector.empty[ConversationMessage])) {
      case ((used, acc), message) =>
        if used + message.tokens <= budgetAfterSystem then (used + message.tokens, message +: acc)
        else (used, acc)
    }

    (system ++ tail).takeRight(messages.length)

  private def priorityWindow(messages: Vector[ConversationMessage], maxTokens: Int): Vector[ConversationMessage] =
    val scored = messages.zipWithIndex.map { case (message, idx) =>
      val score =
        (if message.role == PromptRole.System then 1000 else 0) +
          (if message.role == PromptRole.Tool then 600 else 0) +
          (if message.important then 400 else 0) +
          idx
      (message, score)
    }

    val selected = scored
      .sortBy((message, score) => (-score, message.timestamp.toEpochMilli))
      .foldLeft((0, List.empty[ConversationMessage])) { case ((used, acc), (message, _)) =>
        if used + message.tokens <= maxTokens then (used + message.tokens, message :: acc)
        else (used, acc)
      }
      ._2

    selected.sortBy(_.timestamp.toEpochMilli).toVector

  private def summarizeOldMessages(
    messages: Vector[ConversationMessage],
    maxTokens: Int,
    summaryTargetTokens: Int,
    provider: LlmProvider,
    tokenCounter: TokenCounter,
    summarizer: MessageSummarizer,
  ): IO[ContextError, Vector[ConversationMessage]] =
    if messages.length <= 2 then ZIO.succeed(dropOldest(messages, maxTokens))
    else
      val splitAt = (messages.length / 2).max(1)
      val (old, recent) = messages.splitAt(splitAt)

      for
        summary <- summarizer.summarize(old.toList, summaryTargetTokens)
        summaryWithTokens = summary.copy(tokens = tokenCounter.countMessage(provider, summary))
        merged = (Vector(summaryWithTokens) ++ recent)
        result = dropOldest(merged, maxTokens)
      yield result

case class ClarificationConfig(maxAttempts: Int = 2)

object MultiTurnInteractions:
  def executeWithClarification[A](
    initialPrompt: String,
    llmService: LlmService,
    parse: String => Either[String, A],
    clarificationConfig: ClarificationConfig = ClarificationConfig(),
  ): IO[LlmError, A] =
    loop(
      prompt = initialPrompt,
      llmService = llmService,
      parse = parse,
      attempt = 0,
      maxAttempts = clarificationConfig.maxAttempts.max(0),
    )

  private def loop[A](
    prompt: String,
    llmService: LlmService,
    parse: String => Either[String, A],
    attempt: Int,
    maxAttempts: Int,
  ): IO[LlmError, A] =
    for
      response <- llmService.execute(prompt)
      parsed <- ZIO.fromEither(parse(response.content)).mapError { parseError =>
                  LlmError.ParseError(parseError, response.content)
                }
                .catchAll { err =>
                  if attempt >= maxAttempts then ZIO.fail(err)
                  else
                    val clarificationPrompt =
                      s"""$prompt
                         |
                         |Your previous answer could not be parsed.
                         |Error: ${err.message}
                         |
                         |Please answer again with a strictly valid format.
                         |""".stripMargin
                    loop(clarificationPrompt, llmService, parse, attempt + 1, maxAttempts)
                }
    yield parsed

case class ToolConversationResult(
  thread: ConversationThread,
  response: LlmResponse,
) derives JsonCodec

object ToolConversationManager:
  def run(
    prompt: String,
    thread: ConversationThread,
    llmService: LlmService,
    toolRegistry: ToolRegistry,
    tools: List[Tool],
    maxIterations: Int = 8,
  ): IO[LlmError, ToolConversationResult] =
    loop(prompt, thread, llmService, toolRegistry, tools, 0, maxIterations.max(1))

  private def loop(
    prompt: String,
    thread: ConversationThread,
    llmService: LlmService,
    toolRegistry: ToolRegistry,
    tools: List[Tool],
    iteration: Int,
    maxIterations: Int,
  ): IO[LlmError, ToolConversationResult] =
    if iteration >= maxIterations then
      ZIO.fail(LlmError.InvalidRequestError(s"Conversation tool loop reached max iterations ($maxIterations)"))
    else
      for
        requestAt <- Clock.instant
        userMessage = ConversationMessage.fromCore(
                        Message(MessageRole.User, prompt),
                        timestamp = requestAt,
                      )
        withUser = thread.append(userMessage, ConversationState.InProgress)
        callResponse <- llmService.executeWithTools(prompt, tools)
        afterAssistant <- callResponse.content match
                            case Some(content) =>
                              Clock.instant.map { now =>
                                withUser.append(
                                  ConversationMessage.fromCore(Message(MessageRole.Assistant, content), now),
                                  if callResponse.toolCalls.nonEmpty then ConversationState.WaitingForTool else ConversationState.Completed,
                                )
                              }
                            case None          => ZIO.succeed(withUser.copy(state = ConversationState.WaitingForTool))
        result <-
          if callResponse.toolCalls.isEmpty then
            ZIO.succeed(
              ToolConversationResult(
                thread = afterAssistant.checkpoint(requestAt, Some("completed")),
                response = LlmResponse(
                  content = callResponse.content.getOrElse(""),
                  metadata = Map(
                    "finish_reason" -> callResponse.finishReason,
                    "tool_iterations" -> iteration.toString,
                  ),
                ),
              )
            )
          else
            for
              toolResults <- toolRegistry.executeAll(callResponse.toolCalls)
              toolMessages <- ZIO.foreach(toolResults) { result =>
                                Clock.instant.map { now =>
                                  val content = result.result match
                                    case Right(json)    => json.toJson
                                    case Left(errorMsg) => s"{\"error\": ${errorMsg.toJson}}"
                                  ConversationMessage.fromCore(
                                    Message(MessageRole.Tool, s"tool_call_id=${result.toolCallId} result=$content"),
                                    now,
                                    metadata = Map("tool_call_id" -> result.toolCallId),
                                  ).copy(important = true)
                                }
                              }
              withTools = toolMessages.foldLeft(afterAssistant) { (acc, msg) =>
                            acc.append(msg, ConversationState.InProgress)
                          }
              followUpPrompt =
                s"""$prompt
                   |
                   |Tool results:
                   |${toolMessages.map(_.content).mkString("\n")}
                   |
                   |Continue the task.
                   |""".stripMargin
              continued <- loop(followUpPrompt, withTools, llmService, toolRegistry, tools, iteration + 1, maxIterations)
            yield continued
      yield result
