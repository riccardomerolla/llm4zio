package llm4zio.javaapi

/** The recoverable outcome of a commit, mirroring `llm4zio.flow.GitTool.Commit`. Extends `java.lang.Enum`, so it
  * compiles to a real Java enum: Java branches with `result == CommitResult.Committed` or a `switch`. The `is*`
  * predicates are kept for source compatibility.
  */
enum CommitResult extends java.lang.Enum[CommitResult]:
  case Committed, NothingToCommit

  def isCommitted: Boolean       = this == CommitResult.Committed
  def isNothingToCommit: Boolean = this == CommitResult.NothingToCommit
