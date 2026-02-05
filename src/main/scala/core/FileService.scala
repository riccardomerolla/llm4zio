package core

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import zio.*
import zio.stream.*

/** FileService - File I/O operations with ZIO effects
  *
  * Features:
  *   - Read/write files with proper resource management
  *   - Stream large files efficiently
  *   - Directory traversal
  *   - File metadata extraction
  */
trait FileService:
  def readFile(path: Path): ZIO[Any, Throwable, String]
  def writeFile(path: Path, content: String): ZIO[Any, Throwable, Unit]
  def listFiles(directory: Path, pattern: String): ZStream[Any, Throwable, Path]
  def createDirectory(path: Path): ZIO[Any, Throwable, Unit]

object FileService:
  def readFile(path: Path): ZIO[FileService, Throwable, String] =
    ZIO.serviceWithZIO[FileService](_.readFile(path))

  def writeFile(path: Path, content: String): ZIO[FileService, Throwable, Unit] =
    ZIO.serviceWithZIO[FileService](_.writeFile(path, content))

  def listFiles(directory: Path, pattern: String): ZStream[FileService, Throwable, Path] =
    ZStream.serviceWithStream[FileService](_.listFiles(directory, pattern))

  def createDirectory(path: Path): ZIO[FileService, Throwable, Unit] =
    ZIO.serviceWithZIO[FileService](_.createDirectory(path))

  val live: ZLayer[Any, Nothing, FileService] = ZLayer.succeed {
    new FileService {
      override def readFile(path: Path): ZIO[Any, Throwable, String] =
        ZIO.attempt(Files.readString(path, StandardCharsets.UTF_8))

      override def writeFile(path: Path, content: String): ZIO[Any, Throwable, Unit] =
        ZIO.attempt(Files.writeString(path, content, StandardCharsets.UTF_8)).unit

      override def listFiles(directory: Path, pattern: String): ZStream[Any, Throwable, Path] =
        ZStream.fromIteratorScoped(
          ZIO.fromAutoCloseable(ZIO.attempt(Files.walk(directory)))
            .map(_.iterator().asScala.filter(p => p.toString.matches(pattern)))
        )

      override def createDirectory(path: Path): ZIO[Any, Throwable, Unit] =
        ZIO.attempt(Files.createDirectories(path)).unit
    }
  }
