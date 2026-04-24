package cli.commands

import java.nio.file.{ Path, Paths }

import zio.*

import deploy.control.DeployDispatcher
import deploy.entity.*

object DeployCommand:

  /** Parses the target, resolves paths, dispatches to the correct strategy, prints artifacts + notes. Fails loudly on
    * a bad target string or a strategy error so the CLI exits non-zero.
    */
  def deploy(
    workspaceRaw: String,
    targetRaw: String,
    repoRootRaw: Option[String],
    imageName: Option[String],
    imageTag: Option[String],
    dryRun: Boolean,
  ): IO[String, DeployResult] =
    val workspace = Paths.get(workspaceRaw).toAbsolutePath.normalize
    val repoRoot  = repoRootRaw.map(r => Paths.get(r).toAbsolutePath.normalize).getOrElse(defaultRepoRoot)

    for
      target <- ZIO.fromEither(DeployTarget.parse(targetRaw))
      spec    = DeploySpec(
                  target = target,
                  workspace = workspace,
                  repoRoot = repoRoot,
                  imageName = imageName.getOrElse(defaultImageName(workspace)),
                  imageTag = imageTag.getOrElse("dev"),
                  dryRun = dryRun,
                )
      _      <- Console.printLine(s"▶ Deploying workspace $workspace → $target (dryRun=$dryRun)").orDie
      result <- DeployDispatcher.deploy(spec).mapError(_.message)
      _      <- Console.printLine(formatResult(result)).orDie
    yield result

  private def formatResult(r: DeployResult): String =
    val artifacts = r.artifacts.map(p => s"  • $p").mkString("\n")
    val notes     = r.notes.map(n => s"  - $n").mkString("\n")
    val image     = r.imageRef.map(ref => s"\nImage:  $ref").getOrElse("")
    s"""|
        |Target: ${r.target}$image
        |Artifacts:
        |${if r.artifacts.nonEmpty then artifacts else "  (none)"}
        |Next steps:
        |${if r.notes.nonEmpty then notes else "  (none)"}""".stripMargin

  private def defaultImageName(workspace: Path): String =
    val name = Option(workspace.getFileName).map(_.toString).getOrElse("llm4zio-workspace")
    s"llm4zio-${sanitize(name)}"

  private def sanitize(s: String): String =
    s.toLowerCase.replaceAll("[^a-z0-9-]+", "-").replaceAll("-{2,}", "-").stripPrefix("-").stripSuffix("-")

  private def defaultRepoRoot: Path = Paths.get(".").toAbsolutePath.normalize
