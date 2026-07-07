package llm4zio.javaapi

/** The recoverable outcome of a commit, mirroring `llm4zio.flow.GitTool.Commit`. Scala 3 parameterless enum cases
  * aren't reachable as `Commit.Committed` from Java, so a Java flow branches via the `is*` predicates (`if
  * (result.isCommitted()) …`).
  */
enum CommitResult:
  case Committed, NothingToCommit

  def isCommitted: Boolean       = this == CommitResult.Committed
  def isNothingToCommit: Boolean = this == CommitResult.NothingToCommit
