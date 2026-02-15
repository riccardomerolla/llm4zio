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
    yield new OrchestratorControlPlaneLive(
      activeRuns,
      eventQueues,
      resourceState,
      stepAgents,
    )
  }

final private[orchestration] class OrchestratorControlPlaneLive(
  activeRuns: Ref[Map[String, ActiveRun]],
  eventQueues: Ref[Map[String, List[Queue[ControlPlaneEvent]]]],
  resourceState: Ref[ResourceAllocationState],
  stepAgents: Ref[Map[String, String]],
) extends OrchestratorControlPlane:

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
