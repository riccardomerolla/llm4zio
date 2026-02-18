package orchestration

import zio.*

import models.*

enum WorkflowEngineError:
  case EmptyWorkflow(workflowName: String)
  case InvalidNode(nodeId: String, reason: String)
  case MissingDependency(nodeId: String, dependencyId: String)
  case CircularDependency(nodeIds: List[String])
  case ForcedAgentUnavailable(step: TaskStep, agent: String)
  case NoEligibleAgent(step: TaskStep)

case class WorkflowStepPlan(
  nodeId: String,
  step: TaskStep,
  dependsOn: Set[String],
  assignedAgent: Option[String],
  fallbackAgents: List[String],
  retryLimit: Int,
  parallelGroup: Option[String],
)

case class WorkflowExecutionPlan(
  batches: List[List[WorkflowStepPlan]]
):
  def orderedSteps: List[TaskStep] =
    batches.flatten.map(_.step)

trait WorkflowEngine:
  def buildPlan(
    workflow: WorkflowDefinition,
    context: WorkflowContext,
    candidates: List[AgentInfo] = Nil,
  ): IO[WorkflowEngineError, WorkflowExecutionPlan]

  def insertNodeAfter(
    graph: WorkflowGraph,
    afterNodeId: String,
    node: WorkflowNode,
  ): IO[WorkflowEngineError, WorkflowGraph]

  def removeNode(
    graph: WorkflowGraph,
    nodeId: String,
  ): IO[WorkflowEngineError, WorkflowGraph]

  def updateAgentPolicy(
    graph: WorkflowGraph,
    nodeId: String,
    policy: WorkflowAgentPolicy,
  ): IO[WorkflowEngineError, WorkflowGraph]

object WorkflowEngine:
  def buildPlan(
    workflow: WorkflowDefinition,
    context: WorkflowContext,
    candidates: List[AgentInfo] = Nil,
  ): ZIO[WorkflowEngine, WorkflowEngineError, WorkflowExecutionPlan] =
    ZIO.serviceWithZIO[WorkflowEngine](_.buildPlan(workflow, context, candidates))

  def insertNodeAfter(
    graph: WorkflowGraph,
    afterNodeId: String,
    node: WorkflowNode,
  ): ZIO[WorkflowEngine, WorkflowEngineError, WorkflowGraph] =
    ZIO.serviceWithZIO[WorkflowEngine](_.insertNodeAfter(graph, afterNodeId, node))

  def removeNode(
    graph: WorkflowGraph,
    nodeId: String,
  ): ZIO[WorkflowEngine, WorkflowEngineError, WorkflowGraph] =
    ZIO.serviceWithZIO[WorkflowEngine](_.removeNode(graph, nodeId))

  def updateAgentPolicy(
    graph: WorkflowGraph,
    nodeId: String,
    policy: WorkflowAgentPolicy,
  ): ZIO[WorkflowEngine, WorkflowEngineError, WorkflowGraph] =
    ZIO.serviceWithZIO[WorkflowEngine](_.updateAgentPolicy(graph, nodeId, policy))

  def planFromGraph(
    graph: WorkflowGraph,
    context: WorkflowContext,
    stepAgents: Map[TaskStep, String] = Map.empty,
    candidates: List[AgentInfo] = Nil,
  ): IO[WorkflowEngineError, WorkflowExecutionPlan] =
    WorkflowEngineLive().buildPlan(
      WorkflowDefinition(
        name = "dynamic-workflow",
        steps = graph.nodes.map(_.step),
        stepAgents = stepAgents.toList.map { case (step, agent) => WorkflowStepAgent(step, agent) },
        isBuiltin = false,
        dynamicGraph = Some(graph),
      ),
      context,
      candidates,
    )

  val live: ULayer[WorkflowEngine] = ZLayer.succeed(WorkflowEngineLive())

final private case class WorkflowEngineLive() extends WorkflowEngine:

  override def buildPlan(
    workflow: WorkflowDefinition,
    context: WorkflowContext,
    candidates: List[AgentInfo],
  ): IO[WorkflowEngineError, WorkflowExecutionPlan] =
    for
      graph   <- resolveGraph(workflow)
      _       <- validateGraph(graph)
      active   = filterActiveNodes(graph, context)
      _       <- ZIO
                   .fail(WorkflowEngineError.EmptyWorkflow(workflow.name))
                   .when(active.nodes.isEmpty)
      batches <- topologicalBatches(active)
      mapped  <- ZIO.foreach(batches) { batch =>
                   ZIO.foreach(batch) { node =>
                     for
                       assigned <- resolveAgent(
                                     step = node.step,
                                     stepOverride = workflow.stepAgents.find(_.step == node.step).map(_.agentName.trim),
                                     policy = node.agentPolicy,
                                     candidates = candidates,
                                   )
                       fallback  = buildFallbacks(node.agentPolicy, assigned, candidates, node.step)
                     yield WorkflowStepPlan(
                       nodeId = node.id,
                       step = node.step,
                       dependsOn = node.dependsOn.toSet,
                       assignedAgent = assigned,
                       fallbackAgents = fallback,
                       retryLimit = node.retryLimit,
                       parallelGroup = node.parallelGroup,
                     )
                   }
                 }
    yield WorkflowExecutionPlan(mapped)

  override def insertNodeAfter(
    graph: WorkflowGraph,
    afterNodeId: String,
    node: WorkflowNode,
  ): IO[WorkflowEngineError, WorkflowGraph] =
    if graph.nodes.exists(_.id == node.id) then
      ZIO.fail(WorkflowEngineError.InvalidNode(node.id, "Node id already exists"))
    else if !graph.nodes.exists(_.id == afterNodeId) then
      ZIO.fail(WorkflowEngineError.MissingDependency(node.id, afterNodeId))
    else
      val updatedNode = node.copy(dependsOn = (node.dependsOn :+ afterNodeId).distinct)
      val updated     = WorkflowGraph(graph.nodes :+ updatedNode)
      validateGraph(updated).as(updated)

  override def removeNode(
    graph: WorkflowGraph,
    nodeId: String,
  ): IO[WorkflowEngineError, WorkflowGraph] =
    graph.nodes.find(_.id == nodeId) match
      case None          => ZIO.fail(WorkflowEngineError.InvalidNode(nodeId, "Node not found"))
      case Some(removed) =>
        val rewritten = graph.nodes
          .filterNot(_.id == nodeId)
          .map { node =>
            if node.dependsOn.contains(nodeId) then
              node.copy(dependsOn = (node.dependsOn.filterNot(_ == nodeId) ++ removed.dependsOn).distinct)
            else node
          }
        val updated   = WorkflowGraph(rewritten)
        validateGraph(updated).as(updated)

  override def updateAgentPolicy(
    graph: WorkflowGraph,
    nodeId: String,
    policy: WorkflowAgentPolicy,
  ): IO[WorkflowEngineError, WorkflowGraph] =
    if !graph.nodes.exists(_.id == nodeId) then ZIO.fail(WorkflowEngineError.InvalidNode(nodeId, "Node not found"))
    else
      ZIO.succeed(WorkflowGraph(graph.nodes.map(node =>
        if node.id == nodeId then node.copy(agentPolicy = Some(policy)) else node
      )))

  private def resolveGraph(workflow: WorkflowDefinition): IO[WorkflowEngineError, WorkflowGraph] =
    val graph = workflow.dynamicGraph.getOrElse(WorkflowGraph.fromSequentialSteps(workflow.steps))
    if graph.nodes.nonEmpty then ZIO.succeed(graph)
    else ZIO.fail(WorkflowEngineError.EmptyWorkflow(workflow.name))

  private def validateGraph(graph: WorkflowGraph): IO[WorkflowEngineError, Unit] =
    val nodeIds = graph.nodes.map(_.id)
    val dupes   = nodeIds.diff(nodeIds.distinct).distinct
    if dupes.nonEmpty then ZIO.fail(WorkflowEngineError.InvalidNode(dupes.head, "Duplicate node id"))
    else
      ZIO.foreachDiscard(graph.nodes) { node =>
        for
          _ <- ZIO
                 .fail(WorkflowEngineError.InvalidNode(node.id, "Node id cannot be empty"))
                 .when(node.id.trim.isEmpty)
          _ <- ZIO.foreachDiscard(node.dependsOn) { depId =>
                 if depId == node.id then
                   ZIO.fail(WorkflowEngineError.InvalidNode(node.id, "Node cannot depend on itself"))
                 else if !nodeIds.contains(depId) then
                   ZIO.fail(WorkflowEngineError.MissingDependency(node.id, depId))
                 else ZIO.unit
               }
        yield ()
      }

  private def filterActiveNodes(
    graph: WorkflowGraph,
    context: WorkflowContext,
  ): WorkflowGraph =
    def conditionMatches(condition: WorkflowCondition): Boolean =
      condition match
        case WorkflowCondition.Always                           => true
        case WorkflowCondition.DryRun                           => context.dryRun
        case WorkflowCondition.NotDryRun                        => !context.dryRun
        case WorkflowCondition.ContextFlag(flag)                => context.flags.contains(flag)
        case WorkflowCondition.MetadataEquals(key, valExpected) =>
          context.metadata.get(key).contains(valExpected)
        case WorkflowCondition.StepSucceeded(step)              => context.completedSteps.contains(step)
        case WorkflowCondition.StepFailed(step)                 => context.failedSteps.contains(step)

    val initial = graph.nodes.filter(node => conditionMatches(node.condition))
    val fixed   = iteratePrune(initial)
    val ids     = fixed.map(_.id).toSet
    WorkflowGraph(fixed.map(node => node.copy(dependsOn = node.dependsOn.filter(ids.contains))))

  private def iteratePrune(nodes: List[WorkflowNode]): List[WorkflowNode] =
    val ids    = nodes.map(_.id).toSet
    val pruned = nodes.filter(node => node.dependsOn.forall(ids.contains))
    if pruned.size == nodes.size then pruned
    else iteratePrune(pruned)

  private def topologicalBatches(graph: WorkflowGraph): IO[WorkflowEngineError, List[List[WorkflowNode]]] =
    val dependencies = graph.nodes.map(node => node.id -> node.dependsOn.toSet).toMap
    val byId         = graph.nodes.map(node => node.id -> node).toMap

    def loop(
      remaining: Map[String, Set[String]],
      done: Set[String],
      acc: List[List[WorkflowNode]],
    ): IO[WorkflowEngineError, List[List[WorkflowNode]]] =
      if remaining.isEmpty then ZIO.succeed(acc)
      else
        val readyIds = remaining.collect { case (id, deps) if deps.subsetOf(done) => id }.toList.sorted
        if readyIds.isEmpty then ZIO.fail(WorkflowEngineError.CircularDependency(remaining.keys.toList.sorted))
        else
          val readyNodes = readyIds.flatMap(byId.get)
          val next       = remaining -- readyIds
          loop(next, done ++ readyIds, acc :+ readyNodes)

    loop(dependencies, Set.empty, Nil)

  private def resolveAgent(
    step: TaskStep,
    stepOverride: Option[String],
    policy: Option[WorkflowAgentPolicy],
    candidates: List[AgentInfo],
  ): IO[WorkflowEngineError, Option[String]] =
    stepOverride.map(_.trim).filter(_.nonEmpty) match
      case Some(agent) => ZIO.succeed(Some(agent))
      case None        =>
        val effectivePolicy = policy.getOrElse(WorkflowAgentPolicy())
        val supported       = candidates.filter(agent => agent.supportedSteps.contains(step))
        effectivePolicy.forcedAgent.map(_.trim).filter(_.nonEmpty) match
          case Some(forced) =>
            if supported.exists(_.name == forced) then ZIO.succeed(Some(forced))
            else ZIO.fail(WorkflowEngineError.ForcedAgentUnavailable(step, forced))
          case None         =>
            if supported.isEmpty then ZIO.succeed(None)
            else
              val selected = effectivePolicy.strategy match
                case AgentSelectionStrategy.CapabilityMatch    => supported.sortBy(_.name).headOption
                case AgentSelectionStrategy.LoadBalanced       =>
                  supported.sortBy(agent => (agent.metrics.invocations, -agent.metrics.successRate)).headOption
                case AgentSelectionStrategy.CostOptimized      =>
                  supported.sortBy(agent => (if agent.usesAI then 1 else 0, agent.metrics.averageLatencyMs)).headOption
                case AgentSelectionStrategy.PerformanceHistory =>
                  supported
                    .sortBy(agent =>
                      (-agent.metrics.successRate, agent.metrics.averageLatencyMs, -agent.metrics.invocations)
                    )
                    .headOption
              selected.map(agent => agent.name) match
                case some @ Some(_) => ZIO.succeed(some)
                case None           => ZIO.fail(WorkflowEngineError.NoEligibleAgent(step))

  private def buildFallbacks(
    policy: Option[WorkflowAgentPolicy],
    selected: Option[String],
    candidates: List[AgentInfo],
    step: TaskStep,
  ): List[String] =
    val supportedNames = candidates.filter(_.supportedSteps.contains(step)).map(_.name).toSet
    policy
      .toList
      .flatMap(_.fallbackAgents)
      .map(_.trim)
      .filter(name => name.nonEmpty && supportedNames.contains(name) && !selected.contains(name))
      .distinct
