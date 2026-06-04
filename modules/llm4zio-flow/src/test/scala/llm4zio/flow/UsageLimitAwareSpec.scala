package llm4zio.flow

import java.time.Instant

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object UsageLimitAwareSpec extends ZIOSpecDefault:

  /** A service whose executeStructured fails with the given errors (one per call) then succeeds with "ok". */
  final class ScriptedService(failures: Ref[List[LlmError]]) extends LlmService:
    def executeStream(p: String): Stream[LlmError, LlmChunk]                          = ZStream.empty
    def executeStreamWithHistory(m: List[Message]): Stream[LlmError, LlmChunk]        = ZStream.empty
    def executeWithTools(p: String, t: List[AnyTool]): IO[LlmError, ToolCallResponse] = ZIO.dieMessage("unused")
    def executeStructured[A: JsonCodec](p: String, s: JsonSchema): IO[LlmError, A]    =
      failures.modify {
        case head :: tail => (Some(head), tail)
        case Nil          => (None, Nil)
      }.flatMap {
        case Some(err) => ZIO.fail(err)
        case None      => ZIO.fromEither("\"ok\"".fromJson[A]).orDieWith(e => new RuntimeException(e))
      }
    def isAvailable: UIO[Boolean] = ZIO.succeed(true)

  private def usageErr(at: Instant) = LlmError.UsageLimitError(Some(at), "codex", "limit")

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("UsageLimitAware")(
    test("waits until resetAt then retries (within cap)") {
      for
        events  <- FlowEvents.collecting
        now     <- Clock.instant
        fails   <- Ref.make(List[LlmError](usageErr(now.plusSeconds(7200)))) // 2h out
        svc      = UsageLimitAware(ScriptedService(fails), UsageLimitPolicy.patient)(using events)
        fiber   <- svc.executeStructured[String]("go", Json.Obj()).fork
        _       <- TestClock.adjust(2.hours + 1.minute)
        out     <- fiber.join
        emitted <- events.recorded
      yield assertTrue(out == "ok", emitted.exists { case FlowEvent.Info(m) => m.contains("usage limit"); case _ => false })
    },
    test("gives up (re-raises) when reset is beyond maxWait") {
      for
        events <- FlowEvents.collecting
        now    <- Clock.instant
        fails  <- Ref.make(List[LlmError](usageErr(now.plusSeconds(5 * 3600)))) // 5h out, cap 4h
        svc     = UsageLimitAware(ScriptedService(fails), UsageLimitPolicy.patient)(using events)
        exit   <- svc.executeStructured[String]("go", Json.Obj()).exit
      yield assertTrue(exit.isFailure)
    },
    test("disabled policy fails fast") {
      for
        events <- FlowEvents.collecting
        now    <- Clock.instant
        fails  <- Ref.make(List[LlmError](usageErr(now.plusSeconds(60))))
        svc     = UsageLimitAware(ScriptedService(fails), UsageLimitPolicy.off)(using events)
        exit   <- svc.executeStructured[String]("go", Json.Obj()).exit
      yield assertTrue(exit.isFailure)
    },
  )
