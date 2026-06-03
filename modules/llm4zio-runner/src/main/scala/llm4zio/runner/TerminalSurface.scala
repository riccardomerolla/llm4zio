package llm4zio.runner

import zio.*

/** The terminal output surface: a serialized log zone plus an optionally-animated, pinned bottom status line.
  *
  *   - `log` writes a finished line above the status line.
  *   - `setStatus` sets the pinned label (the active stage); `None` clears it.
  *   - `suspend` clears the status line and pauses animation while `read` runs (so prompts render cleanly), then
  *     redraws.
  *
  * All writes are serialized through a single lock so the animator and log lines never interleave.
  */
trait TerminalSurface:
  def log(line: String): UIO[Unit]
  def setStatus(label: Option[String]): UIO[Unit]
  def suspend[R, E, A](read: ZIO[R, E, A]): ZIO[R, E, A]

object TerminalSurface:
  private val frames: Vector[String] = Vector("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
  private val clearLine: String      = "\r\u001b[2K" // carriage-return + erase-to-end-of-line

  /** Plain surface: no animation, one line per `log`, no status line. For non-TTY / NO_COLOR / CI and tests. */
  val plain: UIO[TerminalSurface] =
    ZIO.succeed(new TerminalSurface:
      def log(line: String): UIO[Unit]                       = Console.printLine(line).orDie
      def setStatus(label: Option[String]): UIO[Unit]        = ZIO.unit
      def suspend[R, E, A](read: ZIO[R, E, A]): ZIO[R, E, A] = read)

  /** Animated surface: a braille spinner + active-stage label pinned to the bottom line, redrawn on a 100ms tick.
    * Writes are serialized via a semaphore so log lines and the animator never interleave. Scoped: the animator fiber
    * stops and the status line is cleared when the scope closes.
    */
  def live(palette: Palette): ZIO[Scope, Nothing, TerminalSurface] =
    for
      lock      <- Semaphore.make(1)
      status    <- Ref.make(Option.empty[String])
      frame     <- Ref.make(0)
      suspended <- Ref.make(false)
      // Redraw the pinned status line (or leave it cleared). Lock-free: callers hold `lock`.
      drawStatus = ZIO.ifZIO(suspended.get)(
                     ZIO.unit,
                     status.get.zip(frame.get).flatMap {
                       case (Some(label), f) => Console.print(s"$clearLine${frames(f % frames.size)} $label").orDie
                       case _                => ZIO.unit
                     },
                   )
      surface    = new TerminalSurface:
                     def log(line: String): UIO[Unit]                       =
                       lock.withPermit(Console.print(clearLine).orDie *> Console.printLine(line).orDie *> drawStatus)
                     def setStatus(label: Option[String]): UIO[Unit]        =
                       lock.withPermit(status.set(label) *> Console.print(clearLine).orDie *> drawStatus)
                     def suspend[R, E, A](read: ZIO[R, E, A]): ZIO[R, E, A] =
                       lock.withPermit(suspended.set(true) *> Console.print(clearLine).orDie) *>
                         read.ensuring(lock.withPermit(suspended.set(false) *> drawStatus))
      _         <- ZIO
                     .when(palette.enabled)(
                       (frame.update(_ + 1) *> lock.withPermit(drawStatus) *> ZIO.sleep(100.millis)).forever
                     )
                     .forkScoped
      _         <- ZIO.addFinalizer(lock.withPermit(Console.print(clearLine).orDie))
    yield surface
