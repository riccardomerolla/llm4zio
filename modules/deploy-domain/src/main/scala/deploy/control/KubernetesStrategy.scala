package deploy.control

import zio.*

import deploy.entity.*

/** Writes `<workspace>/k8s/deployment.yaml` with a ready-to-apply Deployment + Service. Does not run `kubectl apply`;
  * the user is expected to review the manifest and apply it explicitly.
  */
object KubernetesStrategy extends DeployStrategy:

  val target: DeployTarget = DeployTarget.Kubernetes

  def deploy(spec: DeploySpec): IO[DeployError, DeployResult] =
    val manifest = spec.workspace.resolve("k8s").resolve("deployment.yaml")
    for
      _ <- DeployStrategy.writeFile(manifest, KubernetesManifest.render(spec))
    yield DeployResult(
      target,
      List(manifest),
      None,
      List(
        s"Manifest written to $manifest.",
        s"Build+push the image first, then: kubectl apply -f $manifest",
      ),
    )
