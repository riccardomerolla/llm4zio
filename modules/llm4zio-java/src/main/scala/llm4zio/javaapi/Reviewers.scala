package llm4zio.javaapi

import scala.jdk.CollectionConverters.{ ListHasAsScala, SeqHasAsJava }

import llm4zio.flow.Reviewer

/** Java-facing reviewer rosters, mirroring `llm4zio.flow.Reviewers`. Returned as `java.util.List` so a Java flow passes
  * them straight to [[JavaFlow.reviewAndFixLoop]].
  */
object Reviewers:
  /** A lean roster: the few highest-signal reviewers. */
  def minimal(): java.util.List[Reviewer] = llm4zio.flow.Reviewers.minimal.asJava

  /** Every built-in reviewer. */
  def all(): java.util.List[Reviewer] = llm4zio.flow.Reviewers.all.asJava

  /** The TDD-discipline reviewer on its own. */
  def tddDiscipline(): Reviewer = llm4zio.flow.Reviewers.tddDiscipline

  /** A roster extended with extra reviewers — the Java counterpart of `Reviewers.minimal :+ extra` (the lists returned
    * here are Scala-backed and not mutable from Java).
    */
  @annotation.varargs
  def plus(roster: java.util.List[Reviewer], extra: Reviewer*): java.util.List[Reviewer] =
    (roster.asScala.toList ++ extra).asJava
