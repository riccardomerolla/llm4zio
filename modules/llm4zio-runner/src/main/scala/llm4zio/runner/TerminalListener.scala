package llm4zio.runner

import zio.*
import zio.stream.ZStream

import llm4zio.flow.{ FlowEvent, FlowEvents }

/** Renders [[FlowEvent]]s to the terminal as a colored, depth-nested tree (orca-style). */
object TerminalListener:

  /** The styled glyph line for one event, without indentation. Pure -> trivially testable. Returns empty for events
    * that produce no visible line (e.g. token accounting).
    */
  def line(event: FlowEvent, palette: Palette): String = event match
    case FlowEvent.StageStarted(stage)        => palette.stageStart(stage)
    case FlowEvent.StageCompleted(stage)      => palette.stageDone(stage)
    case FlowEvent.StageFailed(stage, detail) => palette.fail(s"$stage — $detail")
    case FlowEvent.Aborted(message)           => palette.fail(s"aborted: $message")
    case FlowEvent.Info(message)              => palette.info(message)
    case FlowEvent.ToolUse(tool, args)        => palette.toolCall(tool, args)
    case FlowEvent.AssistantMessage(text)     => palette.assistant(oneLine(text))
    case FlowEvent.TokensUsed(_, _, _)        => ""

  /** Indent depth (in levels) at which each event's line is printed. `StageStarted` prints at the current depth then
    * opens a child level; `StageCompleted`/`StageFailed`/`Aborted` close a level then print.
    */
  def indentDepths(events: Chunk[FlowEvent]): Chunk[Int] =
    val (_, depths) = events.foldLeft((0, Chunk.empty[Int])) {
      case ((depth, acc), event) =>
        if opensChild(event) then (depth + 1, acc :+ depth)
        else if closesChild(event) then
          val d = math.max(0, depth - 1)
          (d, acc :+ d)
        else (depth, acc :+ depth)
    }
    depths

  private def oneLine(text: String): String = text.replaceAll("\\s+", " ").trim

  private def opensChild(e: FlowEvent): Boolean = e match
    case _: FlowEvent.StageStarted => true
    case _                         => false

  private def closesChild(e: FlowEvent): Boolean = e match
    case _: FlowEvent.StageCompleted | _: FlowEvent.StageFailed | _: FlowEvent.Aborted => true
    case _                                                                             => false

  private def stageLabel(e: FlowEvent): Option[Option[String]] = e match
    case FlowEvent.StageStarted(s)                                                     => Some(Some(s))
    case _: FlowEvent.StageCompleted | _: FlowEvent.StageFailed | _: FlowEvent.Aborted => Some(None)
    case _                                                                             => None

  /** Subscribe to a hub and render every event to `surface`: indented colored lines, with the active stage pinned to
    * the status line. Runs (forked) until the scope closes. Returns a counter of events processed so far — pass it to
    * [[awaitDrained]] before teardown so trailing events (notably a final `StageFailed`) are rendered, not dropped.
    */
  def consumeTo(events: FlowEvents.Hub, palette: Palette, surface: TerminalSurface): ZIO[Scope, Nothing, Ref[Long]] =
    for
      depth    <- Ref.make(0)
      consumed <- Ref.make(0L)
      // Subscribe BEFORE returning (and before any event is published) so no early event is missed — the hub is
      // pub/sub, so a late `ZStream.fromHub` would silently drop everything published before it attached.
      queue    <- events.subscribe
      _        <- ZStream.fromQueue(queue).foreach { event =>
                    (for
                      d <- if closesChild(event) then depth.updateAndGet(x => math.max(0, x - 1)) else depth.get
                      _ <- if opensChild(event) then depth.update(_ + 1) else ZIO.unit
                      _ <- stageLabel(event).fold(ZIO.unit)(surface.setStatus)
                      s  = line(event, palette)
                      _ <- ZIO.unlessDiscard(s.isEmpty)(surface.log("  " * d + s))
                    yield ()) *> consumed.update(_ + 1)
                  }.forkScoped
    yield consumed

  /** Wait until the listener has processed every event published to `events` as of now (snapshot), so trailing events
    * render before the scope interrupts the consumer. Bounded by `timeout`, so a stalled consumer can never hang the
    * shutdown.
    */
  def awaitDrained(events: FlowEvents.Hub, consumed: Ref[Long], timeout: Duration): ZIO[Any, Nothing, Unit] =
    def loop(target: Long): ZIO[Any, Nothing, Unit] =
      consumed.get.flatMap(c => if c >= target then ZIO.unit else ZIO.sleep(5.millis) *> loop(target))
    events.publishedCount.flatMap(loop).timeout(timeout).unit

  /** Convenience: consume to a plain (non-animated) surface — back-compat for callers that don't build a surface. */
  def consume(events: FlowEvents.Hub, palette: Palette): ZIO[Scope, Nothing, Ref[Long]] =
    TerminalSurface.plain.flatMap(consumeTo(events, palette, _))
