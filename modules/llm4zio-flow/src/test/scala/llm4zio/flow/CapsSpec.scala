package llm4zio.flow

import java.nio.file.Files

import zio.*
import zio.test.*

import llm4zio.core.{ Capability, Grants }

/** The flow-layer witness surface (issue #716): unforgeable `Caps` traits gate tool methods at compile time,
  * `Grants.For` derives the runtime set from a witness intersection type, and the tools enforce the ambient grants at
  * runtime — a denial fails typed with [[FlowError.CapabilityDenied]] before any subprocess is spawned, and publishes a
  * [[FlowEvent.CapabilityDenied]] audit event.
  */
object CapsSpec extends ZIOSpecDefault:

  // NB: the privileged mint is granted per-test, never at object level — an object-level given would leak into the
  // `typeChecks` snippets' elaboration scope and make the negative compile checks vacuous.

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Caps")(
    test("witnesses are required at compile time: git.commitAll without Caps.GitWrite does not compile") {
      assertTrue(
        !scala.compiletime.testing.typeChecks(
          "import llm4zio.flow.*\ndef body(git: GitTool): Unit = { val _ = git.commitAll(\"x\") }"
        ),
        scala.compiletime.testing.typeChecks(
          "import llm4zio.flow.*\ndef body(git: GitTool)(using Caps.GitWrite): Unit = { val _ = git.commitAll(\"x\") }"
        ),
      )
    },
    test("the hierarchy is subtyping: a GitPush witness satisfies GitWrite and GitRead summons") {
      assertTrue(
        scala.compiletime.testing.typeChecks(
          "import llm4zio.flow.*\ndef body(git: GitTool)(using Caps.GitPush): Unit = { val _ = git.commitAll(\"x\"); val _2 = git.diff }"
        )
      )
    },
    test("Grants.For derives the runtime set from a witness intersection type") {
      val forReadReason = Caps.grantsFor[Caps.GitRead & Caps.Reasoning]
      val forPushCoder  = Caps.grantsFor[Caps.GitPush & Caps.Coder]
      assertTrue(
        forReadReason.git == Grants.Level.Read,
        forReadReason.reasoning,
        !forReadReason.coder,
        forReadReason.gh == Grants.Level.None,
        forPushCoder.git == Grants.Level.Push,
        forPushCoder.coder,
        Caps.grantsFor[Caps.All] == Grants.all,
      )
    },
    test("a runtime denial fails typed before any subprocess is spawned, and emits an audit event") {
      given Caps.All = Caps.grantAll
      for
        dir    <- ZIO.attemptBlocking(Files.createTempDirectory("caps-spec")).orDie
        events <- FlowEvents.collecting
        git     = GitTool(
                    dir,
                    events,
                  ) // not a git repo: if the guard let git run, the error would be Process, not CapabilityDenied
        res    <- Grants.restricted(Grants.none)(git.commitAll("x")).either
        seen   <- events.recorded
      yield assertTrue(
        res == Left(FlowError.CapabilityDenied(Capability.GitWrite, "git commitAll")),
        seen.exists {
          case FlowEvent.CapabilityDenied(cap, op) => cap == "GitWrite" && op == "git commitAll"
          case _                                   => false
        },
      )
    },
    test("a granted write on a real repo still works (guard is transparent when allowed)") {
      given Caps.All = Caps.grantAll
      for
        dir <- ZIO.attemptBlocking(Files.createTempDirectory("caps-spec")).orDie
        git  = GitTool(dir)
        _   <- git.init
        _   <- git.config("user.email", "t@t")
        _   <- git.config("user.name", "t")
        _   <- ZIO.attemptBlocking(Files.writeString(dir.resolve("a.txt"), "hi")).orDie
        out <- git.commitAll("first")
      yield assertTrue(out == GitTool.Commit.Committed)
    },
    test("push-grade operations emit a CapabilityUsed audit event") {
      given Caps.All = Caps.grantAll
      for
        dir    <- ZIO.attemptBlocking(Files.createTempDirectory("caps-spec")).orDie
        events <- FlowEvents.collecting
        git     = GitTool(dir, events)
        _      <- git.push("origin", "main").either // fails (no repo) — but the Used event precedes the attempt
        seen   <- events.recorded
      yield assertTrue(
        seen.exists {
          case FlowEvent.CapabilityUsed(cap, _) => cap == "GitPush"
          case _                                => false
        }
      )
    },
  )
