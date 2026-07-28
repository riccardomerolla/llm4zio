package llm4zio.core

import zio.json.*
import zio.{ FiberRef, UIO, Unsafe, ZIO }

/** What an operation requires (issue #716, ADR 0021). `Capability` is the *requirement* side of the vocabulary — a tool
  * or flow surface declares the capability an invocation needs; [[Grants]] is the *held* side. Serializable so denials
  * and audit events can carry the exact requirement.
  */
enum Capability derives JsonCodec:
  case GitRead, GitWrite, GitPush
  case GhRead, GhWrite
  case AdoRead, AdoWrite
  case Exec(command: String)
  case UseCoder, UseReasoning, Declassify

/** The powers a flow currently holds. Levels are ordered (`None < Read < Write < Push`) so the read < write < push
  * hierarchy is a pointwise comparison, and [[intersect]] is pointwise-min — structurally incapable of widening, which
  * is what makes runtime narrowing (`Grants.restricted`) safe by construction.
  */
final case class Grants(
  git: Grants.Level,
  gh: Grants.Level,
  ado: Grants.Level,
  exec: Grants.ExecGrant,
  coder: Boolean,
  reasoning: Boolean,
  declassify: Boolean,
) derives JsonCodec:

  def intersect(other: Grants): Grants =
    Grants(
      git = Grants.Level.min(git, other.git),
      gh = Grants.Level.min(gh, other.gh),
      ado = Grants.Level.min(ado, other.ado),
      exec = Grants.ExecGrant.intersect(exec, other.exec),
      coder = coder && other.coder,
      reasoning = reasoning && other.reasoning,
      declassify = declassify && other.declassify,
    )

  /** Pointwise-max — how `Grants.For` combines the powers of a witness intersection type. Dual of [[intersect]]. */
  def union(other: Grants): Grants =
    Grants(
      git = Grants.Level.max(git, other.git),
      gh = Grants.Level.max(gh, other.gh),
      ado = Grants.Level.max(ado, other.ado),
      exec = Grants.ExecGrant.union(exec, other.exec),
      coder = coder || other.coder,
      reasoning = reasoning || other.reasoning,
      declassify = declassify || other.declassify,
    )

  def allows(cap: Capability): Boolean = cap match
    case Capability.GitRead       => git.atLeast(Grants.Level.Read)
    case Capability.GitWrite      => git.atLeast(Grants.Level.Write)
    case Capability.GitPush       => git.atLeast(Grants.Level.Push)
    case Capability.GhRead        => gh.atLeast(Grants.Level.Read)
    case Capability.GhWrite       => gh.atLeast(Grants.Level.Write)
    case Capability.AdoRead       => ado.atLeast(Grants.Level.Read)
    case Capability.AdoWrite      => ado.atLeast(Grants.Level.Write)
    case Capability.Exec(command) => exec.allows(command)
    case Capability.UseCoder      => coder
    case Capability.UseReasoning  => reasoning
    case Capability.Declassify    => declassify

object Grants:

  /** Ordered access level for the versioned-tool families (git uses all four; gh/ado never reach `Push`). */
  enum Level derives JsonCodec:
    case None, Read, Write, Push

    def atLeast(other: Level): Boolean = ordinal >= other.ordinal

  object Level:
    def min(a: Level, b: Level): Level = if a.ordinal <= b.ordinal then a else b
    def max(a: Level, b: Level): Level = if a.ordinal >= b.ordinal then a else b

  /** Exec is the one value-carrying grant: which commands `Proc` may run. */
  enum ExecGrant derives JsonCodec:
    case Denied
    case Allow(commands: Set[String])
    case All

    def allows(command: String): Boolean = this match
      case Denied          => false
      case Allow(commands) => commands.contains(command)
      case All             => true

  object ExecGrant:
    def intersect(a: ExecGrant, b: ExecGrant): ExecGrant = (a, b) match
      case (Denied, _) | (_, Denied) => Denied
      case (All, other)              => other
      case (other, All)              => other
      case (Allow(x), Allow(y))      => Allow(x.intersect(y))

    def union(a: ExecGrant, b: ExecGrant): ExecGrant = (a, b) match
      case (All, _) | (_, All)  => All
      case (Denied, other)      => other
      case (other, Denied)      => other
      case (Allow(x), Allow(y)) => Allow(x.union(y))

  val all: Grants =
    Grants(Level.Push, Level.Write, Level.Write, ExecGrant.All, coder = true, reasoning = true, declassify = true)

  val none: Grants =
    Grants(Level.None, Level.None, Level.None, ExecGrant.Denied, coder = false, reasoning = false, declassify = false)

  /** The ambient runtime gate (ADR 0021) — the app-wide FiberRef pattern ZIO 2 itself uses for default services.
    * Initial `all` keeps ungated code behaving exactly as before; copy-on-fork covers `fork`/`zipPar`/`race` for free;
    * the parent-wins join means a child's gate value never replaces its parent's on `join`, so a widening inside a
    * forked fiber dies with the fiber. Package-private: the only public verbs are the read-only [[current]] and the
    * narrow-only [[restricted]].
    */
  private[llm4zio] val gate: FiberRef[Grants] =
    Unsafe.unsafe(implicit u => FiberRef.unsafe.make(all, fork = identity, join = (parent, _) => parent))

  /** The grants in force on this fiber. */
  def current: UIO[Grants] = gate.get

  /** Run `zio` with the ambient grants narrowed to their intersection with `caps`, restored afterwards on every exit
    * (success, failure, interruption). Intersection is pointwise-min, so this combinator is structurally incapable of
    * widening — nesting only ever narrows further.
    */
  def restricted[R, E, A](caps: Grants)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    gate.locallyWith(_.intersect(caps))(zio)
