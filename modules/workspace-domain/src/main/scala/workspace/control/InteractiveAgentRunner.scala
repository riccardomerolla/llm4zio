package workspace.control

import java.io.{ BufferedReader, BufferedWriter, InputStream, InputStreamReader, OutputStreamWriter }
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import zio.*
import zio.stream.ZStream

import workspace.entity.AgentProcessRef

final case class AgentProcess(
  process: Process,
  stdinWriter: BufferedWriter,
  stdout: ZStream[Any, Throwable, String],
  stderr: ZStream[Any, Throwable, String],
  release: UIO[Unit],
)

trait InteractiveAgentRunner:
  def start(argv: List[String], cwd: String): Task[AgentProcess]
  def sendInput(process: AgentProcess, message: String): Task[Unit]
  def isAlive(process: AgentProcess): Task[Boolean]
  def pause(process: AgentProcess): Task[Unit]
  def resume(process: AgentProcess): Task[Unit]
  def terminate(process: AgentProcess): Task[Unit]

  def register(runId: String, process: AgentProcess): UIO[AgentProcessRef]
  def resolve(ref: AgentProcessRef): Task[Option[AgentProcess]]
  def unregister(ref: AgentProcessRef): UIO[Unit]

object InteractiveAgentRunner:
  val live: ULayer[InteractiveAgentRunner] =
    ZLayer.fromZIO(Ref.make(Map.empty[String, AgentProcess]).map(InteractiveAgentRunnerLive.apply))

  def start(argv: List[String], cwd: String): ZIO[InteractiveAgentRunner, Throwable, AgentProcess] =
    ZIO.serviceWithZIO[InteractiveAgentRunner](_.start(argv, cwd))

  def sendInput(process: AgentProcess, message: String): ZIO[InteractiveAgentRunner, Throwable, Unit] =
    ZIO.serviceWithZIO[InteractiveAgentRunner](_.sendInput(process, message))

  def isAlive(process: AgentProcess): ZIO[InteractiveAgentRunner, Throwable, Boolean] =
    ZIO.serviceWithZIO[InteractiveAgentRunner](_.isAlive(process))

  def terminate(process: AgentProcess): ZIO[InteractiveAgentRunner, Throwable, Unit] =
    ZIO.serviceWithZIO[InteractiveAgentRunner](_.terminate(process))

  def pause(process: AgentProcess): ZIO[InteractiveAgentRunner, Throwable, Unit] =
    ZIO.serviceWithZIO[InteractiveAgentRunner](_.pause(process))

  def resume(process: AgentProcess): ZIO[InteractiveAgentRunner, Throwable, Unit] =
    ZIO.serviceWithZIO[InteractiveAgentRunner](_.resume(process))

  def register(runId: String, process: AgentProcess): ZIO[InteractiveAgentRunner, Nothing, AgentProcessRef] =
    ZIO.serviceWithZIO[InteractiveAgentRunner](_.register(runId, process))

  def resolve(ref: AgentProcessRef): ZIO[InteractiveAgentRunner, Throwable, Option[AgentProcess]] =
    ZIO.serviceWithZIO[InteractiveAgentRunner](_.resolve(ref))

  def unregister(ref: AgentProcessRef): ZIO[InteractiveAgentRunner, Nothing, Unit] =
    ZIO.serviceWithZIO[InteractiveAgentRunner](_.unregister(ref))

final case class InteractiveAgentRunnerLive(processes: Ref[Map[String, AgentProcess]]) extends InteractiveAgentRunner:

  private def streamLines(input: InputStream): ZStream[Any, Throwable, String] =
    ZStream.unwrapScoped {
      ZIO
        .acquireRelease(ZIO.attempt(new BufferedReader(new InputStreamReader(input))))(reader =>
          ZIO.attemptBlockingIO(reader.close()).ignore
        )
        .map { reader =>
          ZStream.repeatZIOOption {
            ZIO
              .attemptBlockingIO(Option(reader.readLine()))
              .mapError(Some(_))
              .flatMap {
                case Some(line) => ZIO.succeed(line)
                case None       => ZIO.fail(None)
              }
          }
        }
    }

  private def destroyProcess(process: Process): Task[Unit] =
    ZIO.attemptBlockingIO {
      if process.isAlive() then
        process.destroy()
        val _ = process.waitFor(2, TimeUnit.SECONDS)
      if process.isAlive() then
        process.destroyForcibly()
        val _ = process.waitFor(2, TimeUnit.SECONDS)
      ()
    }

  override def start(argv: List[String], cwd: String): Task[AgentProcess] =
    ZIO.uninterruptibleMask { restore =>
      for
        scope   <- Scope.make
        process <- restore(
                     ZIO
                       .acquireRelease(
                         ZIO.attemptBlockingIO {
                           val pb = new ProcessBuilder(argv*)
                           pb.directory(Paths.get(cwd).toFile)
                           pb.redirectErrorStream(false)
                           pb.start()
                         }
                       )(proc => destroyProcess(proc).orDie)
                       .provideEnvironment(ZEnvironment(scope))
                   ).onError(_ => scope.close(Exit.unit))
        stdin   <- restore(
                     ZIO.attempt(new BufferedWriter(new OutputStreamWriter(process.getOutputStream)))
                   ).onError(_ => scope.close(Exit.unit))
      yield AgentProcess(
        process = process,
        stdinWriter = stdin,
        stdout = streamLines(process.getInputStream),
        stderr = streamLines(process.getErrorStream),
        release = scope.close(Exit.unit),
      )
    }

  override def sendInput(process: AgentProcess, message: String): Task[Unit] =
    isAlive(process).flatMap { alive =>
      if alive then
        ZIO.attemptBlockingIO {
          process.stdinWriter.write(message)
          process.stdinWriter.newLine()
          process.stdinWriter.flush()
        }.unit
      else ZIO.fail(IllegalStateException("Cannot send input to a terminated process"))
    }

  override def isAlive(process: AgentProcess): Task[Boolean] =
    ZIO.attemptBlocking(process.process.isAlive())

  private def sendSignal(process: Process, signal: String): Task[Unit] =
    ZIO.attemptBlockingIO {
      val pid   = process.pid().toString
      val pb    = new ProcessBuilder("kill", signal, pid)
      val child = pb.start()
      val code  = child.waitFor()
      Either.cond(code == 0, (), s"Failed to send $signal to process $pid (exit=$code)")
    }.flatMap(result => ZIO.fromEither(result).mapError(msg => RuntimeException(msg)))

  override def pause(process: AgentProcess): Task[Unit] =
    isAlive(process).flatMap(alive =>
      if alive then sendSignal(process.process, "-STOP")
      else ZIO.fail(IllegalStateException("Cannot pause a terminated process"))
    )

  override def resume(process: AgentProcess): Task[Unit] =
    isAlive(process).flatMap(alive =>
      if alive then sendSignal(process.process, "-CONT")
      else ZIO.fail(IllegalStateException("Cannot resume a terminated process"))
    )

  override def terminate(process: AgentProcess): Task[Unit] =
    ZIO.attemptBlockingIO(process.stdinWriter.close()).ignore *>
      destroyProcess(process.process).ignore *>
      process.release

  override def register(runId: String, process: AgentProcess): UIO[AgentProcessRef] =
    processes.update(_ + (runId -> process)).as(AgentProcessRef(runId))

  override def resolve(ref: AgentProcessRef): Task[Option[AgentProcess]] =
    processes.get.map(_.get(ref.runId))

  override def unregister(ref: AgentProcessRef): UIO[Unit] =
    processes.update(_ - ref.runId).unit
