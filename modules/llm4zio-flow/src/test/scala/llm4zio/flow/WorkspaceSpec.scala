package llm4zio.flow

import java.nio.file.Path

import zio.Scope
import zio.test.*

object WorkspaceSpec extends ZIOSpecDefault:

  private val ws = Path.of("/home/me/control")

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Workspace")(
    test("repoId is None when repo == workspace (flat, backward-compatible layout)") {
      assertTrue(Workspace.repoId(ws, ws).isEmpty)
    },
    test("repoId is the repo basename plus a short hash when repo != workspace") {
      assertTrue(Workspace.repoId(ws, Path.of("/projects/calculator-rs")).exists(_.startsWith("calculator-rs-")))
    },
    test("repoId is deterministic for the same repo") {
      val repo = Path.of("/projects/calculator-rs")
      assertTrue(Workspace.repoId(ws, repo) == Workspace.repoId(ws, repo))
    },
    test("repos with the same basename but different paths get different ids") {
      assertTrue(Workspace.repoId(ws, Path.of("/a/calc")) != Workspace.repoId(ws, Path.of("/b/calc")))
    },
    test("llm4zioDir is workspace/.llm4zio when repo == workspace") {
      assertTrue(Workspace.llm4zioDir(ws, ws) == ws.resolve(".llm4zio"))
    },
    test("llm4zioDir is namespaced under .llm4zio/<repo-id> when repo != workspace") {
      val dir = Workspace.llm4zioDir(ws, Path.of("/projects/calculator-rs"))
      assertTrue(
        dir.startsWith(ws.resolve(".llm4zio")),
        dir.getParent == ws.resolve(".llm4zio"),
        dir.getFileName.toString.startsWith("calculator-rs-"),
      )
    },
  )
