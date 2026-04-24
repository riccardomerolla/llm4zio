package deploy.control

import zio.*

import deploy.entity.*

/** Writes `<workspace>/cloud-run/service.yaml`. Actual `gcloud run services replace` is a follow-up. */
object CloudRunStrategy extends DeployStrategy:

  val target: DeployTarget = DeployTarget.CloudRun

  def deploy(spec: DeploySpec): IO[DeployError, DeployResult] =
    val manifest = spec.workspace.resolve("cloud-run").resolve("service.yaml")
    for
      _ <- DeployStrategy.writeFile(manifest, CloudRunManifest.render(spec))
    yield DeployResult(
      target,
      List(manifest),
      None,
      List(
        s"Manifest written to $manifest.",
        s"Build+push the image first, then: gcloud run services replace $manifest",
      ),
    )
