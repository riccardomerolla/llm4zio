package orchestration.control

import zio.*
import zio.test.*
import zio.test.Assertion.*

import _root_.config.entity.*

object WorkflowEngineSpec extends ZIOSpecDefault:

  private def node(
    id: String,
    deps: List[String] = Nil,
    step: String = "",
    policy: Option[WorkflowAgentPolicy] = None,
  ): WorkflowNode =
    WorkflowNode(
      id = id,
      step = if step.nonEmpty then step else id,
      dependsOn = deps,
      agentPolicy = policy,
    )

  private def mkGraph(nodes: WorkflowNode*): WorkflowGraph =
    WorkflowGraph(nodes.toList)

  private def mkWorkflow(graph: WorkflowGraph, name: String = "test"): WorkflowDefinition =
    WorkflowDefinition(name = name, steps = Nil, isBuiltin = false, dynamicGraph = Some(graph))

  private def mkAgentInfo(
    name: String,
    supportedSteps: List[String],
  ): AgentInfo =
    AgentInfo(
      name = name,
      displayName = name,
      description = "",
      agentType = AgentType.BuiltIn,
      usesAI = false,
      tags = Nil,
      supportedSteps = supportedSteps,
    )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("WorkflowEngineSpec")(
    test("builds batches respecting dependencies") {
      val graph    = mkGraph(
        node("a"),
        node("b", deps = List("a")),
        node("c", deps = List("a")),
      )
      val workflow = mkWorkflow(graph)
      for
        result <- WorkflowEngine.buildPlan(workflow, WorkflowContext())
      yield assertTrue(
        result.batches.size == 2,
        result.batches.head.map(_.nodeId) == List("a"),
        result.batches(1).map(_.nodeId).sorted == List("b", "c"),
      )
    },
    test("fails with CircularDependency when graph has cycle") {
      val graph    = mkGraph(
        node("a", deps = List("b")),
        node("b", deps = List("a")),
      )
      val workflow = mkWorkflow(graph)
      for
        exit <- WorkflowEngine.buildPlan(workflow, WorkflowContext()).exit
      yield assert(exit)(fails(equalTo(WorkflowEngineError.CircularDependency(List("a", "b")))))
    },
    test("respects forced agent in WorkflowAgentPolicy") {
      val policy   = WorkflowAgentPolicy(forcedAgent = Some("alice"))
      val graph    = mkGraph(
        node("step1", step = "build", policy = Some(policy))
      )
      val workflow = mkWorkflow(graph)
      val alice    = mkAgentInfo("alice", supportedSteps = List("build"))
      val bob      = mkAgentInfo("bob", supportedSteps = List("build"))
      val charlie  = mkAgentInfo("charlie", supportedSteps = List("build"))
      for
        result      <- WorkflowEngine.buildPlan(workflow, WorkflowContext(), List(alice, bob))
        assignedOk   = assertTrue(result.batches.flatten.head.assignedAgent == Some("alice"))
        failedExit  <- WorkflowEngine.buildPlan(workflow, WorkflowContext(), List(bob, charlie)).exit
        failedOk     = assert(failedExit)(
                         fails(equalTo(WorkflowEngineError.ForcedAgentUnavailable("build", "alice")))
                       )
      yield assignedOk && failedOk
    },
    test("removeNode rewires dependsOn through the removed node") {
      val graph = mkGraph(
        node("a"),
        node("b", deps = List("a")),
        node("c", deps = List("b")),
      )
      for
        result <- WorkflowEngine.removeNode(graph, "b")
      yield assertTrue(
        result.nodes.map(_.id).sorted == List("a", "c"),
        result.nodes.exists(n => n.id == "c" && n.dependsOn.contains("a")),
      )
    },
  ).provideLayer(WorkflowEngine.live)
