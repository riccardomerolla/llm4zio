package llm4zio.flow

import java.nio.file.Files

import zio.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.{ AgentSession, LlmError, SessionEvent, SessionResult, TokenUsage }

object DriveSpec extends ZIOSpecDefault:

  private val usage = TokenUsage(100, 20, 120, None)

  /** A scripted [[AgentSession]] that replays a fixed event list and records what it was told. */
  final private class MockSession(
    scripted: List[SessionEvent],
    result: SessionResult,
    val sent: Ref[List[String]],
    val answers: Ref[List[String]],
  ) extends AgentSession:
    def events: ZStream[Any, LlmError, SessionEvent]                         = ZStream.fromIterable(scripted)
    def sendUserMessage(text: String): IO[LlmError, Unit]                    = sent.update(_ :+ text)
    def respondToAsk(answer: String): IO[LlmError, Unit]                     = answers.update(_ :+ answer)
    def respondToApproval(id: String, approved: Boolean): IO[LlmError, Unit] = ZIO.unit
    def awaitResult: IO[LlmError, SessionResult]                             = ZIO.succeed(result)
    def cancel: UIO[Unit]                                                    = ZIO.unit

  private def mock(scripted: List[SessionEvent], result: SessionResult): UIO[MockSession] =
    for
      sent    <- Ref.make(List.empty[String])
      answers <- Ref.make(List.empty[String])
    yield new MockSession(scripted, result, sent, answers)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Drive")(
    test("run relays events to FlowEvents, bridges AskUser, sends the message, returns the result") {
      val scripted = List(
        SessionEvent.TextDelta("working on it"),
        SessionEvent.ToolUse("Edit", """{"file_path":"src/lib.rs"}""", "t1"),
        SessionEvent.AskUser("which base branch?"),
        SessionEvent.Usage(usage, Some("claude-sonnet")),
        SessionEvent.Done("all done"),
      )
      val expected = SessionResult("all done", Some(usage), Some("claude-sonnet"))
      for
        events  <- FlowEvents.collecting
        session <- mock(scripted, expected)
        result  <- {
          given FlowEvents = events
          Drive.run(session, _ => ZIO.succeed("main"), "implement the parser")
        }
        emitted <- events.recorded
        sent    <- session.sent.get
        answers <- session.answers.get
      yield assertTrue(
        result == expected,
        sent == List("implement the parser"),
        answers == List("main"), // the human's answer was relayed back to the session
        emitted.contains(FlowEvent.AssistantMessage("working on it")),
        emitted.contains(FlowEvent.ToolUse("Edit", """{"file_path":"src/lib.rs"}""")),
        emitted.contains(FlowEvent.TokensUsed("coder", Some("claude-sonnet"), usage)),
      )
    },
    test("a failing Interaction fails the turn") {
      val scripted = List(SessionEvent.AskUser("ok?"), SessionEvent.Done(""))
      for
        events  <- FlowEvents.collecting
        session <- mock(scripted, SessionResult("", None, None))
        exit    <- {
          given FlowEvents = events
          Drive.run(session, _ => ZIO.fail(FlowError.Aborted("no human")), "go").exit
        }
      yield assertTrue(exit.isFailure)
    },
    test("implementTaskLoopLive drives each incomplete task, persists, and reports each result") {
      val plan     = Plan("epic-1", List(Task("t1", "do one"), Task("t2", "do two")))
      val expected = SessionResult("ok", Some(usage), Some("m"))
      val scripted = List(SessionEvent.TextDelta("done"), SessionEvent.Done("ok"))
      for
        tmp    <- ZIO.attempt(Files.createTempFile("plan-live-", ".md")).orDie
        events <- FlowEvents.collecting
        seen   <- Ref.make(List.empty[String])
        done   <- {
          given FlowEvents = events
          implementTaskLoopLive(
            tmp,
            plan,
            _ => ZIO.succeed("noop"),
            _ => ZIO.scoped(mock(scripted, expected)),
          )((task, result) => seen.update(_ :+ s"${task.title}:${result.text}"))
        }
        order  <- seen.get
        onDisk <- ZIO.attempt(Files.readString(tmp)).orDie
      yield assertTrue(
        done.tasks.forall(_.completed),
        order == List("t1:ok", "t2:ok"),
        onDisk.contains("[x] t1"),
        onDisk.contains("[x] t2"),
      )
    },
  )
