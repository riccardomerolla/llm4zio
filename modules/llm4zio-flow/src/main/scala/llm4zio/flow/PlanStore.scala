package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.*

/** Plain-file persistence for [[Plan]]s.
  *
  * Plans are stored as the Markdown rendered by [[Plan.render]] — no datastore — so a crashed run resumes by re-reading
  * the file via [[recoverOrCreate]].
  */
object PlanStore:

  /** Write `plan` to `path`, creating parent directories as needed. */
  def save(path: Path, plan: Plan): IO[FlowError, Unit] =
    ZIO
      .attemptBlocking {
        Option(path.getParent).foreach(Files.createDirectories(_))
        Files.write(path, plan.render.getBytes(StandardCharsets.UTF_8))
        ()
      }
      .mapError(e => FlowError.Persistence(s"failed to save plan to $path", Some(e)))

  /** Load the plan at `path`, or None if the file does not exist. */
  def load(path: Path): IO[FlowError, Option[Plan]] =
    ZIO
      .attemptBlocking(Option.when(Files.exists(path))(Files.readString(path)))
      .mapError(e => FlowError.Persistence(s"failed to read plan from $path", Some(e)))
      .flatMap {
        case None       => ZIO.none
        case Some(text) => ZIO.fromEither(Plan.parse(text)).mapBoth(FlowError.PlanParse(_), Some(_))
      }

  /** Remove the plan file (no-op if absent) — e.g. after an epic completes. */
  def delete(path: Path): IO[FlowError, Unit] =
    ZIO
      .attemptBlocking(Files.deleteIfExists(path))
      .mapError(e => FlowError.Persistence(s"failed to delete plan at $path", Some(e)))
      .unit

  /** Resume the plan at `path` if present; otherwise run `create`, persist it, and return it. */
  def recoverOrCreate[R, E >: FlowError](path: Path)(create: => ZIO[R, E, Plan]): ZIO[R, E, Plan] =
    load(path).flatMap {
      case Some(plan) => ZIO.succeed(plan)
      case None       => create.flatMap(plan => save(path, plan).as(plan))
    }
