package llm4zio.flow

import zio.*
import zio.process.Command

import java.nio.file.Path

/** Small zio-process helper shared by the CLI-backed tools (git, gh, …). */
private[flow] object Proc:

  final case class Result(exitCode: Int, stdout: String, stderr: String):
    def ok: Boolean     = exitCode == 0
    def problem: String = if stderr.nonEmpty then stderr else stdout

  /** Run `command args*` in `workDir`, capturing stdout/stderr/exit without
    * failing on a non-zero exit. Only a failure to launch fails the effect.
    */
  def run(command: String, args: Seq[String], workDir: Path): IO[FlowError, Result] =
    (for
      process <- Command(command, args*).workingDirectory(workDir.toFile).run
      outF    <- process.stdout.string.fork
      errF    <- process.stderr.string.fork
      exit    <- process.exitCode
      out     <- outF.join
      err     <- errF.join
    yield Result(exit.code, out.trim, err.trim))
      .mapError(t =>
        FlowError.Process(s"$command ${args.mkString(" ")}", Option(t.getMessage).getOrElse(t.toString))
      )

  /** Like [[run]] but fails the effect on a non-zero exit, returning stdout otherwise. */
  def runOrFail(command: String, args: Seq[String], workDir: Path): IO[FlowError, String] =
    run(command, args, workDir).flatMap { r =>
      if r.ok then ZIO.succeed(r.stdout)
      else ZIO.fail(FlowError.Process(s"$command ${args.mkString(" ")} (exit ${r.exitCode})", r.problem))
    }
