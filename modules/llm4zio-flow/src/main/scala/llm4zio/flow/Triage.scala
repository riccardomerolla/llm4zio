package llm4zio.flow

import zio.json.{ JsonCodec, jsonDiscriminator }

/** A reference to a GitHub issue: `owner/repo#number`. */
final case class IssueRef(owner: String, repo: String, number: Int):
  def shortRef: String = s"$owner/$repo#$number"

object IssueRef:
  private val pattern = """([^/\s]+)/([^/#\s]+)#(\d+)""".r

  def parse(raw: String): Option[IssueRef] =
    raw.trim match
      case pattern(owner, repo, number) => number.toIntOption.map(IssueRef(owner, repo, _))
      case _                            => None

/** The verdict from triaging a bug report. Encoded with a `"kind"` discriminator so a model can produce it as
  * structured output.
  */
@jsonDiscriminator("kind")
enum Triage derives JsonCodec:
  case NotABug(explanation: String)
  case Untestable(summary: String, reproductionSteps: String)
  case Testable(summary: String, branchName: String, failingTestPath: String)
