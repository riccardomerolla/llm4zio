package orchestration.entity

import zio.*

import _root_.config.entity.*
import shared.entity.TaskStep

trait AgentRegistry:
  def registerAgent(request: RegisterAgentRequest): UIO[AgentInfo]
  def findByName(name: String): UIO[Option[AgentInfo]]
  def findAgents(query: AgentQuery): UIO[List[AgentInfo]]
  def getAllAgents: UIO[List[AgentInfo]]
  def findAgentsWithSkill(skill: String): UIO[List[AgentInfo]]
  def findAgentsForStep(step: TaskStep): UIO[List[AgentInfo]]
  def findAgentsForTransformation(inputType: String, outputType: String): UIO[List[AgentInfo]]
  def recordInvocation(agentName: String, success: Boolean, latencyMs: Long): UIO[Unit]
  def updateHealth(agentName: String, success: Boolean, message: Option[String]): UIO[Unit]
  def setAgentEnabled(agentName: String, enabled: Boolean): UIO[Unit]
  def getMetrics(agentName: String): UIO[Option[AgentMetrics]]
  def getHealth(agentName: String): UIO[Option[AgentHealth]]
  def loadCustomAgents(customAgents: List[CustomAgentRow]): UIO[Int]
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

  val builtInAgents: List[AgentInfo] = List(
    AgentInfo(
      name = "chat-agent",
      handle = "chat-agent",
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
      handle = "code-agent",
      displayName = "Code Agent",
      description = "Assists with coding tasks: generation, review, debugging, and explanation.",
      agentType = AgentType.BuiltIn,
      usesAI = true,
      tags = List("code", "generation", "review", "code-generation", "code-review"),
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
      name = "architecture-agent",
      handle = "architecture-agent",
      displayName = "Architecture Agent",
      description = "Reviews codebases for module boundaries, dependencies, and architectural risks.",
      agentType = AgentType.BuiltIn,
      usesAI = true,
      tags = List("architecture", "architecture-analysis", "design", "code-review"),
      skills = List(
        AgentSkill(
          skill = "architecture-analysis",
          description = "Analyze repository structure, coupling, and architectural decisions",
          inputTypes = List("TaskContext", "SourceCode", "Repository"),
          outputTypes = List("ArchitectureReport", "ReviewReport"),
          constraints = List(AgentConstraint.RequiresAI, AgentConstraint.MaxExecutionSeconds(240)),
        )
      ),
      supportedSteps = Nil,
      version = "1.0.0",
    ),
    AgentInfo(
      name = "security-agent",
      handle = "security-agent",
      displayName = "Security Agent",
      description = "Reviews code and configuration for security weaknesses and exposure risks.",
      agentType = AgentType.BuiltIn,
      usesAI = true,
      tags = List("security", "security-analysis", "security-review", "code-review"),
      skills = List(
        AgentSkill(
          skill = "security-analysis",
          description = "Inspect source and configuration for security vulnerabilities and OWASP risks",
          inputTypes = List("TaskContext", "SourceCode", "Repository", "Configuration"),
          outputTypes = List("SecurityReport", "ReviewReport"),
          constraints = List(AgentConstraint.RequiresAI, AgentConstraint.MaxExecutionSeconds(240)),
        )
      ),
      supportedSteps = Nil,
      version = "1.0.0",
    ),
    AgentInfo(
      name = "task-planner",
      handle = "task-planner",
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
      handle = "web-search-agent",
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
      handle = "file-agent",
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
      handle = "report-agent",
      displayName = "Report Agent",
      description = "Generates markdown reports and mermaid diagrams from task artifacts.",
      agentType = AgentType.BuiltIn,
      usesAI = false,
      tags = List("reports", "markdown", "mermaid", "report-generation"),
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
      handle = "router-agent",
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

  private[orchestration] def toCustomAgentInfo(agent: CustomAgentRow): AgentInfo =
    AgentInfo(
      name = agent.name,
      handle = sanitizeHandle(agent.name),
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

  private[orchestration] def sanitizeHandle(raw: String): String =
    raw.trim.toLowerCase.replaceAll("[^a-z0-9_-]+", "-")
