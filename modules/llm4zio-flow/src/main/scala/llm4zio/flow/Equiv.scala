package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.json.*
import zio.json.ast.Json
import zio.{ IO, ZIO }

/** One equivalence test vector: the inputs fed to a program and the observations its execution is expected to produce.
  * `inputs` is deliberately opaque JSON — its shape is a contract between whatever generated the vector and the pack's
  * replay command; only `observations` are compared. `rules` names the spec rules (traceability units) the vector
  * exercises, so the equivalence report can speak per rule, not per vector.
  */
final case class EquivVector(
  schema: Int,
  program: String,
  id: String,
  tier: Equiv.Tier,
  rules: List[String],
  inputs: Json,
  observations: List[Equiv.Observation],
) derives JsonCodec

/** The equivalence-proof vocabulary: vector tiers, the normalized observation model, and (in later tasks) the diff
  * engine that compares expected observations against a replay's actual ones.
  */
object Equiv:

  /** How much a passing vector proves: `Generated` vectors (from the spec pack) prove spec conformance; `Captured`
    * vectors (recorded legacy runs) prove behavioural equivalence. Reports keep the tiers separate, never summed.
    */
  enum Tier:
    case Generated, Captured

  object Tier:
    given JsonCodec[Tier] = JsonCodec.string.transformOrFail(
      {
        case "generated" => Right(Generated)
        case "captured"  => Right(Captured)
        case other       => Left(s"unknown tier: '$other' (expected 'generated' or 'captured')")
      },
      {
        case Generated => "generated"
        case Captured  => "captured"
      },
    )

  /** A normalized, transport-free observation of program behaviour — the unit the diff engine compares. */
  @jsonDiscriminator("type")
  enum Observation:
    /** An emitted record: an output-file row, a report line, a reject record — `kind` names the channel. */
    @jsonHint("record") case Record(kind: String, fields: Map[String, String])

    /** A database mutation: `op` (insert/update/delete) on `table`, addressed by `key`, writing `set`. */
    @jsonHint("db") case DbMutation(table: String, op: String, key: Map[String, String], set: Map[String, String])

  object Observation:
    given JsonCodec[Observation] = DeriveJsonCodec.gen[Observation]

  /** Whether observation order is part of the behaviour being proven (batch report lines) or not (DB rows). */
  enum Ordering:
    case Ordered, Unordered

  /** One way a replay's actual observations diverged from a vector's expected ones. */
  enum Mismatch:
    /** Expected but never observed. */
    case Missing(expected: Observation)

    /** Observed but never expected. */
    case Unexpected(actual: Observation)

    /** The same observation (by identity: record kind, or table+op+key) with a differing field value. */
    case FieldDiff(at: String, field: String, expected: String, actual: String)

  /** Compare a vector's expected observations against a replay's actual ones. Deterministic and pure — no LLM is
    * involved in deciding equivalence; the LLM only triages the mismatches this returns. Exact matches cancel first;
    * under [[Ordering.Unordered]] leftovers still pair by identity so a wrong amount surfaces as a
    * [[Mismatch.FieldDiff]], not an opaque Missing/Unexpected pair.
    */
  def diff(expected: List[Observation], actual: List[Observation], policy: ComparisonPolicy): List[Mismatch] =
    val exp = expected.map(scrub(_, policy.ignore))
    val act = actual.map(scrub(_, policy.ignore))
    policy.ordering match
      case Ordering.Ordered =>
        val positional = exp.zip(act).flatMap { (e, a) =>
          if e == a then Nil
          else if identityOf(e) == identityOf(a) then fieldDiffs(e, a)
          else List(Mismatch.Missing(e), Mismatch.Unexpected(a))
        }
        positional ++ exp.drop(act.size).map(Mismatch.Missing(_)) ++ act.drop(exp.size).map(Mismatch.Unexpected(_))

      case Ordering.Unordered =>
        val (unmatched, leftover)   = exp.foldLeft((List.empty[Observation], act)) {
          case ((misses, remaining), e) =>
            remaining.indexOf(e) match
              case -1 => (misses :+ e, remaining)
              case i  => (misses, remaining.patch(i, Nil, 1))
        }
        val (diffs, missing, extra) =
          unmatched.foldLeft((List.empty[Mismatch], List.empty[Observation], leftover)) {
            case ((diffs, missing, acts), e) =>
              acts.indexWhere(a => identityOf(a) == identityOf(e)) match
                case -1 => (diffs, missing :+ e, acts)
                case i  => (diffs ++ fieldDiffs(e, acts(i)), missing, acts.patch(i, Nil, 1))
          }
        diffs ++ missing.map(Mismatch.Missing(_)) ++ extra.map(Mismatch.Unexpected(_))

  /** What replaying one vector produced: observations to diff, or a crash (a recoverable, typed value — one bad vector
    * must not kill an estate-sized run; the flow records it and moves on).
    */
  enum Replayed:
    case Observed(actual: List[Observation])
    case Crashed(exitCode: Int, problem: String)

  /** Feed `vector` (as JSON) to the pack's replay command on stdin and parse the observation array it prints — the
    * transport-blind boundary between the verify flow and the target implementation. A non-zero exit is a
    * [[Replayed.Crashed]] value; stdout that is not an observation array fails typed (a broken replay contract).
    */
  def replayVector(command: List[String], root: Path, vector: EquivVector): IO[FlowError, Replayed] =
    command match
      case Nil          => ZIO.fail(FlowError.Process("replay", "empty replay command"))
      case head :: tail =>
        Proc.runWithInput(head, tail, root, vector.toJson).flatMap { r =>
          if !r.ok then ZIO.succeed(Replayed.Crashed(r.exitCode, r.problem))
          else
            ZIO
              .fromEither(r.stdout.fromJson[List[Observation]])
              .mapBoth(
                e =>
                  FlowError.PlanParse(
                    s"replay output for ${vector.program}/${vector.id} is not an observation array: $e"
                  ),
                Replayed.Observed(_),
              )
        }

  private def scrub(o: Observation, ignore: Set[String]): Observation = o match
    case Observation.Record(kind, fields)            => Observation.Record(kind, fields -- ignore)
    case Observation.DbMutation(table, op, key, set) => Observation.DbMutation(table, op, key -- ignore, set -- ignore)

  /** The stable identity mismatched observations pair by — field values excluded, keys included. */
  private def identityOf(o: Observation): String = o match
    case Observation.Record(kind, _)               => s"record $kind"
    case Observation.DbMutation(table, op, key, _) =>
      val addr = key.toList.sorted.map((k, v) => s"$k=$v").mkString(",")
      s"db $table $op $addr"

  private def fieldDiffs(e: Observation, a: Observation): List[Mismatch] =
    val (expected, actual) = (e, a) match
      case (Observation.Record(_, ef), Observation.Record(_, af))                     => (ef, af)
      case (Observation.DbMutation(_, _, _, es), Observation.DbMutation(_, _, _, as)) => (es, as)
      case _                                                                          => (Map.empty[String, String], Map.empty[String, String])
    (expected.keySet ++ actual.keySet).toList.sorted.flatMap { field =>
      val (ev, av) = (expected.get(field), actual.get(field))
      Option.when(ev != av)(
        Mismatch.FieldDiff(identityOf(e), field, ev.getOrElse("(absent)"), av.getOrElse("(absent)"))
      )
    }

/** How observations are compared: whether sequence order matters, and which field names (timestamps, generated ids) are
  * exempt from comparison. Parsed from the pack's `## Equivalence` section.
  */
final case class ComparisonPolicy(ordering: Equiv.Ordering, ignore: Set[String])

object ComparisonPolicy:
  val default: ComparisonPolicy = ComparisonPolicy(Equiv.Ordering.Ordered, Set.empty)

/** Plain-file persistence for equivalence vectors: one JSON object per line (JSONL) — the capture interchange format a
  * bank's own tooling writes captured-tier vectors into.
  */
object VectorStore:

  def write(path: Path, vectors: List[EquivVector]): IO[FlowError, Unit] =
    ZIO
      .attemptBlocking {
        Option(path.getParent).foreach(Files.createDirectories(_))
        val lines = vectors.map(_.toJson).mkString("", "\n", "\n")
        Files.write(path, lines.getBytes(StandardCharsets.UTF_8))
        ()
      }
      .mapError(e => FlowError.Persistence(s"failed to write vectors at $path", Some(e)))

  def read(path: Path): IO[FlowError, List[EquivVector]] =
    ZIO
      .attemptBlocking(Files.readString(path))
      .mapError(e => FlowError.Persistence(s"failed to read vectors at $path", Some(e)))
      .flatMap { text =>
        ZIO.foreach(text.linesIterator.map(_.strip).filter(_.nonEmpty).toList.zipWithIndex) { (line, i) =>
          ZIO
            .fromEither(line.fromJson[EquivVector])
            .mapError(e => FlowError.PlanParse(s"malformed vector at $path:${i + 1}: $e"))
        }
      }
