package web.controllers

import java.time.Instant

import zio.*
import zio.http.*
import zio.json.EncoderOps
import zio.test.*

import db.*
import models.*
import orchestration.*

object RunsControllerSpec extends ZIOSpecDefault:

  private val sampleRun   = MigrationRunRow(
    id = 42L,
    sourceDir = "/tmp/source",
    outputDir = "/tmp/output",
    status = RunStatus.Running,
    startedAt = Instant.parse("2026-02-08T00:00:00Z"),
    completedAt = None,
    totalFiles = 10,
    processedFiles = 2,
    successfulConversions = 1,
    failedConversions = 0,
    currentPhase = Some("analysis"),
    errorMessage = None,
  )
  private val failedRun   = sampleRun.copy(
    id = 43L,
    status = RunStatus.Failed,
    currentPhase = Some("Failed"),
    errorMessage = Some("analysis failed"),
  )
  private val workflowRun = sampleRun.copy(
    id = 44L,
    workflowId = Some(7L),
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("RunsControllerSpec")(
    test("GET /runs returns runs list") {
      for
        orchestrator <- TestOrchestrator.make(runs = List(sampleRun))
        repo         <- TestRepository.make()
        controller    = RunsControllerLive(orchestrator, repo, TestWorkflowService.empty)
        response     <- controller.routes.runZIO(Request.get(URL.decode("/runs?page=1&pageSize=20").toOption.get))
        body         <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("Migration Runs"),
        body.contains("42"),
      )
    },
    test("POST /runs starts migration and redirects to dashboard") {
      for
        orchestrator <- TestOrchestrator.make(startedRunId = 77L)
        repo         <- TestRepository.make()
        controller    = RunsControllerLive(orchestrator, repo, TestWorkflowService.empty)
        request       = Request
                          .post(
                            "/runs",
                            Body.fromString("sourceDir=%2Ftmp%2Fsource&outputDir=%2Ftmp%2Foutput&dryRun=on"),
                          )
        response     <- controller.routes.runZIO(request)
        started      <- orchestrator.lastStartedConfig
      yield assertTrue(
        response.status == Status.SeeOther,
        response.rawHeader("location").contains("/"),
        started.exists(_.dryRun),
      )
    },
    test("POST /runs propagates business logic extractor feature from settings") {
      for
        orchestrator <- TestOrchestrator.make(startedRunId = 77L)
        repo         <- TestRepository.make(
                          settings = List(
                            SettingRow("features.enableBusinessLogicExtractor", "true", Instant.parse("2026-02-08T00:00:00Z"))
                          )
                        )
        controller    = RunsControllerLive(orchestrator, repo, TestWorkflowService.empty)
        request       = Request.post(
                          "/runs",
                          Body.fromString("sourceDir=%2Ftmp%2Fsource&outputDir=%2Ftmp%2Foutput&dryRun=on"),
                        )
        _            <- controller.routes.runZIO(request)
        started      <- orchestrator.lastStartedConfig
      yield assertTrue(
        started.exists(_.dryRun),
        started.exists(_.enableBusinessLogicExtractor),
      )
    },
    test("POST /runs/:id/retry resumes from latest failed phase") {
      val failedProgress = PhaseProgressRow(
        id = 1L,
        runId = failedRun.id,
        phase = "analysis",
        status = "Failed",
        itemTotal = 10,
        itemProcessed = 4,
        errorCount = 1,
        updatedAt = Instant.parse("2026-02-08T00:10:00Z"),
      )
      for
        orchestrator <- TestOrchestrator.make(runs = List(failedRun), startedRunId = 88L)
        repo         <- TestRepository.make(
                          progress = Map((failedRun.id, "analysis") -> failedProgress)
                        )
        controller    = RunsControllerLive(orchestrator, repo, TestWorkflowService.empty)
        response     <- controller.routes.runZIO(Request.post(s"/runs/${failedRun.id}/retry", Body.empty))
        started      <- orchestrator.lastStartedConfig
      yield assertTrue(
        response.status == Status.SeeOther,
        response.rawHeader("location").contains("/"),
        started.flatMap(_.retryFromRunId).contains(failedRun.id),
        started.flatMap(_.retryFromStep).contains(MigrationStep.Analysis),
      )
    },
    test("GET /runs/:id reads only workflow-selected phases") {
      val workflow = WorkflowDefinition(
        id = Some(7L),
        name = "Partial",
        steps = List(MigrationStep.Discovery, MigrationStep.Analysis, MigrationStep.Mapping),
        isBuiltin = false,
      )
      for
        orchestrator <- TestOrchestrator.make(runs = List(workflowRun))
        repo         <- TestRepository.make()
        controller    = RunsControllerLive(orchestrator, repo, TestWorkflowService.withWorkflows(workflow))
        response     <- controller.routes.runZIO(Request.get(s"/runs/${workflowRun.id}"))
        queried      <- repo.queriedPhases
      yield assertTrue(
        response.status == Status.Ok,
        queried == List(
          (workflowRun.id, "discovery"),
          (workflowRun.id, "analysis"),
          (workflowRun.id, "mapping"),
        ),
      )
    },
    test("DELETE /runs/:id cancels migration") {
      for
        orchestrator <- TestOrchestrator.make()
        repo         <- TestRepository.make()
        controller    = RunsControllerLive(orchestrator, repo, TestWorkflowService.empty)
        response     <- controller.routes.runZIO(Request.delete("/runs/42"))
        cancelled    <- orchestrator.lastCancelledRunId
      yield assertTrue(
        response.status == Status.NoContent,
        cancelled.contains(42L),
      )
    },
    test("GET /runs/:id/progress returns SSE stream") {
      for
        event        <- Clock.instant.map(now =>
                          ProgressUpdate(1L, "analysis", 2, 10, "working-working-working-working-working", now)
                        )
        orchestrator <- TestOrchestrator.make(
                          runs = List(sampleRun.copy(id = 1L)),
                          progress = Map(1L -> event),
                        )
        repo         <- TestRepository.make()
        controller    = RunsControllerLive(orchestrator, repo, TestWorkflowService.empty)
        response     <- controller.routes.runZIO(Request.get("/runs/1/progress"))
        sseFrame      = RunsController.toSseData(event)
      yield assertTrue(
        response.status == Status.Ok,
        response.hasContentType("text/event-stream"),
        sseFrame.startsWith("data: "),
        sseFrame.endsWith("\n\n"),
        sseFrame.contains(event.toJson),
      )
    },
    test("toSseEvent formats named SSE events") {
      val frame = RunsController.toSseEvent("workflow-diagram", "<div>ok</div>")
      assertTrue(
        frame.startsWith("event: workflow-diagram\n"),
        frame.contains("data: <div>ok</div>"),
        frame.endsWith("\n\n"),
      )
    },
  )

  final private case class TestOrchestrator(
    runsRef: Ref[List[MigrationRunRow]],
    startedRef: Ref[Option[MigrationConfig]],
    cancelledRef: Ref[Option[Long]],
    startedRunId: Long,
    progress: Map[Long, ProgressUpdate],
  ) extends MigrationOrchestrator:

    def lastStartedConfig: UIO[Option[MigrationConfig]] = startedRef.get
    def lastCancelledRunId: UIO[Option[Long]]           = cancelledRef.get

    override def runFullMigration(sourcePath: java.nio.file.Path, outputPath: java.nio.file.Path)
      : ZIO[Any, OrchestratorError, MigrationResult] =
      ZIO.dieMessage("unused in RunsControllerSpec")

    override def runFullMigrationWithProgress(
      sourcePath: java.nio.file.Path,
      outputPath: java.nio.file.Path,
      onProgress: PipelineProgressUpdate => UIO[Unit],
    ): ZIO[Any, OrchestratorError, MigrationResult] =
      ZIO.dieMessage("unused in RunsControllerSpec")

    override def runStep(step: MigrationStep): ZIO[Any, OrchestratorError, StepResult] =
      ZIO.dieMessage("unused in RunsControllerSpec")

    override def runStepStreaming(
      step: MigrationStep
    ): zio.stream.ZStream[Any, OrchestratorError, StepProgressEvent] =
      zio.stream.ZStream.empty

    override def startMigration(config: MigrationConfig): IO[OrchestratorError, Long] =
      startedRef.set(Some(config)).as(startedRunId)

    override def cancelMigration(runId: Long): IO[OrchestratorError, Unit] =
      cancelledRef.set(Some(runId)).unit

    override def getRunStatus(runId: Long): IO[PersistenceError, Option[MigrationRunRow]] =
      runsRef.get.map(_.find(_.id == runId))

    override def listRuns(page: Int, pageSize: Int): IO[PersistenceError, List[MigrationRunRow]] =
      runsRef.get

    override def subscribeToProgress(runId: Long): UIO[Dequeue[ProgressUpdate]] =
      progress.get(runId) match
        case Some(update) =>
          for
            queue <- Queue.unbounded[ProgressUpdate]
            _     <- (queue.offer(update) *> queue.shutdown).forkDaemon
          yield queue
        case None         => Queue.unbounded[ProgressUpdate]

  private object TestOrchestrator:
    def make(
      runs: List[MigrationRunRow] = List(sampleRun),
      startedRunId: Long = 42L,
      progress: Map[Long, ProgressUpdate] = Map.empty,
    ): UIO[TestOrchestrator] =
      for
        runsRef      <- Ref.make(runs)
        startedRef   <- Ref.make(Option.empty[MigrationConfig])
        cancelledRef <- Ref.make(Option.empty[Long])
      yield TestOrchestrator(runsRef, startedRef, cancelledRef, startedRunId, progress)

  final private case class TestRepository(
    progressRows: Ref[Map[(Long, String), PhaseProgressRow]],
    settingsRows: Ref[List[SettingRow]],
    queriedPhasesRef: Ref[List[(Long, String)]],
  ) extends MigrationRepository:

    def queriedPhases: UIO[List[(Long, String)]] = queriedPhasesRef.get

    override def getProgress(runId: Long, phase: String): IO[PersistenceError, Option[PhaseProgressRow]] =
      queriedPhasesRef.update(_ :+ (runId, phase)) *> progressRows.get.map(_.get((runId, phase)))

    override def createRun(run: MigrationRunRow): IO[PersistenceError, Long]                    =
      ZIO.dieMessage("unused in RunsControllerSpec")
    override def updateRun(run: MigrationRunRow): IO[PersistenceError, Unit]                    =
      ZIO.dieMessage("unused in RunsControllerSpec")
    override def getRun(id: Long): IO[PersistenceError, Option[MigrationRunRow]]                =
      ZIO.dieMessage("unused in RunsControllerSpec")
    override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[MigrationRunRow]] =
      ZIO.dieMessage("unused in RunsControllerSpec")
    override def deleteRun(id: Long): IO[PersistenceError, Unit]                                =
      ZIO.dieMessage("unused in RunsControllerSpec")
    override def saveFiles(files: List[CobolFileRow]): IO[PersistenceError, Unit]               =
      ZIO.dieMessage("unused in RunsControllerSpec")
    override def getFilesByRun(runId: Long): IO[PersistenceError, List[CobolFileRow]]           =
      ZIO.dieMessage("unused in RunsControllerSpec")
    override def saveAnalysis(analysis: CobolAnalysisRow): IO[PersistenceError, Long]           =
      ZIO.dieMessage("unused in RunsControllerSpec")
    override def getAnalysesByRun(runId: Long): IO[PersistenceError, List[CobolAnalysisRow]]    =
      ZIO.dieMessage("unused in RunsControllerSpec")
    override def saveDependencies(deps: List[DependencyRow]): IO[PersistenceError, Unit]        =
      ZIO.dieMessage("unused in RunsControllerSpec")
    override def getDependenciesByRun(runId: Long): IO[PersistenceError, List[DependencyRow]]   =
      ZIO.dieMessage("unused in RunsControllerSpec")
    override def saveProgress(p: PhaseProgressRow): IO[PersistenceError, Long]                  =
      ZIO.dieMessage("unused in RunsControllerSpec")
    override def updateProgress(p: PhaseProgressRow): IO[PersistenceError, Unit]                =
      ZIO.dieMessage("unused in RunsControllerSpec")
    override def getAllSettings: IO[PersistenceError, List[SettingRow]]                         = settingsRows.get
    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]              = ZIO.none
    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]          = ZIO.unit

  private object TestRepository:
    def make(
      progress: Map[(Long, String), PhaseProgressRow] = Map.empty,
      settings: List[SettingRow] = Nil,
    ): UIO[TestRepository] =
      for
        progressRef <- Ref.make(progress)
        settingsRef <- Ref.make(settings)
        queriedRef  <- Ref.make(List.empty[(Long, String)])
      yield TestRepository(progressRef, settingsRef, queriedRef)

  private object TestWorkflowService:
    val empty: WorkflowService = new WorkflowService:
      override def createWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Long] =
        ZIO.fail(WorkflowServiceError.ValidationFailed(List("unsupported in test")))

      override def getWorkflow(id: Long): IO[WorkflowServiceError, Option[WorkflowDefinition]] =
        ZIO.succeed(None)

      override def getWorkflowByName(name: String): IO[WorkflowServiceError, Option[WorkflowDefinition]] =
        ZIO.succeed(None)

      override def listWorkflows: IO[WorkflowServiceError, List[WorkflowDefinition]] =
        ZIO.succeed(Nil)

      override def updateWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Unit] =
        ZIO.fail(WorkflowServiceError.ValidationFailed(List("unsupported in test")))

      override def deleteWorkflow(id: Long): IO[WorkflowServiceError, Unit] =
        ZIO.fail(WorkflowServiceError.ValidationFailed(List("unsupported in test")))

    def withWorkflows(workflows: WorkflowDefinition*): WorkflowService =
      val byId = workflows.flatMap(workflow => workflow.id.map(_ -> workflow)).toMap
      new WorkflowService:
        override def createWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Long] =
          ZIO.fail(WorkflowServiceError.ValidationFailed(List("unsupported in test")))

        override def getWorkflow(id: Long): IO[WorkflowServiceError, Option[WorkflowDefinition]] =
          ZIO.succeed(byId.get(id))

        override def getWorkflowByName(name: String): IO[WorkflowServiceError, Option[WorkflowDefinition]] =
          ZIO.succeed(workflows.find(_.name == name))

        override def listWorkflows: IO[WorkflowServiceError, List[WorkflowDefinition]] =
          ZIO.succeed(workflows.toList)

        override def updateWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Unit] =
          ZIO.fail(WorkflowServiceError.ValidationFailed(List("unsupported in test")))

        override def deleteWorkflow(id: Long): IO[WorkflowServiceError, Unit] =
          ZIO.fail(WorkflowServiceError.ValidationFailed(List("unsupported in test")))
