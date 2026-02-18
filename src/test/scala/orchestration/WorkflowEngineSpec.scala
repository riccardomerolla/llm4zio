package orchestration

import zio.*
import zio.test.*

import models.*

object WorkflowEngineSpec extends ZIOSpecDefault:

  private val workflow = WorkflowDefinition(
    name = "dynamic",
    steps = List(TaskStep.Discovery, TaskStep.Analysis, TaskStep.Mapping),
    isBuiltin = false,
  )

  private val candidates = List(
    AgentInfo(
      name = "analysis-fast",
      displayName = "Analysis Fast",
      description = "Fast analysis agent",
      agentType = AgentType.BuiltIn,
      usesAI = true,
      tags = Nil,
      supportedSteps = List(TaskStep.Analysis),
      metrics = AgentMetrics(invocations = 20, successCount = 18, failureCount = 2, totalLatencyMs = 8000),
      health = AgentHealth(status = AgentHealthStatus.Healthy, isEnabled = true),
    ),
    AgentInfo(
      name = "analysis-balanced",
      displayName = "Analysis Balanced",
      description = "Lower load analysis agent",
      agentType = AgentType.BuiltIn,
      usesAI = true,
      tags = Nil,
      supportedSteps = List(TaskStep.Analysis),
      metrics = AgentMetrics(invocations = 3, successCount = 3, failureCount = 0, totalLatencyMs = 3600),
      health = AgentHealth(status = AgentHealthStatus.Healthy, isEnabled = true),
    ),
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("WorkflowEngineSpec")(
    test("buildPlan computes DAG batches with parallel independent nodes") {
      val graph = WorkflowGraph(
        List(
          WorkflowNode(id = "discovery", step = TaskStep.Discovery),
          WorkflowNode(id = "docs", step = TaskStep.Documentation),
          WorkflowNode(id = "analysis", step = TaskStep.Analysis, dependsOn = List("discovery")),
          WorkflowNode(id = "mapping", step = TaskStep.Mapping, dependsOn = List("analysis")),
          WorkflowNode(id = "validation", step = TaskStep.Validation, dependsOn = List("mapping", "docs")),
        )
      )
      val wf    = workflow.copy(dynamicGraph = Some(graph))

      for
        plan <- WorkflowEngine.buildPlan(wf, WorkflowContext())
      yield assertTrue(
        plan.batches.size == 4,
        plan.batches.head.map(_.nodeId).toSet == Set("discovery", "docs"),
        plan.batches(1).map(_.nodeId) == List("analysis"),
        plan.batches(2).map(_.nodeId) == List("mapping"),
        plan.batches(3).map(_.nodeId) == List("validation"),
      )
    },
    test("buildPlan evaluates conditional branches from runtime context") {
      val graph = WorkflowGraph(
        List(
          WorkflowNode(id = "discovery", step = TaskStep.Discovery),
          WorkflowNode(id = "analysis", step = TaskStep.Analysis, dependsOn = List("discovery")),
          WorkflowNode(
            id = "validation",
            step = TaskStep.Validation,
            dependsOn = List("analysis"),
            condition =
              WorkflowCondition.NotDryRun,
          ),
        )
      )
      val wf    = workflow.copy(dynamicGraph = Some(graph))

      for
        dryPlan  <- WorkflowEngine.buildPlan(wf, WorkflowContext(dryRun = true))
        fullPlan <- WorkflowEngine.buildPlan(wf, WorkflowContext(dryRun = false))
      yield assertTrue(
        dryPlan.orderedSteps == List(TaskStep.Discovery, TaskStep.Analysis),
        fullPlan.orderedSteps == List(TaskStep.Discovery, TaskStep.Analysis, TaskStep.Validation),
      )
    },
    test("supports dynamic graph modification at runtime") {
      val base     = WorkflowGraph(
        List(
          WorkflowNode(id = "discovery", step = TaskStep.Discovery),
          WorkflowNode(id = "analysis", step = TaskStep.Analysis, dependsOn = List("discovery")),
        )
      )
      val toInsert = WorkflowNode(id = "mapping", step = TaskStep.Mapping)
      val policy   = WorkflowAgentPolicy(
        strategy = AgentSelectionStrategy.PerformanceHistory,
        fallbackAgents = List(
          "analysis-fast"
        ),
      )

      for
        inserted <- WorkflowEngine.insertNodeAfter(base, "analysis", toInsert)
        removed  <- WorkflowEngine.removeNode(inserted, "analysis")
        updated  <- WorkflowEngine.updateAgentPolicy(removed, "mapping", policy)
      yield assertTrue(
        inserted.nodes.find(_.id == "mapping").exists(_.dependsOn.contains("analysis")),
        removed.nodes.find(_.id == "mapping").exists(_.dependsOn.contains("discovery")),
        updated.nodes.find(_.id == "mapping").flatMap(_.agentPolicy).contains(policy),
      )
    },
    test("runtime agent assignment supports load balancing and fallback lists") {
      val graph = WorkflowGraph(
        List(
          WorkflowNode(
            id = "analysis",
            step = TaskStep.Analysis,
            agentPolicy = Some(
              WorkflowAgentPolicy(
                strategy = AgentSelectionStrategy.LoadBalanced,
                fallbackAgents = List("analysis-fast", "missing-agent"),
              )
            ),
          )
        )
      )
      val wf    = workflow.copy(dynamicGraph = Some(graph))

      for
        plan <- WorkflowEngine.buildPlan(wf, WorkflowContext(), candidates)
      yield assertTrue(
        plan.orderedSteps == List(TaskStep.Analysis),
        plan.batches.head.head.assignedAgent.contains("analysis-balanced"),
        plan.batches.head.head.fallbackAgents == List("analysis-fast"),
      )
    },
    test("fails when forced agent is unavailable") {
      val graph = WorkflowGraph(
        List(
          WorkflowNode(
            id = "analysis",
            step = TaskStep.Analysis,
            agentPolicy = Some(
              WorkflowAgentPolicy(
                strategy = AgentSelectionStrategy.CapabilityMatch,
                forcedAgent = Some("agent-not-found"),
              )
            ),
          )
        )
      )
      val wf    = workflow.copy(dynamicGraph = Some(graph))

      for
        result <- WorkflowEngine.buildPlan(wf, WorkflowContext(), candidates).exit
      yield assertTrue(result.isFailure)
    },
  ).provideLayerShared(WorkflowEngine.live)
