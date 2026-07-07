package llm4zio.javaapi

import java.util.Optional

import llm4zio.flow.IssueRef

/** Parsers for the reference strings a Java flow takes as input. Returns `java.util.Optional` rather than Scala
  * `Option` so Java code branches with `isPresent`/`get`.
  */
object Refs:
  /** Parse an `owner/repo#number` issue reference, empty if malformed. */
  def issue(raw: String): Optional[IssueRef] =
    IssueRef.parse(raw).fold(Optional.empty[IssueRef])(Optional.of)
