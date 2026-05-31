package shared.web

import java.nio.file.{ Files, Path, Paths }

import scala.jdk.CollectionConverters.*

import zio.*
import zio.test.*

object RuntimeAssetCdnSpec extends ZIOSpecDefault:

  override def spec: Spec[Any, Any] =
    suite("RuntimeAssetCdnSpec")(
      test("runtime web views and browser assets do not reference public CDNs") {
        for
          contents <- ZIO.foreach(runtimeRoots)(readFiles).map(_.flatten)
          matches   = contents.flatMap {
                        case (path, text) =>
                          blockedHosts.collect {
                            case host if text.contains(host) => s"$path references $host"
                          }
                      }
        yield assertTrue(matches.isEmpty)
      }
    )

  private val blockedHosts: List[String] =
    List(
      "cdn.jsdelivr.net",
      "unpkg.com",
      "cdnjs.cloudflare.com",
      "esm.sh",
      "cdn.skypack.dev",
    )

  private val runtimeRoots: List[Path] =
    List(
      Paths.get("modules/shared-web-core/src/main/scala"),
      Paths.get("modules/shared-web/src/main/scala"),
      Paths.get("modules/taskrun-domain/src/main/scala"),
      Paths.get("src/main/resources/static/client/components"),
    )

  private def readFiles(root: Path): Task[List[(Path, String)]] =
    ZIO.scoped {
      ZIO
        .acquireRelease(ZIO.attemptBlocking(Files.walk(root)))(stream => ZIO.attempt(stream.close()).orDie)
        .flatMap { stream =>
          ZIO.attemptBlocking {
            stream
              .iterator()
              .asScala
              .filter(path => Files.isRegularFile(path) && isRuntimeSource(path))
              .map(path => path -> Files.readString(path))
              .toList
          }
        }
    }

  private def isRuntimeSource(path: Path): Boolean =
    val name = path.getFileName.toString
    name.endsWith(".scala") || name.endsWith(".js") || name.endsWith(".html") || name.endsWith(".md")
