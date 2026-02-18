package orchestration

import zio.*
import zio.test.*
import zio.test.Assertion.*

import models.*

object ControlPlaneSpec extends ZIOSpecDefault:

  private val testConfig = MigrationConfig(
    sourceDir = java.nio.file.Paths.get("test-source"),
    outputDir = java.nio.file.Paths.get("test-output"),
    stateDir = java.nio.file.Paths.get(".test-state"),
    parallelism = 4,
  )

  private val testLayer: ZLayer[Any, Nothing, OrchestratorControlPlane] =
    ZLayer.succeed(testConfig) >>> OrchestratorControlPlane.live

  private val testWorkflowDef = WorkflowDefinition(
    id = Some(1L),
    name = "Test Workflow",
    description = Some("Test workflow for control plane"),
    steps = List(
      TaskStep.Discovery,
      TaskStep.Analysis,
      TaskStep.Transformation,
    ),
    stepAgents = Nil,
    isBuiltin = true,
  )

  private val testCapabilities = List(
    AgentCapability(
      agentName = "agent-1",
      supportedSteps = List(TaskStep.Discovery, TaskStep.Analysis),
      isEnabled = true,
    ),
    AgentCapability(
      agentName = "agent-2",
      supportedSteps = List(TaskStep.Transformation),
      isEnabled = true,
    ),
  )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("OrchestratorControlPlane")(
    test("startWorkflow creates active run and publishes event") {
      for
        correlationId <- OrchestratorControlPlane.startWorkflow(
                           runId = "run-1",
                           workflowId = 1L,
                           definition = testWorkflowDef,
                         )
        runs          <- OrchestratorControlPlane.getActiveRuns
        runState      <- OrchestratorControlPlane.getRunState("run-1")
      yield assertTrue(
        correlationId.nonEmpty,
        runs.size == 1,
        runs.head.runId == "run-1",
        runs.head.workflowId == 1L,
        runs.head.state == WorkflowRunState.Pending,
        runState.isDefined,
        runState.get.runId == "run-1",
      )
    },
    test("startWorkflow fails for duplicate runId") {
      for
        _      <- OrchestratorControlPlane.startWorkflow("run-dup", 1L, testWorkflowDef)
        result <- OrchestratorControlPlane
                    .startWorkflow("run-dup", 2L, testWorkflowDef)
                    .exit
      yield assert(result)(fails(isSubtype[ControlPlaneError.ActiveRunNotFound](anything)))
    },
    test("routeStep selects eligible agent by capability") {
      for
        _      <- OrchestratorControlPlane.startWorkflow("run-route", 1L, testWorkflowDef)
        agent1 <- OrchestratorControlPlane.routeStep(
                    "run-route",
                    TaskStep.Discovery,
                    testCapabilities,
                  )
        agent2 <- OrchestratorControlPlane.routeStep(
                    "run-route",
                    TaskStep.Transformation,
                    testCapabilities,
                  )
      yield assertTrue(
        agent1 == "agent-1",
        agent2 == "agent-2",
      )
    },
    test("routeStep caches agent selection") {
      for
        _      <- OrchestratorControlPlane.startWorkflow("run-cache", 1L, testWorkflowDef)
        agent1 <- OrchestratorControlPlane.routeStep(
                    "run-cache",
                    TaskStep.Discovery,
                    testCapabilities,
                  )
        agent2 <- OrchestratorControlPlane.routeStep(
                    "run-cache",
                    TaskStep.Discovery,
                    testCapabilities,
                  )
      yield assertTrue(agent1 == agent2)
    },
    test("routeStep fails when no eligible agents") {
      for
        _      <- OrchestratorControlPlane.startWorkflow("run-no-agent", 1L, testWorkflowDef)
        result <- OrchestratorControlPlane
                    .routeStep(
                      "run-no-agent",
                      TaskStep.Discovery,
                      List(
                        AgentCapability(
                          agentName = "disabled-agent",
                          supportedSteps = List(TaskStep.Discovery),
                          isEnabled = false,
                        )
                      ),
                    )
                    .exit
      yield assert(result)(fails(isSubtype[ControlPlaneError.WorkflowRoutingFailed](anything)))
    },
    test("allocateResource respects parallelism limits") {
      for
        resourceState <- OrchestratorControlPlane.getResourceState
        maxParallelism = resourceState.maxParallelism
        slots         <- ZIO.foreach((1 to maxParallelism).toList)(_ =>
                           OrchestratorControlPlane.allocateResource(s"run-alloc-$maxParallelism")
                         )
        state         <- OrchestratorControlPlane.getResourceState
        overLimit     <- OrchestratorControlPlane
                           .allocateResource("run-alloc-over")
                           .exit
      yield assertTrue(
        slots.size == maxParallelism,
        slots.distinct.size == maxParallelism,
        state.currentParallelism == maxParallelism,
        overLimit.isFailure,
      )
    },
    test("releaseResource decrements parallelism counter") {
      for
        slot1       <- OrchestratorControlPlane.allocateResource("run-release-1")
        slot2       <- OrchestratorControlPlane.allocateResource("run-release-2")
        stateBefore <- OrchestratorControlPlane.getResourceState
        _           <- OrchestratorControlPlane.releaseResource("run-release-1", slot1)
        stateAfter  <- OrchestratorControlPlane.getResourceState
      yield assertTrue(
        stateBefore.currentParallelism == stateAfter.currentParallelism + 1,
        !stateAfter.allocatedSlots.contains(slot1),
        stateAfter.allocatedSlots.contains(slot2),
      )
    },
    test("publishEvent broadcasts to subscribers") {
      ZIO.scoped {
        for
          queue1    <- OrchestratorControlPlane.subscribeToEvents("run-pub")
          queue2    <- OrchestratorControlPlane.subscribeToEvents("run-pub")
          now       <- Clock.instant
          event      = WorkflowStarted(
                         correlationId = "test-corr",
                         runId = "run-pub",
                         workflowId = 1L,
                         timestamp = now,
                       )
          _         <- OrchestratorControlPlane.publishEvent(event)
          received1 <- queue1.take
          received2 <- queue2.take
        yield assertTrue(
          received1 == event,
          received2 == event,
        )
      }
    },
    test("subscribeToEvents deregisters queue on scope exit") {
      for
        _    <- ZIO.scoped {
                  OrchestratorControlPlane.subscribeToEvents("run-dereg")
                }
        now  <- Clock.instant
        event = WorkflowStarted(
                  correlationId = "test-corr-2",
                  runId = "run-dereg",
                  workflowId = 2L,
                  timestamp = now,
                )
        _    <- OrchestratorControlPlane.publishEvent(event)
      yield assertCompletes
    },
    test("updateRunState changes workflow state") {
      for
        _      <- OrchestratorControlPlane.startWorkflow("run-update", 1L, testWorkflowDef)
        _      <- OrchestratorControlPlane.updateRunState("run-update", WorkflowRunState.Running)
        state1 <- OrchestratorControlPlane.getRunState("run-update")
        _      <- OrchestratorControlPlane.updateRunState("run-update", WorkflowRunState.Completed)
        state2 <- OrchestratorControlPlane.getRunState("run-update")
      yield assertTrue(
        state1.get.state == WorkflowRunState.Running,
        state2.get.state == WorkflowRunState.Completed,
      )
    },
    test("updateRunState fails for non-existent run") {
      for
        result <- OrchestratorControlPlane
                    .updateRunState("run-nonexist", WorkflowRunState.Running)
                    .exit
      yield assert(result)(fails(isSubtype[ControlPlaneError.ActiveRunNotFound](anything)))
    },
    test("executeCommand pauses workflow") {
      for
        _     <- OrchestratorControlPlane.startWorkflow("run-pause", 1L, testWorkflowDef)
        _     <- OrchestratorControlPlane.executeCommand(PauseWorkflow("run-pause"))
        state <- OrchestratorControlPlane.getRunState("run-pause")
      yield assertTrue(state.get.state == WorkflowRunState.Paused)
    },
    test("executeCommand resumes workflow") {
      for
        _     <- OrchestratorControlPlane.startWorkflow("run-resume", 1L, testWorkflowDef)
        _     <- OrchestratorControlPlane.executeCommand(PauseWorkflow("run-resume"))
        _     <- OrchestratorControlPlane.executeCommand(ResumeWorkflow("run-resume"))
        state <- OrchestratorControlPlane.getRunState("run-resume")
      yield assertTrue(state.get.state == WorkflowRunState.Running)
    },
    test("executeCommand cancels workflow") {
      for
        _     <- OrchestratorControlPlane.startWorkflow("run-cancel", 1L, testWorkflowDef)
        _     <- OrchestratorControlPlane.executeCommand(CancelWorkflow("run-cancel"))
        state <- OrchestratorControlPlane.getRunState("run-cancel")
      yield assertTrue(state.get.state == WorkflowRunState.Cancelled)
    },
    test("resource allocation and release cycle") {
      for
        initialState <- OrchestratorControlPlane.getResourceState
        slot         <- OrchestratorControlPlane.allocateResource("run-cycle")
        midState     <- OrchestratorControlPlane.getResourceState
        _            <- OrchestratorControlPlane.releaseResource("run-cycle", slot)
        finalState   <- OrchestratorControlPlane.getResourceState
      yield assertTrue(
        initialState.currentParallelism + 1 == midState.currentParallelism,
        midState.currentParallelism - 1 == finalState.currentParallelism,
        midState.allocatedSlots.contains(slot),
        !finalState.allocatedSlots.contains(slot),
      )
    },
    test("agent monitor snapshot and history are populated from step events") {
      for
        _        <- OrchestratorControlPlane.startWorkflow("run-monitor", 1L, testWorkflowDef)
        assigned <- OrchestratorControlPlane.routeStep("run-monitor", TaskStep.Analysis, testCapabilities)
        now      <- Clock.instant
        _        <- OrchestratorControlPlane.publishEvent(
                      StepProgress(
                        correlationId = "corr-monitor",
                        runId = "run-monitor",
                        step = TaskStep.Analysis,
                        itemsProcessed = 1,
                        itemsTotal = 10,
                        message = "waiting for tool response",
                        timestamp = now,
                      )
                    )
        snapshot <- OrchestratorControlPlane.getAgentMonitorSnapshot
        history  <- OrchestratorControlPlane.getAgentExecutionHistory(10)
      yield assertTrue(
        assigned == "agent-1",
        snapshot.agents.exists(info =>
          info.agentName == "agent-1" && info.state == AgentExecutionState.WaitingForTool && info.tokensUsed > 0
        ),
        history.exists(evt => evt.agentName == "agent-1" && evt.state == AgentExecutionState.WaitingForTool),
      )
    },
    test("agent execution control commands update state and timeline") {
      for
        _        <- OrchestratorControlPlane.pauseAgentExecution("agent-control")
        _        <- OrchestratorControlPlane.resumeAgentExecution("agent-control")
        _        <- OrchestratorControlPlane.abortAgentExecution("agent-control")
        snapshot <- OrchestratorControlPlane.getAgentMonitorSnapshot
        history  <- OrchestratorControlPlane.getAgentExecutionHistory(5)
      yield assertTrue(
        snapshot.agents.exists(info => info.agentName == "agent-control" && info.state == AgentExecutionState.Aborted),
        history.headOption.exists(evt => evt.agentName == "agent-control" && evt.state == AgentExecutionState.Aborted),
      )
    },
  ).provide(testLayer) @@ TestAspect.sequential
