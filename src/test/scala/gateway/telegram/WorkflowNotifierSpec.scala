package gateway.telegram

import java.time.Instant

import zio.*
import zio.stream.ZStream
import zio.test.*

import db.*
import models.{ MigrationConfig, MigrationStep, OrchestratorError, ProgressUpdate, StepProgressEvent }
import orchestration.{ MigrationOrchestrator, MigrationResult, PipelineProgressUpdate, StepResult }

object WorkflowNotifierSpec extends ZIOSpecDefault:

  final private case class CapturingTelegramClient(sentRef: Ref[List[TelegramSendMessage]]) extends TelegramClient:
    override def getUpdates(
      offset: Option[Long],
      limit: Int,
      timeoutSeconds: Int,
      timeout: Duration,
    ): IO[TelegramClientError, List[TelegramUpdate]] =
      ZIO.succeed(Nil)

    override def sendMessage(
      request: TelegramSendMessage,
      timeout: Duration,
    ): IO[TelegramClientError, TelegramMessage] =
      sentRef.update(_ :+ request) *> ZIO.succeed(
        TelegramMessage(
          message_id = request.reply_to_message_id.getOrElse(1L),
          date = 1710000000L,
          chat = TelegramChat(id = request.chat_id, `type` = "private"),
          text = Some(request.text),
        )
      )

  final private case class StubOrchestrator(
    listedRuns: List[MigrationRunRow],
    statusRef: Ref[Map[Long, List[MigrationRunRow]]],
    progressQueuesRef: Ref[Map[Long, Queue[ProgressUpdate]]],
    cancelledRef: Ref[List[Long]],
  ) extends MigrationOrchestrator:

    override def runFullMigration(sourcePath: java.nio.file.Path, outputPath: java.nio.file.Path)
      : ZIO[Any, OrchestratorError, MigrationResult] =
      ZIO.dieMessage("unused in WorkflowNotifierSpec")

    override def runFullMigrationWithProgress(
      sourcePath: java.nio.file.Path,
      outputPath: java.nio.file.Path,
      onProgress: PipelineProgressUpdate => UIO[Unit],
    ): ZIO[Any, OrchestratorError, MigrationResult] =
      ZIO.dieMessage("unused in WorkflowNotifierSpec")

    override def runStep(step: MigrationStep): ZIO[Any, OrchestratorError, StepResult] =
      ZIO.dieMessage("unused in WorkflowNotifierSpec")

    override def runStepStreaming(step: MigrationStep): ZStream[Any, OrchestratorError, StepProgressEvent] =
      ZStream.empty

    override def startMigration(config: MigrationConfig): IO[OrchestratorError, Long] =
      ZIO.dieMessage("unused in WorkflowNotifierSpec")

    override def cancelMigration(runId: Long): IO[OrchestratorError, Unit] =
      cancelledRef.update(_ :+ runId).unit

    override def getRunStatus(runId: Long): IO[PersistenceError, Option[MigrationRunRow]] =
      statusRef.modify { current =>
        current.get(runId) match
          case Some(head :: tail) =>
            (Some(head), current.updated(runId, if tail.nonEmpty then tail else List(head)))
          case Some(Nil)          =>
            (None, current - runId)
          case None               =>
            (None, current)
      }

    override def listRuns(page: Int, pageSize: Int): IO[PersistenceError, List[MigrationRunRow]] =
      ZIO.succeed(listedRuns)

    override def subscribeToProgress(runId: Long): UIO[Dequeue[ProgressUpdate]] =
      progressQueuesRef.get.flatMap(_.get(runId) match
        case Some(queue) => ZIO.succeed(queue)
        case None        => Queue.unbounded[ProgressUpdate])

  private def runRow(id: Long, status: RunStatus, phase: String): MigrationRunRow =
    MigrationRunRow(
      id = id,
      sourceDir = "/src",
      outputDir = "/out",
      status = status,
      startedAt = Instant.EPOCH,
      completedAt = None,
      totalFiles = 10,
      processedFiles = 4,
      successfulConversions = 0,
      failedConversions = 0,
      currentPhase = Some(phase),
      errorMessage = None,
      workflowId = None,
    )

  private def waitForMessages(
    sentRef: Ref[List[TelegramSendMessage]],
    minSize: Int,
    remaining: Int = 10000,
  ): UIO[List[TelegramSendMessage]] =
    sentRef.get.flatMap { messages =>
      if messages.size >= minSize || remaining <= 0 then ZIO.succeed(messages)
      else ZIO.yieldNow *> waitForMessages(sentRef, minSize, remaining - 1)
    }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("WorkflowNotifierSpec")(
    test("list command sends formatted run summary") {
      for
        sentRef      <- Ref.make(List.empty[TelegramSendMessage])
        statusRef    <- Ref.make(Map.empty[Long, List[MigrationRunRow]])
        progressRef  <- Ref.make(Map.empty[Long, Queue[ProgressUpdate]])
        cancelledRef <- Ref.make(List.empty[Long])
        orchestrator  = StubOrchestrator(
                          listedRuns = List(runRow(9L, RunStatus.Running, "Analysis")),
                          statusRef = statusRef,
                          progressQueuesRef = progressRef,
                          cancelledRef = cancelledRef,
                        )
        client        = CapturingTelegramClient(sentRef)
        subsRef      <- Ref.Synchronized.make(Map.empty[(Long, Long), Fiber.Runtime[Nothing, Unit]])
        notifier      = WorkflowNotifierLive(orchestrator, client, subsRef)
        _            <- notifier.notifyCommand(100L, Some(1L), BotCommand.ListRuns)
        sent         <- sentRef.get
      yield assertTrue(
        sent.nonEmpty,
        sent.head.text.contains("Latest runs:"),
        sent.head.text.contains("#9 Running - Analysis"),
      )
    },
    test("status command streams progress and completion notification") {
      for
        sentRef      <- Ref.make(List.empty[TelegramSendMessage])
        progressQ    <- Queue.unbounded[ProgressUpdate]
        statusRef    <- Ref.make(
                          Map(
                            42L -> List(
                              runRow(42L, RunStatus.Running, "Analysis"),
                              runRow(42L, RunStatus.Completed, "Documentation"),
                            )
                          )
                        )
        progressRef  <- Ref.make(Map(42L -> progressQ))
        cancelledRef <- Ref.make(List.empty[Long])
        orchestrator  = StubOrchestrator(
                          listedRuns = Nil,
                          statusRef = statusRef,
                          progressQueuesRef = progressRef,
                          cancelledRef = cancelledRef,
                        )
        client        = CapturingTelegramClient(sentRef)
        subsRef      <- Ref.Synchronized.make(Map.empty[(Long, Long), Fiber.Runtime[Nothing, Unit]])
        notifier      = WorkflowNotifierLive(orchestrator, client, subsRef)
        _            <- notifier.notifyCommand(200L, Some(10L), BotCommand.Status(42L))
        _            <- progressQ.offer(
                          ProgressUpdate(
                            runId = 42L,
                            phase = "Analysis",
                            itemsProcessed = 2,
                            itemsTotal = 10,
                            message = "Processing",
                            timestamp = Instant.EPOCH,
                          )
                        )
        sent         <- waitForMessages(sentRef, minSize = 3)
      yield assertTrue(
        sent.exists(_.text.contains("Run 42: Running")),
        sent.exists(_.text.contains("Run 42 [Analysis] 2/10")),
        sent.exists(_.text.contains("Run 42 completed successfully.")),
      )
    },
    test("cancel command invokes orchestrator and sends acknowledgment") {
      for
        sentRef      <- Ref.make(List.empty[TelegramSendMessage])
        statusRef    <- Ref.make(Map.empty[Long, List[MigrationRunRow]])
        progressRef  <- Ref.make(Map.empty[Long, Queue[ProgressUpdate]])
        cancelledRef <- Ref.make(List.empty[Long])
        orchestrator  = StubOrchestrator(
                          listedRuns = Nil,
                          statusRef = statusRef,
                          progressQueuesRef = progressRef,
                          cancelledRef = cancelledRef,
                        )
        client        = CapturingTelegramClient(sentRef)
        subsRef      <- Ref.Synchronized.make(Map.empty[(Long, Long), Fiber.Runtime[Nothing, Unit]])
        notifier      = WorkflowNotifierLive(orchestrator, client, subsRef)
        _            <- notifier.notifyCommand(300L, Some(11L), BotCommand.Cancel(77L))
        cancelled    <- cancelledRef.get
        sent         <- sentRef.get
      yield assertTrue(
        cancelled == List(77L),
        sent.exists(_.text.contains("Cancellation requested for run 77.")),
      )
    },
    test("invalid command parse error is formatted for Telegram") {
      for
        sentRef      <- Ref.make(List.empty[TelegramSendMessage])
        statusRef    <- Ref.make(Map.empty[Long, List[MigrationRunRow]])
        progressRef  <- Ref.make(Map.empty[Long, Queue[ProgressUpdate]])
        cancelledRef <- Ref.make(List.empty[Long])
        orchestrator  = StubOrchestrator(
                          listedRuns = Nil,
                          statusRef = statusRef,
                          progressQueuesRef = progressRef,
                          cancelledRef = cancelledRef,
                        )
        client        = CapturingTelegramClient(sentRef)
        subsRef      <- Ref.Synchronized.make(Map.empty[(Long, Long), Fiber.Runtime[Nothing, Unit]])
        notifier      = WorkflowNotifierLive(orchestrator, client, subsRef)
        _            <- notifier.notifyParseError(999L, Some(12L), CommandParseError.UnknownCommand("unknown"))
        sent         <- sentRef.get
      yield assertTrue(
        sent.exists(_.text.contains("Unknown command '/unknown'"))
      )
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(10.seconds)
