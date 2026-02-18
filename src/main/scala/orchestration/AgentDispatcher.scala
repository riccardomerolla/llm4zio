package orchestration

import java.time.Instant

import zio.*

import agents.AgentRegistry
import db.{ PersistenceError, TaskArtifactRow, TaskReportRow, TaskRepository }
import llm4zio.core.LlmService

final case class StepDispatchResult(
  agentName: String,
  content: String,
  completedAt: Instant,
)

trait AgentDispatcher:
  def dispatch(
    stepPlan: WorkflowStepPlan,
    taskRunId: Long,
  ): IO[PersistenceError, StepDispatchResult]

object AgentDispatcher:
  def dispatch(
    stepPlan: WorkflowStepPlan,
    taskRunId: Long,
  ): ZIO[AgentDispatcher, PersistenceError, StepDispatchResult] =
    ZIO.serviceWithZIO[AgentDispatcher](_.dispatch(stepPlan, taskRunId))

  val live: ZLayer[TaskRepository & AgentRegistry & LlmService, Nothing, AgentDispatcher] =
    ZLayer.fromFunction(AgentDispatcherLive.apply)

final case class AgentDispatcherLive(
  repository: TaskRepository,
  registry: AgentRegistry,
  llmService: LlmService,
) extends AgentDispatcher:

  override def dispatch(
    stepPlan: WorkflowStepPlan,
    taskRunId: Long,
  ): IO[PersistenceError, StepDispatchResult] =
    val candidates = (stepPlan.assignedAgent.toList ++ stepPlan.fallbackAgents).map(_.trim).filter(_.nonEmpty).distinct
    candidates match
      case Nil => ZIO.fail(PersistenceError.QueryFailed("dispatch", s"No agent assigned for step '${stepPlan.step}'"))
      case _   => dispatchWithFallback(candidates, stepPlan, taskRunId)

  private def dispatchWithFallback(
    candidates: List[String],
    stepPlan: WorkflowStepPlan,
    taskRunId: Long,
  ): IO[PersistenceError, StepDispatchResult] =
    candidates match
      case Nil             =>
        ZIO.fail(
          PersistenceError.QueryFailed(
            "dispatch",
            s"All assigned/fallback agents failed for step '${stepPlan.step}'",
          )
        )
      case agentName :: xs =>
        dispatchWithRetries(agentName, stepPlan, taskRunId, stepPlan.retryLimit)
          .orElse(dispatchWithFallback(xs, stepPlan, taskRunId))

  private def dispatchWithRetries(
    agentName: String,
    stepPlan: WorkflowStepPlan,
    taskRunId: Long,
    retryLimit: Int,
  ): IO[PersistenceError, StepDispatchResult] =
    dispatchOnce(agentName, stepPlan, taskRunId).retry(Schedule.recurs(Math.max(0, retryLimit)))

  private def dispatchOnce(
    agentName: String,
    stepPlan: WorkflowStepPlan,
    taskRunId: Long,
  ): IO[PersistenceError, StepDispatchResult] =
    for
      startedAtNanos  <- Clock.nanoTime
      attemptedName   <- ZIO.succeed(agentName)
      result          <- (for
                           now          <- Clock.instant
                           agentInfo    <- registry.findByName(attemptedName).flatMap {
                                             case Some(value) => ZIO.succeed(value)
                                             case None        =>
                                               ZIO.fail(
                                                 PersistenceError.QueryFailed(
                                                   "dispatch",
                                                   s"Agent '$attemptedName' not found in registry",
                                                 )
                                               )
                                           }
                           customPrompt <- repository
                                             .getCustomAgentByName(agentInfo.name)
                                             .map(_.map(_.systemPrompt).filter(_.trim.nonEmpty))
                           run          <- repository.getRun(taskRunId).someOrFail(PersistenceError.NotFound("task_runs", taskRunId))
                           prompt        = buildPrompt(
                                             systemPrompt = customPrompt,
                                             step = stepPlan.step,
                                             taskRunId = taskRunId,
                                             workflowId = run.workflowId,
                                             currentPhase = run.currentPhase,
                                           )
                           response     <- llmService
                                             .execute(prompt)
                                             .mapError(err => PersistenceError.QueryFailed("llm.execute", err.toString))
                           completedAt  <- Clock.instant
                           _            <- repository.saveReport(
                                             TaskReportRow(
                                               id = 0L,
                                               taskRunId = taskRunId,
                                               stepName = stepPlan.step,
                                               reportType = "markdown",
                                               content = response.content,
                                               createdAt = completedAt,
                                             )
                                           )
                           _            <- repository.saveArtifact(
                                             TaskArtifactRow(
                                               id = 0L,
                                               taskRunId = taskRunId,
                                               stepName = stepPlan.step,
                                               key = "step.agent",
                                               value = agentInfo.name,
                                               createdAt = completedAt,
                                             )
                                           )
                           _            <- repository.saveArtifact(
                                             TaskArtifactRow(
                                               id = 0L,
                                               taskRunId = taskRunId,
                                               stepName = stepPlan.step,
                                               key = "step.nodeId",
                                               value = stepPlan.nodeId,
                                               createdAt = completedAt,
                                             )
                                           )
                         yield StepDispatchResult(
                           agentName = agentInfo.name,
                           content = response.content,
                           completedAt = completedAt,
                         )).exit
      finishedAtNanos <- Clock.nanoTime
      latencyMs        = Math.max(0L, (finishedAtNanos - startedAtNanos) / 1000000L)
      _               <- result match
                           case Exit.Success(value) =>
                             registry.recordInvocation(value.agentName, success = true, latencyMs = latencyMs) *>
                               registry.updateHealth(value.agentName, success = true, message = Some("Execution completed"))
                           case Exit.Failure(_)     =>
                             registry.recordInvocation(attemptedName, success = false, latencyMs = latencyMs) *>
                               registry.updateHealth(attemptedName, success = false, message = Some("Execution failed"))
      value           <- result match
                           case Exit.Success(v) => ZIO.succeed(v)
                           case Exit.Failure(c) => ZIO.failCause(c)
    yield value

  private def buildPrompt(
    systemPrompt: Option[String],
    step: String,
    taskRunId: Long,
    workflowId: Option[Long],
    currentPhase: Option[String],
  ): String =
    val custom = systemPrompt.map(_.trim).filter(_.nonEmpty).map(v => s"$v\n\n").getOrElse("")
    s"""${custom}Execute workflow step.
       |
       |- taskRunId: $taskRunId
       |- workflowId: ${workflowId.map(_.toString).getOrElse("n/a")}
       |- step: $step
       |- currentPhase: ${currentPhase.getOrElse("n/a")}
       |
       |Return a concise markdown result for this step execution.
       |""".stripMargin
