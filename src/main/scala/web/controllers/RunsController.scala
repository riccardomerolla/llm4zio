package web.controllers

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

import zio.*
import zio.http.*
import zio.json.*
import zio.stream.*

import db.*
import models.*
import orchestration.{ MigrationOrchestrator, WorkflowService, WorkflowServiceError }
import web.ErrorHandlingMiddleware
import web.views.HtmlViews

trait RunsController:
  def routes: Routes[Any, Response]

object RunsController:

  def routes: ZIO[RunsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[RunsController](_.routes)

  val live: ZLayer[MigrationOrchestrator & MigrationRepository & WorkflowService, Nothing, RunsController] =
    ZLayer.fromFunction(RunsControllerLive.apply)

  def toSseData(update: ProgressUpdate): String =
    s"data: ${update.toJson}\n\n"

final case class RunsControllerLive(
  orchestrator: MigrationOrchestrator,
  repository: MigrationRepository,
  workflowService: WorkflowService,
) extends RunsController:

  private val knownPhases = List(
    "discovery",
    "analysis",
    "mapping",
    "business-logic-extraction",
    "transformation",
    "validation",
    "documentation",
  )

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "runs"                           -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          page     <- parseIntQuery(req, "page", 1)
          pageSize <- parseIntQuery(req, "pageSize", 20)
          runs     <- orchestrator.listRuns(page, pageSize)
        yield html(HtmlViews.runsList(runs, page, pageSize))
      }
    },
    Method.GET / "runs" / "new"                   -> handler {
      ErrorHandlingMiddleware.fromOrchestrator {
        for
          workflows <- workflowService.listWorkflows.mapError(workflowAsOrchestrator("listWorkflows"))
        yield html(HtmlViews.runForm(workflows))
      }
    },
    Method.GET / "runs" / long("id")              -> handler { (runId: Long, _: Request) =>
      ErrorHandlingMiddleware.handle {
        for
          run          <- orchestrator
                            .getRunStatus(runId)
                            .someOrFail(PersistenceError.NotFound("migration_runs", runId))
          phaseRows    <- ZIO.foreach(knownPhases)(phase => repository.getProgress(runId, phase)).map(_.flatten)
          workflowName <- workflowNameForRun(run).mapError(err =>
                            OrchestratorError.StateFailed(StateError.ReadError(runId.toString, err.toString))
                          )
        yield html(HtmlViews.runDetail(run, phaseRows, workflowName))
      }
    },
    Method.POST / "runs"                          -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromOrchestrator {
        for
          form        <- parseForm(req)
          sourceDir   <- required(form, "sourceDir")
          outputDir   <- required(form, "outputDir")
          dryRun       = form.get("dryRun").exists(_.equalsIgnoreCase("on"))
          workflowId  <- parseWorkflowId(form)
          _           <- validateWorkflowExists(workflowId)
          settings    <- repository.getAllSettings
                           .map(_.map(s => s.key -> s.value).toMap)
                           .catchAll(_ => ZIO.succeed(Map.empty[String, String]))
          migrationCfg = buildConfigFromDefaults(settings, sourceDir, outputDir, dryRun, workflowId)
          _           <- orchestrator.startMigration(migrationCfg)
        yield Response(
          status = Status.SeeOther,
          headers = Headers(Header.Custom("Location", "/")),
        )
      }
    },
    Method.POST / "runs" / long("id") / "retry"   -> handler { (runId: Long, _: Request) =>
      ErrorHandlingMiddleware.fromOrchestrator {
        for
          run         <- orchestrator
                           .getRunStatus(runId)
                           .mapError(err => OrchestratorError.StateFailed(StateError.ReadError(runId.toString, err.toString)))
                           .someOrFail(OrchestratorError.StateFailed(StateError.StateNotFound(runId.toString)))
          _           <- ZIO
                           .fail(
                             OrchestratorError.Interrupted(
                               s"Run $runId is not failed (status=${run.status}); only failed runs can be retried"
                             )
                           )
                           .unless(run.status == RunStatus.Failed)
          phaseRows   <- ZIO
                           .foreach(knownPhases)(phase => repository.getProgress(runId, phase))
                           .map(_.flatten)
                           .mapError(err =>
                             OrchestratorError.StateFailed(StateError.ReadError(runId.toString, err.toString))
                           )
          failedStep  <- ZIO
                           .fromOption(latestFailedStep(phaseRows, run))
                           .orElseFail(
                             OrchestratorError.StateFailed(
                               StateError.InvalidState(runId.toString, "Unable to determine failed phase for retry")
                             )
                           )
          settings    <- repository.getAllSettings
                           .map(_.map(s => s.key -> s.value).toMap)
                           .catchAll(_ => ZIO.succeed(Map.empty[String, String]))
          migrationCfg = buildConfigFromDefaults(
                           settings,
                           run.sourceDir,
                           run.outputDir,
                           dryRun = false,
                           workflowId = run.workflowId,
                         ).copy(
                           retryFromRunId = Some(runId),
                           retryFromStep = Some(failedStep),
                         )
          _           <- orchestrator.startMigration(migrationCfg)
        yield Response(
          status = Status.SeeOther,
          headers = Headers(Header.Custom("Location", "/")),
        )
      }
    },
    Method.DELETE / "runs" / long("id")           -> handler { (runId: Long, _: Request) =>
      ErrorHandlingMiddleware.fromOrchestrator {
        orchestrator.cancelMigration(runId).as(Response.status(Status.NoContent))
      }
    },
    Method.GET / "runs" / long("id") / "progress" -> handler { (runId: Long, _: Request) =>
      val response =
        for
          queue     <- orchestrator.subscribeToProgress(runId)
          liveEvents = ZStream
                         .fromQueue(queue)
                         .map(RunsController.toSseData)
          heartbeat  = ZStream.repeatWithSchedule(": heartbeat\n\n", Schedule.spaced(15.seconds))
          stream     = liveEvents.mergeHaltLeft(heartbeat)
        yield Response(
          body = Body.fromCharSequenceStreamChunked(stream),
          headers =
            Headers(Header.Custom("Content-Type", "text/event-stream"), Header.Custom("Cache-Control", "no-cache")),
        )

      response
    },
  )

  private def parseIntQuery(req: Request, key: String, default: Int): IO[PersistenceError, Int] =
    ZIO.succeed(req.queryParam(key).flatMap(_.toIntOption).getOrElse(default))

  private def parseForm(req: Request): IO[OrchestratorError, Map[String, String]] =
    req.body.asString.map { body =>
      body
        .split("&")
        .toList
        .flatMap { kv =>
          kv.split("=", 2).toList match
            case key :: value :: Nil =>
              Some(urlDecode(key) -> urlDecode(value))
            case key :: Nil          =>
              Some(urlDecode(key) -> "")
            case _                   => None
        }
        .toMap
    }.mapError(err => OrchestratorError.Interrupted(err.getMessage))

  private def required(form: Map[String, String], key: String): IO[OrchestratorError, String] =
    ZIO
      .fromOption(form.get(key).map(_.trim).filter(_.nonEmpty))
      .orElseFail(OrchestratorError.Interrupted(s"Missing required form field: $key"))

  private def buildConfigFromDefaults(
    s: Map[String, String],
    sourceDir: String,
    outputDir: String,
    dryRun: Boolean,
    workflowId: Option[Long],
  ): MigrationConfig =
    val defaults   = AIProviderConfig()
    val provider   = s.get("ai.provider").flatMap(parseProvider).getOrElse(defaults.provider)
    val aiProvider = AIProviderConfig(
      provider = provider,
      model = s.getOrElse("ai.model", defaults.model),
      baseUrl = s.get("ai.baseUrl").filter(_.nonEmpty).orElse(AIProvider.defaultBaseUrl(provider)),
      apiKey = s.get("ai.apiKey").filter(_.nonEmpty),
      timeout = s.get("ai.timeout").flatMap(_.toLongOption).map(zio.Duration.fromSeconds).getOrElse(defaults.timeout),
      maxRetries = s.get("ai.maxRetries").flatMap(_.toIntOption).getOrElse(defaults.maxRetries),
      requestsPerMinute =
        s.get("ai.requestsPerMinute").flatMap(_.toIntOption).getOrElse(defaults.requestsPerMinute),
      burstSize = s.get("ai.burstSize").flatMap(_.toIntOption).getOrElse(defaults.burstSize),
      acquireTimeout =
        s.get(
          "ai.acquireTimeout"
        ).flatMap(_.toLongOption).map(zio.Duration.fromSeconds).getOrElse(defaults.acquireTimeout),
      temperature = s.get("ai.temperature").flatMap(_.toDoubleOption),
      maxTokens = s.get("ai.maxTokens").flatMap(_.toIntOption),
    )

    val excludePatterns = s
      .get("discovery.excludePatterns")
      .map(_.split("\n").map(_.trim).filter(_.nonEmpty).toList)
      .getOrElse(MigrationConfig(sourceDir = Paths.get(""), outputDir = Paths.get("")).discoveryExcludePatterns)

    MigrationConfig(
      sourceDir = Paths.get(sourceDir),
      outputDir = Paths.get(outputDir),
      aiProvider = Some(aiProvider),
      discoveryMaxDepth = s.get("discovery.maxDepth").flatMap(_.toIntOption).getOrElse(25),
      discoveryExcludePatterns = excludePatterns,
      parallelism = s.get("processing.parallelism").flatMap(_.toIntOption).getOrElse(4),
      batchSize = s.get("processing.batchSize").flatMap(_.toIntOption).getOrElse(10),
      enableCheckpointing = s.get("features.enableCheckpointing").map(_ == "true").getOrElse(true),
      enableBusinessLogicExtractor = s.get("features.enableBusinessLogicExtractor").map(_ == "true").getOrElse(false),
      verbose = s.get("features.verbose").map(_ == "true").getOrElse(false),
      workflowId = workflowId,
      dryRun = dryRun,
      basePackage = s.get("project.basePackage").filter(_.nonEmpty).getOrElse("com.example"),
      projectName = s.get("project.name").filter(_.nonEmpty),
      projectVersion = s.get("project.version").filter(_.nonEmpty).getOrElse("0.0.1-SNAPSHOT"),
      maxCompileRetries = s.get("project.maxCompileRetries").flatMap(_.toIntOption).getOrElse(3),
    )

  private def parseWorkflowId(form: Map[String, String]): IO[OrchestratorError, Option[Long]] =
    form.get("workflowId").map(_.trim).filter(_.nonEmpty) match
      case None      => ZIO.succeed(None)
      case Some(raw) =>
        ZIO
          .fromOption(raw.toLongOption)
          .orElseFail(OrchestratorError.Interrupted(s"Invalid workflow id: $raw"))
          .map(Some(_))

  private def validateWorkflowExists(workflowId: Option[Long]): IO[OrchestratorError, Unit] =
    workflowId match
      case None     => ZIO.unit
      case Some(id) =>
        workflowService
          .getWorkflow(id)
          .mapError(workflowAsOrchestrator("getWorkflow"))
          .flatMap {
            case Some(_) => ZIO.unit
            case None    => ZIO.fail(OrchestratorError.Interrupted(s"Unknown workflow id: $id"))
          }

  private def workflowNameForRun(run: MigrationRunRow): IO[PersistenceError, Option[String]] =
    run.workflowId match
      case None     => ZIO.succeed(None)
      case Some(id) =>
        workflowService
          .getWorkflow(id)
          .map {
            case Some(workflow) => Some(workflow.name)
            case None           => Some(s"Workflow #$id")
          }
          .mapError {
            case WorkflowServiceError.PersistenceFailed(err)             => err
            case WorkflowServiceError.ValidationFailed(errors)           =>
              PersistenceError.QueryFailed("workflowNameForRun", errors.mkString("; "))
            case WorkflowServiceError.StepsDecodingFailed(workflow, why) =>
              PersistenceError.QueryFailed("workflowNameForRun", s"Invalid workflow '$workflow': $why")
          }

  private def parseProvider(value: String): Option[AIProvider] =
    value match
      case "GeminiCli" => Some(AIProvider.GeminiCli)
      case "GeminiApi" => Some(AIProvider.GeminiApi)
      case "OpenAi"    => Some(AIProvider.OpenAi)
      case "Anthropic" => Some(AIProvider.Anthropic)
      case _           => None

  private def latestFailedStep(phases: List[PhaseProgressRow], run: MigrationRunRow): Option[MigrationStep] =
    val fromProgress = phases
      .filter(_.status.equalsIgnoreCase("Failed"))
      .sortBy(_.updatedAt.toEpochMilli)
      .lastOption
      .flatMap(phaseToStep)

    fromProgress.orElse(run.currentPhase.flatMap(parseStep))

  private def phaseToStep(phase: PhaseProgressRow): Option[MigrationStep] =
    parseStep(phase.phase)

  private def parseStep(value: String): Option[MigrationStep] =
    value.trim.toLowerCase match
      case "discovery"      => Some(MigrationStep.Discovery)
      case "analysis"       => Some(MigrationStep.Analysis)
      case "mapping"        => Some(MigrationStep.Mapping)
      case "transformation" => Some(MigrationStep.Transformation)
      case "validation"     => Some(MigrationStep.Validation)
      case "documentation"  => Some(MigrationStep.Documentation)
      case _                => None

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def workflowAsOrchestrator(action: String)(error: WorkflowServiceError): OrchestratorError =
    error match
      case WorkflowServiceError.PersistenceFailed(err)             =>
        OrchestratorError.StateFailed(StateError.WriteError("workflow", s"$action failed: $err"))
      case WorkflowServiceError.ValidationFailed(errors)           =>
        OrchestratorError.Interrupted(errors.mkString("; "))
      case WorkflowServiceError.StepsDecodingFailed(workflow, why) =>
        OrchestratorError.StateFailed(
          StateError.InvalidState(workflow, s"$action failed: invalid workflow steps payload ($why)")
        )

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)
