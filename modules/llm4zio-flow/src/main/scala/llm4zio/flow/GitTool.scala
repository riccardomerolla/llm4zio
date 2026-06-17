package llm4zio.flow

import java.nio.file.Path

import zio.*

/** Thin git wrapper over zio-process, bound to a working directory.
  *
  * Recoverable outcomes (branch already exists, nothing to commit) are returned in the value channel as typed results;
  * genuinely unexpected failures (git missing, a corrupt repo) fail the effect with [[FlowError.Process]].
  */
final class GitTool(workDir: Path):
  import GitTool.*

  // Every git invocation carries GitTool.nonInteractiveEnv so neither git nor any ssh it spawns can block a
  // TTY-less flow on a credential or passphrase prompt — they fail fast instead.
  private def exec(args: String*): IO[FlowError, Proc.Result]  =
    Proc.run("git", args, workDir, GitTool.nonInteractiveEnv)
  private def execOrFail(args: String*): IO[FlowError, String] =
    Proc.runOrFail("git", args, workDir, GitTool.nonInteractiveEnv)

  /** Read a single git config value (`git config --get`); `None` when unset. */
  private def configGet(key: String): IO[FlowError, Option[String]] =
    exec("config", "--get", key).map(r => if r.ok then Some(r.stdout.trim).filter(_.nonEmpty) else None)

  /** `git init` with `main` as the default branch. */
  def init: IO[FlowError, Unit] = execOrFail("-c", "init.defaultBranch=main", "init").unit

  /** `git init --bare` — for creating a local remote in tests/tooling. */
  def initBare: IO[FlowError, Unit] = execOrFail("-c", "init.defaultBranch=main", "init", "--bare").unit

  def config(key: String, value: String): IO[FlowError, Unit] = execOrFail("config", key, value).unit

  def addAll: IO[FlowError, Unit] = execOrFail("add", "-A").unit

  /** Name of the currently checked-out branch. */
  def currentBranch: IO[FlowError, String] = execOrFail("rev-parse", "--abbrev-ref", "HEAD")

  /** Working-tree diff (unstaged changes to tracked files). */
  def diff: IO[FlowError, String] = execOrFail("diff")

  /** Working-tree diff that INCLUDES untracked files (via `git add --intent-to-add`), so a review step sees newly
    * created files, not just edits. Leaves intent-to-add entries in the index; a later `commitAll` (`git add -A`)
    * subsumes them. NB: those entries also make a later [[diff]] include the registered files — once a flow calls
    * `diffAll`, prefer it over `diff` for the rest of the run rather than mixing the two.
    */
  def diffAll: IO[FlowError, String] =
    execOrFail("add", "--intent-to-add", "-A") *> execOrFail("diff")

  /** The branch the current work targets: `origin/HEAD` symbolic ref → its branch, else `origin/main`, then
    * `origin/master`, else `"main"`. Used for PR-accurate diffs and branch-level review.
    */
  def defaultBase: IO[FlowError, String] =
    exec("symbolic-ref", "refs/remotes/origin/HEAD").flatMap { r =>
      val ref = r.stdout.trim
      if r.exitCode == 0 && ref.nonEmpty then ZIO.succeed(ref.stripPrefix("refs/remotes/"))
      else
        exec("rev-parse", "--verify", "--quiet", "origin/main").flatMap { m =>
          if m.exitCode == 0 then ZIO.succeed("origin/main")
          else
            exec("rev-parse", "--verify", "--quiet", "origin/master").map { ms =>
              if ms.exitCode == 0 then "origin/master" else "main"
            }
        }
    }

  /** Diff of HEAD vs `base`. 3-dot (default) = merge-base/PR-accurate; 2-dot = direct comparison. */
  def diffVsBase(base: String, threeDot: Boolean = true): IO[FlowError, String] =
    val range = if threeDot then s"$base...HEAD" else s"$base..HEAD"
    execOrFail("diff", range)

  /** Names of files changed vs `base` — for reviewer file-scoping. */
  def changedFilesVsBase(base: String, threeDot: Boolean = true): IO[FlowError, List[String]] =
    val range = if threeDot then s"$base...HEAD" else s"$base..HEAD"
    execOrFail("diff", "--name-only", range)
      .map(_.linesIterator.map(_.trim).filter(_.nonEmpty).toList)

  /** `git ls-remote <remote>` — the refs a remote advertises. */
  def lsRemote(remote: String): IO[FlowError, String] = execOrFail("ls-remote", remote)

  def addRemote(name: String, url: String): IO[FlowError, Unit] = execOrFail("remote", "add", name, url).unit

  /** Check out an existing branch. */
  def checkout(name: String): IO[FlowError, Unit] = execOrFail("checkout", name).unit

  /** Ensure `name` is checked out, creating it if it does not exist. */
  def checkoutOrCreate(name: String): IO[FlowError, Unit] =
    createBranch(name).flatMap {
      case CreateBranch.Created       => ZIO.unit
      case CreateBranch.AlreadyExists => checkout(name)
    }

  /** Create and check out a branch; [[CreateBranch.AlreadyExists]] if it exists. */
  def createBranch(name: String): IO[FlowError, CreateBranch] =
    exec("checkout", "-b", name).flatMap { r =>
      if r.ok then ZIO.succeed(CreateBranch.Created)
      else if r.stderr.contains("already exists") then ZIO.succeed(CreateBranch.AlreadyExists)
      else ZIO.fail(FlowError.Process(s"git checkout -b $name", r.problem))
    }

  /** Stage everything and commit; [[Commit.NothingToCommit]] if the tree is clean. */
  def commitAll(message: String): IO[FlowError, Commit] =
    addAll *> exec("commit", "-m", message).flatMap { r =>
      if r.ok then ZIO.succeed(Commit.Committed)
      else if (r.stdout + r.stderr).contains("nothing to commit") then ZIO.succeed(Commit.NothingToCommit)
      else ZIO.fail(FlowError.Process("git commit", r.problem))
    }

  /** Push `branch` to `remote`, setting upstream. For a github.com remote a last-resort credential helper is appended
    * (after any configured helper, so a working credential setup still wins; inert for SSH and other hosts), so the
    * push authenticates via `GH_TOKEN`/`GITHUB_TOKEN` or the `gh` login the PR flows already require even when git has
    * no helper configured.
    */
  def push(remote: String, branch: String): IO[FlowError, Unit] =
    configGet(s"remote.$remote.url").flatMap { url =>
      val token = sys.env.get("GH_TOKEN").orElse(sys.env.get("GITHUB_TOKEN")).filter(_.nonEmpty)
      execOrFail(GitTool.pushArgs(remote, branch, url, token)*).unit
    }

object GitTool:
  enum CreateBranch:
    case Created, AlreadyExists

  enum Commit:
    case Committed, NothingToCommit

  /** Environment that forces git — and any ssh it spawns — to run non-interactively. A flow subprocess has no usable
    * TTY, so an HTTPS username/password prompt or an SSH key-passphrase prompt would block the flow forever rather than
    * failing. `GIT_TERMINAL_PROMPT=0` disables the former; `-o BatchMode=yes` on the ssh command disables the latter
    * (appended, so a user's custom `GIT_SSH_COMMAND` is preserved). Merged onto the inherited env by [[Proc]].
    */
  private[flow] val nonInteractiveEnv: Map[String, String] =
    val baseSsh = sys.env.getOrElse("GIT_SSH_COMMAND", "ssh")
    Map("GIT_TERMINAL_PROMPT" -> "0", "GIT_SSH_COMMAND" -> s"$baseSsh -o BatchMode=yes")

  /** Host of a git remote URL, for both `scp`-like SSH (`git@host:path`) and URL forms
    * (`scheme://[user@]host[:port]/path`). `None` for local paths or anything without a recognisable host.
    */
  private[flow] def remoteHost(url: String): Option[String] =
    val scpLike = """^[^@/]+@([^:/]+):.*""".r
    val urlLike = """^[a-zA-Z][a-zA-Z0-9+.\-]*://(?:[^@/]+@)?([^:/]+).*""".r
    url.trim match
      case scpLike(host) => Some(host)
      case urlLike(host) => Some(host)
      case _             => None

  private[flow] def isGithubRemote(url: String): Boolean = remoteHost(url).contains("github.com")

  /** The `git push` args (sans the leading `git`). For a github.com remote it prepends a `-c` credential helper scoped
    * to github.com HTTPS — appended after any config-file helper (a working setup still wins) and added only for github
    * remotes (the scoped helper is meaningless elsewhere). With a token in the env it is used directly (see
    * [[githubHelper]]); otherwise the `gh` CLI's own auth resolution is used.
    */
  private[flow] def pushArgs(
    remote: String,
    branch: String,
    remoteUrl: Option[String],
    envToken: Option[String],
  ): List[String] =
    val credential =
      if remoteUrl.exists(isGithubRemote) then
        List("-c", s"credential.https://github.com.helper=${githubHelper(envToken.isDefined)}")
      else Nil
    credential ++ List("push", "-u", remote, branch)

  /** Shell credential helper for github.com. With a token in the env it echoes that token (`x-access-token` is GitHub's
    * conventional username for token auth); the token is read from `$GH_TOKEN`/`$GITHUB_TOKEN` at helper runtime, never
    * interpolated here, so it stays out of argv and logs. With no token it defers to the `gh` CLI.
    */
  private def githubHelper(hasEnvToken: Boolean): String =
    if hasEnvToken then
      "!f() { test \"$1\" = get && " +
        "printf 'username=x-access-token\\npassword=%s\\n' " +
        "\"${GH_TOKEN:-$GITHUB_TOKEN}\"; }; f"
    else "!gh auth git-credential"
