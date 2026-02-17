package orchestration

import zio.*
import zio.stream.ZStream

import gateway.MessageRouter
import gateway.models.SessionScopeStrategy
import models.*

/** Central control plane for workflow coordination and agent routing
  */
trait OrchestratorControlPlane:
  /** Start a new workflow and allocate resources
    */
  def startWorkflow(
    runId: String,
    workflowId: Long,
    definition: WorkflowDefinition,
  ): ZIO[Any, ControlPlaneError, String]

  /** Get the next step and assigned agent for a run
    */
  def routeStep(
    runId: String,
    step: MigrationStep,
    capabilities: List[AgentCapability],
  ): ZIO[Any, ControlPlaneError, String]

  /** Allocate a parallelism slot for a run
    */
  def allocateResource(runId: String): ZIO[Any, ControlPlaneError, Int]

  /** Release a parallelism slot
    */
  def releaseResource(runId: String, slot: Int): ZIO[Any, ControlPlaneError, Unit]

  /** Publish a control plane event
    */
  def publishEvent(event: ControlPlaneEvent): ZIO[Any, ControlPlaneError, Unit]

  /** Subscribe to events for a specific run
    */
  def subscribeToEvents(runId: String): ZIO[Scope, Nothing, Dequeue[ControlPlaneEvent]]

  /** Get all active runs
    */
  def getActiveRuns: ZIO[Any, ControlPlaneError, List[ActiveRun]]

  /** Get active run state
    */
  def getRunState(runId: String): ZIO[Any, ControlPlaneError, Option[ActiveRun]]

  /** Update run state
    */
  def updateRunState(runId: String, newState: WorkflowRunState): ZIO[Any, ControlPlaneError, Unit]

  /** Process control command
    */
  def executeCommand(command: ControlCommand): ZIO[Any, ControlPlaneError, Unit]

  /** Get current resource allocation state
    */
  def getResourceState: ZIO[Any, ControlPlaneError, ResourceAllocationState]

  /** Get current agent execution monitor snapshot
    */
  def getAgentMonitorSnapshot: ZIO[Any, ControlPlaneError, AgentMonitorSnapshot]

  /** Get agent execution history timeline (most recent first)
    */
  def getAgentExecutionHistory(limit: Int): ZIO[Any, ControlPlaneError, List[AgentExecutionEvent]]

  /** Pause active execution for specific agent
    */
  def pauseAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit]

  /** Resume paused execution for specific agent
    */
  def resumeAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit]

  /** Abort active execution for specific agent
    */
  def abortAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit]

object OrchestratorControlPlane:

  def startWorkflow(
    runId: String,
    workflowId: Long,
    definition: WorkflowDefinition,
  ): ZIO[OrchestratorControlPlane, ControlPlaneError, String] =
    ZIO.serviceWithZIO[OrchestratorControlPlane](_.startWorkflow(runId, workflowId, definition))

  def routeStep(
    runId: String,
    step: MigrationStep,
    capabilities: List[AgentCapability],
  ): ZIO[OrchestratorControlPlane, ControlPlaneError, String] =
    ZIO.serviceWithZIO[OrchestratorControlPlane](_.routeStep(runId, step, capabilities))

  def allocateResource(runId: String): ZIO[OrchestratorControlPlane, ControlPlaneError, Int] =
    ZIO.serviceWithZIO[OrchestratorControlPlane](_.allocateResource(runId))

  def releaseResource(runId: String, slot: Int): ZIO[OrchestratorControlPlane, ControlPlaneError, Unit] =
    ZIO.serviceWithZIO[OrchestratorControlPlane](_.releaseResource(runId, slot))

  def publishEvent(event: ControlPlaneEvent): ZIO[OrchestratorControlPlane, ControlPlaneError, Unit] =
    ZIO.serviceWithZIO[OrchestratorControlPlane](_.publishEvent(event))

  def subscribeToEvents(runId: String): ZIO[OrchestratorControlPlane & Scope, Nothing, Dequeue[ControlPlaneEvent]] =
    ZIO.serviceWithZIO[OrchestratorControlPlane](_.subscribeToEvents(runId))

  def eventStream(runId: String): ZStream[OrchestratorControlPlane & Scope, Nothing, ControlPlaneEvent] =
    ZStream.unwrap(subscribeToEvents(runId).map(queue => ZStream.fromQueue(queue)))

  def getActiveRuns: ZIO[OrchestratorControlPlane, ControlPlaneError, List[ActiveRun]] =
    ZIO.serviceWithZIO[OrchestratorControlPlane](_.getActiveRuns)

  def getRunState(runId: String): ZIO[OrchestratorControlPlane, ControlPlaneError, Option[ActiveRun]] =
    ZIO.serviceWithZIO[OrchestratorControlPlane](_.getRunState(runId))

  def updateRunState(runId: String, newState: WorkflowRunState)
    : ZIO[OrchestratorControlPlane, ControlPlaneError, Unit] =
    ZIO.serviceWithZIO[OrchestratorControlPlane](_.updateRunState(runId, newState))

  def executeCommand(command: ControlCommand): ZIO[OrchestratorControlPlane, ControlPlaneError, Unit] =
    ZIO.serviceWithZIO[OrchestratorControlPlane](_.executeCommand(command))

  def attachMessageRouterMiddleware(
    runId: String,
    channelName: String,
    strategy: SessionScopeStrategy = SessionScopeStrategy.PerRun,
  ): ZIO[OrchestratorControlPlane & MessageRouter & Scope, gateway.MessageRouterError, Fiber.Runtime[Nothing, Unit]] =
    MessageRouter.attachControlPlaneRouting(runId, channelName, strategy)

  def getResourceState: ZIO[OrchestratorControlPlane, ControlPlaneError, ResourceAllocationState] =
    ZIO.serviceWithZIO[OrchestratorControlPlane](_.getResourceState)

  def getAgentMonitorSnapshot: ZIO[OrchestratorControlPlane, ControlPlaneError, AgentMonitorSnapshot] =
    ZIO.serviceWithZIO[OrchestratorControlPlane](_.getAgentMonitorSnapshot)

  def getAgentExecutionHistory(limit: Int)
    : ZIO[OrchestratorControlPlane, ControlPlaneError, List[AgentExecutionEvent]] =
    ZIO.serviceWithZIO[OrchestratorControlPlane](_.getAgentExecutionHistory(limit))

  def pauseAgentExecution(agentName: String): ZIO[OrchestratorControlPlane, ControlPlaneError, Unit] =
    ZIO.serviceWithZIO[OrchestratorControlPlane](_.pauseAgentExecution(agentName))

  def resumeAgentExecution(agentName: String): ZIO[OrchestratorControlPlane, ControlPlaneError, Unit] =
    ZIO.serviceWithZIO[OrchestratorControlPlane](_.resumeAgentExecution(agentName))

  def abortAgentExecution(agentName: String): ZIO[OrchestratorControlPlane, ControlPlaneError, Unit] =
    ZIO.serviceWithZIO[OrchestratorControlPlane](_.abortAgentExecution(agentName))

  val live: ZLayer[MigrationConfig, Nothing, OrchestratorControlPlane] = ZLayer.scoped {
    for
      config        <- ZIO.service[MigrationConfig]
      activeRuns    <- Ref.make[Map[String, ActiveRun]](Map.empty)
      eventQueues   <- Ref.make[Map[String, List[Queue[ControlPlaneEvent]]]](Map.empty)
      resourceState <- Ref.make[ResourceAllocationState](
                         ResourceAllocationState(
                           maxParallelism = config.parallelism,
                           currentParallelism = 0,
                           allocatedSlots = List.empty,
                           rateLimit = None,
                         )
                       )
      stepAgents    <- Ref.make[Map[String, String]](Map.empty)
      agentStates   <- Ref.make[Map[String, AgentExecutionInfo]](Map.empty)
      agentHistory  <- Ref.make[List[AgentExecutionEvent]](Nil)
    yield new OrchestratorControlPlaneLive(
      activeRuns,
      eventQueues,
      resourceState,
      stepAgents,
      agentStates,
      agentHistory,
    )
  }

final private[orchestration] class OrchestratorControlPlaneLive(
  activeRuns: Ref[Map[String, ActiveRun]],
  eventQueues: Ref[Map[String, List[Queue[ControlPlaneEvent]]]],
  resourceState: Ref[ResourceAllocationState],
  stepAgents: Ref[Map[String, String]],
  agentStates: Ref[Map[String, AgentExecutionInfo]],
  agentHistory: Ref[List[AgentExecutionEvent]],
) extends OrchestratorControlPlane:

  private val AgentHistoryMaxSize = 500

  override def startWorkflow(
    runId: String,
    workflowId: Long,
    definition: WorkflowDefinition,
  ): ZIO[Any, ControlPlaneError, String] =
    for
      now           <- Clock.instant
      correlationId <- ZIO.succeed(java.util.UUID.randomUUID().toString)
      run           <- activeRuns.modify { runs =>
                         if runs.contains(runId)
                         then (Left(ControlPlaneError.ActiveRunNotFound(runId)), runs)
                         else
                           val newRun = ActiveRun(
                             runId = runId,
                             workflowId = workflowId,
                             correlationId = correlationId,
                             state = WorkflowRunState.Pending,
                             currentStep = definition.steps.headOption,
                             startTime = now,
                             lastUpdateTime = now,
                           )
                           (Right(newRun), runs + (runId -> newRun))
                       }
      _             <- run match
                         case Left(err) => ZIO.fail(err)
                         case Right(_)  =>
                           publishEvent(
                             WorkflowStarted(
                               correlationId = correlationId,
                               runId = runId,
                               workflowId = workflowId,
                               timestamp = now,
                             )
                           )
    yield correlationId

  override def routeStep(
    runId: String,
    step: MigrationStep,
    capabilities: List[AgentCapability],
  ): ZIO[Any, ControlPlaneError, String] =
    for
      cached <- stepAgents.get.map(_.get(s"$runId-${step.toString}"))
      agent  <- cached match
                  case Some(agentName) =>
                    ZIO.succeed(agentName)
                  case None            =>
                    val eligible = capabilities.filter { cap =>
                      cap.isEnabled && cap.supportedSteps.contains(step)
                    }
                    if eligible.isEmpty
                    then
                      ZIO.fail(
                        ControlPlaneError.WorkflowRoutingFailed(
                          step.toString,
                          s"No agents available for $step",
                        )
                      )
                    else
                      val selected = eligible.head.agentName
                      stepAgents
                        .update(_ + (s"$runId-${step.toString}" -> selected))
                        .as(selected)
      now    <- Clock.instant
      _      <- updateAgentState(
                  agentName = agent,
                  state = AgentExecutionState.Executing,
                  runId = Some(runId),
                  step = Some(step),
                  task = Some(s"${step.toString} for run $runId"),
                  message = Some("Executing"),
                  tokenDelta = 0L,
                  now = now,
                )
      _      <- appendAgentHistory(
                  AgentExecutionEvent(
                    id = java.util.UUID.randomUUID().toString,
                    agentName = agent,
                    state = AgentExecutionState.Executing,
                    runId = Some(runId),
                    step = Some(step),
                    detail = s"Assigned to ${step.toString}",
                    timestamp = now,
                  )
                )
    yield agent

  override def allocateResource(runId: String): ZIO[Any, ControlPlaneError, Int] =
    resourceState.modify { state =>
      if state.currentParallelism >= state.maxParallelism
      then
        (
          Left(
            ControlPlaneError.ResourceAllocationFailed(
              runId,
              s"Max parallelism reached: ${state.maxParallelism}",
            )
          ),
          state,
        )
      else
        val slot     = state.currentParallelism
        val newState = state.copy(
          currentParallelism = state.currentParallelism + 1,
          allocatedSlots = state.allocatedSlots :+ slot,
        )
        (Right(slot), newState)
    }.flatMap {
      case Left(err)   => ZIO.fail(err)
      case Right(slot) =>
        for
          now <- Clock.instant
          _   <- publishEvent(
                   ResourceAllocated(
                     correlationId = java.util.UUID.randomUUID().toString,
                     runId = runId,
                     parallelismSlot = slot,
                     timestamp = now,
                   )
                 )
        yield slot
    }

  override def releaseResource(runId: String, slot: Int): ZIO[Any, ControlPlaneError, Unit] =
    for
      now <- Clock.instant
      _   <- resourceState.update { state =>
               state.copy(
                 currentParallelism = Math.max(0, state.currentParallelism - 1),
                 allocatedSlots = state.allocatedSlots.filterNot(_ == slot),
               )
             }
      _   <- publishEvent(
               ResourceReleased(
                 correlationId = java.util.UUID.randomUUID().toString,
                 runId = runId,
                 parallelismSlot = slot,
                 timestamp = now,
               )
             )
    yield ()

  override def publishEvent(event: ControlPlaneEvent): ZIO[Any, ControlPlaneError, Unit] =
    for
      queues      <- eventQueues.get
      runId       <- ZIO.succeed(event match
                       case WorkflowStarted(_, id, _, _)       => id
                       case WorkflowCompleted(_, id, _, _)     => id
                       case WorkflowFailed(_, id, _, _)        => id
                       case StepStarted(_, id, _, _, _)        => id
                       case StepProgress(_, id, _, _, _, _, _) => id
                       case StepCompleted(_, id, _, _, _)      => id
                       case StepFailed(_, id, _, _, _)         => id
                       case ResourceAllocated(_, id, _, _)     => id
                       case ResourceReleased(_, id, _, _)      => id)
      targetQueues = queues.getOrElse(runId, List.empty)
      _           <- ZIO.foreachDiscard(targetQueues)(q => q.offer(event))
      _           <- trackAgentEvent(event)
    yield ()

  override def subscribeToEvents(runId: String): ZIO[Scope, Nothing, Dequeue[ControlPlaneEvent]] =
    for
      queue <- Queue.bounded[ControlPlaneEvent](100)
      _     <- eventQueues.update { queues =>
                 val current = queues.getOrElse(runId, List.empty)
                 queues + (runId -> (current :+ queue))
               }
      _     <- ZIO.addFinalizer(
                 eventQueues.update { queues =>
                   val current = queues.getOrElse(runId, List.empty)
                   queues + (runId -> current.filterNot(_ == queue))
                 }.unit
               )
    yield queue

  override def getActiveRuns: ZIO[Any, ControlPlaneError, List[ActiveRun]] =
    activeRuns.get.map(_.values.toList)

  override def getRunState(runId: String): ZIO[Any, ControlPlaneError, Option[ActiveRun]] =
    activeRuns.get.map(_.get(runId))

  override def updateRunState(runId: String, newState: WorkflowRunState): ZIO[Any, ControlPlaneError, Unit] =
    for
      now     <- Clock.instant
      updated <- activeRuns.modify { runs =>
                   runs.get(runId) match
                     case None      =>
                       (
                         Left(ControlPlaneError.ActiveRunNotFound(runId)),
                         runs,
                       )
                     case Some(run) =>
                       val updated = run.copy(state = newState, lastUpdateTime = now)
                       (Right(updated), runs + (runId -> updated))
                 }
      _       <- updated match
                   case Left(err)  => ZIO.fail(err)
                   case Right(run) =>
                     publishEvent(
                       WorkflowCompleted(
                         correlationId = run.correlationId,
                         runId = runId,
                         status = newState match
                           case WorkflowRunState.Completed => WorkflowStatus.Completed
                           case WorkflowRunState.Failed    => WorkflowStatus.Failed
                           case WorkflowRunState.Cancelled => WorkflowStatus.Failed
                           case _                          =>
                             WorkflowStatus.Completed
                         ,
                         timestamp = now,
                       )
                     )
    yield ()

  override def executeCommand(command: ControlCommand): ZIO[Any, ControlPlaneError, Unit] =
    command match
      case PauseWorkflow(runId)  =>
        updateRunState(runId, WorkflowRunState.Paused)
      case ResumeWorkflow(runId) =>
        updateRunState(runId, WorkflowRunState.Running)
      case CancelWorkflow(runId) =>
        updateRunState(runId, WorkflowRunState.Cancelled)

  override def getResourceState: ZIO[Any, ControlPlaneError, ResourceAllocationState] =
    resourceState.get

  override def getAgentMonitorSnapshot: ZIO[Any, ControlPlaneError, AgentMonitorSnapshot] =
    for
      now    <- Clock.instant
      states <- agentStates.get.map(_.values.toList.sortBy(_.agentName.toLowerCase))
    yield AgentMonitorSnapshot(
      generatedAt = now,
      agents = states,
    )

  override def getAgentExecutionHistory(limit: Int): ZIO[Any, ControlPlaneError, List[AgentExecutionEvent]] =
    agentHistory.get.map(_.take(limit.max(1)))

  override def pauseAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit] =
    for
      now <- Clock.instant
      _   <- updateAgentState(
               agentName = agentName,
               state = AgentExecutionState.Paused,
               runId = None,
               step = None,
               task = Some("Paused by operator"),
               message = Some("Paused"),
               tokenDelta = 0L,
               now = now,
             )
      _   <- appendAgentHistory(
               AgentExecutionEvent(
                 id = java.util.UUID.randomUUID().toString,
                 agentName = agentName,
                 state = AgentExecutionState.Paused,
                 runId = None,
                 step = None,
                 detail = "Execution paused",
                 timestamp = now,
               )
             )
    yield ()

  override def resumeAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit] =
    for
      now <- Clock.instant
      _   <- updateAgentState(
               agentName = agentName,
               state = AgentExecutionState.Executing,
               runId = None,
               step = None,
               task = Some("Resumed by operator"),
               message = Some("Executing"),
               tokenDelta = 0L,
               now = now,
             )
      _   <- appendAgentHistory(
               AgentExecutionEvent(
                 id = java.util.UUID.randomUUID().toString,
                 agentName = agentName,
                 state = AgentExecutionState.Executing,
                 runId = None,
                 step = None,
                 detail = "Execution resumed",
                 timestamp = now,
               )
             )
    yield ()

  override def abortAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit] =
    for
      now <- Clock.instant
      _   <- updateAgentState(
               agentName = agentName,
               state = AgentExecutionState.Aborted,
               runId = None,
               step = None,
               task = Some("Aborted by operator"),
               message = Some("Aborted"),
               tokenDelta = 0L,
               now = now,
             )
      _   <- appendAgentHistory(
               AgentExecutionEvent(
                 id = java.util.UUID.randomUUID().toString,
                 agentName = agentName,
                 state = AgentExecutionState.Aborted,
                 runId = None,
                 step = None,
                 detail = "Execution aborted",
                 timestamp = now,
               )
             )
    yield ()

  private def trackAgentEvent(event: ControlPlaneEvent): UIO[Unit] =
    event match
      case StepProgress(_, runId, step, _, _, message, timestamp) =>
        val maybeAgentKey = s"$runId-${step.toString}"
        stepAgents.get.flatMap { map =>
          map.get(maybeAgentKey) match
            case None            => ZIO.unit
            case Some(agentName) =>
              val nextState  =
                if message.toLowerCase.contains("tool") then AgentExecutionState.WaitingForTool
                else AgentExecutionState.Executing
              val tokenDelta = (message.length / 4).toLong.max(0L)
              updateAgentState(
                agentName = agentName,
                state = nextState,
                runId = Some(runId),
                step = Some(step),
                task = Some(s"${step.toString} for run $runId"),
                message = Some(message),
                tokenDelta = tokenDelta,
                now = timestamp,
              ) *> appendAgentHistory(
                AgentExecutionEvent(
                  id = java.util.UUID.randomUUID().toString,
                  agentName = agentName,
                  state = nextState,
                  runId = Some(runId),
                  step = Some(step),
                  detail = message,
                  timestamp = timestamp,
                )
              )
        }
      case StepCompleted(_, runId, step, _, timestamp)            =>
        val maybeAgentKey = s"$runId-${step.toString}"
        stepAgents.get.flatMap { map =>
          map.get(maybeAgentKey) match
            case None            => ZIO.unit
            case Some(agentName) =>
              updateAgentState(
                agentName = agentName,
                state = AgentExecutionState.Idle,
                runId = None,
                step = None,
                task = None,
                message = Some("Idle"),
                tokenDelta = 0L,
                now = timestamp,
              ) *> appendAgentHistory(
                AgentExecutionEvent(
                  id = java.util.UUID.randomUUID().toString,
                  agentName = agentName,
                  state = AgentExecutionState.Idle,
                  runId = Some(runId),
                  step = Some(step),
                  detail = s"${step.toString} completed",
                  timestamp = timestamp,
                )
              )
        }
      case StepFailed(_, runId, step, error, timestamp)           =>
        val maybeAgentKey = s"$runId-${step.toString}"
        stepAgents.get.flatMap { map =>
          map.get(maybeAgentKey) match
            case None            => ZIO.unit
            case Some(agentName) =>
              updateAgentState(
                agentName = agentName,
                state = AgentExecutionState.Failed,
                runId = Some(runId),
                step = Some(step),
                task = Some(s"${step.toString} for run $runId"),
                message = Some(error),
                tokenDelta = 0L,
                now = timestamp,
              ) *> appendAgentHistory(
                AgentExecutionEvent(
                  id = java.util.UUID.randomUUID().toString,
                  agentName = agentName,
                  state = AgentExecutionState.Failed,
                  runId = Some(runId),
                  step = Some(step),
                  detail = error,
                  timestamp = timestamp,
                )
              )
        }
      case _                                                      =>
        ZIO.unit

  private def updateAgentState(
    agentName: String,
    state: AgentExecutionState,
    runId: Option[String],
    step: Option[MigrationStep],
    task: Option[String],
    message: Option[String],
    tokenDelta: Long,
    now: java.time.Instant,
  ): UIO[Unit] =
    agentStates.update { states =>
      val existing     = states.get(agentName)
      val previousTime = existing.map(_.lastUpdatedAt).getOrElse(now)
      val latencyDelta = java.time.Duration.between(previousTime, now).toMillis.max(0L)
      val totalTokens  = existing.map(_.tokensUsed).getOrElse(0L) + tokenDelta
      val totalLatency = existing.map(_.latencyMs).getOrElse(0L) + latencyDelta
      val cost         = totalTokens.toDouble * 0.000001
      val next         = AgentExecutionInfo(
        agentName = agentName,
        state = state,
        runId = runId.orElse(existing.flatMap(_.runId)),
        step = step.orElse(existing.flatMap(_.step)),
        task = task.orElse(existing.flatMap(_.task)),
        conversationId = existing.flatMap(_.conversationId),
        tokensUsed = totalTokens,
        latencyMs = totalLatency,
        cost = cost,
        lastUpdatedAt = now,
        message = message,
      )
      states.updated(agentName, next)
    }

  private def appendAgentHistory(event: AgentExecutionEvent): UIO[Unit] =
    agentHistory.update { current =>
      val next = event :: current
      if next.length > AgentHistoryMaxSize then next.take(AgentHistoryMaxSize) else next
    }
