package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.*
import zio.test.*

object WallSpec extends ZIOSpecDefault:

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-wall-")).orDie)(d =>
      ZIO.attempt {
        Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
      }.orDie
    )

  private def write(dir: Path, name: String, content: String): UIO[Path] =
    ZIO.attemptBlocking {
      val path = dir.resolve(name)
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.write(path, content.getBytes(StandardCharsets.UTF_8))
      path
    }.orDie

  private val cobolSources = """.*\.(cbl|CBL|cpy|CPY|jcl|JCL)"""

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Wall")(
    test("check is Clean when nothing in the workspace matches the legacy sources regex") {
      ZIO.scoped {
        for
          dir    <- tempDir
          _      <- write(dir, "src/main/java/Transfer.java", "class Transfer {}\n")
          _      <- write(dir, "docs/specs/ACCTXFR.md", "# ACCTXFR spec\n")
          result <- Wall.check(dir, cobolSources)
        yield assertTrue(result == Wall.Result.Clean)
      }
    },
    test("check is Breached listing every matching file as sorted relative paths") {
      ZIO.scoped {
        for
          dir    <- tempDir
          _      <- write(dir, "src/main/java/Transfer.java", "class Transfer {}\n")
          _      <- write(dir, "legacy/ACCTXFR.cbl", "       IDENTIFICATION DIVISION.\n")
          _      <- write(dir, "copy/ACCTREC.cpy", "       01  ACCT-REC.\n")
          result <- Wall.check(dir, cobolSources)
        yield assertTrue(result == Wall.Result.Breached(List("copy/ACCTREC.cpy", "legacy/ACCTXFR.cbl")))
      }
    },
    test("check ignores the .git directory — the wall is about the working tree") {
      ZIO.scoped {
        for
          dir    <- tempDir
          _      <- write(dir, ".git/objects/stray.cbl", "not really source\n")
          result <- Wall.check(dir, cobolSources)
        yield assertTrue(result == Wall.Result.Clean)
      }
    },
  )
