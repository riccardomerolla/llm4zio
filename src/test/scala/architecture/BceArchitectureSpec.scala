package architecture

import java.nio.file.{ Files, Path, Paths }

import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

import zio.*
import zio.test.*

object BceArchitectureSpec extends ZIOSpecDefault:

  private val componentPattern: Regex =
    raw"^(activity|app|config|conversation|gateway|issues|memory|taskrun|orchestration)\.(boundary|control|entity)(?:\.|$$)".r

  private val importPattern: Regex =
    raw"(?:^|\s|,)(?:_root_\.)?(activity|app|config|conversation|gateway|issues|memory|taskrun|orchestration)\.(boundary|control|entity)(?:\.|[\{\s,]|$$)".r

  final private case class BceFile(path: Path, component: String, layer: String, imports: List[(Int, String, String)])

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("BceArchitectureSpec")(
      test("control does not depend on boundary and entity does not depend on control/boundary") {
        for
          files     <- loadBceFiles
          violations = files.flatMap(file => dependencyDirectionViolations(file))
        yield assert(violations)(Assertion.isEmpty)
      },
      test("no cross-component entity-to-entity direct dependencies") {
        for
          files     <- loadBceFiles
          violations = files.flatMap(file => crossEntityViolations(file))
        yield assert(violations)(Assertion.isEmpty)
      },
    )

  private def loadBceFiles: UIO[List[BceFile]] =
    ZIO.attemptBlocking {
      val root = Paths.get("src/main/scala")
      val iter = Files.walk(root)
      try
        iter.iterator().asScala
          .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".scala"))
          .flatMap(parseBceFile)
          .toList
      finally iter.close()
    }.orDie

  private def parseBceFile(path: Path): Option[BceFile] =
    val lines = Files.readAllLines(path).asScala.toList
    lines.collectFirst { case line if line.startsWith("package ") => line.stripPrefix("package ").trim } match
      case Some(componentPattern(component, layer)) =>
        val imports = lines.zipWithIndex.flatMap { (line, idx) =>
          importPattern
            .findAllMatchIn(line)
            .map(m => (idx + 1, m.group(1), m.group(2)))
            .toList
        }
        Some(BceFile(path, component, layer, imports))
      case _                                        =>
        None

  private def dependencyDirectionViolations(file: BceFile): List[String] =
    file.layer match
      case "control" =>
        file.imports.collect {
          case (line, importedComponent, "boundary") =>
            s"${file.path}:$line control layer depends on boundary layer ($importedComponent.boundary)"
        }
      case "entity"  =>
        file.imports.collect {
          case (line, importedComponent, importedLayer @ ("boundary" | "control")) =>
            s"${file.path}:$line entity layer depends on $importedLayer layer ($importedComponent.$importedLayer)"
        }
      case _         =>
        Nil

  private def crossEntityViolations(file: BceFile): List[String] =
    if file.layer != "entity" then Nil
    else
      file.imports.collect {
        case (line, importedComponent, "entity") if importedComponent != file.component =>
          s"${file.path}:$line entity layer depends on another component entity ($importedComponent.entity)"
      }
