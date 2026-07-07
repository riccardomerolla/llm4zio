package llm4zio.javaapi

/** The outcome of waiting on a PR's CI, mirroring `llm4zio.flow.BuildOutcome`. Scala 3 parameterless enum cases aren't
  * reachable as `BuildResult.Success` from Java, so a Java flow branches via the `is*` predicate methods (`if
  * (status.isSuccess()) …`) rather than `==` on a case.
  */
enum BuildResult:
  case Success, Failure, Pending, TimedOut

  def isSuccess: Boolean  = this == BuildResult.Success
  def isFailure: Boolean  = this == BuildResult.Failure
  def isPending: Boolean  = this == BuildResult.Pending
  def isTimedOut: Boolean = this == BuildResult.TimedOut
