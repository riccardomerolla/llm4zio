package llm4zio.flow

import java.nio.file.Path

import zio.*
import zio.process.Command

/** Small zio-process helper shared by the CLI-backed tools (git, gh, …). */
private[flow] object Proc:

  final case class Result(exitCode: Int, stdout: String, stderr: String):
    def ok: Boolean     = exitCode == 0
    def problem: String = if stderr.nonEmpty then stderr else stdout

  /** Run `command args*` in `workDir`, capturing stdout/stderr/exit without failing on a non-zero exit. Only a failure
    * to launch fails the effect. `env` entries are merged onto the inherited environment (so PATH etc. survive), with
    * `env` winning on conflicts; an empty `env` inherits the parent environment unchanged.
    */
  def run(
    command: String,
    args: Seq[String],
    workDir: Path,
    env: Map[String, String] = Map.empty,
  ): IO[FlowError, Result] =
    (for
      base    <- ZIO.succeed(Command(command, args*).workingDirectory(workDir.toFile))
      process <- (if env.isEmpty then base else base.env(sys.env ++ env)).run
      outF    <- process.stdout.string.fork
      errF    <- process.stderr.string.fork
      exit    <- process.exitCode
      out     <- outF.join
      err     <- errF.join
    yield Result(exit.code, out.trim, err.trim))
      .mapError(t =>
        FlowError.Process(s"$command ${args.mkString(" ")}", Option(t.getMessage).getOrElse(t.toString))
      )

  /** Like [[run]] but feeds `input` to the process's stdin — how the verify flow hands a vector to the pack's replay
    * command.
    */
  def runWithInput(
    command: String,
    args: Seq[String],
    workDir: Path,
    input: String,
    env: Map[String, String] = Map.empty,
  ): IO[FlowError, Result] =
    (for
      base    <- ZIO.succeed(
                   Command(command, args*)
                     .workingDirectory(workDir.toFile)
                     .stdin(zio.process.ProcessInput.fromUTF8String(input))
                 )
      process <- (if env.isEmpty then base else base.env(sys.env ++ env)).run
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
  def runOrFail(
    command: String,
    args: Seq[String],
    workDir: Path,
    env: Map[String, String] = Map.empty,
  ): IO[FlowError, String] =
    run(command, args, workDir, env).flatMap { r =>
      if r.ok then ZIO.succeed(r.stdout)
      else ZIO.fail(FlowError.Process(s"$command ${args.mkString(" ")} (exit ${r.exitCode})", r.problem))
    }
