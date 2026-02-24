package llm4zio.tools

import java.nio.charset.StandardCharsets
import java.nio.file.{ FileSystems, Files, Path, StandardOpenOption }

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.*
import zio.json.ast.Json

object BuiltInTools:
  final case class WorkspaceConfig(
    root: Path,
    maxReadBytes: Long = 256 * 1024,
    maxWriteBytes: Long = 256 * 1024,
  )

  def legacyModernizationTools(config: WorkspaceConfig): List[Tool] =
    List(
      readFile(config),
      writeFile(config),
      discoverFiles(config),
      searchInFiles(config),
      validateCobolSyntax(config),
      validateJavaCompilation(config),
    )

  def readFile(config: WorkspaceConfig): Tool =
    Tool(
      name = "read_file",
      description = "Read a file from the workspace",
      parameters = Json.Obj(
        "type"       -> Json.Str("object"),
        "properties" -> Json.Obj(
          "path" -> Json.Obj("type" -> Json.Str("string"))
        ),
        "required"   -> Json.Arr(Chunk(Json.Str("path"))),
      ),
      tags = Set("file", "read", "workspace"),
      sandbox = ToolSandbox.WorkspaceReadOnly,
      execute = args =>
        for
          path     <- requireString(args, "path")
          resolved <- resolveInWorkspace(config, path)
          _        <- ensureRegularFile(resolved)
          size     <- ZIO
                        .attemptBlocking(Files.size(resolved))
                        .mapError(err => ToolExecutionError.ExecutionFailed(s"Failed to get file size: ${err.getMessage}"))
          _        <- ZIO.fail(ToolExecutionError.InvalidParameters(s"File too large: $size bytes"))
                        .when(size > config.maxReadBytes)
          content  <- ZIO
                        .attemptBlocking(Files.readString(resolved, StandardCharsets.UTF_8))
                        .mapError(err => ToolExecutionError.ExecutionFailed(s"Failed to read file: ${err.getMessage}"))
        yield Json.Obj(
          "path"    -> Json.Str(config.root.relativize(resolved).toString),
          "bytes"   -> Json.Num(BigDecimal(size)),
          "content" -> Json.Str(content),
        ),
    )

  def writeFile(config: WorkspaceConfig): Tool =
    Tool(
      name = "write_file",
      description = "Write text content to a file in the workspace",
      parameters = Json.Obj(
        "type"       -> Json.Str("object"),
        "properties" -> Json.Obj(
          "path"    -> Json.Obj("type" -> Json.Str("string")),
          "content" -> Json.Obj("type" -> Json.Str("string")),
          "append"  -> Json.Obj("type" -> Json.Str("boolean")),
        ),
        "required"   -> Json.Arr(Chunk(Json.Str("path"), Json.Str("content"))),
      ),
      tags = Set("file", "write", "workspace"),
      sandbox = ToolSandbox.WorkspaceReadWrite,
      execute = args =>
        for
          path     <- requireString(args, "path")
          content  <- requireString(args, "content")
          append    = requireBooleanOrDefault(args, "append", default = false)
          bytes     = content.getBytes(StandardCharsets.UTF_8)
          _        <- ZIO.fail(ToolExecutionError.InvalidParameters(s"Content too large: ${bytes.length} bytes"))
                        .when(bytes.length > config.maxWriteBytes)
          resolved <- resolveInWorkspace(config, path)
          _        <- ensureParentDirectory(resolved)
          _        <- writeBytes(resolved, bytes, append)
        yield Json.Obj(
          "path"          -> Json.Str(config.root.relativize(resolved).toString),
          "bytes_written" -> Json.Num(BigDecimal(bytes.length)),
          "appended"      -> Json.Bool(append),
        ),
    )

  def discoverFiles(config: WorkspaceConfig): Tool =
    Tool(
      name = "discover_files",
      description = "Discover files in a workspace subtree",
      parameters = Json.Obj(
        "type"       -> Json.Str("object"),
        "properties" -> Json.Obj(
          "path"        -> Json.Obj("type" -> Json.Str("string")),
          "glob"        -> Json.Obj("type" -> Json.Str("string")),
          "max_results" -> Json.Obj("type" -> Json.Str("integer")),
        ),
      ),
      tags = Set("search", "files", "discovery"),
      sandbox = ToolSandbox.WorkspaceReadOnly,
      execute = args =>
        for
          relativePath <- optionalString(args, "path").map(_.getOrElse("."))
          glob         <- optionalString(args, "glob").map(_.getOrElse("**/*"))
          maxResults   <- optionalInt(args, "max_results").map(_.getOrElse(100))
          root         <- resolveInWorkspace(config, relativePath)
          matcher       = FileSystems.getDefault.getPathMatcher(s"glob:$glob")
          paths        <- ZIO
                            .attemptBlocking {
                              val stream = Files.walk(root)
                              try
                                stream
                                  .iterator()
                                  .asScala
                                  .filter(Files.isRegularFile(_))
                                  .filter(path => matcher.matches(root.relativize(path)))
                                  .take(maxResults)
                                  .toList
                              finally stream.close()
                            }
                            .mapError(err =>
                              ToolExecutionError.ExecutionFailed(s"Failed to discover files: ${err.getMessage}")
                            )
        yield Json.Obj(
          "count" -> Json.Num(BigDecimal(paths.length)),
          "files" -> Json.Arr(Chunk.fromIterable(paths.map(path => Json.Str(config.root.relativize(path).toString)))),
        ),
    )

  def searchInFiles(config: WorkspaceConfig): Tool =
    Tool(
      name = "search_in_files",
      description = "Search text in workspace files",
      parameters = Json.Obj(
        "type"       -> Json.Str("object"),
        "properties" -> Json.Obj(
          "query"       -> Json.Obj("type" -> Json.Str("string")),
          "path"        -> Json.Obj("type" -> Json.Str("string")),
          "max_results" -> Json.Obj("type" -> Json.Str("integer")),
        ),
        "required"   -> Json.Arr(Chunk(Json.Str("query"))),
      ),
      tags = Set("search", "grep", "analysis"),
      sandbox = ToolSandbox.WorkspaceReadOnly,
      execute = args =>
        for
          query        <- requireString(args, "query")
          relativePath <- optionalString(args, "path").map(_.getOrElse("."))
          maxResults   <- optionalInt(args, "max_results").map(_.getOrElse(50))
          root         <- resolveInWorkspace(config, relativePath)
          results      <- ZIO
                            .attemptBlocking(searchOccurrences(config, root, query, maxResults))
                            .mapError(err => ToolExecutionError.ExecutionFailed(s"Search failed: ${err.getMessage}"))
        yield Json.Obj(
          "query"   -> Json.Str(query),
          "count"   -> Json.Num(BigDecimal(results.length)),
          "matches" -> Json.Arr(Chunk.fromIterable(results)),
        ),
    )

  def validateCobolSyntax(config: WorkspaceConfig): Tool =
    Tool(
      name = "validate_cobol_syntax",
      description = "Run lightweight COBOL syntax validation",
      parameters = Json.Obj(
        "type"       -> Json.Str("object"),
        "properties" -> Json.Obj(
          "path" -> Json.Obj("type" -> Json.Str("string"))
        ),
        "required"   -> Json.Arr(Chunk(Json.Str("path"))),
      ),
      tags = Set("validation", "cobol"),
      sandbox = ToolSandbox.WorkspaceReadOnly,
      execute = args =>
        for
          path     <- requireString(args, "path")
          resolved <- resolveInWorkspace(config, path)
          _        <- ensureRegularFile(resolved)
          content  <-
            ZIO
              .attemptBlocking(Files.readString(resolved, StandardCharsets.UTF_8))
              .mapError(err => ToolExecutionError.ExecutionFailed(s"Failed to read COBOL file: ${err.getMessage}"))
          missing   = List(
                        "IDENTIFICATION DIVISION" -> content.toUpperCase.contains("IDENTIFICATION DIVISION"),
                        "PROGRAM-ID"              -> content.toUpperCase.contains("PROGRAM-ID"),
                      ).collect { case (token, false) => token }
        yield Json.Obj(
          "path"   -> Json.Str(config.root.relativize(resolved).toString),
          "valid"  -> Json.Bool(missing.isEmpty),
          "errors" -> Json.Arr(Chunk.fromIterable(missing.map(token => Json.Str(s"Missing required section: $token")))),
        ),
    )

  def validateJavaCompilation(config: WorkspaceConfig): Tool =
    Tool(
      name = "validate_java_compilation",
      description = "Compile Java sources with javac and report diagnostics",
      parameters = Json.Obj(
        "type"       -> Json.Str("object"),
        "properties" -> Json.Obj(
          "path" -> Json.Obj("type" -> Json.Str("string"))
        ),
        "required"   -> Json.Arr(Chunk(Json.Str("path"))),
      ),
      tags = Set("validation", "java", "compile"),
      sandbox = ToolSandbox.WorkspaceReadOnly,
      execute = args =>
        for
          path     <- requireString(args, "path")
          resolved <- resolveInWorkspace(config, path)
          sources  <- collectJavaSources(resolved)
          _        <- ZIO
                        .fail(ToolExecutionError.InvalidParameters("No .java files found for validation"))
                        .when(sources.isEmpty)
          output   <- runJavac(config.root, sources)
        yield output,
    )

  private def collectJavaSources(path: Path): IO[ToolExecutionError, List[Path]] =
    ZIO
      .attemptBlocking {
        if Files.isRegularFile(path) then
          if path.toString.endsWith(".java") then List(path) else List.empty
        else
          val stream = Files.walk(path)
          try
            stream
              .iterator()
              .asScala
              .filter(Files.isRegularFile(_))
              .filter(_.toString.endsWith(".java"))
              .toList
          finally stream.close()
      }
      .mapError(err => ToolExecutionError.ExecutionFailed(s"Failed to discover Java sources: ${err.getMessage}"))

  private def runJavac(root: Path, sources: List[Path]): IO[ToolExecutionError, Json] =
    for
      tempDir       <- ZIO
                         .attemptBlocking(Files.createTempDirectory("llm4zio-javac-"))
                         .mapError(err =>
                           ToolExecutionError.ExecutionFailed(s"Failed to create temporary directory: ${err.getMessage}")
                         )
      compileResult <- ZIO
                         .attemptBlocking {
                           val command  = List("javac", "-d", tempDir.toString) ++ sources.map(_.toString)
                           val process  =
                             new ProcessBuilder(command*).directory(root.toFile).redirectErrorStream(true).start()
                           val output   = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
                           val exitCode = process.waitFor()
                           (exitCode, output)
                         }
                         .mapError(err =>
                           ToolExecutionError.ExecutionFailed(s"javac execution failed: ${err.getMessage}")
                         )
      _             <- deleteDirectory(tempDir)
    yield Json.Obj(
      "valid"         -> Json.Bool(compileResult._1 == 0),
      "exit_code"     -> Json.Num(BigDecimal(compileResult._1)),
      "output"        -> Json.Str(compileResult._2),
      "files_checked" -> Json.Num(BigDecimal(sources.length)),
    )

  private def searchOccurrences(
    config: WorkspaceConfig,
    root: Path,
    query: String,
    maxResults: Int,
  ): List[Json] =
    val normalizedQuery = query.toLowerCase
    val stream          = Files.walk(root)
    try
      stream
        .iterator()
        .asScala
        .filter(Files.isRegularFile(_))
        .flatMap { path =>
          val size = Files.size(path)
          if size > config.maxReadBytes then
            List.empty
          else
            val lines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toList
            lines.zipWithIndex.collect {
              case (line, idx) if line.toLowerCase.contains(normalizedQuery) =>
                Json.Obj(
                  "path"    -> Json.Str(config.root.relativize(path).toString),
                  "line"    -> Json.Num(BigDecimal(idx + 1)),
                  "content" -> Json.Str(line.trim),
                )
            }
        }
        .take(maxResults)
        .toList
    finally stream.close()

  private def writeBytes(path: Path, bytes: Array[Byte], append: Boolean): IO[ToolExecutionError, Unit] =
    val options =
      if append then Array(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      else Array(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

    ZIO
      .attemptBlocking(Files.write(path, bytes, options*))
      .unit
      .mapError(err => ToolExecutionError.ExecutionFailed(s"Failed to write file: ${err.getMessage}"))

  private def ensureParentDirectory(path: Path): IO[ToolExecutionError, Unit] =
    Option(path.getParent) match
      case None         => ZIO.unit
      case Some(parent) =>
        ZIO
          .attemptBlocking(Files.createDirectories(parent))
          .mapError(err =>
            ToolExecutionError.ExecutionFailed(s"Failed to create parent directories: ${err.getMessage}")
          )
          .unit

  private def deleteDirectory(path: Path): UIO[Unit] =
    ZIO
      .attemptBlocking {
        val stream = Files.walk(path)
        try
          stream.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
        finally stream.close()
      }
      .ignore

  private def requireString(args: Json, field: String): IO[ToolExecutionError, String] =
    args match
      case Json.Obj(fields) =>
        fields.toMap.get(field) match
          case Some(Json.Str(value)) => ZIO.succeed(value)
          case _                     => ZIO.fail(ToolExecutionError.InvalidParameters(s"Missing or invalid string field: $field"))
      case _                => ZIO.fail(ToolExecutionError.InvalidParameters("Arguments must be an object"))

  private def optionalString(args: Json, field: String): IO[ToolExecutionError, Option[String]] =
    args match
      case Json.Obj(fields) =>
        fields.toMap.get(field) match
          case Some(Json.Str(value)) => ZIO.succeed(Some(value))
          case Some(_)               => ZIO.fail(ToolExecutionError.InvalidParameters(s"Invalid string field: $field"))
          case None                  => ZIO.succeed(None)
      case _                => ZIO.fail(ToolExecutionError.InvalidParameters("Arguments must be an object"))

  private def optionalInt(args: Json, field: String): IO[ToolExecutionError, Option[Int]] =
    args match
      case Json.Obj(fields) =>
        fields.toMap.get(field) match
          case Some(Json.Num(value)) if value.stripTrailingZeros.scale() <= 0 =>
            ZIO
              .attempt(value.intValueExact())
              .map(valueAsInt => Some(valueAsInt))
              .mapError(_ => ToolExecutionError.InvalidParameters(s"Field out of range for Int: $field"))
          case Some(Json.Num(_))                                              =>
            ZIO.fail(ToolExecutionError.InvalidParameters(s"Invalid integer field: $field"))
          case Some(_)                                                        => ZIO.fail(ToolExecutionError.InvalidParameters(s"Invalid integer field: $field"))
          case None                                                           => ZIO.succeed(None)
      case _                => ZIO.fail(ToolExecutionError.InvalidParameters("Arguments must be an object"))

  private def requireBooleanOrDefault(args: Json, field: String, default: Boolean): Boolean =
    args match
      case Json.Obj(fields) =>
        fields.toMap.get(field) match
          case Some(Json.Bool(value)) => value
          case _                      => default
      case _                => default

  private def resolveInWorkspace(config: WorkspaceConfig, rawPath: String): IO[ToolExecutionError, Path] =
    val normalizedRoot = config.root.toAbsolutePath.normalize()

    for
      resolved <- ZIO
                    .attempt(normalizedRoot.resolve(rawPath).normalize())
                    .mapError(err =>
                      ToolExecutionError.InvalidParameters(s"Invalid path '$rawPath': ${err.getMessage}")
                    )
      _        <- ZIO
                    .fail(ToolExecutionError.SandboxViolation(s"Path escapes workspace root: $rawPath"))
                    .when(!resolved.startsWith(normalizedRoot))
    yield resolved

  private def ensureRegularFile(path: Path): IO[ToolExecutionError, Unit] =
    for
      exists        <- ZIO
                         .attemptBlocking(Files.exists(path))
                         .mapError(err =>
                           ToolExecutionError.ExecutionFailed(s"Failed to check file existence: ${err.getMessage}")
                         )
      _             <- ZIO
                         .fail(ToolExecutionError.InvalidParameters(s"File not found: $path"))
                         .when(!exists)
      isRegularFile <- ZIO
                         .attemptBlocking(Files.isRegularFile(path))
                         .mapError(err =>
                           ToolExecutionError.ExecutionFailed(s"Failed to check file type: ${err.getMessage}")
                         )
      _             <- ZIO
                         .fail(ToolExecutionError.InvalidParameters(s"Not a regular file: $path"))
                         .when(!isRegularFile)
    yield ()
