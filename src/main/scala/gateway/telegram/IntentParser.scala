package gateway.telegram

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
  private val DefaultClarificationOptions: List[String] =
    List("cobolAnalyzer", "dependencyMapper", "javaTransformer", "validationAgent", "documentationAgent")

  private val AgentKeywords: Map[String, List[String]] = Map(
    "cobolDiscovery"         -> List("discover", "inventory", "scan", "find files", "catalog"),
    "cobolAnalyzer"          -> List("analyze", "analysis", "understand", "explain", "inspect"),
    "businessLogicExtractor" -> List("business", "rules", "logic", "domain"),
    "dependencyMapper"       -> List("dependency", "dependencies", "map", "graph", "call flow"),
    "javaTransformer"        -> List("transform", "convert", "migration", "java", "spring"),
    "validationAgent"        -> List("validate", "validation", "check", "compile", "test"),
    "documentationAgent"     -> List("document", "docs", "readme", "report", "guide"),
  )

  def parse(
    message: String,
    state: IntentConversationState,
  ): IntentDecision =
    val normalized = normalize(message)

    if normalized.isEmpty || normalized.startsWith("/") then IntentDecision.Unknown
    else if state.pendingOptions.nonEmpty then resolveClarification(normalized, state.pendingOptions)
    else parseFromKeywords(normalized)

  private def parseFromKeywords(normalized: String): IntentDecision =
    val scored = scoreAgents(normalized)

    scored match
      case Nil                                        =>
        IntentDecision.Clarify(
          question = "I can route this to an agent. Which task do you want?",
          options = DefaultClarificationOptions,
        )
      case head :: Nil if head._2 > 0                 =>
        IntentDecision.Route(head._1, s"matched keywords for ${head._1}")
      case head :: second :: _ if head._2 > second._2 =>
        IntentDecision.Route(head._1, s"best keyword score ${head._2}")
      case _                                          =>
        IntentDecision.Clarify(
          question = "I found multiple possible intents. Please choose one option:",
          options = scored.map(_._1).take(5),
        )

  private def scoreAgents(normalized: String): List[(String, Int)] =
    AgentKeywords.toList
      .map {
        case (agent, keywords) =>
          val score = keywords.count(keyword => normalized.contains(keyword))
          (agent, score)
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

  private def parseIndex(normalized: String, max: Int): Option[Int] =
    normalized.toIntOption.flatMap { n =>
      val idx = n - 1
      if idx >= 0 && idx < max then Some(idx) else None
    }

  private def normalize(value: String): String =
    value.trim.toLowerCase
