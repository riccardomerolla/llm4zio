package bankmod.graph.validate

import bankmod.graph.model.{ EdgeRef, Graph, Protocol, ServiceId }
import bankmod.graph.validate.InvariantError.*

/** One pure function per invariant type.
  *
  * All functions are referentially transparent and return a (possibly empty) `List[InvariantError]`. Callers accumulate
  * results across checks; no short-circuiting occurs.
  */
object Checks:

  // ── Internal helpers ────────────────────────────────────────────────────────

  /** Build an [[EdgeRef]] from an originating [[ServiceId]] and an outbound edge. */
  private def toRef(from: ServiceId, e: bankmod.graph.model.Edge): EdgeRef =
    EdgeRef(from = from, to = e.toService, port = e.toPort)

  /** True when the edge's protocol is asynchronous (event-based); such edges are exempt from tier checks. */
  private def isAsync(protocol: bankmod.graph.model.Protocol): Boolean =
    protocol match
      case _: Protocol.Event => true
      case _                 => false

  // ── Cycle detection (DFS with path tracking) ────────────────────────────────

  /** Detect all directed cycles in `g.services`.
    *
    * Uses a functional DFS with explicit state. For each unvisited node the algorithm walks outbound edges, tracking
    * the current recursion path. When a neighbour that is already on the current path is encountered a
    * [[CycleDetected]] is emitted with the path from the cycle entry point back to itself (inclusive).
    *
    * Missing targets (orphan edges) are silently skipped here — [[structural]] handles them.
    *
    * Assumption: `noWhileLoops` scalafix rule is active; recursion is used throughout.
    */
  def detectCycles(g: Graph): List[CycleDetected] =
    // DFS state: visited = fully explored; onStack = on current recursion path
    def dfsFrom(
      node: ServiceId,
      path: List[ServiceId],
      onStack: Set[ServiceId],
      visited: Set[ServiceId],
      acc: List[CycleDetected],
    ): (Set[ServiceId], List[CycleDetected]) =
      if visited.contains(node) then (visited, acc)
      else
        val newPath    = path :+ node
        val newOnStack = onStack + node
        val neighbours = g.services.get(node).fold(List.empty)(_.outbound.toList.map(_.toService))

        val (finalVisited, foundCycles) = neighbours.foldLeft((visited, acc)) {
          case ((vis, cycles), neighbour) =>
            if newOnStack.contains(neighbour) then
              // Cycle detected: slice path from cycle entry point, append the repeated node
              val cycleStart = newPath.indexOf(neighbour)
              val cyclePath  = newPath.drop(cycleStart) :+ neighbour
              (vis, cycles :+ CycleDetected(cyclePath))
            else if vis.contains(neighbour) then
              // Already fully explored, no cycle through this branch
              (vis, cycles)
            else
              // Recurse only into neighbours that exist in the graph
              if g.services.contains(neighbour) then
                dfsFrom(neighbour, newPath, newOnStack, vis, cycles)
              else (vis, cycles)
        }
        (finalVisited + node, foundCycles)

    val allIds      = g.services.keys.toList
    val (_, cycles) = allIds.foldLeft((Set.empty[ServiceId], List.empty[CycleDetected])) {
      case ((visited, acc), id) =>
        if visited.contains(id) then (visited, acc)
        else
          val (newVisited, newCycles) = dfsFrom(id, List.empty, Set.empty, visited, List.empty)
          (newVisited, acc ++ newCycles)
    }
    cycles

  // ── Tier monotonicity ───────────────────────────────────────────────────────

  /** Enforce that no synchronous edge crosses from a higher-criticality tier to a lower one.
    *
    * The `Criticality` enum ordinals: Tier1 = 0 (highest), Tier2 = 1, Tier3 = 2 (lowest). A violation occurs when
    * `from.tier.ordinal < to.tier.ordinal` AND the edge is synchronous (i.e. not [[Protocol.Event]]). Event edges are
    * asynchronous fire-and-forget and are exempt.
    *
    * Example violation: a Tier1 service calling a Tier2 service via REST.
    */
  def tierMonotonicity(g: Graph): List[TierViolation] =
    for
      (fromId, fromSvc) <- g.services.toList
      edge              <- fromSvc.outbound.toList
      toSvc             <- g.services.get(edge.toService).toList
      if !isAsync(edge.protocol) && fromSvc.tier.ordinal < toSvc.tier.ordinal
    yield TierViolation(from = fromId, to = edge.toService)

  // ── Structural validity ─────────────────────────────────────────────────────

  /** Check that every outbound edge references an existing target service and a declared inbound port.
    *
    *   - [[OrphanEdge]]: `edge.toService` is not a key in `g.services`.
    *   - [[UnknownPort]]: `edge.toPort` is not in `toService.inbound`. Only `toPort` is verified because the M2 ADT
    *     only models `inbound: Set[Port]`; there is no `outbound: Set[Port]` to validate `fromPort` against.
    */
  def structural(g: Graph): List[InvariantError] =
    for
      (fromId, fromSvc) <- g.services.toList
      edge              <- fromSvc.outbound.toList
      error             <- g.services.get(edge.toService) match
                             case None        => List(OrphanEdge(toRef(fromId, edge)))
                             case Some(toSvc) =>
                               if toSvc.inbound.map(_.name).contains(edge.toPort) then List.empty
                               else List(UnknownPort(edge.toService, edge.toPort))
    yield error

  // ── PII boundary ────────────────────────────────────────────────────────────

  /** Verify that PII-bearing services only route to services in the allow-list.
    *
    * The sets are supplied as parameters because the M2 graph ADT carries no PII annotation. The bank architect
    * configures these sets externally via [[ValidationConfig]].
    *
    * A [[PiiBoundaryCrossed]] is emitted for each outbound edge from a `piiServices` member to a service that is NOT in
    * `allowedSinks`.
    */
  def piiBoundary(g: Graph, piiServices: Set[ServiceId], allowedSinks: Set[ServiceId]): List[PiiBoundaryCrossed] =
    for
      (fromId, fromSvc) <- g.services.toList
      if piiServices.contains(fromId)
      edge              <- fromSvc.outbound.toList
      if !allowedSinks.contains(edge.toService)
    yield PiiBoundaryCrossed(from = fromId, to = edge.toService)

  // ── Strong consistency on financial transactions ─────────────────────────────

  /** Verify that every financial-transaction edge carries [[bankmod.graph.model.Consistency.Strong]].
    *
    * The financial edge set is supplied via [[ValidationConfig]] because the graph ADT carries no financial-txn
    * annotation. A [[WeakConsistencyOnFinancialEdge]] is emitted for each named financial edge whose `consistency`
    * field is not `Strong`.
    */
  def strongConsistencyOnFinancialTxns(g: Graph, financialEdges: Set[EdgeRef]): List[WeakConsistencyOnFinancialEdge] =
    for
      (fromId, fromSvc) <- g.services.toList
      edge              <- fromSvc.outbound.toList
      ref                = toRef(fromId, edge)
      if financialEdges.contains(ref)
      if edge.consistency != bankmod.graph.model.Consistency.Strong
    yield WeakConsistencyOnFinancialEdge(ref)

  // ── Packed-decimal guard ────────────────────────────────────────────────────

  /** Verify that every packed-decimal edge has a declared boundary conversion guard.
    *
    * Any edge in `packedEdges` that is NOT also in `guardedEdges` is a violation. Both sets are supplied via
    * [[ValidationConfig]] because the graph ADT carries no type-annotation fields.
    */
  def packedDecimalGuard(packedEdges: Set[EdgeRef], guardedEdges: Set[EdgeRef]): List[MissingPackedDecimalGuard] =
    (packedEdges -- guardedEdges).toList.map(MissingPackedDecimalGuard.apply)
