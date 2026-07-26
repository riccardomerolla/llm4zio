package llm4zio.flow

import llm4zio.flow.Equiv.{ Mismatch, Observation, Tier }

/** The outcome of replaying one vector: the vector and whatever mismatches the diff engine found. */
final case class VectorVerdict(vector: EquivVector, mismatches: List[Mismatch]):
  def passed: Boolean = mismatches.isEmpty

/** Renders the equivalence report — the auditor-facing artifact. It speaks per spec rule (the traceability units), not
  * per vector, keeps the generated and captured tiers in separate columns (never summed), and lists unexercised rules
  * loudly: a rule nobody probed is not a proven rule.
  */
object EquivReport:

  def render(verdicts: List[VectorVerdict], allRules: List[String]): String =
    val rows    = allRules.map(row(verdicts, _))
    val failing = verdicts.filterNot(_.passed)
    val failSec =
      if failing.isEmpty then Nil
      else
        "" :: "## Failing vectors" :: failing.flatMap { v =>
          "" :: s"### ${v.vector.program} ${v.vector.id} (${tierName(v.vector.tier)})" :: v.mismatches.map(describe)
        }
    val lines   =
      List(
        "# Equivalence report",
        "",
        "| Rule | Generated | Captured | Status |",
        "| ---- | --------- | -------- | ------ |",
      ) ++ rows ++ failSec ++ List(
        "",
        "Generated vectors prove spec conformance; captured vectors prove behavioural equivalence.",
        "The two tiers are reported separately and never summed.",
      )
    lines.mkString("", "\n", "\n")

  private def row(verdicts: List[VectorVerdict], rule: String): String =
    val exercising = verdicts.filter(_.vector.rules.contains(rule))
    val status     =
      if exercising.isEmpty then "UNEXERCISED"
      else if exercising.exists(!_.passed) then "FAILING"
      else if exercising.exists(_.vector.tier == Tier.Captured) then "proven (captured)"
      else "proven (generated)"
    s"| $rule | ${cell(exercising, Tier.Generated)} | ${cell(exercising, Tier.Captured)} | $status |"

  private def cell(exercising: List[VectorVerdict], tier: Tier): String =
    val ofTier = exercising.filter(_.vector.tier == tier)
    if ofTier.isEmpty then "—" else s"${ofTier.count(_.passed)}/${ofTier.size}"

  private def tierName(tier: Tier): String = tier match
    case Tier.Generated => "generated"
    case Tier.Captured  => "captured"

  private def describe(m: Mismatch): String = m match
    case Mismatch.FieldDiff(at, field, expected, actual) => s"- $at: `$field` expected $expected, actual $actual"
    case Mismatch.Missing(expected)                      => s"- missing: ${observation(expected)}"
    case Mismatch.Unexpected(actual)                     => s"- unexpected: ${observation(actual)}"

  private def observation(o: Observation): String = o match
    case Observation.Record(kind, fields)            =>
      s"record $kind ${fields.toList.sorted.map((k, v) => s"$k=$v").mkString(", ")}"
    case Observation.DbMutation(table, op, key, set) =>
      val addr   = key.toList.sorted.map((k, v) => s"$k=$v").mkString(",")
      val values = set.toList.sorted.map((k, v) => s"$k=$v").mkString(", ")
      s"db $table $op $addr → $values"
