package agents

import java.time.Instant

import zio.*

import db.CustomAgentRow
import models.*

/** Enhanced agent registry with capability discovery and dynamic registration
  */
trait AgentRegistry:
  /** Register an agent dynamically
    */
  def registerAgent(request: RegisterAgentRequest): UIO[AgentInfo]

  /** Find agent by name
    */
  def findByName(name: String): UIO[Option[AgentInfo]]

  /** Find agents matching query criteria
    */
  def findAgents(query: AgentQuery): UIO[List[AgentInfo]]

  /** Get all registered agents
    */
  def getAllAgents: UIO[List[AgentInfo]]

  /** Find agents with specific skill
    */
  def findAgentsWithSkill(skill: String): UIO[List[AgentInfo]]

  /** Find agents for specific migration step
    */
  def findAgentsForStep(step: TaskStep): UIO[List[AgentInfo]]

  /** Find agents that can transform from input type to output type
    */
  def findAgentsForTransformation(inputType: String, outputType: String): UIO[List[AgentInfo]]

  /** Update agent metrics
    */
  def recordInvocation(
    agentName: String,
    success: Boolean,
    latencyMs: Long,
  ): UIO[Unit]

  /** Update agent health
    */
  def updateHealth(agentName: String, success: Boolean, message: Option[String]): UIO[Unit]

  /** Enable/disable an agent
    */
  def setAgentEnabled(agentName: String, enabled: Boolean): UIO[Unit]

  /** Get agent metrics
    */
  def getMetrics(agentName: String): UIO[Option[AgentMetrics]]

  /** Get agent health
    */
  def getHealth(agentName: String): UIO[Option[AgentHealth]]

  /** Load custom agents from database
    */
  def loadCustomAgents(customAgents: List[CustomAgentRow]): UIO[Int]

  /** Get agents ranked by performance
    */
  def getRankedAgents(query: AgentQuery): UIO[List[AgentInfo]]

object AgentRegistry:

  def registerAgent(request: RegisterAgentRequest): ZIO[AgentRegistry, Nothing, AgentInfo] =
    ZIO.serviceWithZIO[AgentRegistry](_.registerAgent(request))

  def findByName(name: String): ZIO[AgentRegistry, Nothing, Option[AgentInfo]] =
    ZIO.serviceWithZIO[AgentRegistry](_.findByName(name))

  def findAgents(query: AgentQuery): ZIO[AgentRegistry, Nothing, List[AgentInfo]] =
    ZIO.serviceWithZIO[AgentRegistry](_.findAgents(query))

  def getAllAgents: ZIO[AgentRegistry, Nothing, List[AgentInfo]] =
    ZIO.serviceWithZIO[AgentRegistry](_.getAllAgents)

  def findAgentsWithSkill(skill: String): ZIO[AgentRegistry, Nothing, List[AgentInfo]] =
    ZIO.serviceWithZIO[AgentRegistry](_.findAgentsWithSkill(skill))

  def findAgentsForStep(step: TaskStep): ZIO[AgentRegistry, Nothing, List[AgentInfo]] =
    ZIO.serviceWithZIO[AgentRegistry](_.findAgentsForStep(step))

  def findAgentsForTransformation(
    inputType: String,
    outputType: String,
  ): ZIO[AgentRegistry, Nothing, List[AgentInfo]] =
    ZIO.serviceWithZIO[AgentRegistry](_.findAgentsForTransformation(inputType, outputType))

  def recordInvocation(
    agentName: String,
    success: Boolean,
    latencyMs: Long,
  ): ZIO[AgentRegistry, Nothing, Unit] =
    ZIO.serviceWithZIO[AgentRegistry](_.recordInvocation(agentName, success, latencyMs))

  def updateHealth(
    agentName: String,
    success: Boolean,
    message: Option[String],
  ): ZIO[AgentRegistry, Nothing, Unit] =
    ZIO.serviceWithZIO[AgentRegistry](_.updateHealth(agentName, success, message))

  def setAgentEnabled(agentName: String, enabled: Boolean): ZIO[AgentRegistry, Nothing, Unit] =
    ZIO.serviceWithZIO[AgentRegistry](_.setAgentEnabled(agentName, enabled))

  def getMetrics(agentName: String): ZIO[AgentRegistry, Nothing, Option[AgentMetrics]] =
    ZIO.serviceWithZIO[AgentRegistry](_.getMetrics(agentName))

  def getHealth(agentName: String): ZIO[AgentRegistry, Nothing, Option[AgentHealth]] =
    ZIO.serviceWithZIO[AgentRegistry](_.getHealth(agentName))

  def loadCustomAgents(customAgents: List[CustomAgentRow]): ZIO[AgentRegistry, Nothing, Int] =
    ZIO.serviceWithZIO[AgentRegistry](_.loadCustomAgents(customAgents))

  def getRankedAgents(query: AgentQuery): ZIO[AgentRegistry, Nothing, List[AgentInfo]] =
    ZIO.serviceWithZIO[AgentRegistry](_.getRankedAgents(query))

  /** Built-in agent definitions with enhanced capabilities
    */
  val builtInAgents: List[AgentInfo] = List(
    AgentInfo(
      name = "chat-agent",
      displayName = "Chat Agent",
      description = "Handles conversational AI interactions from any messaging channel.",
      agentType = AgentType.BuiltIn,
      usesAI = true,
      tags = List("chat", "conversation", "gateway"),
      skills = List(
        AgentSkill(
          skill = "chat",
          description = "Process user messages and produce conversational replies",
          inputTypes = List("Message", "ConversationContext"),
          outputTypes = List("AgentReply"),
          constraints = List(AgentConstraint.RequiresAI, AgentConstraint.MaxExecutionSeconds(90)),
        )
      ),
      supportedSteps = Nil,
      version = "1.0.0",
    ),
    AgentInfo(
      name = "code-agent",
      displayName = "Code Agent",
      description = "Assists with coding tasks: generation, review, debugging, and explanation.",
      agentType = AgentType.BuiltIn,
      usesAI = true,
      tags = List("code", "generation", "review"),
      skills = List(
        AgentSkill(
          skill = "code-generation",
          description = "Generate or modify code from task context",
          inputTypes = List("TaskContext", "SourceCode", "Prompt"),
          outputTypes = List("SourceCode", "Patch"),
          constraints = List(AgentConstraint.RequiresAI, AgentConstraint.MaxExecutionSeconds(180)),
        ),
        AgentSkill(
          skill = "code-review",
          description = "Review code and report correctness and quality issues",
          inputTypes = List("SourceCode", "Diff", "TaskContext"),
          outputTypes = List("ReviewReport"),
          constraints = List(AgentConstraint.RequiresAI, AgentConstraint.MaxExecutionSeconds(180)),
        ),
      ),
      supportedSteps = Nil,
      version = "1.0.0",
    ),
    AgentInfo(
      name = "task-planner",
      displayName = "Task Planner",
      description = "Breaks down complex user requests into structured task steps.",
      agentType = AgentType.BuiltIn,
      usesAI = true,
      tags = List("planning", "tasks", "workflow"),
      skills = List(
        AgentSkill(
          skill = "task-planning",
          description = "Create an execution plan from user intent and context",
          inputTypes = List("Message", "TaskContext"),
          outputTypes = List("TaskPlan"),
          constraints = List(AgentConstraint.RequiresAI, AgentConstraint.MaxExecutionSeconds(120)),
        )
      ),
      supportedSteps = Nil,
      version = "1.0.0",
    ),
    AgentInfo(
      name = "web-search-agent",
      displayName = "Web Search Agent",
      description = "Searches the web and returns summarised results.",
      agentType = AgentType.BuiltIn,
      usesAI = false,
      tags = List("search", "web", "research"),
      skills = List(
        AgentSkill(
          skill = "web-search",
          description = "Search public sources and return concise summaries",
          inputTypes = List("SearchQuery"),
          outputTypes = List("SearchResults", "Summary"),
          constraints = List(
            AgentConstraint.MaxExecutionSeconds(60)
          ),
        )
      ),
      supportedSteps = Nil,
      version = "1.0.0",
    ),
    AgentInfo(
      name = "file-agent",
      displayName = "File Agent",
      description = "Reads and writes files in the workspace.",
      agentType = AgentType.BuiltIn,
      usesAI = false,
      tags = List("files", "workspace", "io"),
      skills = List(
        AgentSkill(
          skill = "file-read",
          description = "Read files from the workspace",
          inputTypes = List("FilePath"),
          outputTypes = List("FileContent"),
          constraints = List(AgentConstraint.RequiresFileSystem),
        ),
        AgentSkill(
          skill = "file-write",
          description = "Write files in the workspace",
          inputTypes = List("FilePath", "FileContent"),
          outputTypes = List("WriteResult"),
          constraints = List(AgentConstraint.RequiresFileSystem),
        ),
      ),
      supportedSteps = Nil,
      version = "1.0.0",
    ),
    AgentInfo(
      name = "report-agent",
      displayName = "Report Agent",
      description = "Generates markdown reports and mermaid diagrams from task artifacts.",
      agentType = AgentType.BuiltIn,
      usesAI = false,
      tags = List("reports", "markdown", "mermaid"),
      skills = List(
        AgentSkill(
          skill = "report-generation",
          description = "Generate report artifacts from task outputs",
          inputTypes = List("TaskArtifacts", "TaskContext"),
          outputTypes = List("MarkdownReport", "MermaidDiagram"),
          constraints = List(AgentConstraint.RequiresFileSystem),
        )
      ),
      supportedSteps = Nil,
      version = "1.0.0",
    ),
    AgentInfo(
      name = "router-agent",
      displayName = "Router Agent",
      description = "Classifies user intent and routes to the appropriate agent or workflow.",
      agentType = AgentType.BuiltIn,
      usesAI = true,
      tags = List("routing", "intent", "orchestration"),
      skills = List(
        AgentSkill(
          skill = "intent-routing",
          description = "Classify incoming requests and select downstream handlers",
          inputTypes = List("Message", "TaskContext"),
          outputTypes = List("RoutingDecision"),
          constraints = List(AgentConstraint.RequiresAI, AgentConstraint.MaxExecutionSeconds(60)),
        )
      ),
      supportedSteps = Nil,
      version = "1.0.0",
    ),
  )

  /** Backward-compatible static methods for controllers that don't have AgentRegistry in environment
    */
  def allAgents(customAgents: List[CustomAgentRow]): List[AgentInfo] =
    val builtInNamesLower = builtInAgents.map(_.name.toLowerCase).toSet
    val customMapped      = customAgents
      .filterNot(agent => builtInNamesLower.contains(agent.name.trim.toLowerCase))
      .groupBy(_.name.trim.toLowerCase)
      .values
      .map(_.head)
      .toList
      .sortBy(_.displayName.toLowerCase)
      .map(toCustomAgentInfo)

    builtInAgents ++ customMapped

  private[agents] def toCustomAgentInfo(agent: CustomAgentRow): AgentInfo =
    AgentInfo(
      name = agent.name,
      displayName = agent.displayName,
      description = agent.description.getOrElse("Custom agent"),
      agentType = AgentType.Custom,
      usesAI = true,
      tags = agent.tags.toList.flatMap(splitTags),
      skills = Nil,
      supportedSteps = Nil,
      version = "1.0.0",
      metrics = AgentMetrics(),
      health = AgentHealth(status = AgentHealthStatus.Healthy, isEnabled = true),
    )

  private def splitTags(raw: String): List[String] =
    raw.split(",").toList.map(_.trim).filter(_.nonEmpty)

  val live: ZLayer[Any, Nothing, AgentRegistry] = ZLayer {
    for
      agentsRef <- Ref.Synchronized.make[Map[String, AgentInfo]](
                     builtInAgents.map(a => a.name.toLowerCase -> a).toMap
                   )
    yield new AgentRegistryLive(agentsRef)
  }

final private[agents] class AgentRegistryLive(
  agents: Ref.Synchronized[Map[String, AgentInfo]]
) extends AgentRegistry:

  override def registerAgent(request: RegisterAgentRequest): UIO[AgentInfo] =
    val agentInfo = AgentInfo(
      name = request.name,
      displayName = request.displayName,
      description = request.description,
      agentType = request.agentType,
      usesAI = request.usesAI,
      tags = request.tags,
      skills = request.skills,
      supportedSteps = request.supportedSteps,
      version = request.version,
      metrics = AgentMetrics(),
      health = AgentHealth(status = AgentHealthStatus.Healthy, isEnabled = true),
    )
    agents.update(_ + (request.name.toLowerCase -> agentInfo)).as(agentInfo)

  override def findByName(name: String): UIO[Option[AgentInfo]] =
    agents.get.map(_.get(name.trim.toLowerCase))

  override def findAgents(query: AgentQuery): UIO[List[AgentInfo]] =
    agents.get.map { allAgents =>
      allAgents.values.toList.filter { agent =>
        val matchSkill       = query.skill.forall(s => agent.skills.exists(_.skill == s))
        val matchInputType   =
          query.inputType.forall(it => agent.skills.exists(_.inputTypes.contains(it)))
        val matchOutputType  =
          query.outputType.forall(ot => agent.skills.exists(_.outputTypes.contains(ot)))
        val matchStep        = query.supportedStep.forall(agent.supportedSteps.contains)
        val matchSuccessRate =
          query.minSuccessRate.forall(msr => agent.metrics.successRate >= msr)
        val matchEnabled     = !query.onlyEnabled || agent.health.isEnabled

        matchSkill && matchInputType && matchOutputType && matchStep && matchSuccessRate && matchEnabled
      }
    }

  override def getAllAgents: UIO[List[AgentInfo]] =
    agents.get.map(_.values.toList.sortBy(_.name))

  override def findAgentsWithSkill(skill: String): UIO[List[AgentInfo]] =
    findAgents(AgentQuery(skill = Some(skill)))

  override def findAgentsForStep(step: TaskStep): UIO[List[AgentInfo]] =
    findAgents(AgentQuery(supportedStep = Some(step)))

  override def findAgentsForTransformation(inputType: String, outputType: String): UIO[List[AgentInfo]] =
    findAgents(AgentQuery(inputType = Some(inputType), outputType = Some(outputType)))

  override def recordInvocation(agentName: String, success: Boolean, latencyMs: Long): UIO[Unit] =
    for
      now <- Clock.instant
      _   <- agents.updateZIO { allAgents =>
               allAgents.get(agentName.toLowerCase) match
                 case None        => ZIO.succeed(allAgents)
                 case Some(agent) =>
                   val updated = agent.copy(metrics = agent.metrics.recordInvocation(success, latencyMs, now))
                   ZIO.succeed(allAgents + (agentName.toLowerCase -> updated))
             }
    yield ()

  override def updateHealth(agentName: String, success: Boolean, message: Option[String]): UIO[Unit] =
    for
      now <- Clock.instant
      _   <- agents.updateZIO { allAgents =>
               allAgents.get(agentName.toLowerCase) match
                 case None        => ZIO.succeed(allAgents)
                 case Some(agent) =>
                   val updated =
                     if success then agent.copy(health = agent.health.recordSuccess(now))
                     else agent.copy(health = agent.health.recordFailure(now, message.getOrElse("Unknown error")))
                   ZIO.succeed(allAgents + (agentName.toLowerCase -> updated))
             }
    yield ()

  override def setAgentEnabled(agentName: String, enabled: Boolean): UIO[Unit] =
    agents.updateZIO { allAgents =>
      allAgents.get(agentName.toLowerCase) match
        case None        => ZIO.succeed(allAgents)
        case Some(agent) =>
          val updated =
            if enabled then agent.copy(health = agent.health.enable())
            else agent.copy(health = agent.health.disable("Manually disabled"))
          ZIO.succeed(allAgents + (agentName.toLowerCase -> updated))
    }

  override def getMetrics(agentName: String): UIO[Option[AgentMetrics]] =
    findByName(agentName).map(_.map(_.metrics))

  override def getHealth(agentName: String): UIO[Option[AgentHealth]] =
    findByName(agentName).map(_.map(_.health))

  override def loadCustomAgents(customAgents: List[CustomAgentRow]): UIO[Int] =
    agents.get.flatMap { currentAgents =>
      val builtInNamesLower = AgentRegistry.builtInAgents.map(_.name.toLowerCase).toSet
      val deduplicated      = customAgents
        .filterNot(agent => builtInNamesLower.contains(agent.name.trim.toLowerCase))
        .groupBy(_.name.trim.toLowerCase)
        .values
        .map(_.head)
        .toList

      ZIO
        .foreach(deduplicated) { customAgent =>
          val agentInfo = AgentRegistry.toCustomAgentInfo(customAgent)
          agents.update(_ + (agentInfo.name.toLowerCase -> agentInfo)).as(1)
        }
        .map(_.sum)
    }

  override def getRankedAgents(query: AgentQuery): UIO[List[AgentInfo]] =
    findAgents(query).map { matchingAgents =>
      matchingAgents.sortBy { agent =>
        val healthScore  = agent.health.status match
          case AgentHealthStatus.Healthy   => 100
          case AgentHealthStatus.Degraded  => 50
          case AgentHealthStatus.Unhealthy => 10
          case AgentHealthStatus.Unknown   => 75
        val successScore = agent.metrics.successRate * 100
        val latencyScore = Math.max(0, 100 - agent.metrics.averageLatencyMs / 1000)
        val enabledScore = if agent.health.isEnabled then 100 else 0
        -(healthScore + successScore + latencyScore + enabledScore)
      }
    }
