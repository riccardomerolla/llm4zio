package conversation.control

import java.time.Instant

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestAspect.*

import conversation.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ BoardIssueId, ConversationId }

object AgentDialogueCoordinatorSpec extends ZIOSpecDefault:

  private val reviewer = AgentParticipant("review-agent", AgentRole.Reviewer, Instant.now)
  private val author   = AgentParticipant("code-agent", AgentRole.Author, Instant.now)
  private val issueId  = BoardIssueId("issue-1")

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("AgentDialogueCoordinator")(
    test("startDialogue creates a conversation and returns its id") {
      for
        coordinator <- ZIO.service[AgentDialogueCoordinator]
        convId      <- coordinator.startDialogue(issueId, reviewer, author, "Code review", "LGTM overall but found 2 issues")
        turn        <- coordinator.currentTurn(convId)
      yield assertTrue(
        convId.value.nonEmpty,
        turn.currentParticipant == "code-agent",
        turn.turnNumber == 1,
        turn.awaitingResponse,
        !turn.pausedByHuman,
      )
    },
    test("respondInDialogue advances the turn to the other participant") {
      for
        coordinator <- ZIO.service[AgentDialogueCoordinator]
        convId      <- coordinator.startDialogue(issueId, reviewer, author, "Review", "Please fix the null check")
        _           <- coordinator.respondInDialogue(convId, "code-agent", "Fixed in commit abc123")
        turn        <- coordinator.currentTurn(convId)
      yield assertTrue(
        turn.currentParticipant == "review-agent",
        turn.turnNumber == 2,
      )
    },
    test("humanIntervene pauses the dialogue") {
      for
        coordinator <- ZIO.service[AgentDialogueCoordinator]
        convId      <- coordinator.startDialogue(issueId, reviewer, author, "Review", "Checking changes")
        _           <- coordinator.humanIntervene(convId, "riccardo", "Hold on, let me look at this first")
        turn        <- coordinator.currentTurn(convId)
      yield assertTrue(
        turn.pausedByHuman
      )
    },
    test("concludeDialogue closes the conversation with outcome") {
      for
        coordinator <- ZIO.service[AgentDialogueCoordinator]
        convId      <- coordinator.startDialogue(issueId, reviewer, author, "Review", "All good")
        _           <- coordinator.concludeDialogue(convId, DialogueOutcome.Approved("No issues found"))
        repo        <- ZIO.service[ConversationRepository]
        conv        <- repo.get(convId)
      yield assertTrue(
        conv.state.isInstanceOf[ConversationState.Closed]
      )
    },
    test("awaitTurn resolves when the other agent responds") {
      for
        coordinator <- ZIO.service[AgentDialogueCoordinator]
        convId      <- coordinator.startDialogue(issueId, reviewer, author, "Review", "Please explain line 42")
        fiber       <- coordinator.awaitTurn(convId, "review-agent").fork
        _           <- awaitPromiseRegistered(convId, "review-agent")
        _           <- coordinator.respondInDialogue(convId, "code-agent", "Line 42 handles the edge case")
        message     <- fiber.join
      yield assertTrue(
        message.content == "Line 42 handles the edge case",
        message.sender == "code-agent",
      )
    },
    test("awaitTurn fails with NotFound when dialogue is concluded by other agent") {
      for
        coordinator <- ZIO.service[AgentDialogueCoordinator]
        convId      <- coordinator.startDialogue(issueId, reviewer, author, "Review", "Checking code")
        fiber       <- coordinator.awaitTurn(convId, "review-agent").fork
        _           <- awaitPromiseRegistered(convId, "review-agent")
        _           <- coordinator.concludeDialogue(convId, DialogueOutcome.Approved("All good"))
        result      <- fiber.await
      yield assert(result)(fails(isSubtype[PersistenceError.NotFound](anything)))
    },
  ).provide(
    AgentDialogueCoordinator.live,
    stubConversationRepositoryLayer,
    stubDialogueEventBusLayer,
  ) @@ timeout(30.seconds)

  // ── Stubs ──────────────────────────────────────────────────────────

  private val stubConversationRepositoryLayer: ULayer[ConversationRepository] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[ConversationId, List[ConversationEvent]]).map { store =>
        new ConversationRepository:
          def append(event: ConversationEvent): IO[PersistenceError, Unit] =
            store.update { m =>
              val existing = m.getOrElse(event.conversationId, Nil)
              m.updated(event.conversationId, existing :+ event)
            }

          def get(id: ConversationId): IO[PersistenceError, Conversation] =
            store.get.flatMap { m =>
              m.get(id) match
                case Some(events) =>
                  Conversation.fromEvents(events) match
                    case Right(conv) => ZIO.succeed(conv)
                    case Left(err)   => ZIO.fail(PersistenceError.QueryFailed("rebuild", err))
                case None         =>
                  ZIO.fail(PersistenceError.NotFound("Conversation", id.value))
            }

          def list(filter: ConversationFilter): IO[PersistenceError, List[Conversation]] =
            store.get.map { m =>
              m.values.toList.flatMap(events => Conversation.fromEvents(events).toOption)
            }
      }
    )

  private val stubDialogueEventBusLayer: ULayer[Hub[DialogueEvent]] =
    ZLayer.fromZIO(Hub.unbounded[DialogueEvent])

  /** Yield until the forked `awaitTurn` fiber has registered its promise. */
  private def awaitPromiseRegistered(convId: ConversationId, agentName: String): URIO[AgentDialogueCoordinator, Unit] =
    ZIO.serviceWithZIO[AgentDialogueCoordinator] { coordinator =>
      val live = coordinator.asInstanceOf[AgentDialogueCoordinatorLive]
      live.promises.get.map(_.contains((convId, agentName))).repeatUntil(b => b).unit
    }
