package models

import zio.json.*

enum WorkflowCondition derives JsonCodec:
  case Always
  case DryRun
  case NotDryRun
  case ContextFlag(flag: String)
  case MetadataEquals(key: String, value: String)
  case StepSucceeded(step: TaskStep)
  case StepFailed(step: TaskStep)

enum AgentSelectionStrategy derives JsonCodec:
  case CapabilityMatch
  case LoadBalanced
  case CostOptimized
  case PerformanceHistory

case class WorkflowAgentPolicy(
  strategy: AgentSelectionStrategy = AgentSelectionStrategy.CapabilityMatch,
  fallbackAgents: List[String] = Nil,
  forcedAgent: Option[String] = None,
) derives JsonCodec

case class WorkflowNode(
  id: String,
  step: TaskStep,
  dependsOn: List[String] = Nil,
  condition: WorkflowCondition = WorkflowCondition.Always,
  retryLimit: Int = 0,
  parallelGroup: Option[String] = None,
  agentPolicy: Option[WorkflowAgentPolicy] = None,
) derives JsonCodec

case class WorkflowGraph(
  nodes: List[WorkflowNode]
) derives JsonCodec

object WorkflowGraph:
  def fromSequentialSteps(steps: List[TaskStep]): WorkflowGraph =
    WorkflowGraph(
      steps.zipWithIndex.map {
        case (step, index) =>
          WorkflowNode(
            id = s"${step.toString.toLowerCase}-$index",
            step = step,
            dependsOn = if index == 0 then Nil else List(s"${steps(index - 1).toString.toLowerCase}-${index - 1}"),
          )
      }
    )

case class WorkflowContext(
  dryRun: Boolean = false,
  flags: Set[String] = Set.empty,
  metadata: Map[String, String] = Map.empty,
  completedSteps: Set[TaskStep] = Set.empty,
  failedSteps: Set[TaskStep] = Set.empty,
) derives JsonCodec

case class WorkflowStepAgent(
  step: TaskStep,
  agentName: String,
) derives JsonCodec

case class WorkflowDefinition(
  id: Option[Long] = None,
  name: String,
  description: Option[String] = None,
  steps: List[TaskStep],
  stepAgents: List[WorkflowStepAgent] = Nil,
  isBuiltin: Boolean,
  dynamicGraph: Option[WorkflowGraph] = None,
) derives JsonCodec

object WorkflowDefinition:
  val defaultSteps: List[TaskStep] = List("chat")

  val default: WorkflowDefinition =
    WorkflowDefinition(
      id = None,
      name = "Chat Workflow",
      description = Some("Built-in single-step conversational workflow"),
      steps = defaultSteps,
      stepAgents = List(WorkflowStepAgent("chat", "chat-agent")),
      isBuiltin = true,
      dynamicGraph = Some(
        WorkflowGraph(
          List(WorkflowNode(id = "chat", step = "chat"))
        )
      ),
    )

object WorkflowValidator:
  def validate(workflow: WorkflowDefinition): Either[List[String], WorkflowDefinition] =
    val normalizedName     = workflow.name.trim
    val nameErrors         =
      if normalizedName.nonEmpty then Nil
      else List("Workflow name cannot be empty")
    val stepPresenceErrors =
      if workflow.steps.nonEmpty || workflow.dynamicGraph.exists(_.nodes.nonEmpty) then Nil
      else List("Workflow steps cannot be empty")
    val duplicateErrors    = duplicateStepErrors(workflow.steps)
    val graphErrors        = invalidGraph(workflow.dynamicGraph)
    val stepAgentErrors    = invalidStepAgents(
      workflow.steps ++ workflow.dynamicGraph.toList.flatMap(_.nodes.map(_.step)),
      workflow.stepAgents,
    )
    val allErrors          =
      (nameErrors ++ stepPresenceErrors ++ duplicateErrors ++ graphErrors ++ stepAgentErrors).distinct

    if allErrors.isEmpty then Right(workflow.copy(name = normalizedName))
    else Left(allErrors)

  private def duplicateStepErrors(steps: List[TaskStep]): List[String] =
    steps
      .groupBy(identity)
      .collect { case (step, instances) if instances.size > 1 => s"Duplicate step not allowed: ${step.toString}" }
      .toList
      .sorted

  private def invalidStepAgents(steps: List[TaskStep], stepAgents: List[WorkflowStepAgent]): List[String] =
    val includedSteps        = steps.toSet
    val duplicateAssignments = stepAgents
      .groupBy(_.step)
      .collect {
        case (step, assignments) if assignments.size > 1 =>
          s"Duplicate agent assignment for step ${step.toString}"
      }
      .toList

    val invalidAssignments = stepAgents.flatMap {
      case WorkflowStepAgent(step, _) if !includedSteps.contains(step) =>
        Some(s"Assigned agent for step ${step.toString}, but the step is not selected in workflow")
      case WorkflowStepAgent(_, rawAgent) if rawAgent.trim.isEmpty     =>
        Some("Assigned agent name cannot be empty")
      case _                                                           =>
        None
    }
    (duplicateAssignments ++ invalidAssignments).distinct

  private def invalidGraph(dynamicGraph: Option[WorkflowGraph]): List[String] =
    dynamicGraph match
      case None        => Nil
      case Some(graph) =>
        val ids               = graph.nodes.map(_.id.trim)
        val emptyIdErrors     = graph.nodes.collect {
          case node if node.id.trim.isEmpty =>
            "Workflow graph node id cannot be empty"
        }
        val duplicateIdErrors = ids
          .groupBy(identity)
          .collect { case (id, nodes) if nodes.size > 1 => s"Duplicate workflow graph node id: $id" }
          .toList
        val idsSet            = ids.toSet
        val dependencyErrors  = graph.nodes.flatMap { node =>
          node.dependsOn.flatMap { dep =>
            if dep == node.id then Some(s"Workflow graph node ${node.id} cannot depend on itself")
            else if !idsSet.contains(dep) then Some(s"Workflow graph node ${node.id} depends on unknown node $dep")
            else None
          }
        }
        val cycleErrors       =
          if hasCycle(graph) then List("Workflow graph contains a dependency cycle")
          else Nil

        (emptyIdErrors ++ duplicateIdErrors ++ dependencyErrors ++ cycleErrors).distinct

  private def hasCycle(graph: WorkflowGraph): Boolean =
    val deps = graph.nodes.map(node => node.id -> node.dependsOn.toSet).toMap

    enum VisitState:
      case Visiting
      case Visited

    def visit(nodeId: String, seen: Map[String, VisitState]): (Boolean, Map[String, VisitState]) =
      seen.get(nodeId) match
        case Some(VisitState.Visiting) => (true, seen)
        case Some(VisitState.Visited)  => (false, seen)
        case None                      =>
          val withVisiting            = seen.updated(nodeId, VisitState.Visiting)
          val (foundCycle, afterDeps) = deps.getOrElse(nodeId, Set.empty).foldLeft((false, withVisiting)) {
            case ((true, state), _)      => (true, state)
            case ((false, state), depId) =>
              visit(depId, state)
          }
          if foundCycle then (true, afterDeps)
          else (false, afterDeps.updated(nodeId, VisitState.Visited))

    graph.nodes.foldLeft((false, Map.empty[String, VisitState])) {
      case ((true, seen), _)     => (true, seen)
      case ((false, seen), node) => visit(node.id, seen)
    }._1
