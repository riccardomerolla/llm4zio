package core

import java.nio.file.{ Files, Path, Paths }

import zio.*
import zio.test.*
import zio.test.Assertion.*

import models.FileError

object FileServiceSpec extends ZIOSpecDefault:

  /** Creates a temporary directory for tests */
  private def withTempDir[R, E, A](test: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.succeed(Files.createTempDirectory("fileservice-test"))
    )(tempDir =>
      ZIO.succeed {
        if Files.exists(tempDir) then
          Files
            .walk(tempDir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach { p =>
              val _ = Files.deleteIfExists(p)
            }
      }
    )(test)

  def spec: Spec[Any, Any] = suite("FileServiceSpec")(
    // ========================================================================
    // readFile tests
    // ========================================================================
    suite("readFile")(
      test("reads existing file successfully") {
        withTempDir { tempDir =>
          val filePath = tempDir.resolve("test.txt")
          val content  = "Hello, World!"
          for
            _      <- ZIO.succeed(Files.writeString(filePath, content))
            result <- FileService.readFile(filePath).provide(FileService.live)
          yield assertTrue(result == content)
        }
      },
      test("reads file with unicode content") {
        withTempDir { tempDir =>
          val filePath = tempDir.resolve("unicode.txt")
          val content  = "Hello \u4e16\u754c! \u00e9\u00e8\u00ea"
          for
            _      <- ZIO.succeed(Files.writeString(filePath, content))
            result <- FileService.readFile(filePath).provide(FileService.live)
          yield assertTrue(result == content)
        }
      },
      test("fails with NotFound for non-existent file") {
        val nonExistent = Paths.get("/non/existent/file.txt")
        for result <- FileService.readFile(nonExistent).provide(FileService.live).either
        yield assertTrue(result == Left(FileError.NotFound(nonExistent)))
      },
      test("reads multiline file correctly") {
        withTempDir { tempDir =>
          val filePath = tempDir.resolve("multiline.txt")
          val content  = "Line 1\nLine 2\nLine 3"
          for
            _      <- ZIO.succeed(Files.writeString(filePath, content))
            result <- FileService.readFile(filePath).provide(FileService.live)
          yield assertTrue(result == content)
        }
      },
    ),
    // ========================================================================
    // writeFile tests
    // ========================================================================
    suite("writeFile")(
      test("writes file successfully") {
        withTempDir { tempDir =>
          val filePath = tempDir.resolve("output.txt")
          val content  = "Test content"
          for
            _      <- FileService.writeFile(filePath, content).provide(FileService.live)
            result <- ZIO.succeed(Files.readString(filePath))
          yield assertTrue(result == content)
        }
      },
      test("creates parent directories automatically") {
        withTempDir { tempDir =>
          val filePath = tempDir.resolve("nested/deep/path/file.txt")
          val content  = "Nested content"
          for
            _      <- FileService.writeFile(filePath, content).provide(FileService.live)
            result <- ZIO.succeed(Files.readString(filePath))
          yield assertTrue(result == content)
        }
      },
      test("overwrites existing file") {
        withTempDir { tempDir =>
          val filePath = tempDir.resolve("overwrite.txt")
          for
            _      <- FileService.writeFile(filePath, "Original").provide(FileService.live)
            _      <- FileService.writeFile(filePath, "Updated").provide(FileService.live)
            result <- ZIO.succeed(Files.readString(filePath))
          yield assertTrue(result == "Updated")
        }
      },
      test("writes empty file") {
        withTempDir { tempDir =>
          val filePath = tempDir.resolve("empty.txt")
          for
            _      <- FileService.writeFile(filePath, "").provide(FileService.live)
            result <- ZIO.succeed(Files.readString(filePath))
          yield assertTrue(result == "")
        }
      },
    ),
    // ========================================================================
    // listFiles tests
    // ========================================================================
    suite("listFiles")(
      test("lists files with matching extensions") {
        withTempDir { tempDir =>
          for
            _     <- ZIO.succeed {
                       Files.writeString(tempDir.resolve("prog1.cbl"), "COBOL 1")
                       Files.writeString(tempDir.resolve("prog2.cbl"), "COBOL 2")
                       Files.writeString(tempDir.resolve("copy1.cpy"), "Copybook")
                       Files.writeString(tempDir.resolve("readme.txt"), "Readme")
                     }
            files <- FileService
                       .listFiles(tempDir, Set(".cbl", ".cpy"))
                       .runCollect
                       .provide(FileService.live)
          yield assertTrue(
            files.length == 3,
            files.exists(_.toString.endsWith("prog1.cbl")),
            files.exists(_.toString.endsWith("prog2.cbl")),
            files.exists(_.toString.endsWith("copy1.cpy")),
          )
        }
      },
      test("lists all files when extensions is empty") {
        withTempDir { tempDir =>
          for
            _     <- ZIO.succeed {
                       Files.writeString(tempDir.resolve("file1.txt"), "Text")
                       Files.writeString(tempDir.resolve("file2.json"), "JSON")
                     }
            files <- FileService
                       .listFiles(tempDir, Set.empty)
                       .runCollect
                       .provide(FileService.live)
          yield assertTrue(files.length == 2)
        }
      },
      test("lists files recursively in subdirectories") {
        withTempDir { tempDir =>
          for
            _     <- ZIO.succeed {
                       val subDir = tempDir.resolve("subdir")
                       Files.createDirectories(subDir)
                       Files.writeString(tempDir.resolve("root.cbl"), "Root")
                       Files.writeString(subDir.resolve("nested.cbl"), "Nested")
                     }
            files <- FileService
                       .listFiles(tempDir, Set(".cbl"))
                       .runCollect
                       .provide(FileService.live)
          yield assertTrue(
            files.length == 2,
            files.exists(_.toString.endsWith("root.cbl")),
            files.exists(_.toString.endsWith("nested.cbl")),
          )
        }
      },
      test("returns empty stream for empty directory") {
        withTempDir { tempDir =>
          for files <- FileService
                         .listFiles(tempDir, Set(".cbl"))
                         .runCollect
                         .provide(FileService.live)
          yield assertTrue(files.isEmpty)
        }
      },
      test("extension matching is case-insensitive") {
        withTempDir { tempDir =>
          for
            _     <- ZIO.succeed {
                       Files.writeString(tempDir.resolve("prog.CBL"), "Upper")
                       Files.writeString(tempDir.resolve("prog2.cbl"), "Lower")
                     }
            files <- FileService
                       .listFiles(tempDir, Set(".cbl"))
                       .runCollect
                       .provide(FileService.live)
          yield assertTrue(files.length == 2)
        }
      },
    ),
    // ========================================================================
    // ensureDirectory tests
    // ========================================================================
    suite("ensureDirectory")(
      test("creates directory if not exists") {
        withTempDir { tempDir =>
          val newDir = tempDir.resolve("new-directory")
          for
            _      <- FileService.ensureDirectory(newDir).provide(FileService.live)
            exists <- ZIO.succeed(Files.exists(newDir) && Files.isDirectory(newDir))
          yield assertTrue(exists)
        }
      },
      test("creates nested directories") {
        withTempDir { tempDir =>
          val nestedDir = tempDir.resolve("a/b/c/d")
          for
            _      <- FileService.ensureDirectory(nestedDir).provide(FileService.live)
            exists <- ZIO.succeed(Files.exists(nestedDir) && Files.isDirectory(nestedDir))
          yield assertTrue(exists)
        }
      },
      test("succeeds if directory already exists") {
        withTempDir { tempDir =>
          for
            _      <- FileService.ensureDirectory(tempDir).provide(FileService.live)
            exists <- ZIO.succeed(Files.exists(tempDir))
          yield assertTrue(exists)
        }
      },
    ),
    // ========================================================================
    // deleteRecursive tests
    // ========================================================================
    suite("deleteRecursive")(
      test("deletes file") {
        withTempDir { tempDir =>
          val filePath = tempDir.resolve("to-delete.txt")
          for
            _      <- ZIO.succeed(Files.writeString(filePath, "Delete me"))
            _      <- FileService.deleteRecursive(filePath).provide(FileService.live)
            exists <- ZIO.succeed(Files.exists(filePath))
          yield assertTrue(!exists)
        }
      },
      test("deletes directory recursively") {
        withTempDir { tempDir =>
          val dir = tempDir.resolve("to-delete-dir")
          for
            _      <- ZIO.succeed {
                        Files.createDirectories(dir.resolve("subdir"))
                        Files.writeString(dir.resolve("file1.txt"), "File 1")
                        Files.writeString(dir.resolve("subdir/file2.txt"), "File 2")
                      }
            _      <- FileService.deleteRecursive(dir).provide(FileService.live)
            exists <- ZIO.succeed(Files.exists(dir))
          yield assertTrue(!exists)
        }
      },
      test("succeeds if path does not exist") {
        withTempDir { tempDir =>
          val nonExistent = tempDir.resolve("non-existent")
          for result <- FileService.deleteRecursive(nonExistent).provide(FileService.live).either
          yield assertTrue(result.isRight)
        }
      },
    ),
    // ========================================================================
    // copyDirectory tests
    // ========================================================================
    suite("copyDirectory")(
      test("copies directory with files") {
        withTempDir { tempDir =>
          val srcDir  = tempDir.resolve("src")
          val destDir = tempDir.resolve("dest")
          for
            _        <- ZIO.succeed {
                          Files.createDirectories(srcDir)
                          Files.writeString(srcDir.resolve("file1.txt"), "Content 1")
                          Files.writeString(srcDir.resolve("file2.txt"), "Content 2")
                        }
            _        <- FileService.copyDirectory(srcDir, destDir).provide(FileService.live)
            content1 <- ZIO.succeed(Files.readString(destDir.resolve("file1.txt")))
            content2 <- ZIO.succeed(Files.readString(destDir.resolve("file2.txt")))
          yield assertTrue(
            content1 == "Content 1",
            content2 == "Content 2",
          )
        }
      },
      test("copies nested directory structure") {
        withTempDir { tempDir =>
          val srcDir  = tempDir.resolve("src")
          val destDir = tempDir.resolve("dest")
          for
            _    <- ZIO.succeed {
                      Files.createDirectories(srcDir.resolve("a/b"))
                      Files.writeString(srcDir.resolve("root.txt"), "Root")
                      Files.writeString(srcDir.resolve("a/nested.txt"), "Nested")
                      Files.writeString(srcDir.resolve("a/b/deep.txt"), "Deep")
                    }
            _    <- FileService.copyDirectory(srcDir, destDir).provide(FileService.live)
            root <- ZIO.succeed(Files.readString(destDir.resolve("root.txt")))
            nest <- ZIO.succeed(Files.readString(destDir.resolve("a/nested.txt")))
            deep <- ZIO.succeed(Files.readString(destDir.resolve("a/b/deep.txt")))
          yield assertTrue(
            root == "Root",
            nest == "Nested",
            deep == "Deep",
          )
        }
      },
      test("fails when source does not exist") {
        withTempDir { tempDir =>
          val nonExistent = tempDir.resolve("non-existent")
          val destDir     = tempDir.resolve("dest")
          for result <- FileService.copyDirectory(nonExistent, destDir).provide(FileService.live).either
          yield assertTrue(result == Left(FileError.NotFound(nonExistent)))
        }
      },
    ),
    // ========================================================================
    // exists tests
    // ========================================================================
    suite("exists")(
      test("returns true for existing file") {
        withTempDir { tempDir =>
          val filePath = tempDir.resolve("exists.txt")
          for
            _      <- ZIO.succeed(Files.writeString(filePath, "content"))
            exists <- FileService.exists(filePath).provide(FileService.live)
          yield assertTrue(exists)
        }
      },
      test("returns true for existing directory") {
        withTempDir { tempDir =>
          for exists <- FileService.exists(tempDir).provide(FileService.live)
          yield assertTrue(exists)
        }
      },
      test("returns false for non-existent path") {
        val nonExistent = Paths.get("/non/existent/path")
        for exists <- FileService.exists(nonExistent).provide(FileService.live)
        yield assertTrue(!exists)
      },
    ),
    // ========================================================================
    // getFileSize tests
    // ========================================================================
    suite("getFileSize")(
      test("returns correct size for file") {
        withTempDir { tempDir =>
          val filePath = tempDir.resolve("sized.txt")
          val content  = "Hello, World!" // 13 bytes
          for
            _    <- ZIO.succeed(Files.writeString(filePath, content))
            size <- FileService.getFileSize(filePath).provide(FileService.live)
          yield assertTrue(size == 13L)
        }
      },
      test("returns 0 for empty file") {
        withTempDir { tempDir =>
          val filePath = tempDir.resolve("empty.txt")
          for
            _    <- ZIO.succeed(Files.writeString(filePath, ""))
            size <- FileService.getFileSize(filePath).provide(FileService.live)
          yield assertTrue(size == 0L)
        }
      },
      test("fails for non-existent file") {
        val nonExistent = Paths.get("/non/existent/file.txt")
        for result <- FileService.getFileSize(nonExistent).provide(FileService.live).either
        yield assertTrue(result == Left(FileError.NotFound(nonExistent)))
      },
    ),
    // ========================================================================
    // countLines tests
    // ========================================================================
    suite("countLines")(
      test("counts lines correctly") {
        withTempDir { tempDir =>
          val filePath = tempDir.resolve("lines.txt")
          val content  = "Line 1\nLine 2\nLine 3"
          for
            _     <- ZIO.succeed(Files.writeString(filePath, content))
            count <- FileService.countLines(filePath).provide(FileService.live)
          yield assertTrue(count == 3L)
        }
      },
      test("returns 0 for empty file") {
        withTempDir { tempDir =>
          val filePath = tempDir.resolve("empty.txt")
          for
            _     <- ZIO.succeed(Files.writeString(filePath, ""))
            count <- FileService.countLines(filePath).provide(FileService.live)
          yield assertTrue(count == 0L)
        }
      },
      test("counts single line without newline") {
        withTempDir { tempDir =>
          val filePath = tempDir.resolve("single.txt")
          for
            _     <- ZIO.succeed(Files.writeString(filePath, "Single line"))
            count <- FileService.countLines(filePath).provide(FileService.live)
          yield assertTrue(count == 1L)
        }
      },
      test("fails for non-existent file") {
        val nonExistent = Paths.get("/non/existent/file.txt")
        for result <- FileService.countLines(nonExistent).provide(FileService.live).either
        yield assertTrue(result == Left(FileError.NotFound(nonExistent)))
      },
    ),
    // ========================================================================
    // FileError ADT tests
    // ========================================================================
    suite("FileError")(
      test("NotFound has correct message") {
        val error = FileError.NotFound(Paths.get("/test/path"))
        assertTrue(error.message.contains("/test/path"))
      },
      test("PermissionDenied has correct message") {
        val error = FileError.PermissionDenied(Paths.get("/test/path"))
        assertTrue(error.message.contains("Permission denied"))
      },
      test("IOError has correct message") {
        val error = FileError.IOError(Paths.get("/test/path"), "Test cause")
        assertTrue(
          error.message.contains("/test/path"),
          error.message.contains("Test cause"),
        )
      },
    ),
  )
