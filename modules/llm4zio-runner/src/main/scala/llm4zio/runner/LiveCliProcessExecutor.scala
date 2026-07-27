package llm4zio.runner

import java.io.File
import java.nio.charset.StandardCharsets

import zio.*
import zio.process.{ Command, ProcessInput }
import zio.stream.{ Take, ZStream }

import llm4zio.core.{ CliProcessExecutor, LlmError, ProcessResult }

/** A real [[CliProcessExecutor]] over zio-process, for driving CLI coding agents (claude/codex/gemini) from a flow.
  */
object LiveCliProcessExecutor:

  val instance: CliProcessExecutor = new CliProcessExecutor:
    override def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
      argv match
        case Nil         => ZIO.fail(LlmError.InvalidRequestError("empty argv"))
        case cmd :: args =>
          (for
            process  <- command(cmd, args, cwd, envVars).run
            outF     <- process.stdout.lines.fork
            errF     <- process.stderr.lines.fork
            exit     <- process.exitCode
            lines    <- outF.join
            errLines <- errF.join
          yield ProcessResult(lines.toList, exit.code, errLines.toList))
            .mapError(t => LlmError.ProviderError(s"$cmd failed: ${t.getMessage}", None))

    // stdout is streamed live for responsiveness; stderr is drained concurrently (retaining the first
    // MaxStderrLines lines) so a non-zero exit can be reported with the real reason instead of silently draining to
    // an empty stream (agy, unlike codex/pi, has no in-band JSONL error event on stdout — its only failure signal is
    // stderr + exit code). The child is scope-managed: a consumer interrupted mid-stream kills it, never orphans it.
    override def runStreaming(
      argv: List[String],
      cwd: String,
      envVars: Map[String, String],
    ): ZStream[Any, LlmError, String] =
      argv match
        case Nil         => ZStream.fail(LlmError.InvalidRequestError("empty argv"))
        case cmd :: args => streamedLines(cmd, args, cwd, envVars, stdin = None)

    override def runWithStdin(argv: List[String], cwd: String, envVars: Map[String, String], stdin: String)
      : IO[LlmError, ProcessResult] =
      argv match
        case Nil         => ZIO.fail(LlmError.InvalidRequestError("empty argv"))
        case cmd :: args =>
          (for
            process <- command(cmd, args, cwd, envVars).stdin(ProcessInput.fromUTF8String(stdin)).run
            linesF  <- process.stdout.lines.fork
            exit    <- process.exitCode
            lines   <- linesF.join
          yield ProcessResult(lines.toList, exit.code))
            .mapError(t => LlmError.ProviderError(s"$cmd failed: ${t.getMessage}", None))

    override def runStreamingWithStdin(argv: List[String], cwd: String, envVars: Map[String, String], stdin: String)
      : ZStream[Any, LlmError, String] =
      argv match
        case Nil         => ZStream.fail(LlmError.InvalidRequestError("empty argv"))
        case cmd :: args => streamedLines(cmd, args, cwd, envVars, Some(stdin))

    override def runBidirectional(
      argv: List[String],
      cwd: String,
      envVars: Map[String, String],
    ): ZIO[Scope, LlmError, (Queue[String], ZStream[Any, LlmError, String])] =
      argv match
        case Nil         => ZIO.fail(LlmError.InvalidRequestError("empty argv"))
        case cmd :: args =>
          for
            // Queue is scoped on its own so it's shut down even if the process fails to start.
            queue   <- ZIO.acquireRelease(Queue.unbounded[String])(_.shutdown)
            // Each offered line is newline-terminated and flushed eagerly so the agent sees it immediately.
            // Shutting the queue down ends the stream → closes the child's stdin (EOF).
            stdin    = ProcessInput.fromStream(
                         ZStream
                           .fromQueueWithShutdown(queue)
                           .map(line => Chunk.fromArray((line + "\n").getBytes(StandardCharsets.UTF_8)))
                           .flattenChunks,
                         flushChunksEagerly = true,
                       )
            process <- ZIO
                         .acquireRelease(command(cmd, args, cwd, envVars).stdin(stdin).run)(_.killForcibly.ignore)
                         .mapError(t => LlmError.ProviderError(s"$cmd failed to start: ${t.getMessage}", None))
            out      =
              process.stdout.linesStream
                .mapError(t => LlmError.ProviderError(s"$cmd stdout read error: ${t.getMessage}", None))
          yield (queue, out)

  /** How many stderr lines to retain for error reporting. The rest are still drained — a child blocked on a full stderr
    * pipe would never exit — just not kept.
    */
  private val MaxStderrLines = 256

  /** Shared streaming path for [[CliProcessExecutor.runStreaming]] and [[CliProcessExecutor.runStreamingWithStdin]]:
    * the child is scope-managed (killed when the consumer goes away early), stdout streams live, and a non-zero exit
    * fails the stream with the captured stderr.
    *
    * The consumer never reads the stdout pipe directly: interrupting a fiber stuck in a blocking pipe read waits for
    * the read to return, and the read only returns once the child dies — which is the job of the very finalizer that
    * teardown would run next. Pumping stdout into a queue on a daemon fiber (awaited by nobody) breaks that cycle: the
    * consumer blocks only in an interruptible `queue.take`, teardown reaches `killForcibly`, the kill closes the pipes,
    * and the pumper unblocks and finishes on its own.
    */
  private def streamedLines(
    cmd: String,
    args: List[String],
    cwd: String,
    envVars: Map[String, String],
    stdin: Option[String],
  ): ZStream[Any, LlmError, String] =
    ZStream
      .unwrapScoped(
        ZIO.uninterruptible(
          for
            base    <- ZIO.succeed(command(cmd, args, cwd, envVars))
            process <- stdin
                         .fold(base)(s => base.stdin(ProcessInput.fromUTF8String(s)))
                         .run
                         .tap(p => ZIO.addFinalizer(p.killForcibly.ignore))
            errF    <- process.stderr.linesStream
                         .runFold(Chunk.empty[String]) { (acc, line) =>
                           if acc.size < MaxStderrLines then acc :+ line else acc
                         }
                         .fork
            queue   <- Queue.unbounded[Take[Throwable, String]]
            _       <- process.stdout.linesStream.runIntoQueue(queue).forkDaemon
          yield ZStream.fromQueue(queue).flattenTake ++
            ZStream.fromZIO(process.exitCode.zip(errF.join)).flatMap { (exit, errLines) =>
              if exit.code == 0 then ZStream.empty
              else
                ZStream.fail[Throwable](
                  new RuntimeException(s"$cmd exited with code ${exit.code}: ${errLines.mkString("\n")}")
                )
            }
        )
      )
      .mapError(t => LlmError.ProviderError(s"$cmd failed: ${t.getMessage}", None))

  private def command(cmd: String, args: List[String], cwd: String, envVars: Map[String, String]): Command =
    val base = Command(cmd, args*).workingDirectory(new File(cwd))
    if envVars.isEmpty then base else base.env(envVars)
