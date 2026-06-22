package llm4zio.flow

import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import zio.*
import zio.test.*

/** End-to-end check of the two-root layout: when a flow targets an external repo (`workspace != workDir`), the flight
  * recorder writes its trace under the **workspace** — repo-namespaced — and the target repo's tree is never touched.
  */
object WorkspaceLayoutSpec extends ZIOSpecDefault:

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-ws-")).orDie)(d =>
      ZIO
        .attempt(Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_)))
        .orDie
    )

  private def exists(p: Path): UIO[Boolean] =
    ZIO.attemptBlocking(Files.exists(p)).orElseSucceed(false)

  private def traceFiles(dir: Path): UIO[List[Path]] =
    ZIO
      .attemptBlocking {
        if !Files.isDirectory(dir) then Nil
        else
          val s = Files.list(dir)
          try s.iterator.asScala.filter(_.getFileName.toString.startsWith("trace-")).toList
          finally s.close()
      }
      .orElseSucceed(Nil)

  def spec = suite("two-root workspace layout")(
    test("the flight recorder writes under the workspace (repo-namespaced), never into the target repo") {
      ZIO.scoped {
        for
          repo    <- tempDir
          control <- tempDir
          dir      = Workspace.llm4zioDir(control, repo)
          hub     <- FlowEvents.hub()
          rec     <- FlowRecorder.install(hub, dir, keep = 5)
          _       <- rec.record(FlowEvent.StageStarted("Build"))
          _       <- rec.record(FlowEvent.StageCompleted("Build"))
          repoDirty <- exists(repo.resolve(".llm4zio"))
          traces    <- traceFiles(dir)
        yield assertTrue(
          Workspace.repoId(control, repo).isDefined,    // external repo → namespaced bookkeeping
          dir.getParent == control.resolve(".llm4zio"), // under the workspace, not the repo
          !repoDirty,                                    // the target repo's tree stays clean
          traces.nonEmpty,                               // the recorder actually wrote here
        )
      }
    } @@ TestAspect.withLiveClock
  )
