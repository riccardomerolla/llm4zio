package llm4zio.flow

import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import zio.{ IO, ZIO }

/** One reusable modernization pattern: a legacy idiom, its target translation, and the known traps — knowledge as data,
  * like packs and lessons, but universal and curated rather than estate-local and run-accumulated. `matches` is a regex
  * over legacy source: extraction tags each program's spec with the ids of the cards that hit, and implementation
  * injects only the tagged cards into the coder's brief — deterministic selection, bounded context.
  */
final case class PatternCard(id: String, matches: String, body: String)

object Patterns:

  /** All `*.md` cards under `dir`, sorted by id (the filename stem). An absent directory is an empty set — patterns are
    * optional everywhere.
    */
  def load(dir: Path): IO[FlowError, List[PatternCard]] =
    ZIO
      .attemptBlocking {
        if !Files.isDirectory(dir) then Nil
        else
          val stream = Files.list(dir)
          val files  =
            try stream.iterator().asScala.filter(_.getFileName.toString.endsWith(".md")).toList
            finally stream.close()
          files
            .map(f => parse(f.getFileName.toString.stripSuffix(".md"), Files.readString(f)))
            .sortBy(_.id)
      }
      .mapError(e => FlowError.Persistence(s"failed to read pattern cards under $dir", Some(e)))

  /** The cards whose `match` regex hits anywhere in `source` — how extraction decides which patterns a program
    * embodies.
    */
  def matching(source: String, cards: List[PatternCard]): List[PatternCard] =
    cards.filter(c => c.matches.nonEmpty && c.matches.r.findFirstIn(source).isDefined)

  /** Every `PAT-…` id cited in a spec text, in order of first appearance — how implementation finds the cards its
    * current task's specs were tagged with.
    */
  def tagged(spec: String): List[String] =
    """PAT-[A-Za-z0-9-]+""".r.findAllIn(spec).toList.distinct

  private def parse(id: String, text: String): PatternCard =
    val stripped = text.strip
    if stripped.startsWith("---") then
      stripped.drop(3).split("(?m)^---\\s*$", 2) match
        case Array(front, body) =>
          val fields = front.linesIterator.collect {
            case l if l.contains(":") =>
              val (k, v) = l.span(_ != ':')
              k.trim -> v.drop(1).trim
          }.toMap
          PatternCard(id, fields.getOrElse("match", ""), body.strip)
        case _                  => PatternCard(id, "", stripped)
    else PatternCard(id, "", stripped)
