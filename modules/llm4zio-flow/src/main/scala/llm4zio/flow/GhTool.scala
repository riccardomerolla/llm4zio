package llm4zio.flow

import zio.*

import java.nio.file.Path

/** Thin GitHub CLI (`gh`) wrapper over zio-process, bound to a working directory. */
final class GhTool(workDir: Path):

  /** Open a pull request via `gh pr create`; returns the PR URL gh prints. */
  def createPr(
    title: String,
    body: String,
    base: Option[String] = None,
    draft: Boolean = false,
  ): IO[FlowError, String] =
    Proc.runOrFail("gh", GhTool.prCreateArgs(title, body, base, draft), workDir)

object GhTool:
  /** The `gh` argv for opening a PR — pure, so it can be unit-tested. */
  def prCreateArgs(title: String, body: String, base: Option[String], draft: Boolean): List[String] =
    List("pr", "create", "--title", title, "--body", body) ++
      base.toList.flatMap(b => List("--base", b)) ++
      (if draft then List("--draft") else Nil)
