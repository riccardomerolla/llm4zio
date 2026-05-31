package issues.entity

/** Well-known issue tag strings shared across domains.
  *
  * These are real domain values (not arbitrary labels), so they live next to
  * the issue model rather than inside the daemon that first applied them.
  */
object IssueTags:

  /** Pat (triage PM) parks a Backlog issue with this tag when it cannot make
    * a routing decision and opens a supervisor decision instead. Triage skips
    * any issue carrying this tag, so resolving the matching decision must
    * also clear the tag to re-enable triage on the next tick.
    */
  val TriageAwaitingSupervisor: String = "triage:awaiting-supervisor"
