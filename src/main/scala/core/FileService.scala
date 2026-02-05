package core

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.*

import scala.jdk.CollectionConverters.*

import zio.*
import zio.stream.*

import models.FileError

/** FileService - File I/O operations with ZIO effects
  *
  * Features:
  *   - Read/write files with proper resource management
  *   - Stream large files efficiently
  *   - Directory traversal with extension filtering
  *   - File metadata extraction
  *   - Typed error handling with FileError ADT
  */
trait FileService:
  def readFile(path: Path): ZIO[Any, FileError, String]
  def writeFile(path: Path, content: String): ZIO[Any, FileError, Unit]
  def listFiles(directory: Path, extensions: Set[String]): ZStream[Any, FileError, Path]
  def copyDirectory(src: Path, dest: Path): ZIO[Any, FileError, Unit]
  def ensureDirectory(path: Path): ZIO[Any, FileError, Unit]
  def deleteRecursive(path: Path): ZIO[Any, FileError, Unit]
  def exists(path: Path): ZIO[Any, FileError, Boolean]
  def getFileSize(path: Path): ZIO[Any, FileError, Long]
  def countLines(path: Path): ZIO[Any, FileError, Long]

object FileService:
  def readFile(path: Path): ZIO[FileService, FileError, String] =
    ZIO.serviceWithZIO[FileService](_.readFile(path))

  def writeFile(path: Path, content: String): ZIO[FileService, FileError, Unit] =
    ZIO.serviceWithZIO[FileService](_.writeFile(path, content))

  def listFiles(directory: Path, extensions: Set[String]): ZStream[FileService, FileError, Path] =
    ZStream.serviceWithStream[FileService](_.listFiles(directory, extensions))

  def copyDirectory(src: Path, dest: Path): ZIO[FileService, FileError, Unit] =
    ZIO.serviceWithZIO[FileService](_.copyDirectory(src, dest))

  def ensureDirectory(path: Path): ZIO[FileService, FileError, Unit] =
    ZIO.serviceWithZIO[FileService](_.ensureDirectory(path))

  def deleteRecursive(path: Path): ZIO[FileService, FileError, Unit] =
    ZIO.serviceWithZIO[FileService](_.deleteRecursive(path))

  def exists(path: Path): ZIO[FileService, FileError, Boolean] =
    ZIO.serviceWithZIO[FileService](_.exists(path))

  def getFileSize(path: Path): ZIO[FileService, FileError, Long] =
    ZIO.serviceWithZIO[FileService](_.getFileSize(path))

  def countLines(path: Path): ZIO[FileService, FileError, Long] =
    ZIO.serviceWithZIO[FileService](_.countLines(path))

  /** Maps Java file exceptions to typed FileError */
  private def mapToFileError(path: Path)(e: Throwable): FileError = e match
    case _: NoSuchFileException        => FileError.NotFound(path)
    case _: AccessDeniedException      => FileError.PermissionDenied(path)
    case _: DirectoryNotEmptyException => FileError.DirectoryNotEmpty(path)
    case _: FileAlreadyExistsException => FileError.AlreadyExists(path)
    case _: InvalidPathException       => FileError.InvalidPath(path.toString)
    case e: IOException                => FileError.IOError(path, e.getMessage)
    case e                             => FileError.IOError(path, e.getMessage)

  val live: ZLayer[Any, Nothing, FileService] = ZLayer.succeed {
    new FileService {

      override def readFile(path: Path): ZIO[Any, FileError, String] =
        ZIO
          .attemptBlocking(Files.readString(path, StandardCharsets.UTF_8))
          .mapError(mapToFileError(path))

      override def writeFile(path: Path, content: String): ZIO[Any, FileError, Unit] =
        for
          _ <- ensureParentDirectory(path)
          _ <- ZIO
                 .attemptBlocking(Files.writeString(path, content, StandardCharsets.UTF_8))
                 .mapError(mapToFileError(path))
        yield ()

      override def listFiles(directory: Path, extensions: Set[String]): ZStream[Any, FileError, Path] =
        ZStream.unwrapScoped {
          ZIO
            .fromAutoCloseable(ZIO.attemptBlocking(Files.walk(directory)))
            .map { stream =>
              ZStream
                .fromIterator(
                  stream
                    .iterator()
                    .asScala
                    .filter { p =>
                      Files.isRegularFile(p) &&
                      (extensions.isEmpty || extensions.exists(ext =>
                        p.toString.toLowerCase.endsWith(ext.toLowerCase)
                      ))
                    }
                )
                .mapError(mapToFileError(directory))
            }
            .mapError(mapToFileError(directory))
        }

      override def copyDirectory(src: Path, dest: Path): ZIO[Any, FileError, Unit] =
        for
          srcExists <- ZIO
                         .attemptBlocking(Files.exists(src) && Files.isDirectory(src))
                         .mapError(mapToFileError(src))
          _         <- ZIO.fail(FileError.NotFound(src)).unless(srcExists)
          _         <- ensureDirectory(dest)
          sources   <- ZIO
                         .attemptBlocking {
                           Files.walk(src).iterator().asScala.toList
                         }
                         .mapError(mapToFileError(src))
          _         <- ZIO.foreachDiscard(sources) { source =>
                         val relative    = src.relativize(source)
                         val destination = dest.resolve(relative)
                         ZIO
                           .attemptBlocking {
                             if Files.isDirectory(source) then Files.createDirectories(destination)
                             else Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
                           }
                           .mapError(mapToFileError(source))
                           .unit
                       }
        yield ()

      override def ensureDirectory(path: Path): ZIO[Any, FileError, Unit] =
        ZIO
          .attemptBlocking {
            val existsAndIsDir = Files.exists(path) && Files.isDirectory(path)
            val notExists      = !Files.exists(path)
            (existsAndIsDir, notExists)
          }
          .mapError(mapToFileError(path))
          .flatMap {
            case (true, _) => ZIO.unit
            case (_, true) =>
              ZIO
                .attemptBlocking {
                  val _ = Files.createDirectories(path)
                }
                .mapError(mapToFileError(path))
            case _         => ZIO.fail(FileError.IOError(path, "Path exists but is not a directory"))
          }

      override def deleteRecursive(path: Path): ZIO[Any, FileError, Unit] =
        ZIO
          .attemptBlocking {
            if Files.exists(path) then
              Files
                .walk(path)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p => Files.delete(p))
          }
          .mapError(mapToFileError(path))

      override def exists(path: Path): ZIO[Any, FileError, Boolean] =
        ZIO
          .attemptBlocking(Files.exists(path))
          .mapError(mapToFileError(path))

      override def getFileSize(path: Path): ZIO[Any, FileError, Long] =
        ZIO
          .attemptBlocking(Files.size(path))
          .mapError(mapToFileError(path))

      override def countLines(path: Path): ZIO[Any, FileError, Long] =
        ZIO.scoped {
          ZIO
            .fromAutoCloseable(ZIO.attemptBlocking(Files.lines(path)))
            .flatMap(stream => ZIO.attemptBlocking(stream.count()))
            .mapError(mapToFileError(path))
        }

      private def ensureParentDirectory(path: Path): ZIO[Any, FileError, Unit] =
        Option(path.getParent) match
          case Some(parent) => ensureDirectory(parent)
          case None         => ZIO.unit
    }
  }
