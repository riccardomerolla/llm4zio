package gateway.boundary.telegram

import java.io.{ BufferedInputStream, BufferedOutputStream }
import java.nio.file.{ Files, Path, Paths, StandardOpenOption }
import java.util.UUID
import java.util.zip.{ ZipEntry, ZipOutputStream }

import zio.*

enum FileTransferError:
  case NoFilesDetected
  case InvalidAttachmentPath(raw: String)
  case AttachmentNotFound(path: Path)
  case AttachmentNotReadable(path: Path)
  case IoFailure(message: String)
  case TelegramFailure(error: TelegramClientError)

final case class FileTransferProgress(
  stage: String,
  percentage: Int,
  detail: String,
)

trait FileTransfer:
  def sendAsZip(
    chatId: Long,
    replyToMessageId: Option[Long],
    files: List[Path],
    caption: Option[String],
    onProgress: FileTransferProgress => UIO[Unit],
  ): IO[FileTransferError, List[TelegramMessage]]

object FileTransfer:
  val ChunkSizeBytes: Int                                     = 1024 * 256
  val MaxUploadPartBytes: Long                                = 45L * 1024L * 1024L
  private val SupportedSuffixes                               = List(".pdf", ".zip")
  private val ProgressNoop: FileTransferProgress => UIO[Unit] = _ => ZIO.unit

  def sendAsZip(
    chatId: Long,
    replyToMessageId: Option[Long],
    files: List[Path],
    caption: Option[String],
    onProgress: FileTransferProgress => UIO[Unit] = ProgressNoop,
  ): ZIO[FileTransfer, FileTransferError, List[TelegramMessage]] =
    ZIO.serviceWithZIO[FileTransfer](_.sendAsZip(chatId, replyToMessageId, files, caption, onProgress))

  def attachmentPaths(metadata: Map[String, String]): IO[FileTransferError, List[Path]] =
    ZIO.foreach(metadata.values.toList.distinct)(toAttachmentPath).map(_.flatten)

  val live: ZLayer[TelegramClient, Nothing, FileTransfer] =
    ZLayer.fromFunction(FileTransferLive.apply)

  private def toAttachmentPath(raw: String): IO[FileTransferError, Option[Path]] =
    val trimmed = raw.trim
    if trimmed.isEmpty then ZIO.none
    else if !SupportedSuffixes.exists(ext => trimmed.toLowerCase.endsWith(ext)) then ZIO.none
    else
      ZIO
        .attempt(Paths.get(trimmed))
        .mapBoth(
          _ => FileTransferError.InvalidAttachmentPath(trimmed),
          Some(_),
        )

final case class FileTransferLive(client: TelegramClient) extends FileTransfer:

  override def sendAsZip(
    chatId: Long,
    replyToMessageId: Option[Long],
    files: List[Path],
    caption: Option[String],
    onProgress: FileTransferProgress => UIO[Unit],
  ): IO[FileTransferError, List[TelegramMessage]] =
    for
      validated <- validateFiles(files)
      _         <- onProgress(FileTransferProgress("prepare", 5, s"Found ${validated.length} attachment(s)."))
      zipPath   <- buildZip(validated, onProgress)
      sent      <- uploadZip(chatId, replyToMessageId, zipPath, caption, onProgress).ensuring(deleteFile(zipPath))
    yield sent

  private def validateFiles(paths: List[Path]): IO[FileTransferError, List[Path]] =
    val candidates = paths.map(_.toAbsolutePath.normalize).distinct
    if candidates.isEmpty then ZIO.fail(FileTransferError.NoFilesDetected)
    else
      ZIO.foreach(candidates) { path =>
        ZIO
          .attemptBlocking((Files.exists(path), Files.isRegularFile(path), Files.isReadable(path)))
          .mapError(t => FileTransferError.IoFailure(Option(t.getMessage).getOrElse(path.toString)))
          .flatMap {
            case (false, _, _) => ZIO.fail(FileTransferError.AttachmentNotFound(path))
            case (_, false, _) => ZIO.fail(FileTransferError.AttachmentNotReadable(path))
            case (_, _, false) => ZIO.fail(FileTransferError.AttachmentNotReadable(path))
            case _             => ZIO.succeed(path)
          }
      }

  private def buildZip(
    files: List[Path],
    onProgress: FileTransferProgress => UIO[Unit],
  ): IO[FileTransferError, Path] =
    for
      zipPath <- createTempFile("telegram-transfer", ".zip")
      write    = ZIO
                   .attemptBlocking {
                     val output = Files.newOutputStream(
                       zipPath,
                       StandardOpenOption.CREATE,
                       StandardOpenOption.TRUNCATE_EXISTING,
                       StandardOpenOption.WRITE,
                     )
                     val zipOut = ZipOutputStream(BufferedOutputStream(output, FileTransfer.ChunkSizeBytes))
                     try
                       files.foreach { file =>
                         val entry = ZipEntry(file.getFileName.toString)
                         zipOut.putNextEntry(entry)
                         val input = BufferedInputStream(Files.newInputStream(file), FileTransfer.ChunkSizeBytes)
                         try
                           val buffer          = Array.ofDim[Byte](FileTransfer.ChunkSizeBytes)
                           def copyAll(): Unit =
                             val read = input.read(buffer)
                             if read != -1 then
                               zipOut.write(buffer, 0, read)
                               copyAll()
                           copyAll()
                         finally input.close()
                         zipOut.closeEntry()
                       }
                     finally zipOut.close()
                   }
                   .mapError(t => FileTransferError.IoFailure(Option(t.getMessage).getOrElse("failed to build zip")))
      _       <- onProgress(FileTransferProgress("zip", 30, "Creating ZIP archive..."))
      _       <- write
      _       <- onProgress(FileTransferProgress("zip", 65, s"ZIP archive ready: ${zipPath.getFileName}."))
    yield zipPath

  private def uploadZip(
    chatId: Long,
    replyToMessageId: Option[Long],
    zipPath: Path,
    caption: Option[String],
    onProgress: FileTransferProgress => UIO[Unit],
  ): IO[FileTransferError, List[TelegramMessage]] =
    for
      zipSize         <- fileSize(zipPath)
      _               <- onProgress(FileTransferProgress("upload", 75, s"Uploading ${zipPath.getFileName} (${zipSize} bytes)."))
      split           <- splitIfNeeded(zipPath, zipSize)
      (parts, cleanup) = split
      sent            <- ZIO.foreach(parts.zipWithIndex) {
                           case (partPath, idx) =>
                             val partNo      = idx + 1
                             val totalParts  = parts.length
                             val partLabel   = if totalParts == 1 then "" else s" (part $partNo/$totalParts)"
                             val partCaption =
                               if totalParts == 1 then caption
                               else Some(caption.getOrElse("Generated files") + s" - part $partNo/$totalParts")
                             val pct         = 75 + ((partNo.toDouble / totalParts.toDouble) * 25.0).toInt
                             onProgress(FileTransferProgress("upload", pct, s"Uploading${partLabel}...")) *>
                               client
                                 .sendDocument(
                                   TelegramSendDocument(
                                     chat_id = chatId,
                                     document_path = partPath.toString,
                                     file_name = Some(partPath.getFileName.toString),
                                     caption = partCaption,
                                     reply_to_message_id = replyToMessageId,
                                   )
                                 )
                                 .mapError(FileTransferError.TelegramFailure.apply)
                         }.ensuring(cleanup)
      _               <- onProgress(FileTransferProgress("upload", 100, "Transfer completed."))
    yield sent

  private def splitIfNeeded(zipPath: Path, zipSize: Long): IO[FileTransferError, (List[Path], UIO[Unit])] =
    if zipSize <= FileTransfer.MaxUploadPartBytes then ZIO.succeed((List(zipPath), ZIO.unit))
    else splitIntoParts(zipPath).map(parts => (parts, deleteFiles(parts)))

  private def splitIntoParts(zipPath: Path): IO[FileTransferError, List[Path]] =
    ZIO
      .attemptBlocking {
        val buffer = Array.ofDim[Byte](FileTransfer.ChunkSizeBytes)
        val input  = BufferedInputStream(Files.newInputStream(zipPath), FileTransfer.ChunkSizeBytes)
        try
          final case class PartState(
            partIndex: Int,
            bytesInPart: Long,
            currentPartPath: Path,
            output: BufferedOutputStream,
            createdParts: List[Path],
          )

          def openPart(partIndex: Int): (Path, BufferedOutputStream) =
            val path = partPath(zipPath, partIndex)
            val out  = BufferedOutputStream(
              Files.newOutputStream(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
              ),
              FileTransfer.ChunkSizeBytes,
            )
            (path, out)

          def rotate(state: PartState): PartState =
            state.output.close()
            val nextIndex       = state.partIndex + 1
            val (nextPath, out) = openPart(nextIndex)
            PartState(
              partIndex = nextIndex,
              bytesInPart = 0L,
              currentPartPath = nextPath,
              output = out,
              createdParts = state.createdParts :+ state.currentPartPath,
            )

          def writeChunk(state: PartState, offset: Int, length: Int): PartState =
            if length <= 0 then state
            else if state.bytesInPart >= FileTransfer.MaxUploadPartBytes then
              writeChunk(rotate(state), offset, length)
            else
              val writable = math.min(length.toLong, FileTransfer.MaxUploadPartBytes - state.bytesInPart).toInt
              state.output.write(buffer, offset, writable)
              val updated  = state.copy(bytesInPart = state.bytesInPart + writable.toLong)
              writeChunk(updated, offset + writable, length - writable)

          def copyAll(state: PartState): List[Path] =
            val read = input.read(buffer)
            if read == -1 then
              state.output.close()
              state.createdParts :+ state.currentPartPath
            else
              copyAll(writeChunk(state, offset = 0, length = read))

          val (firstPath, firstOut) = openPart(1)
          val initial               = PartState(
            partIndex = 1,
            bytesInPart = 0L,
            currentPartPath = firstPath,
            output = firstOut,
            createdParts = List.empty,
          )
          copyAll(initial)
        finally input.close()
      }
      .mapError(t => FileTransferError.IoFailure(Option(t.getMessage).getOrElse("failed to split archive")))

  private def createTempFile(prefix: String, suffix: String): IO[FileTransferError, Path] =
    ZIO
      .attemptBlocking(Files.createTempFile(s"$prefix-${UUID.randomUUID().toString.take(8)}", suffix))
      .mapError(t => FileTransferError.IoFailure(Option(t.getMessage).getOrElse("failed to create temp file")))

  private def deleteFile(path: Path): UIO[Unit] =
    ZIO.attemptBlocking(Files.deleteIfExists(path)).ignore

  private def deleteFiles(paths: List[Path]): UIO[Unit] =
    ZIO.foreachDiscard(paths)(deleteFile)

  private def fileSize(path: Path): IO[FileTransferError, Long] =
    ZIO
      .attemptBlocking(Files.size(path))
      .mapError(t => FileTransferError.IoFailure(Option(t.getMessage).getOrElse(path.toString)))

  private def partPath(base: Path, index: Int): Path =
    val parent = Option(base.getParent).getOrElse(Paths.get("."))
    parent.resolve(f"${base.getFileName.toString}.part$index%03d")
