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

  private def exec(args: String*): IO[FlowError, Proc.Result]  = Proc.run("git", args, workDir)
  private def execOrFail(args: String*): IO[FlowError, String] = Proc.runOrFail("git", args, workDir)

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

  /** Push `branch` to `remote`, setting upstream. */
  def push(remote: String, branch: String): IO[FlowError, Unit] =
    execOrFail("push", "-u", remote, branch).unit

object GitTool:
  enum CreateBranch:
    case Created, AlreadyExists

  enum Commit:
    case Committed, NothingToCommit
