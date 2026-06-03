package llm4zio.runner

import zio.{ Chunk, Ref, Scope, ZIO }

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
    * the status line. Runs until the scope closes.
    */
  def consumeTo(events: FlowEvents.Hub, palette: Palette, surface: TerminalSurface): ZIO[Scope, Nothing, Unit] =
    Ref.make(0).flatMap { depth =>
      events.stream.foreach { event =>
        for
          d <- if closesChild(event) then depth.updateAndGet(x => math.max(0, x - 1)) else depth.get
          _ <- if opensChild(event) then depth.update(_ + 1) else ZIO.unit
          _ <- stageLabel(event).fold(ZIO.unit)(surface.setStatus)
          s  = line(event, palette)
          _ <- ZIO.unlessDiscard(s.isEmpty)(surface.log("  " * d + s))
        yield ()
      }.forkScoped.unit
    }

  /** Convenience: consume to a plain (non-animated) surface — back-compat for callers that don't build a surface. */
  def consume(events: FlowEvents.Hub, palette: Palette): ZIO[Scope, Nothing, Unit] =
    TerminalSurface.plain.flatMap(consumeTo(events, palette, _))
