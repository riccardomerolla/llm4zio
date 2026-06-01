package llm4zio.flow

import zio.json.JsonCodec

/** Severity of a [[ReviewIssue]]. */
enum Severity derives JsonCodec:
  case Critical, Warning, Info

/** A single finding from a review. */
final case class ReviewIssue(
  severity: Severity,
  title: String,
  description: String = "",
  file: Option[String] = None,
  line: Option[Int] = None,
  suggestion: Option[String] = None,
  confidence: Double = 1.0,
) derives JsonCodec

/** The outcome of one review pass. */
final case class ReviewResult(issues: List[ReviewIssue], summary: String = "") derives JsonCodec:
  /** No issues outstanding. */
  def isClean: Boolean = issues.isEmpty
