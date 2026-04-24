package deploy.control

import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import zio.*

import deploy.entity.*

/** Runs `sbt assembly` at the repo root, then copies the produced JAR into `<workspace>/dist/`. */
object JvmFatJarStrategy extends DeployStrategy:

  val target: DeployTarget = DeployTarget.JvmFatJar

  def deploy(spec: DeploySpec): IO[DeployError, DeployResult] =
    val distDir = spec.workspace.resolve("dist")

    val dryNote =
      s"Would run: sbt assembly (in ${spec.repoRoot}); resulting JAR would be copied to $distDir"

    if spec.dryRun then
      ZIO.succeed(DeployResult(target, Nil, None, List(dryNote)))
    else
      for
        _       <- DeployStrategy
                     .runProcess(List("sbt", "assembly"), spec.repoRoot)
                     .flatMap {
                       case (_, 0)      => ZIO.unit
                       case (log, code) =>
                         ZIO.fail(DeployError.BuildFailed("sbt assembly", code, log.takeRight(50).mkString("\n")))
                     }
        jar     <- findProducedJar(spec.repoRoot)
        _       <- ZIO
                     .attemptBlocking(Files.createDirectories(distDir))
                     .mapError(e => DeployError.IOFailure(s"mkdir $distDir failed: ${e.getMessage}"))
        copied   = distDir.resolve(jar.getFileName.toString)
        _       <- ZIO
                     .attemptBlocking(Files.copy(jar, copied, java.nio.file.StandardCopyOption.REPLACE_EXISTING))
                     .mapError(e => DeployError.IOFailure(s"copy $jar → $copied failed: ${e.getMessage}"))
      yield DeployResult(
        target,
        List(copied),
        None,
        List(s"JAR copied to $copied. Run with: java -jar $copied"),
      )

  // Find the most recently written *.jar under target/scala-<version>/.
  private def findProducedJar(repoRoot: Path): IO[DeployError, Path] =
    ZIO
      .attemptBlocking {
        val targetDir = repoRoot.resolve("target")
        if !Files.isDirectory(targetDir) then None
        else
          Files
            .list(targetDir)
            .iterator()
            .asScala
            .toList
            .filter(p => p.getFileName.toString.startsWith("scala-"))
            .flatMap { d =>
              Files.list(d).iterator().asScala.toList.filter(_.getFileName.toString.endsWith(".jar"))
            }
            .sortBy(p => -Files.getLastModifiedTime(p).toMillis)
            .headOption
      }
      .mapError(e => DeployError.IOFailure(s"scan target/ failed: ${e.getMessage}"))
      .flatMap {
        case Some(jar) => ZIO.succeed(jar)
        case None      =>
          ZIO.fail(
            DeployError.IOFailure(
              s"sbt assembly completed but no JAR found under ${repoRoot.resolve("target")}"
            )
          )
      }
