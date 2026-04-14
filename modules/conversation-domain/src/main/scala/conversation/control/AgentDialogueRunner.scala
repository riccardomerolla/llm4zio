package conversation.control

import zio.*

import conversation.entity.{ AgentRole, DialogueOutcome }
import shared.errors.PersistenceError
import shared.ids.Ids.ConversationId

trait AgentDialogueRunner:
  def runDialogue(
    conversationId: ConversationId,
    agentRole: AgentRole,
    maxTurns: Int = 10,
  ): IO[PersistenceError, DialogueOutcome]
