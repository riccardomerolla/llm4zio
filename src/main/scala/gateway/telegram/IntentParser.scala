package gateway.telegram

import zio.*
import zio.json.*

import llm4zio.core.LlmService
import models.AgentInfo

final case class IntentConversationState(
  pendingOptions: List[String] = Nil,
  lastAgent: Option[String] = None,
  history: List[String] = Nil,
)

enum IntentDecision:
  case Route(agentName: String, rationale: String)
  case Clarify(question: String, options: List[String])
  case Unknown

object IntentParser:
  private val ConfidenceThreshold = 0.70

  def parse(
    message: String,
    state: IntentConversationState,
    availableAgents: List[AgentInfo],
  ): URIO[LlmService, IntentDecision] =
    val normalized = normalize(message)
    val options    = clarificationOptions(availableAgents)

    if normalized.isEmpty || normalized.startsWith("/") then ZIO.succeed(IntentDecision.Unknown)
    else if state.pendingOptions.nonEmpty then ZIO.succeed(resolveClarification(normalized, state.pendingOptions))
    else if state.lastAgent.nonEmpty then
      ZIO.succeed(IntentDecision.Route(state.lastAgent.get, "continuing with selected agent"))
    else
      parseWithLLM(message, availableAgents)
        .catchAll(_ => parseFromKeywords(normalized, availableAgents))
        .flatMap {
          case IntentDecision.Route(agent, _) if !options.contains(agent) =>
            parseFromKeywords(normalized, availableAgents)
          case IntentDecision.Clarify(question, opts) if opts.isEmpty      =>
            ZIO.succeed(IntentDecision.Clarify(question, options))
          case other                                                       =>
            ZIO.succeed(other)
        }

  private[telegram] def parseWithLLM(
    message: String,
    availableAgents: List[AgentInfo],
  ): ZIO[LlmService, llm4zio.core.LlmError, IntentDecision] =
    val options = clarificationOptions(availableAgents)
    val prompt  = buildPrompt(message, availableAgents)
    for
      isAvailable <- LlmService.isAvailable
      _           <- ZIO.fail(llm4zio.core.LlmError.ConfigError("llm unavailable")).unless(isAvailable)
      response    <- LlmService.execute(prompt)
      decoded     <- ZIO
                       .fromEither(response.content.fromJson[LlmRouterResponse])
                       .mapError(err => llm4zio.core.LlmError.ParseError(err, response.content))
    yield toDecision(decoded, options)

  private[telegram] def parseFromKeywords(
    normalized: String,
    availableAgents: List[AgentInfo],
  ): UIO[IntentDecision] =
    val options = clarificationOptions(availableAgents)
    val scored  = scoreAgents(normalized, availableAgents)

    scored match
      case Nil                                        =>
        ZIO.succeed(IntentDecision.Clarify(
          question = "I can route this to an agent. Which task do you want?",
          options = options,
        ))
      case head :: Nil if head._2 > 0                 =>
        ZIO.succeed(IntentDecision.Route(head._1, s"matched keywords for ${head._1}"))
      case head :: second :: _ if head._2 > second._2 =>
        ZIO.succeed(IntentDecision.Route(head._1, s"best keyword score ${head._2}"))
      case _                                          =>
        ZIO.succeed(IntentDecision.Clarify(
          question = "I found multiple possible intents. Please choose one option:",
          options = scored.map(_._1).take(5).distinct,
        ))

  private def scoreAgents(normalized: String, agents: List[AgentInfo]): List[(String, Int)] =
    val messageTerms = tokenize(normalized).toSet
    agents
      .map {
        agent =>
          val keywords = agentKeywords(agent)
          val score    = keywords.count(messageTerms.contains)
          (agent.name, score)
      }
      .filter(_._2 > 0)
      .sortBy { case (_, score) => -score }

  private def resolveClarification(normalized: String, options: List[String]): IntentDecision =
    parseIndex(normalized, options.length)
      .map(index => IntentDecision.Route(options(index), s"selected option ${index + 1}"))
      .orElse(
        options.find(option => normalized.contains(normalize(option))).map(agent =>
          IntentDecision.Route(agent, "selected by name during clarification")
        )
      )
      .getOrElse(
        IntentDecision.Clarify(
          question = "I still need one option number or agent name.",
          options = options,
        )
      )

  private def toDecision(parsed: LlmRouterResponse, options: List[String]): IntentDecision =
    if parsed.clarify.getOrElse(false) then
      IntentDecision.Clarify(
        question = parsed.question.filter(_.trim.nonEmpty).getOrElse(
          "I need clarification to route this request. Which agent should handle it?"
        ),
        options = parsed.options.filter(_.nonEmpty).map(_.filter(options.contains)).filter(_.nonEmpty).getOrElse(options),
      )
    else
      (parsed.agent, parsed.confidence) match
        case (Some(agent), Some(confidence)) if confidence >= ConfidenceThreshold && options.contains(agent) =>
          IntentDecision.Route(agent, f"llm confidence=$confidence%.2f")
        case _                                                                                               =>
          IntentDecision.Clarify(
            question = parsed.question.filter(_.trim.nonEmpty).getOrElse(
              "I’m not fully confident about the best agent. Please choose one:"
            ),
            options = parsed.options.filter(_.nonEmpty).map(_.filter(options.contains)).filter(_.nonEmpty).getOrElse(options),
          )

  private def buildPrompt(message: String, agents: List[AgentInfo]): String =
    val renderedAgents = agents.map { agent =>
      val skills = agent.skills.map(_.description).mkString("; ")
      s"- ${agent.name}: ${agent.description}. Skills: $skills. Tags: ${agent.tags.mkString(", ")}"
    }.mkString("\n")
    s"""You are a request router.
       |Classify the user message to one agent from this list:
       |$renderedAgents
       |
       |Return JSON only, with one of these shapes:
       |{"agent":"<name>","confidence":0.0}
       |{"clarify":true,"question":"...","options":["name1","name2",...]}
       |
       |User message:
       |$message
       |""".stripMargin

  private def clarificationOptions(agents: List[AgentInfo]): List[String] =
    agents.map(_.name).distinct.sorted

  private def agentKeywords(agent: AgentInfo): Set[String] =
    val fromSkills = agent.skills.flatMap(skill => tokenize(skill.description) ++ tokenize(skill.skill))
    val fromName   = tokenize(agent.name) ++ tokenize(agent.displayName)
    (agent.tags.flatMap(tokenize) ++ fromSkills ++ fromName).toSet

  private def tokenize(value: String): List[String] =
    value
      .toLowerCase
      .replace('-', ' ')
      .split("[^a-z0-9]+")
      .toList
      .map(_.trim)
      .filter(term => term.length >= 3)

  private def parseIndex(normalized: String, max: Int): Option[Int] =
    normalized.toIntOption.flatMap { n =>
      val idx = n - 1
      if idx >= 0 && idx < max then Some(idx) else None
    }

  private def normalize(value: String): String =
    value.trim.toLowerCase

  private final case class LlmRouterResponse(
    agent: Option[String] = None,
    confidence: Option[Double] = None,
    clarify: Option[Boolean] = None,
    question: Option[String] = None,
    options: Option[List[String]] = None,
  ) derives JsonCodec
