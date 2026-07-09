package llm4zio.flow

import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import zio.{ IO, ZIO }

/** Deterministic layer-1 checks for extracted spec packs — the "how do you KNOW it's complete" gates that run before
  * any LLM judge: coverage units enumerated straight from the legacy source must all appear in the traceability matrix.
  * Results come back as [[ReviewResult]]s so they compose with `fixLoop` like any other review.
  */
object SpecChecks:

  /** Enumerate coverage units under `root`, per rule: every file whose relative path matches `rule.files` is scanned
    * line by line with `rule.unit` (first capture group, or the whole match). Units are distinct BY NAME across the
    * rule's files, in file order — a name shared by two programs counts once, so this check is a floor ("the matrix
    * mentions every unit name"), not a per-program audit; the judge owns semantic depth.
    */
  def coverageUnits(root: Path, rules: List[CoverageRule]): IO[FlowError, Map[String, List[String]]] =
    ZIO
      .attemptBlocking {
        val stream = Files.walk(root)
        val files  =
          try stream.iterator().asScala.filter(Files.isRegularFile(_)).toList.sortBy(_.toString)
          finally stream.close()
        rules.map { rule =>
          val fileRegex = rule.files.r
          val unitRegex = rule.unit.r
          val units     =
            for
              file <- files if fileRegex.matches(relative(root, file))
              line <- Files.readAllLines(file).asScala
              m    <- unitRegex.findFirstMatchIn(line)
            yield if m.groupCount >= 1 then m.group(1) else m.matched
          rule.name -> units.distinct
        }.toMap
      }
      .mapError(e => FlowError.Persistence(s"failed to scan coverage units under $root", Some(e)))

  /** Check every coverage unit found under `root` appears verbatim in `traceability`; each absent unit is a Critical
    * issue.
    */
  def coverage(root: Path, rules: List[CoverageRule], traceability: String): IO[FlowError, ReviewResult] =
    coverageUnits(root, rules).map { unitsByRule =>
      val issues =
        for
          rule <- rules
          unit <- unitsByRule.getOrElse(rule.name, Nil) if !traceability.contains(unit)
        yield ReviewIssue(
          Severity.Critical,
          s"uncovered ${rule.name}: $unit",
          s"'$unit' exists in the legacy source but does not appear in the traceability matrix.",
        )
      ReviewResult(issues, if issues.isEmpty then "coverage complete" else s"${issues.size} unit(s) uncovered")
    }

  /** Sanity-check the Gherkin under `dir`: every `.feature` file needs a `Feature:` header and at least one scenario,
    * and every scenario at least one Given/When/Then step. No feature files at all fails the gate — an extraction that
    * produced no scenarios has nothing to hand the implementation flow.
    */
  def features(dir: Path): IO[FlowError, ReviewResult] =
    ZIO
      .attemptBlocking {
        val files =
          if !Files.isDirectory(dir) then Nil
          else
            val stream = Files.walk(dir)
            try stream.iterator().asScala.filter(_.getFileName.toString.endsWith(".feature")).toList
            finally stream.close()
        if files.isEmpty then
          ReviewResult(
            List(ReviewIssue(Severity.Critical, "no feature files", s"no .feature files found under $dir")),
            "no features",
          )
        else
          val issues = files.flatMap(f => featureIssues(relative(dir, f), Files.readString(f)))
          ReviewResult(issues, if issues.isEmpty then "features well-formed" else s"${issues.size} malformed")
      }
      .mapError(e => FlowError.Persistence(s"failed to check features under $dir", Some(e)))

  private def featureIssues(name: String, text: String): List[ReviewIssue] =
    val lines     = text.linesIterator.map(_.trim).toList
    val scenarios = lines.count(l => l.startsWith("Scenario:") || l.startsWith("Scenario Outline:"))
    val steps     = lines.count(l => List("Given ", "When ", "Then ", "And ", "But ").exists(l.startsWith))
    val problem   =
      if !lines.exists(_.startsWith("Feature:")) then Some("missing a 'Feature:' header")
      else if scenarios == 0 then Some("contains no scenarios")
      else if steps == 0 then Some("has scenarios but no Given/When/Then steps")
      else None
    problem.map(p => ReviewIssue(Severity.Critical, s"malformed feature: $name", p, file = Some(name))).toList

  private def relative(root: Path, file: Path): String =
    root.relativize(file).toString.replace('\\', '/')
