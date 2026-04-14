package conversation.control

import zio.*
import zio.json.*
import zio.json.ast.Json

import conversation.entity.*
import llm4zio.core.LlmService
import shared.errors.PersistenceError
import shared.ids.Ids.ConversationId

final case class AgentDialogueRunnerLive(
  coordinator: AgentDialogueCoordinator,
  repo: ConversationRepository,
  llm: LlmService,
) extends AgentDialogueRunner:

  def runDialogue(
    conversationId: ConversationId,
    agentRole: AgentRole,
    maxTurns: Int = 10,
  ): IO[PersistenceError, DialogueOutcome] =
    loop(conversationId, agentRole, agentName = "", maxTurns, turnsTaken = 0)

  private def loop(
    conversationId: ConversationId,
    agentRole: AgentRole,
    agentName: String,
    maxTurns: Int,
    turnsTaken: Int,
  ): IO[PersistenceError, DialogueOutcome] =
    if turnsTaken >= maxTurns then
      val outcome = DialogueOutcome.MaxTurnsReached(turnsTaken)
      coordinator.concludeDialogue(conversationId, outcome).as(outcome)
    else
      val resolvedAgentName = resolvedName(agentRole, agentName)
      val waitForTurn       = coordinator.awaitTurn(conversationId, resolvedAgentName)

      waitForTurn.foldZIO(
        {
          case PersistenceError.NotFound("Dialogue", _) =>
            repo.get(conversationId).map { conv =>
              conv.state match
                case ConversationState.Closed(_, _) => DialogueOutcome.Completed("Concluded by peer")
                case _                              => DialogueOutcome.Completed("Concluded")
            }
          case other                                    => ZIO.fail(other)
        },
        _ =>
          for
            conv     <- repo.get(conversationId)
            name      = resolveAgentName(conv, agentRole)
            prompt    = buildPrompt(conv, agentRole, name)
            response <- callLlm(prompt)
            _        <- coordinator.respondInDialogue(conversationId, name, response.content)
            outcome  <- concludeOrContinue(conversationId, agentRole, name, response, maxTurns, turnsTaken)
          yield outcome,
      )

  private def concludeOrContinue(
    conversationId: ConversationId,
    agentRole: AgentRole,
    name: String,
    response: AgentResponse,
    maxTurns: Int,
    turnsTaken: Int,
  ): IO[PersistenceError, DialogueOutcome] =
    if response.concluded then
      val out = response.outcome.getOrElse(DialogueOutcome.Approved("Concluded"))
      coordinator.concludeDialogue(conversationId, out).as(out)
    else
      loop(conversationId, agentRole, name, maxTurns, turnsTaken + 1)

  private def resolvedName(
    role: AgentRole,
    knownName: String,
  ): String =
    if knownName.nonEmpty then knownName
    else s"${role.toString.toLowerCase}-agent"

  private def resolveAgentName(conv: Conversation, role: AgentRole): String =
    conv.channel match
      case ChannelInfo.AgentToAgent(_, participants) =>
        participants.find(_.role == role).map(_.agentName).getOrElse(s"${role.toString.toLowerCase}-agent")
      case _                                         => s"${role.toString.toLowerCase}-agent"

  private def buildPrompt(conv: Conversation, role: AgentRole, agentName: String): String =
    val history = conv.messages
      .map(m => s"[${m.sender}]: ${m.content}")
      .mkString("\n")
    s"""You are $agentName with role ${role.toString} in an A2A dialogue.
       |
       |Conversation history:
       |$history
       |
       |Respond with a JSON object: {"content": "your message", "concluded": true/false, "outcome": null or {"Approved": {"summary": "..."}} or {"ChangesRequested": {"comments": ["..."]}} }
       |""".stripMargin

  private def callLlm(prompt: String): IO[PersistenceError, AgentResponse] =
    llm
      .executeStructured[AgentResponse](prompt, Json.Null)
      .mapError(err => PersistenceError.QueryFailed("LLM call failed", err.toString))

object AgentDialogueRunnerLive:
  val live: ZLayer[AgentDialogueCoordinator & ConversationRepository & LlmService, Nothing, AgentDialogueRunner] =
    ZLayer {
      for
        coordinator <- ZIO.service[AgentDialogueCoordinator]
        repo        <- ZIO.service[ConversationRepository]
        llm         <- ZIO.service[LlmService]
      yield AgentDialogueRunnerLive(coordinator, repo, llm)
    }
