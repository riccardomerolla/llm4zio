package agents

import java.nio.file.Path

import zio.*
import zio.json.*

import core.{ FileService, Logger }
import models.*
import orchestration.MigrationResult

/** DocumentationAgent - Generate comprehensive migration documentation.
  */
trait DocumentationAgent:
  def generateDocs(result: MigrationResult): ZIO[Any, DocError, MigrationDocumentation]

object DocumentationAgent:
  def generateDocs(result: MigrationResult): ZIO[DocumentationAgent, DocError, MigrationDocumentation] =
    ZIO.serviceWithZIO[DocumentationAgent](_.generateDocs(result))

  val live: ZLayer[FileService, Nothing, DocumentationAgent] =
    ZLayer.fromFunction { (fileService: FileService) =>
      new DocumentationAgent {
        private val reportDir  = Path.of("reports/documentation")
        private val diagramDir = reportDir.resolve("diagrams")

        override def generateDocs(result: MigrationResult): ZIO[Any, DocError, MigrationDocumentation] =
          for
            _           <- validateResult(result)
            generatedAt <- Clock.instant
            _           <- Logger.info("Generating migration documentation")
            summary      = renderSummary(result, generatedAt)
            design       = renderDesignDocument(result)
            api          = renderApiDocumentation(result)
            dataMapping  = renderDataMappingReference(result)
            deployment   = renderDeploymentGuide(result)
            diagrams     = renderDiagrams(result)
            docs         = MigrationDocumentation(
                             generatedAt = generatedAt,
                             summaryReport = summary,
                             designDocument = design,
                             apiDocumentation = api,
                             dataMappingReference = dataMapping,
                             deploymentGuide = deployment,
                             diagrams = diagrams,
                           )
            _           <- writeDocumentation(docs)
            _           <- Logger.info("Documentation generation complete")
          yield docs

        private def validateResult(result: MigrationResult): ZIO[Any, DocError, Unit] =
          if result.projects.isEmpty then ZIO.fail(DocError.InvalidResult("no projects were generated"))
          else ZIO.unit

        private def renderSummary(result: MigrationResult, generatedAt: java.time.Instant): String =
          val status             = if result.success then "SUCCESS" else "FAILED"
          val projects           = result.projects.map(_.projectName).mkString("\n", "\n", "")
          val validationStatuses =
            if result.validationReports.isEmpty then "- No validation reports available"
            else result.validationReports.map(r => s"- ${r.projectName}: ${r.overallStatus}").mkString("\n")

          s"""# Migration Summary Report
             |
             |## Overview
             |- **Generated At:** $generatedAt
             |- **Overall Status:** $status
             |- **Generated Projects:** ${result.projects.size}
             |- **Validation Reports:** ${result.validationReports.size}
             |
             |## Programs Migrated
             |$projects
             |## Validation Summary
             |$validationStatuses
             |""".stripMargin

        private def renderDesignDocument(result: MigrationResult): String =
          val serviceCount    = result.projects.map(_.services.size).sum
          val controllerCount = result.projects.map(_.controllers.size).sum
          val entityCount     = result.projects.map(_.entities.size).sum

          s"""# Design Decision Document
             |
             |## Architecture Decisions
             |- Migrated to Spring Boot services with layered architecture
             |- Domain models represented as JPA entities
             |- REST controllers expose legacy program operations
             |
             |## Generated Components
             |- Services: $serviceCount
             |- Controllers: $controllerCount
             |- Entities: $entityCount
             |
             |## Diagram
             |```mermaid
             |${architectureDiagram(result)}
             |```
             |""".stripMargin

        private def renderApiDocumentation(result: MigrationResult): String =
          val endpoints =
            result.projects.flatMap { project =>
              project.controllers.flatMap { controller =>
                controller.endpoints.map { endpoint =>
                  s"- `${endpoint.method}` `${controller.basePath}${endpoint.path}` -> `${endpoint.methodName}`"
                }
              }
            }

          s"""# API Documentation
             |
             |## OpenAPI Configuration
             |- Generated projects include `OpenApiConfig.java` and SpringDoc integration.
             |
             |## Endpoints
             |${if endpoints.isEmpty then "- No endpoints generated" else endpoints.mkString("\n")}
             |""".stripMargin

        private def renderDataMappingReference(result: MigrationResult): String =
          val mappings = result.projects.flatMap { project =>
            project.entities.flatMap { entity =>
              entity.fields.map(field =>
                s"| ${project.projectName} | ${field.cobolSource} | ${entity.className}.${field.name} | ${field.javaType} |"
              )
            }
          }

          s"""# Data Mapping Reference
             |
             || Program | COBOL Source | Java Target | Java Type |
             ||---|---|---|---|
             |${if mappings.isEmpty then "| - | - | - | - |" else mappings.mkString("\n")}
             |""".stripMargin

        private def renderDeploymentGuide(result: MigrationResult): String =
          val artifacts = result.projects.map(_.configuration.artifactId).mkString(", ")
          s"""# Deployment Guide
             |
             |## Build
             |```bash
             |mvn -q clean package
             |```
             |
             |## Runtime
             |- Generated artifacts: $artifacts
             |- Java: 17+
             |- Spring Boot: 3.x
             |
             |## Recommended Steps
             |1. Run integration tests in staging.
             |2. Verify database migrations and entity mappings.
             |3. Enable observability and monitor key business flows.
             |""".stripMargin

        private def renderDiagrams(result: MigrationResult): List[Diagram] =
          List(
            Diagram("architecture", DiagramType.Mermaid, architectureDiagram(result)),
            Diagram("data-flow", DiagramType.Mermaid, dataFlowDiagram(result)),
          )

        private def architectureDiagram(result: MigrationResult): String =
          val nodes = result.projects.map(p => s"  ${safeId(p.projectName)}[\"${p.projectName}\"]")
          val edges = result.projects.flatMap { project =>
            project.repositories.map(repo =>
              s"  ${safeId(project.projectName)} --> ${safeId(repo.name)}[\"${repo.name}\"]"
            )
          }
          (List("graph TD") ++ nodes ++ edges).mkString("\n")

        private def dataFlowDiagram(result: MigrationResult): String =
          val flows = result.projects.map { project =>
            s"  ${safeId(project.projectName)}Ctrl[\"${project.projectName} Controller\"] --> ${safeId(project.projectName)}Svc[\"${project.projectName} Service\"]"
          }
          (List("graph LR") ++ flows).mkString("\n")

        private def safeId(raw: String): String =
          raw.replaceAll("[^A-Za-z0-9]", "")

        private def writeDocumentation(docs: MigrationDocumentation): ZIO[Any, DocError, Unit] =
          for
            _ <-
              fileService.ensureDirectory(reportDir).mapError(fe => DocError.ReportWriteFailed(reportDir, fe.message))
            _ <-
              fileService.ensureDirectory(diagramDir).mapError(fe => DocError.ReportWriteFailed(diagramDir, fe.message))
            _ <- writeFileAtomic(reportDir.resolve("migration-summary.md"), docs.summaryReport)
            _ <- writeFileAtomic(
                   reportDir.resolve("migration-summary.html"),
                   toHtml("Migration Summary", docs.summaryReport),
                 )
            _ <- writeFileAtomic(reportDir.resolve("design-document.md"), docs.designDocument)
            _ <-
              writeFileAtomic(reportDir.resolve("design-document.html"), toHtml("Design Document", docs.designDocument))
            _ <- writeFileAtomic(reportDir.resolve("api-documentation.md"), docs.apiDocumentation)
            _ <- writeFileAtomic(
                   reportDir.resolve("api-documentation.html"),
                   toHtml("API Documentation", docs.apiDocumentation),
                 )
            _ <- writeFileAtomic(reportDir.resolve("data-mapping-reference.md"), docs.dataMappingReference)
            _ <- writeFileAtomic(
                   reportDir.resolve("data-mapping-reference.html"),
                   toHtml("Data Mapping Reference", docs.dataMappingReference),
                 )
            _ <- writeFileAtomic(reportDir.resolve("deployment-guide.md"), docs.deploymentGuide)
            _ <- writeFileAtomic(
                   reportDir.resolve("deployment-guide.html"),
                   toHtml("Deployment Guide", docs.deploymentGuide),
                 )
            _ <- writeFileAtomic(reportDir.resolve("documentation.json"), docs.toJsonPretty)
            _ <- ZIO.foreachDiscard(docs.diagrams) { diagram =>
                   val extension = diagram.diagramType match
                     case DiagramType.Mermaid  => "mmd"
                     case DiagramType.PlantUML => "puml"
                   writeFileAtomic(diagramDir.resolve(s"${diagram.name}.$extension"), diagram.content)
                 }
          yield ()

        private def writeFileAtomic(path: Path, content: String): ZIO[Any, DocError, Unit] =
          for
            suffix  <- ZIO.attemptBlocking(java.util.UUID.randomUUID().toString)
                         .mapError(e => DocError.ReportWriteFailed(path, e.getMessage))
            tempPath = path.resolveSibling(s"${path.getFileName}.tmp.$suffix")
            _       <- fileService.writeFile(tempPath, content)
                         .mapError(fe => DocError.ReportWriteFailed(tempPath, fe.message))
            _       <- ZIO
                         .attemptBlocking {
                           import java.nio.file.StandardCopyOption
                           try
                             java.nio.file.Files.move(
                               tempPath,
                               path,
                               StandardCopyOption.REPLACE_EXISTING,
                               StandardCopyOption.ATOMIC_MOVE,
                             )
                           catch
                             case _: java.nio.file.AtomicMoveNotSupportedException =>
                               java.nio.file.Files.move(
                                 tempPath,
                                 path,
                                 StandardCopyOption.REPLACE_EXISTING,
                               )
                         }
                         .mapError(e => DocError.ReportWriteFailed(path, e.getMessage))
          yield ()

        private def toHtml(title: String, markdown: String): String =
          s"""<!doctype html>
             |<html lang=\"en\">
             |<head>
             |  <meta charset=\"utf-8\" />
             |  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />
             |  <title>$title</title>
             |</head>
             |<body>
             |<pre>
             |${escapeHtml(markdown)}
             |</pre>
             |</body>
             |</html>
             |""".stripMargin

        private def escapeHtml(value: String): String =
          value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
      }
    }
