package bankmod.graph.model

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*
import io.github.iltotore.iron.constraint.string.*

/** Iron-based refinement type definitions for the bankmod graph model.
  *
  * Each type is a type alias for `BaseType :| ConstraintType`. Smart constructors are provided via companion objects to
  * allow creation from unvalidated inputs and return `Either[String, T]`.
  */
object Refinements:

  // ── String refinements ────────────────────────────────────────────────────

  /** Semantic version string, e.g. "1.2.3" or "2.0.0-alpha.1". */
  type SemVerR = String :| SemanticVersion

  object SemVer:
    def from(s: String): Either[String, SemVerR] = s.refineEither[SemanticVersion]

  // ── Numeric refinements ───────────────────────────────────────────────────

  /** Latency in milliseconds. Must be in [0, 3_600_000] (0 ms to 1 hour). */
  type LatencyMsR = Int :| Interval.Closed[0, 3600000]

  object LatencyMs:
    def from(n: Int): Either[String, LatencyMsR] = n.refineEither[Interval.Closed[0, 3600000]]

  /** A percentage value in [0, 100]. */
  type PercentageR = Int :| Interval.Closed[0, 100]

  object Percentage:
    def from(n: Int): Either[String, PercentageR] = n.refineEither[Interval.Closed[0, 100]]

  /** Number of retries, bounded in [0, 10]. */
  type BoundedRetriesR = Int :| Interval.Closed[0, 10]

  object BoundedRetries:
    def from(n: Int): Either[String, BoundedRetriesR] = n.refineEither[Interval.Closed[0, 10]]

  // ── URL-like string ───────────────────────────────────────────────────────

  /** URL-like string. Validated against the Iron ValidURL pattern. */
  type UrlLikeR = String :| ValidURL

  object UrlLike:
    def from(s: String): Either[String, UrlLikeR] = s.refineEither[ValidURL]
