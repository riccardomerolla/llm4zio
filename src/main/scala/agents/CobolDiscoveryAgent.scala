package agents

import java.nio.charset.{ CodingErrorAction, StandardCharsets }
import java.nio.file.{ FileSystems, Files, Path }

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.*
import zio.stream.*

import core.{ FileService, Logger }
import models.{ CobolFile, DiscoveryError, FileInventory, FileType, InventorySummary }

/** CobolDiscoveryAgent - Scan and catalog COBOL source files and copybooks
  *
  * Responsibilities:
  *   - Traverse directory structures
  *   - Identify .cbl, .cob, .cpy, .jcl files
  *   - Extract metadata (file size, last modified, encoding, line count)
  *   - Build initial file inventory
  *   - Generate discovery reports
  *
  * Interactions:
  *   - Output consumed by: CobolAnalyzerAgent, DependencyMapperAgent
  */
trait CobolDiscoveryAgent:
  def discover(sourcePath: Path): ZIO[Any, DiscoveryError, FileInventory]

object CobolDiscoveryAgent:
  def discover(sourcePath: Path): ZIO[CobolDiscoveryAgent, DiscoveryError, FileInventory] =
    ZIO.serviceWithZIO[CobolDiscoveryAgent](_.discover(sourcePath))

  val live: ZLayer[FileService & models.MigrationConfig, Nothing, CobolDiscoveryAgent] =
    ZLayer.fromFunction { (fileService: FileService, config: models.MigrationConfig) =>
      new CobolDiscoveryAgent {
        private val supportedExtensions = Set(".cbl", ".cob", ".cpy", ".jcl")
        private val maxDepth            = config.discoveryMaxDepth
        private val excludePatterns     = config.discoveryExcludePatterns
        private val maxProbeBytes       = 65536

        override def discover(sourcePath: Path): ZIO[Any, DiscoveryError, FileInventory] =
          for
            _        <- Logger.info(s"Starting discovery in $sourcePath")
            _        <- validateSourceDir(sourcePath)
            files    <- listCobolFiles(sourcePath).mapZIO(analyzeFile).runCollect.map(_.toList)
            now      <- Clock.instant
            summary   = summarize(files)
            inventory = FileInventory(
                          discoveredAt = now,
                          sourceDirectory = sourcePath,
                          files = files,
                          summary = summary,
                        )
            _        <- writeReports(inventory)
            _        <- Logger.info(s"Discovery complete: ${summary.totalFiles} files found")
          yield inventory

        private def validateSourceDir(sourcePath: Path): ZIO[Any, DiscoveryError, Unit] =
          ZIO
            .attemptBlocking(Files.exists(sourcePath) && Files.isDirectory(sourcePath))
            .mapError(e => DiscoveryError.ScanFailed(sourcePath, e.getMessage))
            .flatMap(exists => ZIO.fail(DiscoveryError.SourceNotFound(sourcePath)).unless(exists))
            .unit

        private def listCobolFiles(sourcePath: Path): ZStream[Any, DiscoveryError, Path] =
          val matchers = excludePatterns.map(pattern => FileSystems.getDefault.getPathMatcher(s"glob:$pattern"))
          ZStream.unwrapScoped {
            ZIO
              .fromAutoCloseable(ZIO.attemptBlocking(Files.walk(sourcePath, maxDepth)))
              .map { stream =>
                ZStream
                  .fromIterator(stream.iterator().asScala)
                  .mapError(e => DiscoveryError.ScanFailed(sourcePath, e.getMessage))
                  .filter(path => Files.isRegularFile(path))
                  .filter(path => !Files.isSymbolicLink(path))
                  .filter(path => hasSupportedExtension(path))
                  .filter(path => !isExcluded(path, matchers))
              }
              .mapError(e => DiscoveryError.ScanFailed(sourcePath, e.getMessage))
          }

        private def hasSupportedExtension(path: Path): Boolean =
          val name = path.getFileName.toString.toLowerCase
          supportedExtensions.exists(name.endsWith)

        private def isExcluded(path: Path, matchers: List[java.nio.file.PathMatcher]): Boolean =
          val normalized = path.toAbsolutePath.normalize()
          matchers.exists(_.matches(normalized))

        private def analyzeFile(path: Path): ZIO[Any, DiscoveryError, CobolFile] =
          for
            size      <- fileService.getFileSize(path).mapError(fe => DiscoveryError.MetadataFailed(path, fe.message))
            lineCount <- countLinesSafe(path)
            lastMod   <- ZIO
                           .attemptBlocking(Files.getLastModifiedTime(path).toInstant)
                           .mapError(e => DiscoveryError.MetadataFailed(path, e.getMessage))
            encoding  <- detectEncoding(path)
            fileType  <- detectFileType(path)
          yield CobolFile(
            path = path,
            name = path.getFileName.toString,
            size = size,
            lineCount = lineCount,
            lastModified = lastMod,
            encoding = encoding,
            fileType = fileType,
          )

        private def detectFileType(path: Path): ZIO[Any, DiscoveryError, FileType] =
          val name = path.getFileName.toString.toLowerCase
          if name.endsWith(".jcl") then ZIO.succeed(FileType.JCL)
          else
            readSnippet(path).map { snippet =>
              val upper             = snippet.toUpperCase
              val hasIdentification =
                upper.contains("IDENTIFICATION DIVISION") ||
                upper.contains("PROGRAM-ID") ||
                upper.contains("PROGRAM ID") ||
                upper.contains("IDENTIFICATION.")
              val hasDivision       =
                upper.contains("PROCEDURE DIVISION") ||
                upper.contains("ENVIRONMENT DIVISION") ||
                upper.contains("DATA DIVISION") ||
                upper.contains("PROCEDURE.") ||
                upper.contains("ENVIRONMENT.") ||
                upper.contains("DATA.")
              val looksLikeJcl      =
                upper.linesIterator.exists(_.trim.startsWith("//")) &&
                (upper.contains(" JOB ") || upper.contains(" EXEC ") || upper.contains("JOB ") || upper.contains(
                  "EXEC "
                ))

              if looksLikeJcl then FileType.JCL
              else if hasIdentification || hasDivision then FileType.Program
              else if name.endsWith(".cpy") then FileType.Copybook
              else FileType.Copybook
            }

        private def readSnippet(path: Path): ZIO[Any, DiscoveryError, String] =
          ZIO
            .attemptBlocking {
              val in = Files.newInputStream(path)
              try
                val buffer   = Array.ofDim[Byte](maxProbeBytes)
                val readSize = in.read(buffer)
                if readSize <= 0 then ""
                else
                  val actual  = if readSize == maxProbeBytes then buffer else buffer.take(readSize)
                  val decoder = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                  decoder.decode(java.nio.ByteBuffer.wrap(actual)).toString
              finally in.close()
            }
            .mapError(e => DiscoveryError.MetadataFailed(path, e.getMessage))

        private def detectEncoding(path: Path): ZIO[Any, DiscoveryError, String] =
          for
            bytes    <- ZIO
                          .attemptBlocking {
                            val all = Files.readAllBytes(path)
                            if all.length > 8192 then all.take(8192) else all
                          }
                          .mapError(e => DiscoveryError.EncodingDetectionFailed(path, e.getMessage))
            encoding <- ZIO
                          .attemptBlocking {
                            val decoder = StandardCharsets.UTF_8
                              .newDecoder()
                              .onMalformedInput(CodingErrorAction.REPORT)
                              .onUnmappableCharacter(CodingErrorAction.REPORT)
                            val _       = decoder.decode(java.nio.ByteBuffer.wrap(bytes))
                            "UTF-8"
                          }
                          .catchAll(_ => ZIO.succeed("EBCDIC"))
          yield encoding

        private def countLinesSafe(path: Path): ZIO[Any, DiscoveryError, Long] =
          fileService
            .countLines(path)
            .mapError(fe => DiscoveryError.MetadataFailed(path, fe.message))
            .catchAll { _ =>
              ZIO
                .attemptBlocking {
                  val bytes = Files.readAllBytes(path)
                  if bytes.isEmpty then 0L
                  else
                    val lineBreaks = bytes.count(b => b == '\n' || b == '\r' || b == 0x15.toByte)
                    if lineBreaks == 0 then 1L else lineBreaks.toLong
                }
                .mapError(e => DiscoveryError.MetadataFailed(path, e.getMessage))
            }

        private def summarize(files: List[CobolFile]): InventorySummary =
          InventorySummary(
            totalFiles = files.length,
            programFiles = files.count(_.fileType == FileType.Program),
            copybooks = files.count(_.fileType == FileType.Copybook),
            jclFiles = files.count(_.fileType == FileType.JCL),
            totalLines = files.map(_.lineCount).sum,
            totalBytes = files.map(_.size).sum,
          )

        private def writeReports(inventory: FileInventory): ZIO[Any, DiscoveryError, Unit] =
          val reportDir     = Path.of("reports/discovery")
          val inventoryPath = reportDir.resolve("inventory.json")
          val markdownPath  = reportDir.resolve("discovery-report.md")
          val json          = inventory.toJsonPretty
          for
            _ <- fileService.ensureDirectory(reportDir)
                   .mapError(fe => DiscoveryError.ReportWriteFailed(reportDir, fe.message))
            _ <- validateInventorySchema(inventoryPath, json)
            _ <- fileService
                   .writeFileAtomic(inventoryPath, json)
                   .mapError(fe => DiscoveryError.ReportWriteFailed(inventoryPath, fe.message))
            _ <- fileService
                   .writeFileAtomic(markdownPath, renderMarkdown(inventory))
                   .mapError(fe => DiscoveryError.ReportWriteFailed(markdownPath, fe.message))
          yield ()

        private def validateInventorySchema(path: Path, json: String): ZIO[Any, DiscoveryError, Unit] =
          ZIO
            .fromEither(json.fromJson[FileInventory])
            .mapError(error => DiscoveryError.ReportSchemaMismatch(path, error))
            .unit

        private def renderMarkdown(inventory: FileInventory): String =
          val summary = inventory.summary
          val header  =
            s"""# COBOL Discovery Report
               |
               |Discovered at: ${inventory.discoveredAt}
               |Source directory: ${inventory.sourceDirectory}
               |
               |## Summary
               |- Total files: ${summary.totalFiles}
               |- Program files: ${summary.programFiles}
               |- Copybooks: ${summary.copybooks}
               |- JCL files: ${summary.jclFiles}
               |- Total lines: ${summary.totalLines}
               |- Total bytes: ${summary.totalBytes}
               |
               |## Files
               |""".stripMargin
          val rows    = inventory.files.sortBy(_.name).map { file =>
            s"- ${file.name} (${file.fileType}, ${file.size} bytes, ${file.lineCount} lines, ${file.encoding})"
          }
          (header + rows.mkString("\n") + "\n").trim
      }
    }
