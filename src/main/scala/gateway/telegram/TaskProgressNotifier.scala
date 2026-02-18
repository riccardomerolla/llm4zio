package gateway.telegram

import zio.*
import zio.stream.ZStream

import db.{ ChatRepository, PersistenceError, TaskRepository, TaskRunRow }
import gateway.ChannelRegistry
import models.*
import orchestration.OrchestratorControlPlane

enum TaskProgressNotifierError:
  case Persistence(error: PersistenceError)
  case Telegram(error: TelegramClientError)
  case ControlPlane(error: ControlPlaneError)
  case Channel(message: String)
  case InvalidRunId(runId: String)
  case MissingTelegramSession(runId: Long)
  case MissingRun(runId: Long)

trait TaskProgressNotifier:
  def runOnce: UIO[Unit]
  def runLoop: UIO[Nothing]

object TaskProgressNotifier:
  def runOnce: ZIO[TaskProgressNotifier, Nothing, Unit] =
    ZIO.serviceWithZIO[TaskProgressNotifier](_.runOnce)

  val live
    : ZLayer[
      OrchestratorControlPlane & ChatRepository & TaskRepository & ChannelRegistry & Ref[MigrationConfig],
      Nothing,
      TaskProgressNotifier,
    ] =
    ZLayer.scoped {
      for
        controlPlane <- ZIO.service[OrchestratorControlPlane]
        chatRepo     <- ZIO.service[ChatRepository]
        taskRepo     <- ZIO.service[TaskRepository]
        channelRegistry <- ZIO.service[ChannelRegistry]
        configRef    <- ZIO.service[Ref[MigrationConfig]]
        seenRef      <- Ref.make((Set.empty[String], List.empty[String]))
        service       = TaskProgressNotifierLive(controlPlane, chatRepo, taskRepo, channelRegistry, configRef, seenRef)
        _            <- service.runLoop.forkScoped
      yield service
    }

final case class TaskProgressNotifierLive(
  controlPlane: OrchestratorControlPlane,
  chatRepository: ChatRepository,
  taskRepository: TaskRepository,
  channelRegistry: ChannelRegistry,
  configRef: Ref[MigrationConfig],
  seenEventsRef: Ref[(Set[String], List[String])],
) extends TaskProgressNotifier:

  private val SeenCapacity = 4096

  override def runOnce: UIO[Unit] =
    ZIO.scoped {
      for
        queue <- controlPlane.subscribeAllEvents
        _     <- ZStream.fromQueue(queue).take(1).mapZIO(handleEventSafely).runDrain
      yield ()
    }

  override def runLoop: UIO[Nothing] =
    ZIO.scoped {
      for
        queue <- controlPlane.subscribeAllEvents
        _     <- ZStream.fromQueue(queue).mapZIO(handleEventSafely).runDrain
      yield ()
    }.forever

  private def handleEventSafely(event: ControlPlaneEvent): UIO[Unit] =
    shouldNotify(event).flatMap {
      case false => ZIO.unit
      case true  =>
        (for
          key <- eventKey(event)
          dup <- markSeen(key)
          _   <- if dup then ZIO.unit else handleEvent(event)
        yield ()).catchAll(err => ZIO.logWarning(s"task progress notifier skipped event due to error: $err"))
    }

  private def shouldNotify(event: ControlPlaneEvent): UIO[Boolean] =
    configRef.get.map { cfg =>
      val telegramEnabled = cfg.telegram.enabled
      val interesting     = event match
        case _: WorkflowStarted                     => true
        case StepStarted(_, _, _, _, _)            => true
        case StepCompleted(_, _, _, _, _)          => true
        case _: WorkflowFailed                     => true
        case WorkflowCompleted(_, _, status, _)    => status == WorkflowStatus.Completed
        case _                                     => false
      telegramEnabled && interesting
    }

  private def handleEvent(event: ControlPlaneEvent): IO[TaskProgressNotifierError, Unit] =
    for
      runIdLong <- parseRunId(extractRunId(event))
      run       <- taskRepository.getRun(runIdLong).mapError(TaskProgressNotifierError.Persistence.apply).flatMap {
                     case Some(value) => ZIO.succeed(value)
                     case None        => ZIO.fail(TaskProgressNotifierError.MissingRun(runIdLong))
                   }
      context   <- chatRepository.getSessionContextByTaskRunId(runIdLong).mapError(TaskProgressNotifierError.Persistence.apply)
      chatId    <- context match
                     case Some(link) if link.channelName.trim.equalsIgnoreCase("telegram") =>
                       parseChatId(link.sessionKey).orElseFail(TaskProgressNotifierError.MissingTelegramSession(runIdLong))
                     case _ =>
                       ZIO.fail(TaskProgressNotifierError.MissingTelegramSession(runIdLong))
      taskName  <- resolveTaskName(run)
      baseUrl   <- resolveBaseUrl
      message    = buildMessage(event, taskName, run, baseUrl)
      keyboard   = buildKeyboard(event, run.id, baseUrl)
      client     <- resolveTelegramClient
      _         <- client
                     .sendMessage(
                       TelegramSendMessage(
                         chat_id = chatId,
                         text = message,
                         reply_markup = keyboard,
                       )
                     )
                     .mapError(TaskProgressNotifierError.Telegram.apply)
                     .unit
    yield ()

  private def resolveTelegramClient: IO[TaskProgressNotifierError, TelegramClient] =
    channelRegistry
      .get("telegram")
      .mapError(err => TaskProgressNotifierError.Channel(s"telegram channel unavailable: $err"))
      .flatMap {
        case channel: TelegramChannel => ZIO.succeed(channel.client)
        case _                        => ZIO.fail(TaskProgressNotifierError.Channel("telegram channel type mismatch"))
      }

  private def resolveTaskName(run: TaskRunRow): IO[TaskProgressNotifierError, String] =
    taskRepository
      .getArtifactsByTask(run.id)
      .mapError(TaskProgressNotifierError.Persistence.apply)
      .map { artifacts =>
        artifacts
          .find(_.key == "task.name")
          .map(_.value.trim)
          .filter(_.nonEmpty)
          .getOrElse(s"Task #${run.id}")
      }

  private def resolveBaseUrl: IO[TaskProgressNotifierError, String] =
    taskRepository
      .getAllSettings
      .mapError(TaskProgressNotifierError.Persistence.apply)
      .map { settings =>
        val map       = settings.map(s => s.key -> s.value.trim).toMap
        val configured =
          List("gateway.url", "gateway.baseUrl", "gateway.publicUrl", "web.baseUrl")
            .flatMap(map.get)
            .find(_.nonEmpty)
            .getOrElse("http://localhost:8080")
        val withScheme =
          if configured.startsWith("http://") || configured.startsWith("https://") then configured
          else s"http://$configured"
        withScheme.stripSuffix("/")
      }

  private def buildMessage(
    event: ControlPlaneEvent,
    taskName: String,
    run: TaskRunRow,
    baseUrl: String,
  ): String =
    event match
      case WorkflowStarted(_, _, _, _)          =>
        val header = ResponseFormatter.formatTaskProgress(
          WorkflowRunState.Running,
          taskName = taskName,
          stepName = run.currentPhase,
        )
        val workflowLine = run.workflowId.map(id => s"Workflow: #$id").getOrElse("Workflow: N/A")
        s"$header\n$workflowLine"
      case StepStarted(_, _, step, _, _)        =>
        s"→ Step: ${step.trim} — Running..."
      case StepCompleted(_, _, step, _, _)      =>
        s"✓ Step completed: ${step.trim}"
      case WorkflowCompleted(_, _, WorkflowStatus.Failed, _) =>
        val currentStep = run.currentPhase.filter(_.trim.nonEmpty)
        val header      = ResponseFormatter.formatTaskProgress(
          WorkflowRunState.Failed,
          taskName = taskName,
          stepName = currentStep,
        )
        val errorText   = run.errorMessage.filter(_.trim.nonEmpty).map(err => s"\nError: $err").getOrElse("")
        s"$header$errorText\nView details: $baseUrl/tasks/${run.id}"
      case WorkflowCompleted(_, _, _, _)        =>
        s"""${ResponseFormatter.formatTaskProgress(WorkflowRunState.Completed, taskName, None)}
           |View results: $baseUrl/tasks/${run.id}""".stripMargin
      case WorkflowFailed(_, _, error, _)       =>
        val currentStep = run.currentPhase.filter(_.trim.nonEmpty)
        val header      = ResponseFormatter.formatTaskProgress(
          WorkflowRunState.Failed,
          taskName = taskName,
          stepName = currentStep,
        )
        s"$header\nError: ${error.trim}\nView details: $baseUrl/tasks/${run.id}"
      case _                                    =>
        s"Task update for #${run.id}"

  private def buildKeyboard(
    event: ControlPlaneEvent,
    taskRunId: Long,
    baseUrl: String,
  ): Option[TelegramInlineKeyboardMarkup] =
    event match
      case WorkflowCompleted(_, _, WorkflowStatus.Failed, _) =>
        Some(
          TelegramInlineKeyboardMarkup(
            inline_keyboard = List(
              List(
                detailsButton(taskRunId, baseUrl),
                TelegramInlineKeyboardButton(
                  text = "Retry",
                  callback_data = Some(s"wf:retry:$taskRunId:running"),
                ),
              )
            )
          )
        )
      case WorkflowCompleted(_, _, WorkflowStatus.Completed, _) =>
        Some(
          TelegramInlineKeyboardMarkup(
            inline_keyboard = List(
              List(
                detailsButton(taskRunId, baseUrl)
              )
            )
          )
        )
      case _: WorkflowFailed =>
        Some(
          TelegramInlineKeyboardMarkup(
            inline_keyboard = List(
              List(
                detailsButton(taskRunId, baseUrl),
                TelegramInlineKeyboardButton(
                  text = "Retry",
                  callback_data = Some(s"wf:retry:$taskRunId:running"),
                ),
              )
            )
          )
        )
      case _                                                        => None

  private def detailsButton(taskRunId: Long, baseUrl: String): TelegramInlineKeyboardButton =
    val url = s"$baseUrl/tasks/$taskRunId"
    if isTelegramButtonUrlValid(url) then
      TelegramInlineKeyboardButton(
        text = "View Details",
        url = Some(url),
      )
    else
      TelegramInlineKeyboardButton(
        text = "View Details",
        callback_data = Some(s"wf:details:$taskRunId:running"),
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

  private def markSeen(key: String): UIO[Boolean] =
    seenEventsRef.modify {
      case (seen, ordered) =>
        if seen.contains(key) then (true, (seen, ordered))
        else
          val appended = ordered :+ key
          if appended.length <= SeenCapacity then
            (false, (seen + key, appended))
          else
            val oldest = appended.head
            (false, (seen - oldest + key, appended.tail))
    }

  private def eventKey(event: ControlPlaneEvent): IO[TaskProgressNotifierError, String] =
    val key = event match
      case WorkflowStarted(correlationId, runId, _, ts)     =>
        s"workflow-start:$correlationId:$runId:${ts.toEpochMilli}"
      case StepStarted(correlationId, runId, step, _, ts)   =>
        s"step-start:$correlationId:$runId:${step.trim}:${ts.toEpochMilli}"
      case StepCompleted(correlationId, runId, step, _, ts) =>
        s"step-done:$correlationId:$runId:${step.trim}:${ts.toEpochMilli}"
      case WorkflowFailed(correlationId, runId, _, ts)      =>
        s"workflow-failed:$correlationId:$runId:${ts.toEpochMilli}"
      case WorkflowCompleted(correlationId, runId, status, ts) =>
        s"workflow-completed:$correlationId:$runId:${status.toString}:${ts.toEpochMilli}"
      case other                                            =>
        s"ignored:${other.getClass.getSimpleName}:${other.timestamp.toEpochMilli}"
    ZIO.succeed(key)

  private def extractRunId(event: ControlPlaneEvent): String =
    event match
      case WorkflowStarted(_, runId, _, _)     => runId
      case WorkflowCompleted(_, runId, _, _)   => runId
      case WorkflowFailed(_, runId, _, _)      => runId
      case StepStarted(_, runId, _, _, _)      => runId
      case StepProgress(_, runId, _, _, _, _, _) => runId
      case StepCompleted(_, runId, _, _, _)    => runId
      case StepFailed(_, runId, _, _, _)       => runId
      case ResourceAllocated(_, runId, _, _)   => runId
      case ResourceReleased(_, runId, _, _)    => runId

  private def parseRunId(raw: String): IO[TaskProgressNotifierError, Long] =
    ZIO.fromOption(raw.trim.toLongOption).orElseFail(TaskProgressNotifierError.InvalidRunId(raw))

  private def parseChatId(sessionKey: String): IO[TaskProgressNotifierError, Long] =
    ZIO
      .fromOption(sessionKey.split(":").lastOption.flatMap(_.toLongOption))
      .orElseFail(TaskProgressNotifierError.InvalidRunId(sessionKey))
