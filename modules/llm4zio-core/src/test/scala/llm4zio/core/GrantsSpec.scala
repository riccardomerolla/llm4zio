package llm4zio.core

import zio.Scope
import zio.json.*
import zio.test.*

import llm4zio.core.Grants.{ ExecGrant, Level }

/** The capability vocabulary: `Capability` is what an operation requires, `Grants` is what a flow holds. Intersection
  * is pointwise-min so narrowing can never widen; `allows` honours the read < write < push hierarchy.
  */
object GrantsSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Grants")(
    test("all allows every capability; none allows none") {
      val caps = List(
        Capability.GitRead,
        Capability.GitWrite,
        Capability.GitPush,
        Capability.GhRead,
        Capability.GhWrite,
        Capability.AdoRead,
        Capability.AdoWrite,
        Capability.Exec("ls"),
        Capability.UseCoder,
        Capability.UseReasoning,
        Capability.Declassify,
      )
      assertTrue(
        caps.forall(Grants.all.allows),
        caps.forall(c => !Grants.none.allows(c)),
      )
    },
    test("the git hierarchy: write implies read, push is stronger than write") {
      val readWrite = Grants.none.copy(git = Level.Write)
      assertTrue(
        readWrite.allows(Capability.GitRead),
        readWrite.allows(Capability.GitWrite),
        !readWrite.allows(Capability.GitPush),
        Grants.none.copy(git = Level.Read).allows(Capability.GitRead),
        !Grants.none.copy(git = Level.Read).allows(Capability.GitWrite),
      )
    },
    test("the gh hierarchy: write implies read") {
      val ghRead = Grants.none.copy(gh = Level.Read)
      assertTrue(
        ghRead.allows(Capability.GhRead),
        !ghRead.allows(Capability.GhWrite),
        Grants.none.copy(gh = Level.Write).allows(Capability.GhRead),
      )
    },
    test("exec grants gate individual commands") {
      val lsOnly = Grants.none.copy(exec = ExecGrant.Allow(Set("ls", "cat")))
      assertTrue(
        lsOnly.allows(Capability.Exec("ls")),
        lsOnly.allows(Capability.Exec("cat")),
        !lsOnly.allows(Capability.Exec("rm")),
        Grants.none.copy(exec = ExecGrant.All).allows(Capability.Exec("anything")),
        !Grants.none.allows(Capability.Exec("ls")),
      )
    },
    test("intersect is pointwise-min: it can only narrow, never widen") {
      val a = Grants.all.copy(git = Level.Write, exec = ExecGrant.Allow(Set("ls", "cat")), declassify = false)
      val b = Grants.all.copy(git = Level.Push, gh = Level.Read, exec = ExecGrant.Allow(Set("cat", "rm")))
      val i = a.intersect(b)
      assertTrue(
        i.git == Level.Write,                    // min(Write, Push)
        i.gh == Level.Read,                      // min(Write, Read)
        i.exec == ExecGrant.Allow(Set("cat")),   // set intersection
        !i.declassify,                           // && of booleans
        Grants.all.intersect(a) == a,            // all is the identity
        a.intersect(Grants.none) == Grants.none, // none is absorbing
        a.intersect(b) == b.intersect(a), // commutative
      )
    },
    test("intersect with All exec keeps the narrower side") {
      val allow = ExecGrant.Allow(Set("ls"))
      assertTrue(
        Grants.all.copy(exec = ExecGrant.All).intersect(Grants.all.copy(exec = allow)).exec == allow,
        Grants.all.copy(exec = allow).intersect(Grants.all.copy(exec = ExecGrant.Denied)).exec == ExecGrant.Denied,
      )
    },
    test("union is pointwise-max: combining granted powers for Grants.For intersections") {
      val a = Grants.none.copy(git = Level.Read, exec = ExecGrant.Allow(Set("ls")), coder = true)
      val b = Grants.none.copy(git = Level.Push, gh = Level.Read, exec = ExecGrant.Allow(Set("cat")))
      val u = a.union(b)
      assertTrue(
        u.git == Level.Push,
        u.gh == Level.Read,
        u.exec == ExecGrant.Allow(Set("ls", "cat")),
        u.coder,
        !u.declassify,
        Grants.none.union(a) == a,         // none is the identity
        a.union(Grants.all) == Grants.all, // all is absorbing
        Grants.none.copy(exec = ExecGrant.All).union(a).exec == ExecGrant.All,
      )
    },
    test("Grants and Capability round-trip through JSON (audit events carry them)") {
      val g               = Grants.all.copy(git = Level.Write, exec = ExecGrant.Allow(Set("ls")))
      val cap: Capability = Capability.Exec("rm -rf")
      assertTrue(
        g.toJson.fromJson[Grants] == Right(g),
        cap.toJson.fromJson[Capability] == Right(cap),
      )
    },
  )
