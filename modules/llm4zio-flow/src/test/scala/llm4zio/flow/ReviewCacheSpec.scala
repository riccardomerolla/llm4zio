package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.*
import zio.test.*

object ReviewCacheSpec extends ZIOSpecDefault:

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-reviewcache-")).orDie)(d =>
      ZIO.attempt {
        Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
      }.orDie
    )

  private val dirty = ReviewResult(
    List(ReviewIssue(Severity.Critical, "judge[ACCTXFR]: completeness scored 1", "missing R4")),
    "judge:ACCTXFR",
  )
  private val clean = ReviewResult(Nil, "judge:ACCTXFR")

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ReviewCache")(
    test("fingerprint is deterministic, content-sensitive, and part-boundary-sensitive") {
      assertTrue(
        ReviewCache.fingerprint("a", "b") == ReviewCache.fingerprint("a", "b"),
        ReviewCache.fingerprint("a", "b") != ReviewCache.fingerprint("a", "c"),
        // length-prefixed parts: moving a character across a boundary must change the hash
        ReviewCache.fingerprint("ab", "c") != ReviewCache.fingerprint("a", "bc"),
      )
    },
    test("first call evaluates and persists; a matching fingerprint reuses the verdict without re-evaluating") {
      ZIO.scoped {
        for
          dir       <- tempDir
          path       = dir.resolve("gate").resolve("ACCTXFR.json")
          calls     <- Ref.make(0)
          fp         = ReviewCache.fingerprint("source", "spec")
          first     <- ReviewCache.cached(path, fp)(calls.update(_ + 1).as(dirty))
          again     <- ReviewCache.cached(path, fp)(calls.update(_ + 1).as(clean))
          n         <- calls.get
          persisted <- ZIO.attemptBlocking(Files.exists(path)).orDie
        yield assertTrue(
          first == dirty,
          again == dirty, // the cached verdict, not the second evaluate's result
          n == 1,
          persisted,
        )
      }
    },
    test("a changed fingerprint re-evaluates and overwrites the stored verdict") {
      ZIO.scoped {
        for
          dir    <- tempDir
          path    = dir.resolve("ACCTXFR.json")
          calls  <- Ref.make(0)
          _      <- ReviewCache.cached(path, ReviewCache.fingerprint("v1"))(calls.update(_ + 1).as(dirty))
          second <- ReviewCache.cached(path, ReviewCache.fingerprint("v2"))(calls.update(_ + 1).as(clean))
          reused <- ReviewCache.cached(path, ReviewCache.fingerprint("v2"))(calls.update(_ + 1).as(dirty))
          n      <- calls.get
        yield assertTrue(second == clean, reused == clean, n == 2)
      }
    },
    test("a dirty verdict round-trips with its issues intact") {
      ZIO.scoped {
        for
          dir    <- tempDir
          path    = dir.resolve("ACCTXFR.json")
          fp      = ReviewCache.fingerprint("x")
          _      <- ReviewCache.cached(path, fp)(ZIO.succeed(dirty))
          reused <- ReviewCache.cached(path, fp)(ZIO.succeed(clean))
        yield assertTrue(
          reused.issues.map(_.title) == List("judge[ACCTXFR]: completeness scored 1"),
          reused.issues.head.severity == Severity.Critical,
          reused.summary == "judge:ACCTXFR",
        )
      }
    },
    test("a malformed cache file falls through to evaluate instead of failing") {
      ZIO.scoped {
        for
          dir    <- tempDir
          path    = dir.resolve("ACCTXFR.json")
          _      <- ZIO.attemptBlocking(Files.write(path, "not json {{{".getBytes(StandardCharsets.UTF_8))).orDie
          result <- ReviewCache.cached(path, ReviewCache.fingerprint("x"))(ZIO.succeed(clean))
          healed <- ReviewCache.cached(path, ReviewCache.fingerprint("x"))(ZIO.succeed(dirty))
        yield assertTrue(result == clean, healed == clean)
      }
    },
    test("evaluate failures propagate and nothing is cached") {
      ZIO.scoped {
        for
          dir    <- tempDir
          path    = dir.resolve("ACCTXFR.json")
          fp      = ReviewCache.fingerprint("x")
          failed <- ReviewCache.cached(path, fp)(ZIO.fail(FlowError.Llm("empty response", None))).flip
          after  <- ReviewCache.cached(path, fp)(ZIO.succeed(clean))
        yield assertTrue(
          failed == FlowError.Llm("empty response", None),
          after == clean,
        )
      }
    },
  )
