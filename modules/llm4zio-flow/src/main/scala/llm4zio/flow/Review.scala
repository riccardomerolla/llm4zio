package llm4zio.flow

/** Severity of a [[ReviewIssue]]. */
enum Severity:
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
)

/** The outcome of one review pass. */
final case class ReviewResult(issues: List[ReviewIssue], summary: String = ""):
  /** No issues outstanding. */
  def isClean: Boolean = issues.isEmpty
