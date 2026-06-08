package llm4zio.flow

import java.nio.file.Path

import zio.*
import zio.json.{ DecoderOps, JsonCodec }

/** A GitHub issue, as read via `gh`. */
final case class Issue(title: String, body: String, author: String)

/** A pull request opened/managed via `gh`. */
final case class PullRequest(owner: String, repo: String, number: Int, url: String):
  def shortRef: String = s"$owner/$repo#$number"

object PullRequest:
  private val urlPattern = """https?://[^/]+/([^/]+)/([^/]+)/pull/(\d+).*""".r

  /** Parse the PR URL that `gh pr create` prints. */
  def fromUrl(url: String): Option[PullRequest] =
    url.trim match
      case urlPattern(owner, repo, number) => number.toIntOption.map(PullRequest(owner, repo, _, url.trim))
      case _                               => None

/** Terminal/active state of a PR's checks. */
enum BuildOutcome:
  case Success, Failure, Pending, TimedOut

/** Thin GitHub CLI (`gh`) wrapper over zio-process, bound to a working directory. */
final class GhTool(workDir: Path):

  /** Open a pull request via `gh pr create`; parses the printed URL. */
  def createPr(
    title: String,
    body: String,
    base: Option[String] = None,
    draft: Boolean = false,
  ): IO[FlowError, PullRequest] =
    Proc.runOrFail("gh", GhTool.prCreateArgs(title, body, base, draft), workDir).flatMap { out =>
      ZIO
        .fromOption(out.linesIterator.flatMap(PullRequest.fromUrl).nextOption())
        .orElseFail(FlowError.Process("gh pr create", s"could not parse a PR URL from: $out"))
    }

  /** Read an issue via `gh issue view --json`. Idempotent, so a transient gh/GitHub blip (e.g. a dropped GraphQL
    * connection) is retried a few times with backoff rather than aborting the flow.
    */
  def readIssue(ref: IssueRef): IO[FlowError, Issue] =
    Proc
      .runOrFail("gh", GhTool.issueViewArgs(ref), workDir)
      .flatMap(json => ZIO.fromEither(GhTool.parseIssue(json)).mapError(e => FlowError.Process("gh issue view", e)))
      .retry(GhTool.transientRead)

  /** Comment on an issue. */
  def writeIssueComment(ref: IssueRef, body: String): IO[FlowError, Unit] =
    Proc.runOrFail("gh", GhTool.issueCommentArgs(ref, body), workDir).unit

  /** Comment on a pull request. */
  def writePrComment(pr: PullRequest, body: String): IO[FlowError, Unit] =
    Proc.runOrFail("gh", GhTool.prCommentArgs(pr, body), workDir).unit

  /** Update a PR's title + body via `gh pr edit`. */
  def updatePr(pr: PullRequest, title: String, body: String): IO[FlowError, Unit] =
    Proc.runOrFail("gh", GhTool.prEditArgs(pr, title, body), workDir).unit

  /** One-shot check of a PR's CI state (maps `gh pr checks` exit code). */
  def prChecks(pr: PullRequest): IO[FlowError, BuildOutcome] =
    Proc.run("gh", GhTool.prChecksArgs(pr), workDir).map(r => GhTool.outcomeFromExit(r.exitCode))

  /** Poll the PR's checks until they reach a terminal state or `timeout`. */
  def waitForBuild(pr: PullRequest, timeout: Duration): IO[FlowError, BuildOutcome] =
    def loop: IO[FlowError, BuildOutcome] =
      prChecks(pr).flatMap {
        case BuildOutcome.Pending => ZIO.sleep(15.seconds) *> loop
        case terminal             => ZIO.succeed(terminal)
      }
    loop.timeoutTo(BuildOutcome.TimedOut)(identity)(timeout)

object GhTool:
  /** Bounded backoff for idempotent `gh` reads — absorbs a transient GitHub/network blip without aborting the flow. */
  private[flow] val transientRead: Schedule[Any, FlowError, Any] =
    Schedule.recurs(3) && Schedule.exponential(1.second)

  /** The `gh` argv for opening a PR — pure, so it can be unit-tested. */
  def prCreateArgs(title: String, body: String, base: Option[String], draft: Boolean): List[String] =
    List("pr", "create", "--title", title, "--body", body) ++
      base.toList.flatMap(b => List("--base", b)) ++
      (if draft then List("--draft") else Nil)

  def issueViewArgs(ref: IssueRef): List[String] =
    List("issue", "view", ref.number.toString, "--repo", s"${ref.owner}/${ref.repo}", "--json", "title,body,author")

  def issueCommentArgs(ref: IssueRef, body: String): List[String] =
    List("issue", "comment", ref.number.toString, "--repo", s"${ref.owner}/${ref.repo}", "--body", body)

  def prCommentArgs(pr: PullRequest, body: String): List[String] =
    List("pr", "comment", pr.number.toString, "--repo", s"${pr.owner}/${pr.repo}", "--body", body)

  def prEditArgs(pr: PullRequest, title: String, body: String): List[String] =
    List("pr", "edit", pr.number.toString, "--repo", s"${pr.owner}/${pr.repo}", "--title", title, "--body", body)

  def prChecksArgs(pr: PullRequest): List[String] =
    List("pr", "checks", pr.number.toString, "--repo", s"${pr.owner}/${pr.repo}")

  /** `gh pr checks` exit codes: 0 = all passed, 8 = still pending, else failing. */
  def outcomeFromExit(exitCode: Int): BuildOutcome =
    exitCode match
      case 0 => BuildOutcome.Success
      case 8 => BuildOutcome.Pending
      case _ => BuildOutcome.Failure

  private case class GhAuthor(login: String) derives JsonCodec
  private case class GhIssueJson(title: String, body: String, author: GhAuthor) derives JsonCodec

  /** Decode the JSON `gh issue view --json title,body,author` prints. */
  def parseIssue(json: String): Either[String, Issue] =
    json.fromJson[GhIssueJson].map(j => Issue(j.title, j.body, j.author.login))
