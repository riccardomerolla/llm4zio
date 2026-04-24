package deploy.entity

import java.nio.file.Path

/** Outcome of a deploy. `artifacts` lists everything produced on disk (Dockerfile, manifests, JAR); `imageRef` is set
  * when a container image was actually built. `notes` collects human-facing instructions (next-steps, dry-run
  * warnings, etc.) that the CLI prints after the command completes.
  */
final case class DeployResult(
  target: DeployTarget,
  artifacts: List[Path],
  imageRef: Option[String] = None,
  notes: List[String] = Nil,
)
