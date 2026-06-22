package llm4zio.flow

import java.nio.file.Path

import scala.util.hashing.MurmurHash3

/** Where llm4zio keeps its bookkeeping (`.llm4zio/`: plans, traces, logs) relative to the **workspace** (the launch
  * directory), separate from the **repo** (`workDir`) the coder/git operate on.
  *
  * When the repo is the workspace (the common case — a script run from inside the repo) the layout is flat and
  * unchanged: `workspace/.llm4zio`. When a script targets an external repo (`--repo`), bookkeeping is namespaced by the
  * repo so one control directory can drive many repos without plan/trace collisions: `workspace/.llm4zio/<repo-id>`.
  */
object Workspace:

  /** The bookkeeping subdirectory name for `repo` under `workspace`: [[None]] when they are the same path (flat
    * layout), else `Some("<repo-basename>-<short-hash>")` — basename for legibility, hash of the absolute path for
    * collision-safety across same-named repos.
    */
  def repoId(workspace: Path, repo: Path): Option[String] =
    val ws = workspace.normalize
    val r  = repo.normalize
    if ws == r then None
    else
      val base = Option(r.getFileName).map(_.toString).filter(_.nonEmpty).getOrElse("repo")
      Some(s"$base-${Integer.toHexString(MurmurHash3.stringHash(r.toString))}")

  /** The directory holding `.llm4zio/` bookkeeping: `workspace/.llm4zio` when `repo == workspace`, else
    * `workspace/.llm4zio/<repo-id>`. Pass this as `Plan.defaultPath`'s `dir` and as the trace recorder's root.
    */
  def llm4zioDir(workspace: Path, repo: Path): Path =
    val root = workspace.normalize.resolve(".llm4zio")
    repoId(workspace, repo).fold(root)(root.resolve)
