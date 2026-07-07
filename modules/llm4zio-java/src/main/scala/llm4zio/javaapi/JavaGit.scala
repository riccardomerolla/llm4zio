package llm4zio.javaapi

import zio.Runtime

import llm4zio.flow.GitTool

/** Version-control side effects, Java-shaped. Recoverable outcomes come back as values with `is*` predicates
  * ([[CommitResult]]); genuine failures throw [[Llm4zioException]].
  */
final class JavaGit private[javaapi] (runtime: Runtime[Any], git: GitTool):

  /** Check out `name`, creating it from HEAD if it doesn't exist. */
  def checkoutOrCreate(name: String): Unit = Bridge.runSync(runtime, git.checkoutOrCreate(name))

  /** Check out an existing branch. */
  def checkout(name: String): Unit = Bridge.runSync(runtime, git.checkout(name))

  /** Stage everything and commit. Returns whether a commit was made or there was nothing to commit — branch with
    * `result.isCommitted()`.
    */
  def commitAll(message: String): CommitResult =
    Bridge.runSync(runtime, git.commitAll(message)) match
      case GitTool.Commit.Committed       => CommitResult.Committed
      case GitTool.Commit.NothingToCommit => CommitResult.NothingToCommit

  /** Push `branch` to `remote`. */
  def push(remote: String, branch: String): Unit = Bridge.runSync(runtime, git.push(remote, branch))

  /** The unstaged working-tree diff. */
  def diff(): String = Bridge.runSync(runtime, git.diff)

  /** The diff of staged + unstaged changes. */
  def diffAll(): String = Bridge.runSync(runtime, git.diffAll)

  /** The current branch name. */
  def currentBranch(): String = Bridge.runSync(runtime, git.currentBranch)

  /** The base branch a PR should target (the remote's default branch, else a sensible local default). */
  def defaultBase(): String = Bridge.runSync(runtime, git.defaultBase)

  /** The diff of the current branch against `base`. */
  def diffVsBase(base: String): String = Bridge.runSync(runtime, git.diffVsBase(base))
