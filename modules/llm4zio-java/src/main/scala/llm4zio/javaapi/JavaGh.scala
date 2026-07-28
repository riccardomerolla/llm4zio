package llm4zio.javaapi

import zio.Runtime

import llm4zio.flow.{ BuildOutcome, GhTool, Issue, IssueRef, PullRequest }

/** GitHub side effects via the `gh` CLI, Java-shaped. `Issue`/`PullRequest` are case classes (Java accessors:
  * `issue.title()`, `pr.url()`); a CI wait returns a [[BuildResult]] Java compares/switches on. Failures throw
  * [[Llm4zioException]].
  */
final class JavaGh private[javaapi] (runtime: Runtime[Any], gh: GhTool):

  // The Java facade is full-grant in v4.2 (runtime default Grants.all through the Bridge); a Grants builder is the
  // v4.3 fast-follow. The mint below is the facade's entry-point grant, greppable by design.
  private[javaapi] given llm4zio.flow.Caps.All = llm4zio.flow.Caps.grantAll

  /** Read an issue by `owner/repo#number` reference. */
  def readIssue(ref: IssueRef): Issue = Bridge.runSync(runtime, gh.readIssue(ref))

  /** Post a comment on the issue. */
  def writeIssueComment(ref: IssueRef, body: String): Unit =
    Bridge.runSync(runtime, gh.writeIssueComment(ref, body))

  /** Open a PR for the current branch (reuses an existing open PR if there is one). */
  def createPr(title: String, body: String): PullRequest =
    Bridge.runSync(runtime, gh.createPr(title, body))

  /** Open a PR against an explicit base branch. */
  def createPr(title: String, body: String, base: String): PullRequest =
    Bridge.runSync(runtime, gh.createPr(title, body, base = Option(base)))

  /** Post a comment on a PR. */
  def writePrComment(pr: PullRequest, body: String): Unit =
    Bridge.runSync(runtime, gh.writePrComment(pr, body))

  /** Update a PR's title and body. */
  def updatePr(pr: PullRequest, title: String, body: String): Unit =
    Bridge.runSync(runtime, gh.updatePr(pr, title, body))

  /** Poll the PR's CI until it concludes or `timeoutSeconds` elapses. Returns the [[BuildResult]]. */
  def waitForBuild(pr: PullRequest, timeoutSeconds: Long): BuildResult =
    Bridge.runSync(runtime, gh.waitForBuild(pr, java.time.Duration.ofSeconds(timeoutSeconds))) match
      case BuildOutcome.Success  => BuildResult.Success
      case BuildOutcome.Failure  => BuildResult.Failure
      case BuildOutcome.Pending  => BuildResult.Pending
      case BuildOutcome.TimedOut => BuildResult.TimedOut
