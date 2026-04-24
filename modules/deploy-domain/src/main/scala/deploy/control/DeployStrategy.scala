package deploy.control

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.*

import deploy.entity.*

/** Pluggable packaging/deploy backend. Each concrete strategy owns a single [[DeployTarget]]. */
trait DeployStrategy:
  def target: DeployTarget
  def deploy(spec: DeploySpec): IO[DeployError, DeployResult]

object DeployStrategy:

  /** Write a file under `path`, creating parent directories; returns the path. Intentionally package-private so
    * strategies share one implementation.
    */
  private[deploy] def writeFile(path: Path, content: String): IO[DeployError, Path] =
    ZIO
      .attemptBlocking {
        Files.createDirectories(path.getParent)
        Files.write(path, content.getBytes(StandardCharsets.UTF_8))
        path
      }
      .mapError(e => DeployError.IOFailure(s"write($path) failed: ${e.getMessage}"))

  /** Returns true iff `tool` is executable from the current PATH. Best-effort: swallows failure to `false`. */
  private[deploy] def whichExists(tool: String): UIO[Boolean] =
    ZIO
      .attemptBlocking {
        val pb = new ProcessBuilder("sh", "-c", s"command -v $tool >/dev/null 2>&1")
        pb.start().waitFor() == 0
      }
      .orElseSucceed(false)

  /** Run a subprocess, capturing merged stdout/stderr and exit code. Used by Docker + FatJar strategies to invoke
    * external toolchains.
    */
  private[deploy] def runProcess(
    argv: List[String],
    cwd: Path,
  ): IO[DeployError, (List[String], Int)] =
    ZIO
      .attemptBlockingIO {
        val pb = new ProcessBuilder(argv*)
        pb.directory(cwd.toFile)
        pb.redirectErrorStream(true)
        val process = pb.start()
        val lines   = scala.io.Source.fromInputStream(process.getInputStream).getLines().toList
        val exit    = process.waitFor()
        (lines, exit)
      }
      .mapError(e => DeployError.IOFailure(s"${argv.headOption.getOrElse("?")} launch failed: ${e.getMessage}"))
