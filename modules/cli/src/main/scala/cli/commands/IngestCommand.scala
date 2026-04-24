package cli.commands

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import java.security.MessageDigest
import java.time.Instant

import zio.*
import zio.json.*
import zio.stream.ZStream

import shared.errors.FileError
import shared.services.FileService

object IngestCommand:

  final case class IngestRecord(
    source: String,
    scope: String,
    tags: List[String],
    text: String,
    sha256: String,
    bytes: Long,
    ingestedAt: Instant,
  ) derives JsonCodec

  final case class IngestSummary(
    workspace: Path,
    manifestPath: Path,
    scanned: Int,
    included: Int,
    skipped: Int,
    totalBytes: Long,
  )

  val defaultExtensions: Set[String] = Set("md", "txt", "markdown")

  /** Scan `source` (file or directory), read matching text files, emit a JSONL manifest under
    * `<workspace>/ingested/ingest-<timestamp>.jsonl`. This produces a portable spec of what to ingest — actual indexing
    * belongs to the gateway (which owns the embedding provider).
    */
  def ingest(
    workspaceRaw: String,
    sourceRaw: String,
    scope: String,
    tags: List[String],
    extensions: Set[String],
  ): ZIO[FileService, String, IngestSummary] =
    val workspace = Paths.get(workspaceRaw).toAbsolutePath.normalize
    val source    = Paths.get(sourceRaw).toAbsolutePath.normalize
    val now       = Instant.now()
    val manifest  = workspace
      .resolve("ingested")
      .resolve(s"ingest-${now.toEpochMilli}.jsonl")

    val collect: ZIO[FileService, String, (Int, List[IngestRecord])] =
      gatherPaths(source, extensions).mapError(toMsg).flatMap { paths =>
        ZIO
          .foreach(paths) { p =>
            FileService
              .readFile(p)
              .mapError(toMsg)
              .map(text => Some(toRecord(source.toAbsolutePath, p, text, scope, tags, now)))
              .catchAll(err => Console.printLineError(s"  ! skip $p: $err").ignore.as(None))
          }
          .map(results => (paths.size, results.flatten))
      }

    for
      _              <- ensureRegularOrDir(source)
      _              <- FileService.ensureDirectory(manifest.getParent).mapError(toMsg)
      scannedRecords <- collect
      (scanned, recs) = scannedRecords
      _              <- FileService
                          .writeFile(manifest, recs.map(_.toJson).mkString("\n") + (if recs.nonEmpty then "\n" else ""))
                          .mapError(toMsg)
    yield IngestSummary(
      workspace = workspace,
      manifestPath = manifest,
      scanned = scanned,
      included = recs.size,
      skipped = scanned - recs.size,
      totalBytes = recs.map(_.bytes).sum,
    )

  def render(s: IngestSummary): String =
    s"""|
        |Manifest: ${s.manifestPath}
        |Scanned:  ${s.scanned} file(s)
        |Included: ${s.included}
        |Skipped:  ${s.skipped}
        |Bytes:    ${s.totalBytes}
        |
        |Next: run `llm4zio-cli gateway run` and have it consume the manifest to index embeddings.""".stripMargin

  private def ensureRegularOrDir(source: Path): IO[String, Unit] =
    ZIO
      .attempt(Files.exists(source))
      .mapError(t => s"Cannot access $source: ${t.getMessage}")
      .flatMap { exists =>
        if !exists then ZIO.fail(s"Source does not exist: $source")
        else ZIO.unit
      }

  private def gatherPaths(source: Path, extensions: Set[String]): ZIO[FileService, FileError, List[Path]] =
    ZIO.attempt(Files.isDirectory(source)).orDie.flatMap { isDir =>
      if isDir then
        FileService
          .listFiles(source, extensions)
          .runCollect
          .map(_.toList)
      else
        val ok = extensions.isEmpty || extensions.exists(ext => source.toString.toLowerCase.endsWith(s".$ext"))
        if ok then ZStream.succeed(source).runCollect.map(_.toList)
        else ZIO.succeed(Nil)
    }

  private def toRecord(
    root: Path,
    file: Path,
    text: String,
    scope: String,
    tags: List[String],
    at: Instant,
  ): IngestRecord =
    val bytes    = text.getBytes(StandardCharsets.UTF_8)
    val relative =
      try root.relativize(file).toString
      catch case _: IllegalArgumentException => file.toString
    IngestRecord(
      source = relative,
      scope = scope,
      tags = tags,
      text = text,
      sha256 = sha256Hex(bytes),
      bytes = bytes.length.toLong,
      ingestedAt = at,
    )

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"$b%02x").mkString

  private def toMsg(err: Any): String = err match
    case fe: FileError => fe.message
    case other         => other.toString
