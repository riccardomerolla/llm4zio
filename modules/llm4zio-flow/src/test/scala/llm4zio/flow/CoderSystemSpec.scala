package llm4zio.flow

import zio.*
import zio.json.JsonCodec
import zio.stream.{ Stream, ZStream }
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object CoderSystemSpec extends ZIOSpecDefault:

  /** Records the message history it is asked to stream, so we can inspect the system message. */
  private def recordingService(seen: Ref[List[Message]]): LlmService = new LlmService:
    def executeStream(p: String): Stream[LlmError, LlmChunk]                          = ZStream.empty
    def executeStreamWithHistory(m: List[Message]): Stream[LlmError, LlmChunk]        =
      ZStream.fromZIO(seen.set(m)).drain ++ ZStream.succeed(LlmChunk(delta = "ok"))
    def executeWithTools(p: String, t: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.fail(LlmError.InvalidRequestError("n/a"))
    def executeStructured[A: JsonCodec](p: String, s: JsonSchema): IO[LlmError, A]    =
      ZIO.fail(LlmError.InvalidRequestError("n/a"))
    def isAvailable: UIO[Boolean]                                                     = ZIO.succeed(true)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("CoderSystem")(
    test("gitOwnership instruction forbids commit/push/branch") {
      assertTrue(
        CoderSystem.gitOwnership.toLowerCase.contains("do not"),
        CoderSystem.gitOwnership.toLowerCase.contains("commit"),
        CoderSystem.gitOwnership.toLowerCase.contains("push"),
        CoderSystem.gitOwnership.toLowerCase.contains("branch"),
      )
    },
    test("Chat.start prepends gitOwnership by default") {
      for
        seen <- Ref.make(List.empty[Message])
        chat <- Chat.start(recordingService(seen), system = Some("Implement tasks."))
        _    <- chat.ask("do it")
        msgs <- seen.get
      yield assertTrue(
        msgs.head.role == MessageRole.System,
        msgs.head.content.contains(CoderSystem.gitOwnership),
        msgs.head.content.contains("Implement tasks."),
      )
    },
    test("Chat.start with no system prompt still injects gitOwnership (default)") {
      for
        seen <- Ref.make(List.empty[Message])
        chat <- Chat.start(recordingService(seen))
        _    <- chat.ask("x")
        msgs <- seen.get
      yield assertTrue(msgs.head.content == CoderSystem.gitOwnership)
    },
    test("Chat.start with manageGit=true omits the gitOwnership instruction") {
      for
        seen <- Ref.make(List.empty[Message])
        chat <- Chat.start(recordingService(seen), system = Some("Implement tasks."), manageGit = true)
        _    <- chat.ask("do it")
        msgs <- seen.get
      yield assertTrue(
        !msgs.head.content.contains(CoderSystem.gitOwnership),
        msgs.head.content == "Implement tasks.",
      )
    },
  )
