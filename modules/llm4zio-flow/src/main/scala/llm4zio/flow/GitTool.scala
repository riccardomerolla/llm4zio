package llm4zio.flow

import zio.*
import zio.process.Command

import java.nio.file.Path

/** Thin git wrapper over zio-process, bound to a working directory.
  *
  * Recoverable outcomes (branch already exists, nothing to commit) are returned
  * in the value channel as typed results; genuinely unexpected failures (git
  * missing, a corrupt repo) fail the effect with [[FlowError.Process]].
  */
final class GitTool(workDir: Path):
  import GitTool.*

  private def exec(args: String*): IO[FlowError, ProcResult] =
    (for
      process <- Command("git", args*).workingDirectory(workDir.toFile).run
      outF    <- process.stdout.string.fork
      errF    <- process.stderr.string.fork
      exit    <- process.exitCode
      out     <- outF.join
      err     <- errF.join
    yield ProcResult(exit.code, out.trim, err.trim))
      .mapError(t => FlowError.Process(s"git ${args.mkString(" ")}", Option(t.getMessage).getOrElse(t.toString)))

  private def execOrFail(args: String*): IO[FlowError, String] =
    exec(args*).flatMap { r =>
      if r.ok then ZIO.succeed(r.stdout)
      else ZIO.fail(FlowError.Process(s"git ${args.mkString(" ")} (exit ${r.exitCode})", r.problem))
    }

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

  /** `git ls-remote <remote>` — the refs a remote advertises. */
  def lsRemote(remote: String): IO[FlowError, String] = execOrFail("ls-remote", remote)

  def addRemote(name: String, url: String): IO[FlowError, Unit] = execOrFail("remote", "add", name, url).unit

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

  private final case class ProcResult(exitCode: Int, stdout: String, stderr: String):
    def ok: Boolean      = exitCode == 0
    def problem: String  = if stderr.nonEmpty then stderr else stdout
