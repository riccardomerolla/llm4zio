package llm4zio.flow

import zio.{ IO, ZIO }

import llm4zio.core.{ Capability, Grants }

/** Compile-time capability witnesses (issue #716, ADR 0021) — tacit's `using`-capability model in stable Scala.
  *
  * A witness is an unforgeable token: the traits are sealed with no public implementations, so flow code can *receive*
  * one (as an entry-point context argument) but never construct one. Tool methods require the witness they need
  * (`git.commitAll` takes `using Caps.GitWrite`), so a `flow.restricted[C]` body that never declared a power is a
  * compile error away from using it. Subtyping encodes the hierarchy: a `GitPush` witness satisfies `GitWrite` and
  * `GitRead` summons.
  *
  * Witnesses are the *static* half; the ambient [[llm4zio.core.Grants]] FiberRef is the *dynamic* half — it catches
  * everything a lexical token cannot (mid-flow `Grants.restricted` narrowing, tokens captured before a narrowing, and
  * model-chosen tool calls). The one deliberate bypass is [[Caps.grantAll]], package-private so every use inside the
  * library is a visible, greppable decision.
  */
object Caps:
  sealed trait GitRead
  sealed trait GitWrite extends GitRead
  sealed trait GitPush  extends GitWrite
  sealed trait GhRead
  sealed trait GhWrite  extends GhRead
  sealed trait AdoRead
  sealed trait AdoWrite extends AdoRead

  /** Exec is compile-time coarse; the runtime allowlist is value-level data on [[Grants]] (`ExecGrant`). */
  sealed trait Exec
  sealed trait Coder
  sealed trait Reasoning
  sealed trait Declassify

  /** Every power at once — what plain `flow()` passes, and what subsumes any restricted intersection. */
  sealed trait All extends GitPush, GhWrite, AdoWrite, Exec, Coder, Reasoning, Declassify

  /** The mint. Package-private on purpose: entry points (`flow()`, `Llm4zio.run`, the Java Bridge) and tests inside the
    * library may grant everything; user code cannot.
    */
  private[llm4zio] object grantAll extends All

  /** The one public escape hatch, deliberately loud (mirrors ZIO's `Unsafe` marker): mint a full witness outside any
    * flow entry — an embedder's test harness, direct tool construction. Every use is greppable and glaring in review;
    * inside `flow.restricted` bodies it is the way to *visibly* opt out, never an accident.
    *
    * {{{given Caps.All = Unsafe.unsafe(implicit u => Caps.unsafe.all)}}}
    */
  object unsafe:
    def all(using zio.Unsafe): All = grantAll

  /** The runtime [[Grants]] a witness intersection type stands for — the single source of truth that keeps the
    * compile-time and runtime layers from drifting (`flow.restricted[C]` derives its FiberRef narrowing from the same
    * `C` that provides the witnesses).
    *
    * Implementation note: a `given`-based type class over `A & B` is ambiguous to Scala's implicit search
    * (intersections have no canonical split), so derivation uses ZIO's own `Tag`/LightTypeTag decomposition — the
    * machinery built for exactly this shape of type. The witness vocabulary is closed (sealed), so the leaf table is
    * total; an unrecognized leaf contributes nothing (deny-by-default).
    */
  def grantsFor[C](using tag: zio.EnvironmentTag[C]): Grants =
    val t     = tag.tag
    val parts = if t.decompose.isEmpty then Set(t) else t.decompose
    parts.foldLeft(Grants.none)((acc, leaf) => acc.union(leafGrants(leaf)))

  private val leafTable: List[(zio.LightTypeTag, Grants)] = List(
    zio.Tag[All].tag        -> Grants.all,
    zio.Tag[GitPush].tag    -> Grants.none.copy(git = Grants.Level.Push),
    zio.Tag[GitWrite].tag   -> Grants.none.copy(git = Grants.Level.Write),
    zio.Tag[GitRead].tag    -> Grants.none.copy(git = Grants.Level.Read),
    zio.Tag[GhWrite].tag    -> Grants.none.copy(gh = Grants.Level.Write),
    zio.Tag[GhRead].tag     -> Grants.none.copy(gh = Grants.Level.Read),
    zio.Tag[AdoWrite].tag   -> Grants.none.copy(ado = Grants.Level.Write),
    zio.Tag[AdoRead].tag    -> Grants.none.copy(ado = Grants.Level.Read),
    // Exec grants the full runtime allowlist; a flow narrows it with the entry's value-level refinement.
    zio.Tag[Exec].tag       -> Grants.none.copy(exec = Grants.ExecGrant.All),
    zio.Tag[Coder].tag      -> Grants.none.copy(coder = true),
    zio.Tag[Reasoning].tag  -> Grants.none.copy(reasoning = true),
    zio.Tag[Declassify].tag -> Grants.none.copy(declassify = true),
  )

  private def leafGrants(leaf: zio.LightTypeTag): Grants =
    leafTable.collectFirst { case (t, g) if leaf == t => g }.getOrElse(Grants.none)

  /** The runtime guard the tools share: checks the ambient grants, emits the audit event, and either proceeds or fails
    * typed. A denial here means the flow narrowed at runtime below what its witnesses allow statically — a bug in the
    * flow, so it fails fast ([[FlowError.CapabilityDenied]]) per the recoverable-vs-catastrophic split.
    * `CapabilityUsed` is emitted only for publish-grade operations to keep traces readable.
    */
  private[llm4zio] def guarded[A](
    cap: Capability,
    op: String,
    events: FlowEvents,
  )(
    body: IO[FlowError, A]
  ): IO[FlowError, A] =
    Grants.current.flatMap { grants =>
      if grants.allows(cap) then
        val audit = cap match
          case Capability.GitPush | Capability.GhWrite | Capability.AdoWrite | Capability.Exec(_) =>
            events.publish(FlowEvent.CapabilityUsed(cap.toString, op))
          case _                                                                                  => ZIO.unit
        audit *> body
      else
        events.publish(FlowEvent.CapabilityDenied(cap.toString, op)) *>
          ZIO.fail(FlowError.CapabilityDenied(cap, op))
    }
