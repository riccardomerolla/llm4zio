package llm4zio.agents

import zio.*
import zio.json.*
import zio.json.ast.Json

enum AgentError derives JsonCodec:
  case ValidationError(message: String)
  case ExecutionError(agent: String, message: String)
  case RoutingError(message: String)
  case NotFound(agent: String)
  case Conflict(message: String)

object AgentError:
  extension (error: AgentError)
    def message: String = error match
      case AgentError.ValidationError(msg)     => msg
      case AgentError.ExecutionError(_, msg)   => msg
      case AgentError.RoutingError(msg)        => msg
      case AgentError.NotFound(agent)          => s"Agent not found: $agent"
      case AgentError.Conflict(msg)            => msg

case class AgentMetadata(
  name: String,
  capabilities: Set[String],
  version: String,
  description: String,
  priority: Int = 0,
) derives JsonCodec

case class AgentResult(
  agent: String,
  content: String,
  handoff: Option[AgentHandoff] = None,
  statePatch: Map[String, Json] = Map.empty,
  metadata: Map[String, String] = Map.empty,
) derives JsonCodec

case class AgentHandoff(
  targetAgent: String,
  reason: String,
  payload: Json = Json.Obj(),
) derives JsonCodec

trait Agent:
  def metadata: AgentMetadata
  def execute(input: String, context: AgentContext): IO[AgentError, AgentResult]

enum ConflictResolution derives JsonCodec:
  case HighestPriority
  case NewestVersion
  case FirstRegistered
  case FailOnConflict

object Agent:
  def validate(agent: Agent): IO[AgentError, Unit] =
    if agent.metadata.name.trim.isEmpty then
      ZIO.fail(AgentError.ValidationError("Agent name must be non-empty"))
    else if agent.metadata.capabilities.isEmpty then
      ZIO.fail(AgentError.ValidationError(s"Agent ${agent.metadata.name} must expose at least one capability"))
    else ZIO.unit

object AgentRouter:
  def route(
    capability: String,
    agents: List[Agent],
    strategy: ConflictResolution = ConflictResolution.HighestPriority,
  ): IO[AgentError, Agent] =
    val candidates = agents.filter(_.metadata.capabilities.contains(capability))

    candidates match
      case Nil => ZIO.fail(AgentError.RoutingError(s"No agent available for capability: $capability"))
      case head :: Nil => ZIO.succeed(head)
      case many =>
        strategy match
          case ConflictResolution.HighestPriority =>
            ZIO.succeed(many.maxBy(agent => (agent.metadata.priority, agent.metadata.name)))
          case ConflictResolution.NewestVersion   =>
            ZIO.succeed(many.maxBy(agent => (semanticVersionScore(agent.metadata.version), agent.metadata.name)))
          case ConflictResolution.FirstRegistered =>
            ZIO.succeed(many.head)
          case ConflictResolution.FailOnConflict  =>
            val names = many.map(_.metadata.name).mkString(", ")
            ZIO.fail(AgentError.Conflict(s"Multiple agents claim capability '$capability': $names"))

  private def semanticVersionScore(version: String): Long =
    val parts = version.split("\\.").toList
    val normalized = (parts ++ List("0", "0", "0")).take(3)
      .map(_.takeWhile(_.isDigit))
      .map(part => if part.isEmpty then 0L else part.toLong)

    normalized match
      case major :: minor :: patch :: Nil => major * 1_000_000L + minor * 1_000L + patch
      case _                              => 0L

object AgentCoordinator:
  def executeWithHandoff(
    input: String,
    initialAgent: Agent,
    context: AgentContext,
    agents: List[Agent],
    maxDepth: Int = 4,
  ): IO[AgentError, AgentResult] =
    if maxDepth <= 0 then ZIO.fail(AgentError.ValidationError("maxDepth must be > 0"))
    else loop(input, initialAgent, context, agents, depth = 0, maxDepth)

  def executeParallel(
    input: String,
    context: AgentContext,
    agents: List[Agent],
    aggregate: Chunk[AgentResult] => AgentResult = defaultAggregation,
  ): IO[AgentError, AgentResult] =
    ZIO
      .foreachPar(agents)(_.execute(input, context))
      .map(results => aggregate(Chunk.fromIterable(results)))

  private def loop(
    input: String,
    current: Agent,
    context: AgentContext,
    agents: List[Agent],
    depth: Int,
    maxDepth: Int,
  ): IO[AgentError, AgentResult] =
    if depth >= maxDepth then ZIO.fail(AgentError.ValidationError(s"Handoff depth exceeded limit: $maxDepth"))
    else
      for
        result <- current.execute(input, context)
        next <- result.handoff match
                  case None          => ZIO.succeed(result)
                  case Some(handoff) =>
                    for
                      target <- ZIO
                                  .fromOption(agents.find(_.metadata.name == handoff.targetAgent))
                                  .orElseFail(AgentError.NotFound(handoff.targetAgent))
                      nextInput = renderHandoffInput(input, result, handoff)
                      continued <- loop(nextInput, target, context, agents, depth + 1, maxDepth)
                    yield continued
      yield next

  private def renderHandoffInput(input: String, result: AgentResult, handoff: AgentHandoff): String =
    s"""$input
       |
       |Handoff from ${result.agent} to ${handoff.targetAgent}
       |Reason: ${handoff.reason}
       |Payload: ${handoff.payload.toJson}
       |
       |Continue based on this handoff context.
       |""".stripMargin

  private def defaultAggregation(results: Chunk[AgentResult]): AgentResult =
    val combinedContent = results.map(result => s"[${result.agent}] ${result.content}").mkString("\n")
    val mergedMetadata = results.foldLeft(Map.empty[String, String])(_ ++ _.metadata)

    AgentResult(
      agent = "parallel-coordinator",
      content = combinedContent,
      metadata = mergedMetadata + ("agents_executed" -> results.length.toString),
    )
