package cli.commands

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.*
import zio.json.*
import zio.test.*

import shared.services.FileService

object IngestCommandSpec extends ZIOSpecDefault:

  private def tempDir(prefix: String): UIO[Path] =
    ZIO.succeed(Files.createTempDirectory(prefix))

  private def write(p: Path, content: String): UIO[Unit] =
    ZIO.succeed { Files.createDirectories(p.getParent); Files.writeString(p, content) }.unit

  def spec = suite("IngestCommand")(
    test("scans a directory and writes a JSONL manifest with one record per matching file") {
      for
        tmp       <- tempDir("ingest-src-")
        ws        <- tempDir("ingest-ws-")
        _         <- write(tmp.resolve("a.md"), "# A")
        _         <- write(tmp.resolve("b.txt"), "body-b")
        _         <- write(tmp.resolve("ignored.bin"), "skip")
        _         <- write(tmp.resolve("sub/c.md"), "# C")
        summary   <- IngestCommand
                       .ingest(ws.toString, tmp.toString, "knowledge", List("cli"), IngestCommand.defaultExtensions)
                       .provide(FileService.live)
        lines      = Files.readAllLines(summary.manifestPath).toArray.toList.map(_.toString)
        records    = lines.flatMap(_.fromJson[IngestCommand.IngestRecord].toOption)
        includedOk = summary.included == 3
      yield
        assertTrue(includedOk) &&
          assertTrue(records.size == 3) &&
          assertTrue(records.forall(_.scope == "knowledge")) &&
          assertTrue(records.forall(_.tags == List("cli"))) &&
          assertTrue(records.forall(_.sha256.length == 64)) &&
          assertTrue(records.map(_.source).exists(_.endsWith("a.md"))) &&
          assertTrue(records.map(_.source).exists(_.endsWith("c.md")))
    },
    test("single file source works when extension matches") {
      for
        tmp     <- tempDir("ingest-single-")
        ws      <- tempDir("ingest-ws-")
        file     = tmp.resolve("note.md")
        _       <- write(file, "hello")
        summary <- IngestCommand
                     .ingest(ws.toString, file.toString, "k", Nil, IngestCommand.defaultExtensions)
                     .provide(FileService.live)
      yield
        assertTrue(summary.included == 1) &&
          assertTrue(summary.totalBytes == "hello".getBytes(StandardCharsets.UTF_8).length.toLong)
    },
    test("missing source fails with a clear error") {
      for
        ws     <- tempDir("ingest-ws-")
        result <- IngestCommand
                    .ingest(ws.toString, "/does/not/exist.md", "k", Nil, IngestCommand.defaultExtensions)
                    .provide(FileService.live)
                    .either
      yield assertTrue(result.left.exists(_.contains("does not exist")))
    },
    test("custom extensions filter correctly") {
      for
        tmp     <- tempDir("ingest-exts-")
        ws      <- tempDir("ingest-ws-")
        _       <- write(tmp.resolve("x.rst"), "r")
        _       <- write(tmp.resolve("y.md"), "m")
        summary <- IngestCommand
                     .ingest(ws.toString, tmp.toString, "k", Nil, Set("rst"))
                     .provide(FileService.live)
      yield assertTrue(summary.included == 1)
    },
  )
