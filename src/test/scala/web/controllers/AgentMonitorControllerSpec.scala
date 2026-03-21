package web.controllers

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import _root_.config.entity.WorkflowDefinition
import app.boundary.AgentMonitorControllerLive
import llm4zio.observability.LlmMetrics
import orchestration.control.*
import shared.errors.ControlPlaneError
import taskrun.entity.TaskStep

object AgentMonitorControllerSpec extends ZIOSpecDefault:
  private object Steps:
    val Analysis: TaskStep = "Analysis"

  private val sampleInfo = AgentExecutionInfo(
    agentName = "agent-1",
    state = AgentExecutionState.Executing,
    runId = Some("run-1"),
    step = Some(Steps.Analysis),
    task = Some("Analysis for run run-1"),
    conversationId = None,
    tokensUsed = 42L,
    latencyMs = 120L,
    cost = 0.000042,
    lastUpdatedAt = Instant.EPOCH,
    startedAt = None,
    message = Some("working"),
  )

  final private class StubControlPlane(actionRef: Ref[List[String]]) extends OrchestratorControlPlane:
    override def startWorkflow(
      runId: String,
      workflowId: Long,
      definition: WorkflowDefinition,
    ): ZIO[Any, ControlPlaneError, String] = ZIO.succeed("corr")
    override def routeStep(
      runId: String,
      step: TaskStep,
      capabilities: List[AgentCapability],
    ): ZIO[Any, ControlPlaneError, String] = ZIO.succeed("agent-1")
    override def allocateResource(runId: String): ZIO[Any, ControlPlaneError, Int]                            = ZIO.succeed(0)
    override def releaseResource(runId: String, slot: Int): ZIO[Any, ControlPlaneError, Unit]                 = ZIO.unit
    override def publishEvent(event: ControlPlaneEvent): ZIO[Any, ControlPlaneError, Unit]                    = ZIO.unit
    override def subscribeToEvents(runId: String): ZIO[Scope, Nothing, Dequeue[ControlPlaneEvent]]            =
      Queue.unbounded[ControlPlaneEvent].map(identity)
    override def subscribeAllEvents: ZIO[Scope, Nothing, Dequeue[ControlPlaneEvent]]                          =
      Queue.unbounded[ControlPlaneEvent].map(identity)
    override def getActiveRuns: ZIO[Any, ControlPlaneError, List[ActiveRun]]                                  = ZIO.succeed(Nil)
    override def getRunState(runId: String): ZIO[Any, ControlPlaneError, Option[ActiveRun]]                   = ZIO.none
    override def updateRunState(runId: String, newState: WorkflowRunState): ZIO[Any, ControlPlaneError, Unit] = ZIO.unit
    override def executeCommand(command: ControlCommand): ZIO[Any, ControlPlaneError, Unit]                   = ZIO.unit
    override def getResourceState: ZIO[Any, ControlPlaneError, ResourceAllocationState]                       =
      ZIO.succeed(ResourceAllocationState(1, 0, Nil, None))
    override def getAgentMonitorSnapshot: ZIO[Any, ControlPlaneError, AgentMonitorSnapshot]                   =
      Clock.instant.map(ts => AgentMonitorSnapshot(ts, List(sampleInfo)))
    override def getAgentExecutionHistory(limit: Int): ZIO[Any, ControlPlaneError, List[AgentExecutionEvent]] =
      ZIO.succeed(
        List(
          AgentExecutionEvent(
            id = "evt-1",
            agentName = "agent-1",
            state = AgentExecutionState.Executing,
            runId = Some("run-1"),
            step = Some(Steps.Analysis),
            detail = "Processing",
            timestamp = Instant.EPOCH,
          )
        ).take(limit.max(1))
      )
    override def pauseAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit]                    =
      actionRef.update("pause:" + agentName :: _).unit
    override def resumeAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit]                   =
      actionRef.update("resume:" + agentName :: _).unit
    override def abortAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit]                    =
      actionRef.update("abort:" + agentName :: _).unit
    override def notifyWorkspaceAgent(
      agentName: String,
      state: AgentExecutionState,
      runId: Option[String],
      conversationId: Option[String],
      message: Option[String],
      tokenDelta: Long,
    ): UIO[Unit] = ZIO.unit

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AgentMonitorControllerSpec")(
    test("GET /agent-monitor redirects to command center") {
      for
        actions   <- Ref.make(List.empty[String])
        metrics   <- ZIO.service[LlmMetrics]
        controller = AgentMonitorControllerLive(StubControlPlane(actions), metrics)
        response  <- controller.routes.runZIO(Request.get("/agent-monitor"))
        location   = response.headers.header(Header.Location).map(_.renderedValue)
      yield assertTrue(response.status == Status.MovedPermanently, location.contains("/"))
    }.provide(LlmMetrics.layer, Scope.default),
    test("GET /api/agent-monitor/snapshot returns JSON snapshot") {
      for
        actions   <- Ref.make(List.empty[String])
        metrics   <- ZIO.service[LlmMetrics]
        controller = AgentMonitorControllerLive(StubControlPlane(actions), metrics)
        response  <- controller.routes.runZIO(Request.get("/api/agent-monitor/snapshot"))
        body      <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("\"agents\""),
        body.contains("\"agentName\":\"agent-1\""),
      )
    }.provide(LlmMetrics.layer, Scope.default),
    test("POST pause endpoint calls control plane") {
      for
        actions   <- Ref.make(List.empty[String])
        metrics   <- ZIO.service[LlmMetrics]
        controller = AgentMonitorControllerLive(StubControlPlane(actions), metrics)
        response  <- controller.routes.runZIO(Request.post("/api/agent-monitor/agents/agent-1/pause", Body.empty))
        history   <- actions.get
      yield assertTrue(response.status == Status.Ok, history.contains("pause:agent-1"))
    }.provide(LlmMetrics.layer, Scope.default),
  )
