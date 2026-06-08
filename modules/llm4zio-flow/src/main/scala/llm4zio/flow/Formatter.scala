package llm4zio.flow

import java.nio.file.Path

import zio.*

/** A formatter hook for the review/fix loop: run a project formatter (e.g. `sbt fmt`, `prettier --write .`) so the code
  * reviewers see — and the commit captures — consistently formatted output, instead of reviewers flagging style the
  * formatter would fix. Best-effort: a formatter failure is surfaced as an Info event but never aborts the flow.
  */
object Formatter:

  /** Build a formatter step from an optional shell command run in `workDir`. `None`/blank → a no-op `ZIO.unit`. */
  def step(command: Option[String], workDir: Path)(using events: FlowEvents): IO[FlowError, Unit] =
    command.map(_.trim).filter(_.nonEmpty) match
      case None      => ZIO.unit
      case Some(cmd) =>
        // Run through a shell (like orca's `bash -c`) so the command can be a pipeline or use shell features.
        events.publish(FlowEvent.Info(s"⌗ format: $cmd")) *>
          Proc
            .run("bash", List("-c", cmd), workDir)
            .flatMap(r =>
              ZIO.unless(r.ok)(
                events.publish(FlowEvent.Info(s"format exited ${r.exitCode}: ${r.problem.take(200)}"))
              ).unit
            )
            .catchAll(e => events.publish(FlowEvent.Info(s"format failed: ${e.message}")))
