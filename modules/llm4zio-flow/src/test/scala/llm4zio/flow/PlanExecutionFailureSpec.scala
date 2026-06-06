package llm4zio.flow

import java.nio.file.{ Files, Path }

import zio.*
import zio.json.JsonCodec
import zio.stream.{ Stream, ZStream }
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

/** Failure-mode coverage for the plan-execution loop, driven against a scripted mock coder through the real stack
  * ([[implementTaskLoop]] → [[stage]] → [[Chat]] → optional [[TransientRetry]] / [[withUsageLimitRetry]]). One place to
  * assert how a flow behaves when the LLM coder misbehaves: what completes, what persists, what the user sees, and
  * whether the run recovers or fails.
  */
object PlanExecutionFailureSpec extends ZIOSpecDefault:

  // ── A coder we can script ────────────────────────────────────────────────────
  // Each call to executeStream(WithHistory) consumes the next scripted outcome — a success (emit one chunk) or a
  // failure (fail the stream with a typed LlmError). Past the script it falls back to `default`. `attempts` counts
  // every call, so retry behaviour is observable. A retried stream re-runs this, consuming the next entry — exactly
  // how a real retried turn re-invokes the provider.
  final class ScriptedCoder(
    script: Ref[List[IO[LlmError, String]]],
    attempts: Ref[Int],
    default: IO[LlmError, String],
  ) extends LlmService:
    private def next: Stream[LlmError, LlmChunk]                                               =
      ZStream.unwrap(
        (attempts.update(_ + 1) *> script.modify {
          case h :: t => (h, t)
          case Nil    => (default, Nil)
        }).map(io => ZStream.fromZIO(io).map(s => LlmChunk(delta = s, finishReason = Some("stop"))))
      )
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = next
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = next
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.dieMessage("unused")
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.dieMessage("unused")
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  private def coderWithDefault(
    default: IO[LlmError, String]
  )(
    outcomes: IO[LlmError, String]*
  ): UIO[(ScriptedCoder, Ref[Int])] =
    for
      script   <- Ref.make(outcomes.toList)
      attempts <- Ref.make(0)
    yield (ScriptedCoder(script, attempts, default), attempts)

  /** A scripted coder that, past its outcomes, keeps succeeding. */
  private def coder(outcomes: IO[LlmError, String]*): UIO[(ScriptedCoder, Ref[Int])] =
    coderWithDefault(ZIO.succeed("ok"))(outcomes*)

  /** A coder that always fails with `err`. */
  private def alwaysFailing(err: LlmError): UIO[(ScriptedCoder, Ref[Int])] =
    coderWithDefault(ZIO.fail(err))()

  // The per-task body the example flow uses: ask the coder, map its LlmError to FlowError.Llm (cause preserved).
  // NB: `import zio.*` brings `zio.Task`, which shadows `flow.Task` in type position — so qualify it here.
  private def implement(chat: Chat)(t: llm4zio.flow.Task): IO[FlowError, Unit] =
    chat.ask(t.description).mapError(e => FlowError.Llm(e.message, Some(e))).unit

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-planfail-")).orDie)(d =>
      ZIO.attempt {
        Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
      }.orDie
    )

  private def retryNotices(events: Chunk[FlowEvent]): Int =
    events.collect { case FlowEvent.Info(m) if m.contains("transient error") => m }.size

  private val connReset = LlmError.ProviderError("connection reset")

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("plan execution — failure modes")(
    test("happy path: every task runs, completes, and persists") {
      ZIO.scoped {
        for
          ev              <- FlowEvents.collecting
          given FlowEvents = ev
          dir             <- tempDir
          path             = dir.resolve("plan.md")
          (svc, attempts) <- coder(ZIO.succeed("a"), ZIO.succeed("b"))
          chat            <- Chat.start(svc, manageGit = true)
          plan             = Plan("epic", List(Task("t1", "do 1"), Task("t2", "do 2")))
          out             <- implementTaskLoop(path, plan)(implement(chat))
          disk            <- PlanStore.load(path)
          tries           <- attempts.get
          rec             <- ev.recorded
        yield assertTrue(
          out.tasks.forall(_.completed),
          disk.exists(_.tasks.forall(_.completed)),
          tries == 2,
          rec.contains(FlowEvent.StageCompleted("t1")),
          rec.contains(FlowEvent.StageCompleted("t2")),
        )
      }
    },
    test(
      "a non-transient coder failure stops the plan, marks the task failed, persists prior tasks, and does not retry"
    ) {
      ZIO.scoped {
        for
          ev              <- FlowEvents.collecting
          given FlowEvents = ev
          dir             <- tempDir
          path             = dir.resolve("plan.md")
          (svc, attempts) <- coder(ZIO.succeed("ok"), ZIO.fail(LlmError.InvalidRequestError("bad prompt")))
          chat            <- Chat.start(TransientRetry(svc, maxRetries = 3, baseDelay = Duration.Zero), manageGit = true)
          plan             = Plan("epic", List(Task("t1", "1"), Task("t2", "2"), Task("t3", "3")))
          res             <- implementTaskLoop(path, plan)(implement(chat)).either
          disk            <- PlanStore.load(path)
          tries           <- attempts.get
          rec             <- ev.recorded
        yield assertTrue(
          res.left.exists(_.message.contains("bad prompt")),
          disk.exists(p => p.tasks.find(_.title == "t1").exists(_.completed)),
          disk.exists(p => p.tasks.find(_.title == "t2").exists(!_.completed)),
          rec.exists { case FlowEvent.StageFailed("t2", _) => true; case _ => false },
          tries == 2, // t1 once, t2 once — InvalidRequestError is not retried
          retryNotices(rec) == 0,
        )
      }
    },
    test("a transient coder failure is retried (with ⟳ notices) and the task then completes") {
      ZIO.scoped {
        for
          ev              <- FlowEvents.collecting
          given FlowEvents = ev
          dir             <- tempDir
          path             = dir.resolve("plan.md")
          (svc, attempts) <- coder(ZIO.fail(connReset), ZIO.fail(connReset), ZIO.succeed("done"))
          chat            <- Chat.start(TransientRetry(svc, maxRetries = 3, baseDelay = Duration.Zero), manageGit = true)
          plan             = Plan("epic", List(Task("t1", "1")))
          out             <- implementTaskLoop(path, plan)(implement(chat))
          tries           <- attempts.get
          rec             <- ev.recorded
        yield assertTrue(
          out.tasks.forall(_.completed),
          tries == 3, // 2 failures + 1 success
          retryNotices(rec) == 2,
          rec.contains(FlowEvent.StageCompleted("t1")),
        )
      }
    },
    test("a persistent transient failure exhausts retries and fails the task") {
      ZIO.scoped {
        for
          ev              <- FlowEvents.collecting
          given FlowEvents = ev
          dir             <- tempDir
          path             = dir.resolve("plan.md")
          (svc, attempts) <- alwaysFailing(connReset)
          chat            <- Chat.start(TransientRetry(svc, maxRetries = 2, baseDelay = Duration.Zero), manageGit = true)
          plan             = Plan("epic", List(Task("t1", "1")))
          res             <- implementTaskLoop(path, plan)(implement(chat)).either
          tries           <- attempts.get
          rec             <- ev.recorded
        yield assertTrue(
          res.isLeft,
          tries == 3, // initial + 2 retries
          retryNotices(rec) == 2,
          rec.exists { case FlowEvent.StageFailed("t1", _) => true; case _ => false },
        )
      }
    },
    test("LLM4ZIO_RETRIES=0 semantics: maxRetries=0 fails fast with no retry notice") {
      ZIO.scoped {
        for
          ev              <- FlowEvents.collecting
          given FlowEvents = ev
          dir             <- tempDir
          path             = dir.resolve("plan.md")
          (svc, attempts) <- alwaysFailing(connReset)
          chat            <- Chat.start(TransientRetry(svc, maxRetries = 0, baseDelay = Duration.Zero), manageGit = true)
          plan             = Plan("epic", List(Task("t1", "1")))
          res             <- implementTaskLoop(path, plan)(implement(chat)).either
          tries           <- attempts.get
          rec             <- ev.recorded
        yield assertTrue(res.isLeft, tries == 1, retryNotices(rec) == 0)
      }
    },
    test("gemini's '[API Error: An unknown error occurred.]' is treated as transient and retried") {
      ZIO.scoped {
        for
          ev              <- FlowEvents.collecting
          given FlowEvents = ev
          dir             <- tempDir
          path             = dir.resolve("plan.md")
          apiErr           = LlmError.ProviderError("Gemini CLI returned an error: [API Error: An unknown error occurred.]")
          (svc, attempts) <- coder(ZIO.fail(apiErr), ZIO.succeed("done"))
          chat            <- Chat.start(TransientRetry(svc, maxRetries = 3, baseDelay = Duration.Zero), manageGit = true)
          plan             = Plan("epic", List(Task("t1", "1")))
          out             <- implementTaskLoop(path, plan)(implement(chat))
          tries           <- attempts.get
          rec             <- ev.recorded
        yield assertTrue(out.tasks.forall(_.completed), tries == 2, retryNotices(rec) == 1)
      }
    },
    test("a usage-limit failure pauses and re-enters the plan until it succeeds") {
      ZIO.scoped {
        for
          ev              <- FlowEvents.collecting
          now             <- Clock.instant
          dir             <- tempDir
          path             = dir.resolve("plan.md")
          limit            = LlmError.UsageLimitError(Some(now.plusSeconds(3600)), "mock", "daily cap")
          (svc, attempts) <- coder(ZIO.fail(limit), ZIO.succeed("done"))
          chat            <- Chat.start(svc, manageGit = true)
          plan             = Plan("epic", List(Task("t1", "1")))
          fiber           <- {
            given FlowEvents = ev
            withUsageLimitRetry(UsageLimitPolicy.patient)(
              implementTaskLoop(path, plan)(implement(chat))
            ).fork
          }
          _               <- TestClock.adjust(1.hour + 1.minute)
          out             <- fiber.join
          tries           <- attempts.get
          rec             <- ev.recorded
        yield assertTrue(
          out.tasks.forall(_.completed),
          tries == 2, // failed once (usage cap), succeeded on re-entry
          rec.exists { case FlowEvent.Info(m) => m.toLowerCase.contains("usage limit"); case _ => false },
        )
      }
    },
    test("after a failure, re-running from the persisted plan resumes at the first incomplete task") {
      ZIO.scoped {
        for
          ev              <- FlowEvents.collecting
          given FlowEvents = ev
          dir             <- tempDir
          path             = dir.resolve("plan.md")
          plan             = Plan("epic", List(Task("t1", "1"), Task("t2", "2"), Task("t3", "3")))
          (svc1, _)       <- coder(ZIO.succeed("ok"), ZIO.fail(LlmError.InvalidRequestError("boom")))
          chat1           <- Chat.start(svc1, manageGit = true)
          _               <- implementTaskLoop(path, plan)(implement(chat1)).either
          recovered       <- PlanStore.load(path)
          (svc2, _)       <- coder(ZIO.succeed("ok"), ZIO.succeed("ok"))
          chat2           <- Chat.start(svc2, manageGit = true)
          ran2            <- Ref.make(List.empty[String])
          out             <- implementTaskLoop(path, recovered.get)(t => ran2.update(_ :+ t.title) *> implement(chat2)(t))
          seen            <- ran2.get
        yield assertTrue(
          recovered.exists(p => p.tasks.find(_.title == "t1").exists(_.completed)),
          seen == List("t2", "t3"), // t1 already done, skipped
          out.tasks.forall(_.completed),
        )
      }
    },
    test("an explicit abort surfaces an Aborted event and fails with FlowError.Aborted") {
      ZIO.scoped {
        for
          ev              <- FlowEvents.collecting
          given FlowEvents = ev
          dir             <- tempDir
          path             = dir.resolve("plan.md")
          plan             = Plan("epic", List(Task("t1", "1")))
          res             <- implementTaskLoop(path, plan)(_ => fail("user aborted")).either
          rec             <- ev.recorded
        yield assertTrue(
          res == Left(FlowError.Aborted("user aborted")),
          rec.contains(FlowEvent.Aborted("user aborted")),
          rec.exists { case FlowEvent.StageFailed("t1", _) => true; case _ => false },
        )
      }
    },
    test("a persistence failure while saving progress fails the loop with FlowError.Persistence") {
      ZIO.scoped {
        for
          ev              <- FlowEvents.collecting
          given FlowEvents = ev
          dir             <- tempDir
          blocker          = dir.resolve("blocker")
          _               <- ZIO.attempt(Files.writeString(blocker, "x")).orDie
          path             = blocker.resolve("plan.md") // parent is a regular file ⇒ createDirectories fails
          (svc, _)        <- coder(ZIO.succeed("ok"))
          chat            <- Chat.start(svc, manageGit = true)
          plan             = Plan("epic", List(Task("t1", "1")))
          res             <- implementTaskLoop(path, plan)(implement(chat)).either
        yield assertTrue(
          res.isLeft,
          res.left.exists { case _: FlowError.Persistence => true; case _ => false },
        )
      }
    },
  ) @@ TestAspect.sequential
