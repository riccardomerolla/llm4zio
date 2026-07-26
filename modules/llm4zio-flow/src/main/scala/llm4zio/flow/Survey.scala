package llm4zio.flow

import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import zio.json.JsonCodec
import zio.{ IO, ZIO }

/** One unit in the estate inventory: a program, job, copybook, or descriptor — whatever the pack's `sources:` regex
  * enumerates. `units` counts the pack's coverage units inside it (paragraphs, steps) as a size signal.
  */
final case class SurveyNode(path: String, name: String, lines: Int, units: Int) derives JsonCodec

/** One dependency edge, named after the survey rule that found it (`calls`, `copies`, `exec-pgm`, …). */
final case class SurveyEdge(from: String, to: String, kind: String) derives JsonCodec

final case class SurveyGraph(nodes: List[SurveyNode], edges: List[SurveyEdge]) derives JsonCodec:
  def incoming(name: String): List[SurveyEdge] = edges.filter(_.to == name)
  def outgoing(name: String): List[SurveyEdge] = edges.filter(_.from == name)

/** The deterministic front bookend: inventory + dependency graph built from regexes alone — `SpecChecks` style, no LLM.
  * Banks stall before extraction because nobody can say what the estate contains and what depends on what; this answers
  * both, cheaply and auditable, at any estate size. The LLM enters only afterwards, for the judgments the graph cannot
  * make (dynamic CALL targets, rewrite/retire/wrap triage, wave slicing).
  */
object Survey:

  /** Build the estate graph: one node per file matching `sources` (unit counts via `units` rules), one edge per
    * survey-rule match — from the containing file's unit name to the captured target name.
    */
  def graph(root: Path, sources: String, units: List[CoverageRule], edges: List[CoverageRule])
    : IO[FlowError, SurveyGraph] =
    ZIO
      .attemptBlocking {
        val stream = Files.walk(root)
        val files  =
          try stream.iterator().asScala.filter(Files.isRegularFile(_)).toList.sortBy(_.toString)
          finally stream.close()
        val scoped = files
          .map(f => root.relativize(f).toString.replace('\\', '/') -> f)
          .filter((rel, _) => rel.matches(sources))

        val nodes = scoped.map { (rel, file) =>
          val text      = Files.readString(file)
          val lineCount = text.linesIterator.size
          val unitCount = units
            .filter(rule => rel.matches(rule.files))
            .map(rule => text.linesIterator.flatMap(rule.unit.r.findFirstMatchIn(_)).size)
            .sum
          SurveyNode(rel, unitName(rel), lineCount, unitCount)
        }

        val found = edges.flatMap { rule =>
          scoped
            .filter((rel, _) => rel.matches(rule.files))
            .flatMap { (rel, file) =>
              val text  = Files.readString(file)
              val regex = rule.unit.r
              regex
                .findAllMatchIn(text)
                .map(m => if m.groupCount >= 1 then m.group(1) else m.matched)
                .toList
                .distinct
                .map(target => SurveyEdge(unitName(rel), target, rule.name))
            }
        }
        SurveyGraph(nodes, found.distinct)
      }
      .mapError(e => FlowError.Persistence(s"failed to survey $root", Some(e)))

  /** The inventory as Markdown: one row per node with size and degree signals; nodes nothing references and that
    * reference nothing are flagged `unreferenced` — the deterministic retire-candidate floor (entry points like jobs
    * have outgoing edges, so they never trip it).
    */
  def renderInventory(graph: SurveyGraph): String =
    val rows = graph.nodes.sortBy(_.name).map { n =>
      val in    = graph.incoming(n.name).size
      val out   = graph.outgoing(n.name).size
      val flags = if in == 0 && out == 0 then "unreferenced — retire candidate?" else ""
      s"| ${n.name} | ${n.path} | ${n.lines} | ${n.units} | $in | $out | $flags |"
    }
    (List(
      "# Estate inventory",
      "",
      "| Unit | Path | Lines | Units | In | Out | Flags |",
      "| ---- | ---- | ----- | ----- | -- | --- | ----- |",
    ) ++ rows ++ List(
      "",
      s"${graph.nodes.size} unit(s), ${graph.edges.size} edge(s). Edges and counts are regex-derived",
      "(`## Survey:` / `## Coverage:` rules in the pack) — deterministic, auditable, re-runnable.",
    )).mkString("", "\n", "\n")

  /** `cobol/ACCTXFR.cbl` → `ACCTXFR`. */
  private def unitName(rel: String): String =
    val base = rel.split('/').last
    base.take(base.lastIndexOf('.') match
      case -1 => base.length
      case i  => i)
