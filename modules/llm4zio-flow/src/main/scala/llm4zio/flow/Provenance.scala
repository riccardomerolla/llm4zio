package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.json.*
import zio.{ IO, ZIO }

/** The clean-room receipt: one JSON manifest recording exactly what crossed the wall and on whose authority — the
  * spec-pack file hashes, the seats (role → model) that produced them, the gate verdict digests, and the human
  * approver. Written by seed, append-extended by verify (equivalence report hash) and review (fix-spec ids), committed
  * like any other artifact so the evidence chain survives machines. Fields are only ever added by [[extend]] — never
  * rewritten — by convention.
  */
final case class Provenance(
  schema: Int,
  pack: String,
  llm4zioVersion: String,
  createdAt: String,
  approvedBy: Option[String],
  seats: Map[String, String],
  specs: Map[String, String],
  gateVerdicts: Map[String, String],
  equivalenceReport: Option[String],
  fixSpecs: List[String],
) derives JsonCodec

object Provenance:

  def write(path: Path, provenance: Provenance): IO[FlowError, Unit] =
    ZIO
      .attemptBlocking {
        Option(path.getParent).foreach(Files.createDirectories(_))
        Files.write(path, provenance.toJsonPretty.getBytes(StandardCharsets.UTF_8))
        ()
      }
      .mapError(e => FlowError.Persistence(s"failed to write provenance manifest at $path", Some(e)))

  def load(path: Path): IO[FlowError, Provenance] =
    ZIO
      .attemptBlocking(Files.readString(path))
      .mapError(e => FlowError.Persistence(s"failed to read provenance manifest at $path", Some(e)))
      .flatMap(json =>
        ZIO
          .fromEither(json.fromJson[Provenance])
          .mapError(e => FlowError.PlanParse(s"malformed provenance manifest at $path: $e"))
      )

  /** Load the manifest, apply `f`, persist and return the result — how verify and review append their evidence. */
  def extend(path: Path)(f: Provenance => Provenance): IO[FlowError, Provenance] =
    load(path).map(f).tap(write(path, _))

  /** Plain SHA-256 hex over each file's bytes, keyed by the given `root`-relative paths — deliberately the same digest
    * `shasum -a 256` prints, so a bank can verify the manifest against the files without llm4zio.
    */
  def hashFiles(root: Path, files: List[String]): IO[FlowError, Map[String, String]] =
    ZIO
      .attemptBlocking {
        files.map { file =>
          val digest = java.security.MessageDigest.getInstance("SHA-256")
          val bytes  = Files.readAllBytes(root.resolve(file))
          file -> digest.digest(bytes).map("%02x".format(_)).mkString
        }.toMap
      }
      .mapError(e => FlowError.Persistence(s"failed to hash files under $root", Some(e)))
