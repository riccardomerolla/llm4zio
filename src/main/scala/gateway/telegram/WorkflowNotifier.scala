package gateway.telegram

import zio.*

import db.{ MigrationRunRow, PersistenceError, RunStatus }
import models.*
import orchestration.MigrationOrchestrator

enum WorkflowNotifierError:
  case Telegram(error: TelegramClientError)
  case Orchestrator(error: OrchestratorError)
  case Persistence(error: PersistenceError)

trait WorkflowNotifier:
  def notifyCommand(
    chatId: Long,
    replyToMessageId: Option[Long],
    command: BotCommand,
  ): IO[WorkflowNotifierError, Unit]

  def notifyParseError(
    chatId: Long,
    replyToMessageId: Option[Long],
    error: CommandParseError,
  ): IO[WorkflowNotifierError, Unit]

object WorkflowNotifier:
  def notifyCommand(
    chatId: Long,
    replyToMessageId: Option[Long],
    command: BotCommand,
  ): ZIO[WorkflowNotifier, WorkflowNotifierError, Unit] =
    ZIO.serviceWithZIO[WorkflowNotifier](_.notifyCommand(chatId, replyToMessageId, command))

  def notifyParseError(
    chatId: Long,
    replyToMessageId: Option[Long],
    error: CommandParseError,
  ): ZIO[WorkflowNotifier, WorkflowNotifierError, Unit] =
    ZIO.serviceWithZIO[WorkflowNotifier](_.notifyParseError(chatId, replyToMessageId, error))

  val noop: WorkflowNotifier =
    new WorkflowNotifier:
      override def notifyCommand(
        chatId: Long,
        replyToMessageId: Option[Long],
        command: BotCommand,
      ): IO[WorkflowNotifierError, Unit] =
        ZIO.unit

      override def notifyParseError(
        chatId: Long,
        replyToMessageId: Option[Long],
        error: CommandParseError,
      ): IO[WorkflowNotifierError, Unit] =
        ZIO.unit

  val live: ZLayer[MigrationOrchestrator & TelegramClient, Nothing, WorkflowNotifier] =
    ZLayer.fromZIO {
      for
        orchestrator  <- ZIO.service[MigrationOrchestrator]
        client        <- ZIO.service[TelegramClient]
        subscriptions <- Ref.Synchronized.make(Map.empty[(Long, Long), Fiber.Runtime[Nothing, Unit]])
      yield WorkflowNotifierLive(orchestrator, client, subscriptions)
    }

final case class WorkflowNotifierLive(
  orchestrator: MigrationOrchestrator,
  client: TelegramClient,
  subscriptions: Ref.Synchronized[Map[(Long, Long), Fiber.Runtime[Nothing, Unit]]],
) extends WorkflowNotifier:

  override def notifyCommand(
    chatId: Long,
    replyToMessageId: Option[Long],
    command: BotCommand,
  ): IO[WorkflowNotifierError, Unit] =
    command.toWorkflowOperation match
      case BotWorkflowOperation.ShowWelcome =>
        sendText(chatId, welcomeMessage, replyToMessageId)

      case BotWorkflowOperation.ShowHelp =>
        sendText(chatId, helpMessage, replyToMessageId)

      case BotWorkflowOperation.ListRuns =>
        for
          runs <- orchestrator.listRuns(page = 1, pageSize = 10).mapError(WorkflowNotifierError.Persistence.apply)
          _    <- sendText(chatId, formatRunList(runs), replyToMessageId)
        yield ()

      case BotWorkflowOperation.ShowRunStatus(runId) =>
        for
          maybeRun <- orchestrator.getRunStatus(runId).mapError(WorkflowNotifierError.Persistence.apply)
          _        <- maybeRun match
                        case None      => sendText(chatId, s"Run $runId was not found.", replyToMessageId)
                        case Some(run) =>
                          sendText(chatId, formatRunStatus(run), replyToMessageId) *>
                            maybeStartProgressStreaming(chatId, run.id, run.status)
        yield ()

      case BotWorkflowOperation.ShowRunLogs(runId) =>
        for
          maybeRun <- orchestrator.getRunStatus(runId).mapError(WorkflowNotifierError.Persistence.apply)
          _        <- maybeRun match
                        case None      => sendText(chatId, s"Run $runId was not found.", replyToMessageId)
                        case Some(run) =>
                          sendText(chatId, s"Streaming progress updates for run ${run.id}...", replyToMessageId) *>
                            maybeStartProgressStreaming(chatId, run.id, run.status)
        yield ()

      case BotWorkflowOperation.CancelRun(runId) =>
        orchestrator
          .cancelMigration(runId)
          .mapError(WorkflowNotifierError.Orchestrator.apply) *>
          sendText(chatId, s"Cancellation requested for run $runId.", replyToMessageId)

  override def notifyParseError(
    chatId: Long,
    replyToMessageId: Option[Long],
    error: CommandParseError,
  ): IO[WorkflowNotifierError, Unit] =
    sendText(chatId, formatParseError(error), replyToMessageId)

  private def maybeStartProgressStreaming(chatId: Long, runId: Long, status: RunStatus)
    : IO[WorkflowNotifierError, Unit] =
    if isTerminal(status) then sendTerminalNotification(chatId, runId, status)
    else startProgressStreaming(chatId, runId)

  private def startProgressStreaming(chatId: Long, runId: Long): IO[WorkflowNotifierError, Unit] =
    val key = (chatId, runId)
    subscriptions.modifyZIO { active =>
      if active.contains(key) then ZIO.succeed(((), active))
      else
        streamRunProgress(chatId, runId)
          .forkDaemon
          .map(fiber => ((), active.updated(key, fiber)))
    }

  private def streamRunProgress(chatId: Long, runId: Long): UIO[Unit] =
    val key = (chatId, runId)

    orchestrator.subscribeToProgress(runId).flatMap { queue =>
      def loop: IO[WorkflowNotifierError, Unit] =
        for
          progress <- queue.take
          _        <- sendText(chatId, formatProgress(progress), None)
          maybeRun <- orchestrator.getRunStatus(runId).mapError(WorkflowNotifierError.Persistence.apply)
          _        <- maybeRun match
                        case Some(run) if isTerminal(run.status) =>
                          sendTerminalNotification(chatId, run.id, run.status)
                        case _                                   =>
                          loop
        yield ()

      loop
        .catchAll(err =>
          sendText(chatId, s"Command failed: ${formatWorkflowError(err)}", None).ignore *>
            ZIO.logWarning(s"telegram progress stream failed for run $runId: ${formatWorkflowError(err)}")
        )
        .ensuring(queue.shutdown *> subscriptions.update(_ - key))
    }

  private def sendText(
    chatId: Long,
    text: String,
    replyToMessageId: Option[Long],
  ): IO[WorkflowNotifierError, Unit] =
    client
      .sendMessage(
        TelegramSendMessage(
          chat_id = chatId,
          text = text,
          reply_to_message_id = replyToMessageId,
        )
      )
      .mapError(WorkflowNotifierError.Telegram.apply)
      .unit

  private def sendTerminalNotification(chatId: Long, runId: Long, status: RunStatus): IO[WorkflowNotifierError, Unit] =
    val text = status match
      case RunStatus.Completed => s"Run $runId completed successfully."
      case RunStatus.Failed    => s"Run $runId failed. Use /status $runId for details."
      case RunStatus.Cancelled => s"Run $runId was cancelled."
      case RunStatus.Pending   => s"Run $runId is pending."
      case RunStatus.Running   => s"Run $runId is running."

    sendText(chatId, text, None)

  private def isTerminal(status: RunStatus): Boolean =
    status == RunStatus.Completed || status == RunStatus.Failed || status == RunStatus.Cancelled

  private def formatRunList(runs: List[MigrationRunRow]): String =
    if runs.isEmpty then "No migration runs found."
    else
      val lines = runs.map(run =>
        s"#${run.id} ${run.status.toString} - ${run.currentPhase.getOrElse("n/a")}"
      )
      ("Latest runs:" :: lines).mkString("\n")

  private def formatRunStatus(run: MigrationRunRow): String =
    val phase = run.currentPhase.getOrElse("n/a")
    val err   = run.errorMessage.map(message => s"\nError: $message").getOrElse("")
    s"Run ${run.id}: ${run.status.toString}\nPhase: $phase\nProgress: ${run.processedFiles}/${run.totalFiles}$err"

  private def formatProgress(progress: ProgressUpdate): String =
    val pct =
      if progress.itemsTotal <= 0 then 0
      else Math.min(100, (progress.itemsProcessed.toDouble / progress.itemsTotal.toDouble * 100.0).toInt)
    s"Run ${progress.runId} [${progress.phase}] ${progress.itemsProcessed}/${progress.itemsTotal} (${pct}%) - ${progress.message}"

  private val welcomeMessage: String =
    "Legacy Modernization Bot is online. Use /help to see available commands."

  private val helpMessage: String =
    List(
      "Available commands:",
      "/start - show welcome message",
      "/help - show this help",
      "/list - list latest runs",
      "/status {id} - show run status",
      "/logs {id} - stream progress updates",
      "/cancel {id} - cancel a run",
    ).mkString("\n")

  private def formatParseError(error: CommandParseError): String =
    error match
      case CommandParseError.EmptyInput                       =>
        "Command is empty. Use /help to list available commands."
      case CommandParseError.NotACommand(_)                   =>
        "Unsupported text message. Use /help to list available commands."
      case CommandParseError.UnknownCommand(command)          =>
        s"Unknown command '/$command'. Use /help to list available commands."
      case CommandParseError.MissingParameter(command, param) =>
        s"Command '/$command' requires parameter '$param'."
      case CommandParseError.InvalidRunId(command, raw)       =>
        s"Command '/$command' received invalid run id '$raw'."

  private def formatWorkflowError(error: WorkflowNotifierError): String =
    error match
      case WorkflowNotifierError.Telegram(err)     => s"telegram API error: $err"
      case WorkflowNotifierError.Orchestrator(err) => err.message
      case WorkflowNotifierError.Persistence(err)  => s"persistence error: $err"
