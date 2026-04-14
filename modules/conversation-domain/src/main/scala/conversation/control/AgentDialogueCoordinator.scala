package conversation.control

import java.time.Instant
import java.util.UUID

import zio.*

import conversation.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ BoardIssueId, ConversationId, MessageId }

trait AgentDialogueCoordinator:
  def startDialogue(
    issueId: BoardIssueId,
    initiator: AgentParticipant,
    respondent: AgentParticipant,
    topic: String,
    openingMessage: String,
  ): IO[PersistenceError, ConversationId]

  def respondInDialogue(
    conversationId: ConversationId,
    agentName: String,
    message: String,
  ): IO[PersistenceError, Unit]

  def humanIntervene(
    conversationId: ConversationId,
    userId: String,
    message: String,
  ): IO[PersistenceError, Unit]

  def concludeDialogue(
    conversationId: ConversationId,
    outcome: DialogueOutcome,
  ): IO[PersistenceError, Unit]

  def currentTurn(conversationId: ConversationId): IO[PersistenceError, TurnState]

  def awaitTurn(conversationId: ConversationId, agentName: String): IO[PersistenceError, Message]

object AgentDialogueCoordinator:
  val live: ZLayer[ConversationRepository & Hub[DialogueEvent], Nothing, AgentDialogueCoordinator] =
    ZLayer {
      for
        repo     <- ZIO.service[ConversationRepository]
        hub      <- ZIO.service[Hub[DialogueEvent]]
        turns    <- Ref.make(Map.empty[ConversationId, TurnState])
        promises <- Ref.make(Map.empty[(ConversationId, String), Promise[PersistenceError, Message]])
      yield AgentDialogueCoordinatorLive(repo, hub, turns, promises)
    }

final case class AgentDialogueCoordinatorLive(
  repo: ConversationRepository,
  dialogueHub: Hub[DialogueEvent],
  turns: Ref[Map[ConversationId, TurnState]],
  promises: Ref[Map[(ConversationId, String), Promise[PersistenceError, Message]]],
) extends AgentDialogueCoordinator:

  def startDialogue(
    issueId: BoardIssueId,
    initiator: AgentParticipant,
    respondent: AgentParticipant,
    topic: String,
    openingMessage: String,
  ): IO[PersistenceError, ConversationId] =
    val convId = ConversationId(UUID.randomUUID().toString)
    val now    = Instant.now
    val msgId  = MessageId(UUID.randomUUID().toString)
    for
      _        <- repo.append(
                    ConversationEvent.Created(
                      conversationId = convId,
                      channel = ChannelInfo.AgentToAgent(issueId, List(initiator, respondent)),
                      title = topic,
                      description =
                        s"A2A dialogue: ${initiator.agentName} (${initiator.role}) ↔ ${respondent.agentName} (${respondent.role})",
                      runId = None,
                      createdBy = Some(initiator.agentName),
                      occurredAt = now,
                    )
                  )
      msg       = Message(
                    id = msgId,
                    sender = initiator.agentName,
                    senderType = SenderType.Agent(initiator.role),
                    content = openingMessage,
                    messageType = MessageType.Text(),
                    createdAt = now,
                  )
      _        <- repo.append(ConversationEvent.MessageSent(convId, msg, now))
      turnState = TurnState(
                    conversationId = convId,
                    currentParticipant = respondent.agentName,
                    turnNumber = 1,
                    awaitingResponse = true,
                    pausedByHuman = false,
                  )
      _        <- turns.update(_.updated(convId, turnState))
      _        <- dialogueHub.publish(
                    DialogueEvent.DialogueStarted(convId, issueId, List(initiator, respondent), topic, now)
                  ).ignore
      _        <- dialogueHub.publish(
                    DialogueEvent.MessagePosted(convId, initiator.agentName, initiator.role, openingMessage, 0, now)
                  ).ignore
    yield convId

  def respondInDialogue(
    conversationId: ConversationId,
    agentName: String,
    message: String,
  ): IO[PersistenceError, Unit] =
    val now   = Instant.now
    val msgId = MessageId(UUID.randomUUID().toString)
    for
      state   <- getTurnOrFail(conversationId)
      conv    <- repo.get(conversationId)
      role     = resolveRole(conv, agentName)
      msg      = Message(
                   id = msgId,
                   sender = agentName,
                   senderType = SenderType.Agent(role),
                   content = message,
                   messageType = MessageType.Text(),
                   createdAt = now,
                 )
      _       <- repo.append(ConversationEvent.MessageSent(conversationId, msg, now))
      nextName = resolveOtherParticipant(conv, agentName)
      nextTurn = state.copy(
                   currentParticipant = nextName,
                   turnNumber = state.turnNumber + 1,
                   awaitingResponse = true,
                   pausedByHuman = false,
                 )
      _       <- turns.update(_.updated(conversationId, nextTurn))
      _       <- dialogueHub.publish(
                   DialogueEvent.MessagePosted(conversationId, agentName, role, message, state.turnNumber, now)
                 ).ignore
      _       <- dialogueHub.publish(
                   DialogueEvent.TurnChanged(conversationId, nextName, now)
                 ).ignore
      _       <- fulfillPromise(conversationId, agentName, msg)
    yield ()

  def humanIntervene(
    conversationId: ConversationId,
    userId: String,
    message: String,
  ): IO[PersistenceError, Unit] =
    val now   = Instant.now
    val msgId = MessageId(UUID.randomUUID().toString)
    val msg   = Message(
      id = msgId,
      sender = userId,
      senderType = SenderType.User(),
      content = message,
      messageType = MessageType.Text(),
      createdAt = now,
    )
    for
      _ <- repo.append(ConversationEvent.MessageSent(conversationId, msg, now))
      _ <- turns.update(_.updatedWith(conversationId)(_.map(_.copy(pausedByHuman = true))))
      _ <- dialogueHub.publish(DialogueEvent.HumanIntervened(conversationId, userId, now)).ignore
    yield ()

  def concludeDialogue(
    conversationId: ConversationId,
    outcome: DialogueOutcome,
  ): IO[PersistenceError, Unit] =
    val now = Instant.now
    for
      _ <- repo.append(ConversationEvent.Closed(conversationId, now, now))
      _ <- turns.update(_ - conversationId)
      _ <- failPendingPromises(conversationId)
      _ <- dialogueHub.publish(DialogueEvent.DialogueConcluded(conversationId, outcome, now)).ignore
    yield ()

  def currentTurn(conversationId: ConversationId): IO[PersistenceError, TurnState] =
    getTurnOrFail(conversationId)

  def awaitTurn(conversationId: ConversationId, agentName: String): IO[PersistenceError, Message] =
    for
      promise <- Promise.make[PersistenceError, Message]
      _       <- promises.update(_.updated((conversationId, agentName), promise))
      msg     <- promise.await
    yield msg

  // ── Internal helpers ────────────────────────────────────────────────

  private def getTurnOrFail(conversationId: ConversationId): IO[PersistenceError, TurnState] =
    turns.get.flatMap { m =>
      m.get(conversationId) match
        case Some(state) => ZIO.succeed(state)
        case None        => ZIO.fail(PersistenceError.NotFound("TurnState", conversationId.value))
    }

  private def resolveOtherParticipant(conv: Conversation, currentAgent: String): String =
    conv.channel match
      case ChannelInfo.AgentToAgent(_, participants) =>
        participants.find(_.agentName != currentAgent).map(_.agentName).getOrElse(currentAgent)
      case _                                         => currentAgent

  private def resolveRole(conv: Conversation, agentName: String): AgentRole =
    conv.channel match
      case ChannelInfo.AgentToAgent(_, participants) =>
        participants.find(_.agentName == agentName).map(_.role).getOrElse(AgentRole.Author)
      case _                                         => AgentRole.Author

  private def failPendingPromises(conversationId: ConversationId): UIO[Unit] =
    promises.modify { m =>
      val (toFail, toKeep) = m.partition { case ((cid, _), _) => cid == conversationId }
      (toFail.values.toList, toKeep)
    }.flatMap { pending =>
      ZIO.foreachDiscard(pending)(_.fail(PersistenceError.NotFound("Dialogue", conversationId.value)).ignore)
    }

  private def fulfillPromise(conversationId: ConversationId, senderName: String, msg: Message): UIO[Unit] =
    promises.get.flatMap { m =>
      val waiting = m.collect {
        case ((cid, waiter), p) if cid == conversationId && waiter != senderName => (cid, waiter, p)
      }
      ZIO.foreachDiscard(waiting) {
        case (cid, waiter, p) =>
          p.succeed(msg) *> promises.update(_ - ((cid, waiter)))
      }
    }
