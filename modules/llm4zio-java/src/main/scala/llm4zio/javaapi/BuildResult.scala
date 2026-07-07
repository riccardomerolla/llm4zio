package llm4zio.javaapi

/** The outcome of waiting on a PR's CI, mirroring `llm4zio.flow.BuildOutcome`. Extends `java.lang.Enum`, so it compiles
  * to a real Java enum: Java branches with `status == BuildResult.Success` or an exhaustive `switch`. The `is*`
  * predicates are kept for source compatibility.
  */
enum BuildResult extends java.lang.Enum[BuildResult]:
  case Success, Failure, Pending, TimedOut

  def isSuccess: Boolean  = this == BuildResult.Success
  def isFailure: Boolean  = this == BuildResult.Failure
  def isPending: Boolean  = this == BuildResult.Pending
  def isTimedOut: Boolean = this == BuildResult.TimedOut
