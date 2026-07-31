package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, StandardOpenOption }

import zio.{ IO, ZIO }

import llm4zio.eval.Dimension

/** How to enumerate one kind of coverage unit in a legacy source tree: `files` scopes which paths to scan (regex over
  * the relative path) and `unit` extracts unit names (first capture group, or the whole match) line by line.
  */
final case class CoverageRule(name: String, files: String, unit: String)

/** A modernization pack: the per-source-kind knowledge a flow is parameterized by, loaded from a plain directory
  * (`pack.md` manifest) so teams author and version packs as data, not code.
  */
final case class Pack(
  name: String,
  source: String,
  scaffold: Option[String],
  sources: Option[String],
  // The spec-worthy subset of `sources` (relative-path regex): the files that each get their own behavioural spec
  // in a per-unit extraction (programs and jobs, not copybooks/descriptors). Falls back to `sources` when unset.
  programs: Option[String],
  // Regex template locating a program's TARGET implementation files (relative paths), `<NAME>` substituted with the
  // program name. The seam that makes per-program judging possible. Defaults to a case-insensitive name match.
  programFiles: Option[String],
  specsDir: String,
  featuresDir: String,
  gates: Map[String, List[String]],
  // The replay command (whitespace-tokenized): reads one vector JSON on stdin, emits normalized observations JSON
  // on stdout — how the verify flow drives the target implementation without llm4zio knowing the transport.
  replay: Option[List[String]],
  // How the verify flow compares observations (`## Equivalence` section; defaults to ordered, nothing ignored).
  equivalence: ComparisonPolicy,
  judgeDimensions: List[Dimension],
  coverage: List[CoverageRule],
  // Dependency-edge regexes (`## Survey: <name>` sections) the survey flow scans the estate with:
  // each match in a file becomes an edge from that file's unit to the captured name.
  survey: List[CoverageRule],
  prompts: Map[String, String],
  lenses: List[Reviewer],
  lessons: Option[String],
  dir: Path,
):

  /** The command behind a named gate (whitespace-tokenized, ready for `Reviewers.lintCommand`). */
  def gate(name: String): Option[List[String]] = gates.get(name)

  /** The prompt template `prompts/<name>.md`, if the pack ships one. */
  def prompt(name: String): Option[String] = prompts.get(name)

  /** The relative-path regex for `program`'s implementation files: the pack's `programFiles:` template with `<NAME>`
    * substituted, or a case-insensitive "path contains the program name" fallback.
    */
  def filesFor(program: String): String =
    programFiles.fold(s".*(?i)${java.util.regex.Pattern.quote(program)}.*")(_.replace("<NAME>", program))

object Pack:
  private val HeaderPrefix  = "# Pack: "
  private val ListItem      = """- ([^:]+): (.+)""".r
  private val DimensionItem = """- ([^(]+)\(0\.\.(\d+)\): (.+)""".r

  /** Load the pack rooted at `dir` (must contain a `pack.md` manifest; prompts, lessons, and reviewer lenses are
    * optional sidecar files).
    */
  def load(dir: Path): IO[FlowError, Pack] =
    for
      manifest <- ZIO
                    .attemptBlocking(Files.readString(dir.resolve("pack.md")))
                    .mapError(e =>
                      FlowError.Persistence(s"failed to read pack manifest at ${dir.resolve("pack.md")}", Some(e))
                    )
      prompts  <- markdownFiles(dir.resolve("prompts"))
      lenses   <- markdownFiles(dir.resolve("reviewers"))
      lessons  <- readIfPresent(dir.resolve("lessons.md"))
      pack     <- ZIO.fromEither(parse(manifest, dir)).mapError(FlowError.PlanParse(_))
    yield pack.copy(
      prompts = prompts,
      lenses = lenses.toList.sortBy(_._1).map(Reviewer.parse.tupled),
      lessons = lessons.map(_.strip).filter(_.nonEmpty),
    )

  /** Append a distilled lesson to the pack's `lessons.md` (created on first use). Lessons are how a review flow feeds
    * what it learned back into future runs: loaders surface them via [[Pack.lessons]] for brief injection.
    */
  def appendLesson(dir: Path, lesson: String): IO[FlowError, Unit] =
    ZIO
      .attemptBlocking {
        val path  = dir.resolve("lessons.md")
        val entry = s"- ${lesson.strip}\n"
        Files.write(
          path,
          entry.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND,
        )
        ()
      }
      .mapError(e => FlowError.Persistence(s"failed to append lesson to ${dir.resolve("lessons.md")}", Some(e)))

  /** Slug → trimmed content for every `*.md` directly under `dir` (empty when the directory is absent). */
  private def markdownFiles(dir: Path): IO[FlowError, Map[String, String]] =
    ZIO
      .attemptBlocking {
        if !Files.isDirectory(dir) then Map.empty
        else
          val stream = Files.list(dir)
          try
            stream
              .filter(p => p.getFileName.toString.endsWith(".md"))
              .toArray
              .toList
              .collect { case p: Path => p.getFileName.toString.stripSuffix(".md") -> Files.readString(p).strip }
              .toMap
          finally stream.close()
      }
      .mapError(e => FlowError.Persistence(s"failed to read pack files under $dir", Some(e)))

  private def readIfPresent(path: Path): IO[FlowError, Option[String]] =
    ZIO
      .attemptBlocking(Option.when(Files.exists(path))(Files.readString(path)))
      .mapError(e => FlowError.Persistence(s"failed to read $path", Some(e)))

  private def parse(markdown: String, dir: Path): Either[String, Pack] =
    val chunks = markdown.strip.split("(?m)^(?=## )").toList.map(_.strip).filter(_.nonEmpty)
    chunks match
      case head :: sections if head.startsWith(HeaderPrefix) =>
        val headLines = head.linesIterator.toList
        val fields    = keyValues(headLines.tail)
        fields.get("source") match
          case Some(source) =>
            Right(
              Pack(
                name = headLines.head.drop(HeaderPrefix.length).trim,
                source = source,
                scaffold = fields.get("scaffold"),
                sources = fields.get("sources"),
                programs = fields.get("programs"),
                programFiles = fields.get("programFiles"),
                specsDir = fields.getOrElse("specs-dir", "docs/specs"),
                featuresDir = fields.getOrElse("features-dir", "features"),
                gates = section(sections, "Gates").map(namedListItems).getOrElse(Map.empty).map { (k, v) =>
                  k -> v.split("\\s+").toList
                },
                replay = fields.get("replay").map(_.split("\\s+").toList),
                equivalence = section(sections, "Equivalence").fold(ComparisonPolicy.default)(equivalencePolicy),
                judgeDimensions = section(sections, "Judge").fold(List.empty[Dimension])(dimensions),
                coverage = coverageRules(sections),
                survey = prefixedRules(sections, "## Survey: "),
                prompts = Map.empty,
                lenses = Nil,
                lessons = None,
                dir = dir,
              )
            )
          case None         => Left("pack manifest is missing a 'source:' field")
      case _                                                 =>
        Left(s"expected a '$HeaderPrefix<name>' header")

  /** The body of the `## <title>` section, if present. */
  private def section(sections: List[String], title: String): Option[String] =
    sections.collectFirst {
      case s if s.linesIterator.nextOption().exists(_.trim == s"## $title") =>
        s.linesIterator.toList.tail.mkString("\n").strip
    }

  /** `- name: value` list items of a section body, in order. */
  private def namedListItems(body: String): Map[String, String] =
    body.linesIterator.collect { case ListItem(name, value) => name.trim -> value.trim }.toMap

  /** `- ordering: …` / `- ignore: a, b` list items of the Equivalence section. */
  private def equivalencePolicy(body: String): ComparisonPolicy =
    val fields   = namedListItems(body)
    val ordering = fields.get("ordering").map(_.toLowerCase) match
      case Some("unordered") => Equiv.Ordering.Unordered
      case Some("per-key")   => Equiv.Ordering.PerKey
      case _                 => Equiv.Ordering.Ordered
    val ignore   = fields.get("ignore").fold(Set.empty[String])(_.split(",").map(_.trim).filter(_.nonEmpty).toSet)
    ComparisonPolicy(ordering, ignore)

  /** `- name (0..max): rubric` list items of the Judge section, in order. */
  private def dimensions(body: String): List[Dimension] =
    body.linesIterator.collect {
      case DimensionItem(name, max, rubric) =>
        Dimension(name.trim, rubric.trim, max.toInt)
    }.toList

  /** Every `## Coverage: <name>` section, each carrying `files:` and `unit:` regexes. */
  private def coverageRules(sections: List[String]): List[CoverageRule] =
    prefixedRules(sections, "## Coverage: ")

  /** Every `## <prefix><name>` section carrying `files:` and `unit:` regexes — the shape Coverage and Survey share. */
  private def prefixedRules(sections: List[String], prefix: String): List[CoverageRule] =
    sections.flatMap { s =>
      val lines = s.linesIterator.toList
      lines.headOption.map(_.trim).filter(_.startsWith(prefix)).flatMap { header =>
        val fields = keyValues(lines.tail)
        for
          files <- fields.get("files")
          unit  <- fields.get("unit")
        yield CoverageRule(header.drop(prefix.length).trim, files, unit)
      }
    }

  private def keyValues(lines: List[String]): Map[String, String] =
    lines.collect {
      case l if l.contains(":") && !l.trim.startsWith("#") && !l.trim.startsWith("-") =>
        val (k, v) = l.span(_ != ':')
        k.trim -> v.drop(1).trim
    }.toMap
