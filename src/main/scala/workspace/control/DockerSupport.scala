package workspace.control

import zio.*

import workspace.entity.WorkspaceError

object DockerSupport:

  private val cachedResult: Ref[Option[Boolean]] =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(Ref.make(Option.empty[Boolean])).getOrThrowFiberFailure())

  /** Returns true if `docker info` exits 0. Cached in a Ref after first call. */
  val isAvailable: Task[Boolean] =
    cachedResult.get.flatMap {
      case Some(result) => ZIO.succeed(result)
      case None         =>
        ZIO
          .attemptBlockingIO {
            val pb      = new ProcessBuilder("docker", "info")
            pb.redirectErrorStream(true)
            val process = pb.start()
            // discard output — only care about exit code
            val _       = scala.io.Source.fromInputStream(process.getInputStream).getLines().toList
            process.waitFor() == 0
          }
          .catchAll(_ => ZIO.succeed(false))
          .tap(result => cachedResult.set(Some(result)))
    }

  /** Fails with `WorkspaceError.DockerNotAvailable` if Docker is not present. */
  val requireDocker: IO[WorkspaceError, Unit] =
    isAvailable.orDie.flatMap { available =>
      if available then ZIO.unit
      else ZIO.fail(WorkspaceError.DockerNotAvailable("docker info returned non-zero exit code"))
    }
