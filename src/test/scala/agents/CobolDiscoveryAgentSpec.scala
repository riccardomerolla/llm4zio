package agents

import java.nio.file.{ Files, Path }

import zio.*
import zio.json.*
import zio.test.*

import core.FileService
import models.{ FileInventory, InventorySummary }

object CobolDiscoveryAgentSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Any] = suite("CobolDiscoveryAgentSpec")(
    test("discovers COBOL files and builds summary") {
      ZIO.scoped {
        for
          tempDir   <- ZIO.attemptBlocking(Files.createTempDirectory("discovery-spec"))
          _         <- createFile(tempDir.resolve("PROG1.cbl"), "IDENTIFICATION.\nPROCEDURE.\n")
          _         <- createFile(tempDir.resolve("COPY1.cpy"), "01 WS-NAME PIC X(10).\n")
          _         <- createFile(tempDir.resolve("SHARED.cbl"), "01 WS-SHARED PIC X(5).\n")
          _         <- createFile(tempDir.resolve("JOB1.jcl"), "//JOB JOB\n")
          _         <- createFile(tempDir.resolve("README.txt"), "ignore\n")
          _         <- createFile(tempDir.resolve("target/IGNORED.cbl"), "IGNORE\n")
          inventory <- CobolDiscoveryAgent
                         .discover(tempDir)
                         .provide(
                           FileService.live,
                           ZLayer.succeed(models.MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                           CobolDiscoveryAgent.live,
                         )
          report    <- readReport()
          parsed     = report.fromJson[FileInventory]
        yield assertTrue(
          inventory.files.map(_.name).toSet == Set("PROG1.cbl", "COPY1.cpy", "SHARED.cbl", "JOB1.jcl"),
          inventory.summary == InventorySummary(
            totalFiles = 4,
            programFiles = 1,
            copybooks = 2,
            jclFiles = 1,
            totalLines = 5,
            totalBytes = inventory.files.map(_.size).sum,
          ),
          parsed.isRight,
        )
      }
    },
    test("detects non-UTF8 content as EBCDIC") {
      ZIO.scoped {
        for
          tempDir   <- ZIO.attemptBlocking(Files.createTempDirectory("discovery-encoding"))
          path       = tempDir.resolve("BINARY.cbl")
          _         <- ZIO.attemptBlocking {
                         Files.createDirectories(path.getParent)
                         Files.write(path, Array[Byte](0xc1.toByte, 0xc2.toByte))
                       }
          inventory <- CobolDiscoveryAgent
                         .discover(tempDir)
                         .provide(
                           FileService.live,
                           ZLayer.succeed(models.MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                           CobolDiscoveryAgent.live,
                         )
          encoding   = inventory.files.find(_.name == "BINARY.cbl").map(_.encoding).getOrElse("")
        yield assertTrue(encoding == "EBCDIC")
      }
    },
  )

  private def createFile(path: Path, content: String): Task[Unit] =
    ZIO.attemptBlocking {
      Files.createDirectories(path.getParent)
      Files.writeString(path, content)
    }.unit

  private def readReport(): Task[String] =
    ZIO.attemptBlocking {
      Files.readString(Path.of("reports/discovery/inventory.json"))
    }
