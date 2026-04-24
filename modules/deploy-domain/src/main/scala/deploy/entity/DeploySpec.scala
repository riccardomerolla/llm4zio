package deploy.entity

import java.nio.file.Path

/** Inputs for a single deploy invocation. `repoRoot` is where `sbt assembly` will run; `workspace` is the llm4zio
  * workspace being shipped inside the artifact. `dryRun` means "generate files but don't shell out to docker/sbt/gcloud
  * /kubectl" — useful for tests and CI previews.
  */
final case class DeploySpec(
  target: DeployTarget,
  workspace: Path,
  repoRoot: Path,
  imageName: String = "llm4zio-workspace",
  imageTag: String = "dev",
  dryRun: Boolean = false,
)
