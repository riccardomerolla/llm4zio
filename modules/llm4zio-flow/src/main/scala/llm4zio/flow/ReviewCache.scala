package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.json.*
import zio.{ IO, UIO, ZIO }

/** A durable, content-addressed cache for [[ReviewResult]]s — the plain-file primitive behind resumable review/judge
  * gates. One verdict per file, stamped with the fingerprint of the content it was rendered against: a matching
  * fingerprint reuses the stored verdict without re-evaluating (an LLM judge call saved); changed content — or a
  * deleted file — re-evaluates and overwrites. Stateless like [[PlanStore]]: the files ARE the resume state, so a
  * crashed or auto-resumed gate re-judges only what actually changed, and committing them makes resume survive
  * machines.
  */
object ReviewCache:

  final private case class Entry(fingerprint: String, result: ReviewResult) derives JsonCodec

  /** SHA-256 (hex) over the parts, length-prefixed so part boundaries count: `("ab","c")` ≠ `("a","bc")`. Feed it
    * everything the verdict depends on — the content under review AND the rubric that judged it.
    */
  def fingerprint(parts: String*): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    parts.foreach { part =>
      val bytes = part.getBytes(StandardCharsets.UTF_8)
      digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array())
      digest.update(bytes)
    }
    digest.digest().map("%02x".format(_)).mkString

  /** The verdict stored at `path` when its fingerprint matches; otherwise run `evaluate`, persist the fresh verdict,
    * and return it. A missing or unreadable file just means "evaluate" — corruption never fails the gate — while a
    * failed `evaluate` propagates and caches nothing.
    */
  def cached(path: Path, fingerprint: String)(evaluate: IO[FlowError, ReviewResult]): IO[FlowError, ReviewResult] =
    load(path).flatMap {
      case Some(entry) if entry.fingerprint == fingerprint => ZIO.succeed(entry.result)
      case _                                               => evaluate.tap(result => write(path, Entry(fingerprint, result)))
    }

  private def load(path: Path): UIO[Option[Entry]] =
    ZIO
      .attemptBlocking(Option.when(Files.exists(path))(Files.readString(path)))
      .map(_.flatMap(_.fromJson[Entry].toOption))
      .orElseSucceed(None)

  private def write(path: Path, entry: Entry): IO[FlowError, Unit] =
    ZIO
      .attemptBlocking {
        Option(path.getParent).foreach(Files.createDirectories(_))
        Files.write(path, entry.toJson.getBytes(StandardCharsets.UTF_8))
        ()
      }
      .mapError(e => FlowError.Persistence(s"failed to write review cache at $path", Some(e)))
