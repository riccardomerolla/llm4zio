package bankmod.app

import java.nio.file.{ Files, Path, Paths, StandardOpenOption }

import zio.*

import bankmod.graph.model.Schemas
import bankmod.graph.render.SampleGraph

/** Writes the canonical [[SampleGraph.sample]] fixture to `examples/bankmod/sample-graph.json` by round-tripping
  * through `Schemas.graphCodec`.
  *
  * Why a dedicated seeder (not folded into `DocsGenerator`):
  *   - During a demo we don't want `sbt bankmodDocs` to clobber a hand-edited `sample-graph.json` while showing
  *     live-reload.
  *   - The four docs under `docs/bankmod/` that reference `BANKMOD_GRAPH_FILE=…/sample-graph.json` need a real file to
  *     point at. This seeder produces that file once; it's then checked into the repo so fresh clones work
  *     out-of-the-box.
  *   - The JSON matches the ADT byte-for-byte because it comes from the same codec used by `ModelWatcher.reload`, so
  *     re-loading the seeded file is guaranteed to succeed.
  *
  * Usage: `sbt bankmodSeedExample` — writes to `<repo-root>/examples/bankmod/sample-graph.json`. Accepts an optional
  * path argument to override the destination.
  */
object SampleGraphSeeder extends ZIOAppDefault:

  /** Default output: walk up from CWD looking for the repo root (directory containing `build.sbt`) and write to
    * `<root>/examples/bankmod/sample-graph.json`. Mirrors [[DocsGenerator.defaultOutDir]] so the tool behaves
    * identically whether invoked from the repo root or from inside an sbt fork.
    */
  private def defaultOutFile: Path =
    val cwd                     = Paths.get("").toAbsolutePath
    def findRoot(p: Path): Path =
      Option(p).fold(cwd) { path =>
        if Files.exists(path.resolve("build.sbt")) then path
        else Option(path.getParent).fold(cwd)(findRoot)
      }
    findRoot(cwd).resolve("examples").resolve("bankmod").resolve("sample-graph.json")

  override def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    for
      args   <- getArgs
      outFile = args.headOption.map(Paths.get(_)).getOrElse(defaultOutFile)
      json    = Schemas.graphCodec.encodeToString(SampleGraph.sample)
      _      <- ZIO.attemptBlocking(Files.createDirectories(outFile.getParent))
      _      <- ZIO.attemptBlocking(
                  Files.writeString(
                    outFile,
                    json,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                  )
                )
      _      <- Console.printLine(
                  s"bankmod seed: wrote ${SampleGraph.sample.services.size}-service graph (${json.length} bytes) to $outFile"
                )
    yield ()
