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

  /** Open a pull request, or return the existing open PR for the current branch if one is already there. Find-or-create
    * makes the step idempotent, so a re-run (e.g. auto-resume) past PR creation does not fail with "a pull request
    * already exists".
    */
  def createPr(
    title: String,
    body: String,
    base: Option[String] = None,
    draft: Boolean = false,
  ): IO[FlowError, PullRequest] =
    findOpenPr.flatMap {
      case Some(existing) =>
        ZIO.logInfo(s"reusing existing PR ${existing.shortRef}").as(existing)
      case None           =>
        Proc.runOrFail("gh", GhTool.prCreateArgs(title, body, base, draft), workDir).flatMap { out =>
          ZIO
            .fromOption(out.linesIterator.flatMap(PullRequest.fromUrl).nextOption())
            .orElseFail(FlowError.Process("gh pr create", s"could not parse a PR URL from: $out"))
        }
    }

  /** The open PR for the current branch, if any (`gh pr view` exits non-zero when there is none). */
  private def findOpenPr: IO[FlowError, Option[PullRequest]] =
    Proc.run("gh", GhTool.prViewArgs, workDir).map { r =>
      if r.ok then r.stdout.linesIterator.flatMap(PullRequest.fromUrl).nextOption() else None
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

  /** Update a PR's title + body via a REST PATCH (`gh api`), which — unlike `gh pr edit` — works on repos with Projects
    * (classic) sunset. See [[GhTool.prPatchArgs]].
    */
  def updatePr(pr: PullRequest, title: String, body: String): IO[FlowError, Unit] =
    Proc.runOrFail("gh", GhTool.prPatchArgs(pr, title, body), workDir).unit

  /** Read the PR's check rollup via `gh pr view --json statusCheckRollup`. A `--json` read makes a transient gh/GitHub
    * blip a non-zero exit (retried with backoff, like [[readIssue]]) instead of conflating it with a red build the way
    * `gh pr checks` exit codes do.
    */
  def prChecks(pr: PullRequest): IO[FlowError, BuildOutcome] =
    Proc
      .runOrFail("gh", GhTool.prChecksArgs(pr), workDir)
      .flatMap(json =>
        ZIO.fromEither(GhTool.outcomeFromChecksJson(json)).mapError(e => FlowError.Process("gh pr view", e))
      )
      .retry(GhTool.transientRead)

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

  /** `gh` argv to read the current branch's PR URL (empty output / non-zero exit when there is none). Pure. */
  val prViewArgs: List[String] = List("pr", "view", "--json", "url", "--jq", ".url")

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

  /** A REST PATCH on the pull endpoint rather than `gh pr edit`: the latter runs a GraphQL metadata query selecting
    * `projectCards`, which fails on repos where GitHub has sunset Projects (classic), aborting the edit. `gh api` PATCH
    * touches no projects.
    */
  def prPatchArgs(pr: PullRequest, title: String, body: String): List[String] =
    List(
      "api",
      "--method",
      "PATCH",
      s"repos/${pr.owner}/${pr.repo}/pulls/${pr.number}",
      "-f",
      s"title=$title",
      "-f",
      s"body=$body",
    )

  def prChecksArgs(pr: PullRequest): List[String] =
    List("pr", "view", pr.number.toString, "--repo", s"${pr.owner}/${pr.repo}", "--json", "statusCheckRollup")

  // A rollup entry is either a CheckRun (status/conclusion) or a StatusContext (state); Option fields absorb
  // whichever half is absent.
  private case class GhCheck(
    status: Option[String] = None,
    conclusion: Option[String] = None,
    state: Option[String] = None,
  ) derives JsonCodec
  private case class GhChecksJson(statusCheckRollup: List[GhCheck] = Nil) derives JsonCodec

  private val failingConclusions = Set("FAILURE", "TIMED_OUT", "CANCELLED", "ACTION_REQUIRED", "STARTUP_FAILURE")
  private val pendingStates      = Set("PENDING", "EXPECTED")
  private val failingStates      = Set("FAILURE", "ERROR")

  /** Derive the build outcome from a `statusCheckRollup` payload: any still-running check ⇒ Pending (wait for the full
    * picture before declaring red), else any failing conclusion/state ⇒ Failure, else Success. An empty rollup (no
    * checks configured) is Success — there is nothing to wait on.
    */
  def outcomeFromChecksJson(json: String): Either[String, BuildOutcome] =
    json.fromJson[GhChecksJson].map { parsed =>
      val checks  = parsed.statusCheckRollup
      val pending = checks.exists(c =>
        c.status.exists(s => !s.equalsIgnoreCase("COMPLETED")) ||
        c.state.exists(s => pendingStates.contains(s.toUpperCase(java.util.Locale.US)))
      )
      val failed  = checks.exists(c =>
        c.conclusion.exists(x => failingConclusions.contains(x.toUpperCase(java.util.Locale.US))) ||
        c.state.exists(x => failingStates.contains(x.toUpperCase(java.util.Locale.US)))
      )
      if pending then BuildOutcome.Pending
      else if failed then BuildOutcome.Failure
      else BuildOutcome.Success
    }

  private case class GhAuthor(login: String) derives JsonCodec
  private case class GhIssueJson(title: String, body: String, author: GhAuthor) derives JsonCodec

  /** Decode the JSON `gh issue view --json title,body,author` prints. */
  def parseIssue(json: String): Either[String, Issue] =
    json.fromJson[GhIssueJson].map(j => Issue(j.title, j.body, j.author.login))
