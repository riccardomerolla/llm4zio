package deploy.control

import zio.*

import deploy.entity.*

/** Writes a Dockerfile to `<repoRoot>/Dockerfile.llm4zio`, optionally invokes `docker build` tagging the image. */
object DockerStrategy extends DeployStrategy:

  val target: DeployTarget = DeployTarget.Docker

  def deploy(spec: DeploySpec): IO[DeployError, DeployResult] =
    val dockerfile = spec.repoRoot.resolve("Dockerfile.llm4zio")
    val ref        = s"${spec.imageName}:${spec.imageTag}"
    val dryNote    = s"Dockerfile at $dockerfile. Build with: docker build -t $ref -f $dockerfile ${spec.repoRoot}"

    def dryResult(extra: String): DeployResult =
      DeployResult(target, List(dockerfile), None, List(s"$extra$dryNote"))

    def build: IO[DeployError, DeployResult] =
      DeployStrategy
        .runProcess(
          List("docker", "build", "-t", ref, "-f", dockerfile.toString, spec.repoRoot.toString),
          spec.repoRoot,
        )
        .flatMap {
          case (_, 0)      =>
            ZIO.succeed(
              DeployResult(
                target,
                List(dockerfile),
                Some(ref),
                List(s"Built image $ref. Push with: docker push $ref"),
              )
            )
          case (log, code) =>
            ZIO.fail(DeployError.BuildFailed("docker", code, log.takeRight(50).mkString("\n")))
        }

    DeployStrategy.writeFile(dockerfile, DockerfileRenderer.render(spec)) *> {
      if spec.dryRun then ZIO.succeed(dryResult(""))
      else
        DeployStrategy.whichExists("docker").flatMap {
          case false => ZIO.succeed(dryResult("'docker' not on PATH; skipped build. "))
          case true  => build
        }
    }
