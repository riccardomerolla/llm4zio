package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.*
import zio.test.*

object ProvenanceSpec extends ZIOSpecDefault:

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-provenance-")).orDie)(d =>
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

  private val manifest = Provenance(
    schema = 1,
    pack = "cobol-springboot",
    llm4zioVersion = "3.23.0",
    createdAt = "2026-07-26T10:00:00Z",
    approvedBy = Some("riccardo"),
    seats = Map("analyst" -> "gemini-2.5-pro", "judge" -> "gemini-2.5-pro"),
    specs = Map("specs/ACCTXFR.md" -> "ab12", "specs/INTCALC.md" -> "cd34"),
    gateVerdicts = Map("ACCTXFR" -> "ef56"),
    equivalenceReport = None,
    fixSpecs = Nil,
  )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Provenance")(
    test("a manifest written before contextTruncations existed still parses, and extend can add them") {
      // Pre-4.3.0 manifests have no contextTruncations key. The field is defaulted precisely so an existing
      // evidence chain keeps loading rather than failing a gate on a schema it predates.
      val legacy =
        """{"schema":1,"pack":"p","llm4zioVersion":"4.2.0","createdAt":"now","approvedBy":null,
          |"seats":{},"specs":{},"gateVerdicts":{},"equivalenceReport":null,"fixSpecs":[]}""".stripMargin
      ZIO.scoped {
        for
          dir  <- tempDir
          path <- write(dir, "provenance.json", legacy)
          p    <- Provenance.load(path)
          ext  <- Provenance.extend(path)(_.copy(contextTruncations = List("specs: 900 → 400 chars")))
          re   <- Provenance.load(path)
        yield assertTrue(
          p.contextTruncations.isEmpty,
          ext.contextTruncations.size == 1,
          re.contextTruncations == List("specs: 900 → 400 chars"),
        )
      }
    },
    test("write/load round-trips the manifest") {
      ZIO.scoped {
        for
          dir    <- tempDir
          path    = dir.resolve("docs/modernization/provenance.json")
          _      <- Provenance.write(path, manifest)
          loaded <- Provenance.load(path)
        yield assertTrue(loaded == manifest)
      }
    },
    test("extend loads, applies, and persists — verify attaches the equivalence report hash") {
      ZIO.scoped {
        for
          dir      <- tempDir
          path      = dir.resolve("provenance.json")
          _        <- Provenance.write(path, manifest)
          extended <- Provenance.extend(path)(_.copy(equivalenceReport = Some("9f9f")))
          reloaded <- Provenance.load(path)
        yield assertTrue(
          extended.equivalenceReport == Some("9f9f"),
          reloaded == extended,
          reloaded.specs == manifest.specs,
        )
      }
    },
    test("hashFiles is plain sha256 hex over file bytes, keyed by relative path — externally checkable with shasum") {
      ZIO.scoped {
        for
          dir    <- tempDir
          _      <- write(dir, "specs/ACCTXFR.md", "hello\n")
          hashes <- Provenance.hashFiles(dir, List("specs/ACCTXFR.md"))
        yield assertTrue(
          hashes == Map("specs/ACCTXFR.md" -> "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03")
        )
      }
    },
    test("load on a missing manifest fails with a typed Persistence error — running phases out of order is a bug") {
      ZIO.scoped {
        for
          dir    <- tempDir
          result <- Provenance.load(dir.resolve("absent.json")).either
        yield assertTrue(result.left.exists {
          case FlowError.Persistence(_, _) => true
          case _                           => false
        })
      }
    },
  )
