package agents

import zio.*

import models.{ DependencyGraph, MigrationDocumentation, SpringBootProject, ValidationReport }

/** DocumentationAgent - Generate comprehensive migration documentation
  *
  * Responsibilities:
  *   - Create technical design documents
  *   - Generate API documentation
  *   - Document data model mappings
  *   - Produce migration summary reports
  *   - Create deployment guides
  *
  * Interactions:
  *   - Input from: All agents
  *   - Output: Final documentation deliverables
  */
trait DocumentationAgent:
  def generateDocumentation(
    project: SpringBootProject,
    validationReport: ValidationReport,
    dependencyGraph: DependencyGraph,
  ): ZIO[Any, Throwable, MigrationDocumentation]

object DocumentationAgent:
  def generateDocumentation(
    project: SpringBootProject,
    validationReport: ValidationReport,
    dependencyGraph: DependencyGraph,
  ): ZIO[DocumentationAgent, Throwable, MigrationDocumentation] =
    ZIO.serviceWithZIO[DocumentationAgent](_.generateDocumentation(project, validationReport, dependencyGraph))

  val live: ZLayer[Any, Nothing, DocumentationAgent] = ZLayer.succeed {
    new DocumentationAgent {
      override def generateDocumentation(
        project: SpringBootProject,
        validationReport: ValidationReport,
        dependencyGraph: DependencyGraph,
      ): ZIO[Any, Throwable, MigrationDocumentation] =
        // TODO: Implement documentation generation
        ZIO.succeed(MigrationDocumentation.empty)
    }
  }
