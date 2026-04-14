package conversation.control

import java.time.Instant

import zio.*
import zio.json.*
import zio.stream.*
import zio.test.*
import zio.test.Assertion.*

import conversation.entity.*
import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }
import shared.errors.PersistenceError
import shared.ids.Ids.{ BoardIssueId, ConversationId }

object AgentDialogueRunnerSpec extends ZIOSpecDefault:

  private val reviewerParticipant = AgentParticipant("reviewer-agent", AgentRole.Reviewer, Instant.now)
  private val authorParticipant   = AgentParticipant("author-agent", AgentRole.Author, Instant.now)
  private val issueId             = BoardIssueId("issue-1")

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("AgentDialogueRunner")(
    test("review passes after 1 round of critique and fix") {
      // Flow: reviewer initiates → author responds (manual) → reviewer critiques (LLM 0) →
      //       author fixes (manual) → reviewer approves (LLM 1, concluded=true)
      val reviewerResponses = List(
        AgentResponse("Found a null check issue on line 42", concluded = false, outcome = None),
        AgentResponse("Looks good now, approved", concluded = true, outcome = Some(DialogueOutcome.Approved("No issues found"))),
      )

      for
        repo        <- makeStubRepo
        hub         <- Hub.unbounded[DialogueEvent]
        events      <- Ref.make(List.empty[DialogueEvent])
        sub         <- hub.subscribe
        _           <- ZStream.fromQueue(sub).tap(e => events.update(_ :+ e)).runDrain.fork
        turns       <- Ref.make(Map.empty[ConversationId, TurnState])
        promises    <- Ref.make(Map.empty[(ConversationId, String), Promise[PersistenceError, conversation.entity.Message]])
        coordinator  = AgentDialogueCoordinatorLive(repo, hub, turns, promises)
        reviewerLlm  = makeMockLlm(reviewerResponses)
        runner       = AgentDialogueRunnerLive(coordinator, repo, reviewerLlm)
        convId      <- coordinator.startDialogue(issueId, reviewerParticipant, authorParticipant, "Code review", "Please review changes in PR #42")
        // Fork reviewer runner — it blocks on awaitTurn("reviewer-agent")
        revFiber    <- runner.runDialogue(convId, AgentRole.Reviewer, maxTurns = 10).fork
        _           <- ZIO.yieldNow
        // Simulate author's first response (turn is author's after startDialogue)
        _           <- coordinator.respondInDialogue(convId, "author-agent", "Thanks for the feedback, working on it")
        // Reviewer wakes, calls LLM (critique), respondInDialogue → turn goes to author
        // Wait for reviewer to finish responding by polling turn
        _           <- waitForTurn(turns, convId, "author-agent")
        // Simulate author's second response (fix)
        _           <- coordinator.respondInDialogue(convId, "author-agent", "Fixed the null check in commit abc123")
        // Reviewer wakes, calls LLM (approval, concluded=true), concludes dialogue
        revOutcome  <- revFiber.join
        _           <- ZIO.sleep(50.millis)
        conv        <- repo.get(convId)
        collected   <- events.get
      yield assertTrue(
        revOutcome == DialogueOutcome.Approved("No issues found"),
        conv.messages.size == 5,
        collected.exists(_.isInstanceOf[DialogueEvent.DialogueStarted]),
        collected.exists(_.isInstanceOf[DialogueEvent.DialogueConcluded]),
      )
    },
    test("review requests rework") {
      val reviewerResponses = List(
        AgentResponse("The error handling needs improvement", concluded = false, outcome = None),
        AgentResponse(
          "Still not right, requesting changes",
          concluded = true,
          outcome = Some(DialogueOutcome.ChangesRequested(List("Fix error handling", "Add tests"))),
        ),
      )

      for
        repo        <- makeStubRepo
        hub         <- Hub.unbounded[DialogueEvent]
        events      <- Ref.make(List.empty[DialogueEvent])
        sub         <- hub.subscribe
        _           <- ZStream.fromQueue(sub).tap(e => events.update(_ :+ e)).runDrain.fork
        turns       <- Ref.make(Map.empty[ConversationId, TurnState])
        promises    <- Ref.make(Map.empty[(ConversationId, String), Promise[PersistenceError, conversation.entity.Message]])
        coordinator  = AgentDialogueCoordinatorLive(repo, hub, turns, promises)
        reviewerLlm  = makeMockLlm(reviewerResponses)
        runner       = AgentDialogueRunnerLive(coordinator, repo, reviewerLlm)
        convId      <- coordinator.startDialogue(issueId, reviewerParticipant, authorParticipant, "Code review", "Reviewing error handling changes")
        revFiber    <- runner.runDialogue(convId, AgentRole.Reviewer, maxTurns = 10).fork
        _           <- ZIO.yieldNow
        _           <- coordinator.respondInDialogue(convId, "author-agent", "I see, let me address that")
        _           <- waitForTurn(turns, convId, "author-agent")
        _           <- coordinator.respondInDialogue(convId, "author-agent", "Updated the error handling")
        revOutcome  <- revFiber.join
        _           <- ZIO.sleep(50.millis)
        conv        <- repo.get(convId)
        collected   <- events.get
      yield assertTrue(
        revOutcome == DialogueOutcome.ChangesRequested(List("Fix error handling", "Add tests")),
        conv.messages.size == 5,
        collected.exists {
          case DialogueEvent.DialogueConcluded(_, DialogueOutcome.ChangesRequested(_), _) => true
          case _                                                                          => false
        },
      )
    },
    test("max turns reached concludes dialogue") {
      // With maxTurns=2, reviewer takes 2 turns then concludes with MaxTurnsReached
      val reviewerResponses = List(
        AgentResponse("Needs more work on line 10", concluded = false, outcome = None),
        AgentResponse("Still not right", concluded = false, outcome = None),
      )

      for
        repo        <- makeStubRepo
        hub         <- Hub.unbounded[DialogueEvent]
        events      <- Ref.make(List.empty[DialogueEvent])
        sub         <- hub.subscribe
        _           <- ZStream.fromQueue(sub).tap(e => events.update(_ :+ e)).runDrain.fork
        turns       <- Ref.make(Map.empty[ConversationId, TurnState])
        promises    <- Ref.make(Map.empty[(ConversationId, String), Promise[PersistenceError, conversation.entity.Message]])
        coordinator  = AgentDialogueCoordinatorLive(repo, hub, turns, promises)
        reviewerLlm  = makeMockLlm(reviewerResponses)
        runner       = AgentDialogueRunnerLive(coordinator, repo, reviewerLlm)
        convId      <- coordinator.startDialogue(issueId, reviewerParticipant, authorParticipant, "Code review", "Reviewing complex changes")
        revFiber    <- runner.runDialogue(convId, AgentRole.Reviewer, maxTurns = 2).fork
        _           <- ZIO.yieldNow
        // Author responds — triggers reviewer turn 1 (critique)
        _           <- coordinator.respondInDialogue(convId, "author-agent", "Working on it")
        _           <- waitForTurn(turns, convId, "author-agent")
        // Author responds — triggers reviewer turn 2 (max turns reached)
        _           <- coordinator.respondInDialogue(convId, "author-agent", "Updated again")
        revOutcome  <- revFiber.join
        _           <- ZIO.sleep(50.millis)
        conv        <- repo.get(convId)
        collected   <- events.get
      yield assertTrue(
        revOutcome == DialogueOutcome.MaxTurnsReached(2),
        conv.messages.size == 5,
        collected.exists {
          case DialogueEvent.DialogueConcluded(_, DialogueOutcome.MaxTurnsReached(_), _) => true
          case _                                                                         => false
        },
      )
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds)

  // ── Helpers ──────────────────────────────────────────────────────────

  /** Poll until the turn state shows the expected participant. */
  private def waitForTurn(
    turns: Ref[Map[ConversationId, TurnState]],
    convId: ConversationId,
    expectedParticipant: String,
  ): UIO[Unit] =
    turns.get
      .flatMap { m =>
        m.get(convId) match
          case Some(ts) if ts.currentParticipant == expectedParticipant => ZIO.succeed(())
          case _ => ZIO.fail("not yet")
      }
      .retry(Schedule.spaced(5.millis) && Schedule.recurs(200))
      .ignore

  private def makeStubRepo: UIO[ConversationRepository] =
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
          store.get.map(_.values.toList.flatMap(events => Conversation.fromEvents(events).toOption))
    }

  private def makeMockLlm(responses: List[AgentResponse]): LlmService =
    new LlmService:
      private val counter = new java.util.concurrent.atomic.AtomicInteger(0)

      def executeStream(prompt: String): Stream[LlmError, LlmChunk] =
        ZStream.fail(LlmError.ProviderError("Not implemented"))

      def executeStreamWithHistory(messages: List[llm4zio.core.Message]): Stream[LlmError, LlmChunk] =
        ZStream.fail(LlmError.ProviderError("Not implemented"))

      def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
        ZIO.fail(LlmError.ProviderError("Not implemented"))

      def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
        val idx  = counter.getAndIncrement()
        val resp = if idx < responses.size then responses(idx) else responses.last
        val json = resp.toJson
        summon[JsonCodec[A]].decoder.decodeJson(json) match
          case Right(a)  => ZIO.succeed(a)
          case Left(err) => ZIO.fail(LlmError.ParseError(err, json))

      def isAvailable: UIO[Boolean] = ZIO.succeed(true)
