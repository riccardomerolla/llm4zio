package gateway.telegram

import zio.*
import zio.json.*

import agents.AgentRegistry
import db.*
import orchestration.TaskExecutor

enum WorkflowNotifierError:
  case Telegram(error: TelegramClientError)
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

  val live: ZLayer[TelegramClient & AgentRegistry & TaskRepository & TaskExecutor, Nothing, WorkflowNotifier] =
    ZLayer.fromZIO {
      for
        client        <- ZIO.service[TelegramClient]
        agentRegistry <- ZIO.service[AgentRegistry]
        repository    <- ZIO.service[TaskRepository]
        taskExecutor  <- ZIO.service[TaskExecutor]
      yield WorkflowNotifierLive(client, agentRegistry, repository, taskExecutor)
    }

final case class WorkflowNotifierLive(
  client: TelegramClient,
  agentRegistry: AgentRegistry,
  repository: TaskRepository,
  taskExecutor: TaskExecutor,
) extends WorkflowNotifier:

  override def notifyCommand(
    chatId: Long,
    replyToMessageId: Option[Long],
    command: BotCommand,
  ): IO[WorkflowNotifierError, Unit] =
    command match
      case BotCommand.Start        => notifyStart(chatId, replyToMessageId)
      case BotCommand.Help         => notifyHelp(chatId, replyToMessageId)
      case BotCommand.ListTasks    => notifyTasks(chatId, replyToMessageId)
      case BotCommand.Status(id)   => notifyStatus(chatId, replyToMessageId, id)
      case BotCommand.Logs(id)     => notifyLogs(chatId, replyToMessageId, id)
      case BotCommand.Cancel(id)   => notifyCancel(chatId, replyToMessageId, id)

  override def notifyParseError(
    chatId: Long,
    replyToMessageId: Option[Long],
    error: CommandParseError,
  ): IO[WorkflowNotifierError, Unit] =
    sendText(chatId, parseErrorMessage(error), replyToMessageId)

  private def sendText(
    chatId: Long,
    text: String,
    replyToMessageId: Option[Long],
    replyMarkup: Option[TelegramInlineKeyboardMarkup] = None,
  ): IO[WorkflowNotifierError, Unit] =
    client
      .sendMessage(
        TelegramSendMessage(
          chat_id = chatId,
          text = text,
          reply_to_message_id = replyToMessageId,
          reply_markup = replyMarkup,
        )
      )
      .mapError(WorkflowNotifierError.Telegram.apply)
      .unit

  private def notifyStart(chatId: Long, replyToMessageId: Option[Long]): IO[WorkflowNotifierError, Unit] =
    for
      gatewayName <- settingValue("gateway.name")
      telegramOn  <- settingValue("telegram.enabled").map(_.exists(_.equalsIgnoreCase("true")))
      agentsCount <- agentRegistry.getAllAgents.map(_.length)
      channels     = (List("web") ++ (if telegramOn then List("telegram") else Nil)).mkString(", ")
      text         =
        s"""Welcome to ${gatewayName.getOrElse("Gateway")} bot.
           |Channels online: $channels
           |Available agents: $agentsCount
           |
           |Quick start:
           |- /tasks to list recent tasks
           |- /status <id> to inspect a task
           |- /help for all commands""".stripMargin
      _          <- sendText(chatId, text, replyToMessageId)
    yield ()

  private def notifyHelp(chatId: Long, replyToMessageId: Option[Long]): IO[WorkflowNotifierError, Unit] =
    sendText(
      chatId,
      """Available commands:
        |/start - Show gateway welcome and quick-start tips.
        |/help - Show this command list.
        |/tasks - List the last 5 tasks.
        |/status <id> - Show task status, current step, and progress.
        |/logs <id> - Show recent task step outputs.
        |/cancel <id> - Cancel a running task.""".stripMargin,
      replyToMessageId,
    )

  private def notifyTasks(chatId: Long, replyToMessageId: Option[Long]): IO[WorkflowNotifierError, Unit] =
    for
      runs     <- repository.listRuns(offset = 0, limit = 5).mapError(WorkflowNotifierError.Persistence.apply)
      baseUrl  <- taskBaseUrl
      tasks    <- ZIO.foreach(runs)(toTaskSummary)
      text      = formatTaskList(tasks)
      keyboard  = if tasks.isEmpty then None
                  else
                    Some(
                      TelegramInlineKeyboardMarkup(
                        inline_keyboard = tasks.map(task =>
                          List(
                            detailsButton(task.id, baseUrl, text = s"View #${task.id} ->")
                          )
                        )
                      )
                    )
      _        <- sendText(chatId, text, replyToMessageId, keyboard)
    yield ()

  private def notifyStatus(
    chatId: Long,
    replyToMessageId: Option[Long],
    taskId: Long,
  ): IO[WorkflowNotifierError, Unit] =
    for
      runOpt <- repository.getRun(taskId).mapError(WorkflowNotifierError.Persistence.apply)
      _      <- runOpt match
                  case None      =>
                    sendText(chatId, s"Task #$taskId was not found.", replyToMessageId)
                  case Some(run) =>
                    for
                      summary <- toTaskSummary(run)
                      baseUrl <- taskBaseUrl
                      text     =
                        s"""Task #${summary.id}: ${summary.name}
                           |Status: ${statusBadge(summary.status)} ${summary.status.toString}
                           |Workflow: ${summary.workflowName}
                           |Current step: ${summary.currentStep}
                           |Progress: ${summary.stepProgress}
                           |View: $baseUrl/tasks/${summary.id}""".stripMargin
                      markup   = statusMarkup(summary, baseUrl)
                      _       <- sendText(chatId, text, replyToMessageId, markup)
                    yield ()
    yield ()

  private def notifyLogs(
    chatId: Long,
    replyToMessageId: Option[Long],
    taskId: Long,
  ): IO[WorkflowNotifierError, Unit] =
    for
      taskOpt  <- repository.getRun(taskId).mapError(WorkflowNotifierError.Persistence.apply)
      message  <- taskOpt match
                    case None      =>
                      ZIO.succeed(s"Task #$taskId was not found.")
                    case Some(_)   =>
                      repository
                        .getReportsByTask(taskId)
                        .mapError(WorkflowNotifierError.Persistence.apply)
                        .map { reports =>
                          val latest = reports.takeRight(5)
                          if latest.isEmpty then s"No logs available for task #$taskId."
                          else
                            val lines = latest.map { report =>
                              val compact = report.content.trim.replace("\n", " ").take(100)
                              s"- ${report.stepName}: $compact"
                            }.mkString("\n")
                            s"Recent logs for task #$taskId:\n$lines"
                        }
      _        <- sendText(chatId, message, replyToMessageId)
    yield ()

  private def notifyCancel(
    chatId: Long,
    replyToMessageId: Option[Long],
    taskId: Long,
  ): IO[WorkflowNotifierError, Unit] =
    for
      runOpt <- repository.getRun(taskId).mapError(WorkflowNotifierError.Persistence.apply)
      _      <- runOpt match
                  case None      =>
                    sendText(chatId, s"Task #$taskId was not found.", replyToMessageId)
                  case Some(run) =>
                    run.status match
                      case RunStatus.Completed | RunStatus.Cancelled =>
                        sendText(
                          chatId,
                          s"Task #$taskId is ${run.status.toString.toLowerCase} and cannot be cancelled.",
                          replyToMessageId,
                        )
                      case _                                         =>
                        for
                          now <- Clock.instant
                          _   <- repository
                                   .updateRun(
                                     run.copy(
                                       status = RunStatus.Cancelled,
                                       completedAt = Some(now),
                                       errorMessage = Some("Cancelled by Telegram command"),
                                     )
                                   )
                                   .mapError(WorkflowNotifierError.Persistence.apply)
                          _   <- taskExecutor.cancel(run.id)
                          _   <- sendText(chatId, s"Task #$taskId cancelled.", replyToMessageId)
                        yield ()
    yield ()

  private def settingValue(key: String): IO[WorkflowNotifierError, Option[String]] =
    repository
      .getSetting(key)
      .mapError(WorkflowNotifierError.Persistence.apply)
      .map(_.map(_.value.trim).filter(_.nonEmpty))

  private def taskBaseUrl: IO[WorkflowNotifierError, String] =
    ZIO
      .foreach(List("gateway.url", "gateway.baseUrl", "gateway.publicUrl", "web.baseUrl"))(settingValue)
      .map(_.flatten.headOption.getOrElse("http://localhost:8080"))
      .map { raw =>
        val normalized = raw.trim
        val withScheme =
          if normalized.startsWith("http://") || normalized.startsWith("https://") then normalized else s"http://$normalized"
        withScheme.stripSuffix("/")
      }

  private def toTaskSummary(run: TaskRunRow): IO[WorkflowNotifierError, TaskSummary] =
    for
      artifacts <- repository.getArtifactsByTask(run.id).mapError(WorkflowNotifierError.Persistence.apply)
      workflow  <- run.workflowId match
                     case Some(workflowId) =>
                       repository.getWorkflow(workflowId).mapError(WorkflowNotifierError.Persistence.apply)
                     case None             =>
                       ZIO.none
      taskName   = artifacts.find(_.key == "task.name").map(_.value.trim).filter(_.nonEmpty).getOrElse(s"Task #${run.id}")
      workflowNm = workflow.map(_.name).orElse(run.workflowId.map(id => s"Workflow #$id")).getOrElse("N/A")
      steps      = workflow.flatMap(parseWorkflowSteps).getOrElse(Nil)
      stepProg   = formatStepProgress(run, steps)
      current    = run.currentPhase.filter(_.trim.nonEmpty).getOrElse("n/a")
    yield TaskSummary(
      id = run.id,
      name = taskName,
      status = run.status,
      workflowName = workflowNm,
      currentStep = current,
      stepProgress = stepProg,
    )

  private def parseWorkflowSteps(workflow: WorkflowRow): Option[List[String]] =
    workflow.steps.fromJson[List[String]].toOption.map(_.map(_.trim).filter(_.nonEmpty))

  private def formatStepProgress(run: TaskRunRow, steps: List[String]): String =
    val total = steps.length
    if total <= 0 then "n/a"
    else
      run.status match
        case RunStatus.Completed | RunStatus.Cancelled => s"$total/$total completed"
        case _                                         =>
          val completed = run.currentPhase
            .flatMap { phase =>
              val index = steps.indexOf(phase)
              if index >= 0 then Some(index) else None
            }
            .getOrElse(0)
          s"$completed/$total completed"

  private def formatTaskList(tasks: List[TaskSummary]): String =
    if tasks.isEmpty then "No tasks found."
    else
      val lines = tasks.map { task =>
        s"#${task.id} ${task.name} | ${statusBadge(task.status)} ${task.status.toString} | ${task.workflowName}"
      }.mkString("\n")
      s"Recent tasks:\n$lines"

  private def statusMarkup(
    task: TaskSummary,
    baseUrl: String,
  ): Option[TelegramInlineKeyboardMarkup] =
    val viewRow = List(
      detailsButton(task.id, baseUrl, text = "View ->")
    )
    val action = task.status match
      case RunStatus.Running | RunStatus.Pending =>
        List(TelegramInlineKeyboardButton(text = "Cancel", callback_data = Some(s"wf:cancel:${task.id}:running")))
      case RunStatus.Paused                      =>
        List(TelegramInlineKeyboardButton(text = "Resume", callback_data = Some(s"wf:toggle:${task.id}:paused")))
      case RunStatus.Failed                      =>
        List(TelegramInlineKeyboardButton(text = "Retry", callback_data = Some(s"wf:retry:${task.id}:running")))
      case _                                     => Nil

    val rows = if action.isEmpty then List(viewRow) else List(viewRow, action)
    Some(TelegramInlineKeyboardMarkup(inline_keyboard = rows))

  private def statusBadge(status: RunStatus): String =
    status match
      case RunStatus.Pending   => "🟡"
      case RunStatus.Running   => "🟢"
      case RunStatus.Paused    => "||"
      case RunStatus.Completed => "✅"
      case RunStatus.Failed    => "❌"
      case RunStatus.Cancelled => "⛔"

  private def detailsButton(taskId: Long, baseUrl: String, text: String): TelegramInlineKeyboardButton =
    val url = s"$baseUrl/tasks/$taskId"
    if isTelegramButtonUrlValid(url) then
      TelegramInlineKeyboardButton(
        text = text,
        url = Some(url),
      )
    else
      TelegramInlineKeyboardButton(
        text = text,
        callback_data = Some(s"wf:details:$taskId:running"),
      )

  private def isTelegramButtonUrlValid(url: String): Boolean =
    scala.util.Try(java.net.URI(url)).toOption.exists { uri =>
      val schemeOk = Option(uri.getScheme).exists(s => s.equalsIgnoreCase("http") || s.equalsIgnoreCase("https"))
      val host     = Option(uri.getHost).map(_.trim).getOrElse("")
      val hostOk   =
        host.nonEmpty &&
          host.contains(".") &&
          !host.equalsIgnoreCase("localhost")
      schemeOk && hostOk
    }

  private final case class TaskSummary(
    id: Long,
    name: String,
    status: RunStatus,
    workflowName: String,
    currentStep: String,
    stepProgress: String,
  )

  private def parseErrorMessage(error: CommandParseError): String =
    error match
      case CommandParseError.EmptyInput                       =>
        "Command is empty. Use /help."
      case CommandParseError.NotACommand(_)                   =>
        "Unsupported message. Use /help."
      case CommandParseError.UnknownCommand(command)          =>
        s"Unknown command '/$command'. Use /help."
      case CommandParseError.MissingParameter(command, param) =>
        s"Command '/$command' requires parameter '$param'."
      case CommandParseError.InvalidTaskId(command, raw)      =>
        s"Invalid task id '$raw' for '/$command'."
