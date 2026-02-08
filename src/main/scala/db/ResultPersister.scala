package db

import zio.*
import zio.json.*

import models.{ FileType as DomainFileType, * }

trait ResultPersister:
  def saveDiscoveryResult(runId: Long, inventory: FileInventory): IO[PersistenceError, Unit]
  def saveAnalysisResult(runId: Long, analysis: CobolAnalysis): IO[PersistenceError, Unit]
  def saveDependencyResult(runId: Long, graph: DependencyGraph): IO[PersistenceError, Unit]
  def saveTransformResult(runId: Long, project: SpringBootProject): IO[PersistenceError, Unit]
  def saveValidationResult(runId: Long, report: ValidationReport): IO[PersistenceError, Unit]

object ResultPersister:
  def saveDiscoveryResult(runId: Long, inventory: FileInventory): ZIO[ResultPersister, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ResultPersister](_.saveDiscoveryResult(runId, inventory))

  def saveAnalysisResult(runId: Long, analysis: CobolAnalysis): ZIO[ResultPersister, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ResultPersister](_.saveAnalysisResult(runId, analysis))

  def saveDependencyResult(runId: Long, graph: DependencyGraph): ZIO[ResultPersister, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ResultPersister](_.saveDependencyResult(runId, graph))

  def saveTransformResult(runId: Long, project: SpringBootProject): ZIO[ResultPersister, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ResultPersister](_.saveTransformResult(runId, project))

  def saveValidationResult(runId: Long, report: ValidationReport): ZIO[ResultPersister, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ResultPersister](_.saveValidationResult(runId, report))

  val live: ZLayer[MigrationRepository, Nothing, ResultPersister] =
    ZLayer.fromFunction(ResultPersisterLive.apply)

final case class ResultPersisterLive(
  repository: MigrationRepository
) extends ResultPersister:

  override def saveDiscoveryResult(runId: Long, inventory: FileInventory): IO[PersistenceError, Unit] =
    repository.saveFiles(
      inventory.files.map(file =>
        CobolFileRow(
          id = 0L,
          runId = runId,
          path = file.path.toString,
          name = file.name,
          fileType = toDbFileType(file.fileType),
          size = file.size,
          lineCount = file.lineCount,
          encoding = file.encoding,
          createdAt = inventory.discoveredAt,
        )
      )
    )

  override def saveAnalysisResult(runId: Long, analysis: CobolAnalysis): IO[PersistenceError, Unit] =
    for
      now    <- Clock.instant
      fileId <- resolveFileId(runId, analysis.file.path.toString, analysis.file.name)
      _      <- repository.saveAnalysis(
                  CobolAnalysisRow(
                    id = 0L,
                    runId = runId,
                    fileId = fileId,
                    analysisJson = analysis.toJson,
                    createdAt = now,
                  )
                )
    yield ()

  override def saveDependencyResult(runId: Long, graph: DependencyGraph): IO[PersistenceError, Unit] =
    repository.saveDependencies(
      graph.edges.map(edge =>
        DependencyRow(
          id = 0L,
          runId = runId,
          sourceNode = edge.from,
          targetNode = edge.to,
          edgeType = edge.edgeType.toString,
        )
      )
    )

  override def saveTransformResult(runId: Long, project: SpringBootProject): IO[PersistenceError, Unit] =
    saveArtifactJson(
      runId = runId,
      artifactPath = s"/virtual/transform/${project.projectName}.json",
      artifactName = s"${project.projectName}.json",
      payloadJson = project.toJson,
      createdAt = project.generatedAt,
    )

  override def saveValidationResult(runId: Long, report: ValidationReport): IO[PersistenceError, Unit] =
    for
      now <- Clock.instant
      _   <- saveArtifactJson(
               runId = runId,
               artifactPath = "/virtual/validation/report.json",
               artifactName = "validation-report.json",
               payloadJson = report.toJson,
               createdAt = now,
             )
    yield ()

  private def saveArtifactJson(
    runId: Long,
    artifactPath: String,
    artifactName: String,
    payloadJson: String,
    createdAt: java.time.Instant,
  ): IO[PersistenceError, Unit] =
    for
      artifactId <- ensureArtifactFile(runId, artifactPath, artifactName, createdAt)
      _          <- repository.saveAnalysis(
                      CobolAnalysisRow(
                        id = 0L,
                        runId = runId,
                        fileId = artifactId,
                        analysisJson = payloadJson,
                        createdAt = createdAt,
                      )
                    )
    yield ()

  private def ensureArtifactFile(
    runId: Long,
    path: String,
    name: String,
    createdAt: java.time.Instant,
  ): IO[PersistenceError, Long] =
    for
      files <- repository.getFilesByRun(runId)
      id    <- files.find(_.path == path).map(_.id) match
                 case Some(existingId) => ZIO.succeed(existingId)
                 case None             =>
                   for
                     _         <- repository.saveFiles(
                                    List(
                                      CobolFileRow(
                                        id = 0L,
                                        runId = runId,
                                        path = path,
                                        name = name,
                                        fileType = db.FileType.Program,
                                        size = 0L,
                                        lineCount = 0L,
                                        encoding = "UTF-8",
                                        createdAt = createdAt,
                                      )
                                    )
                                  )
                     refreshed <- repository.getFilesByRun(runId)
                     savedId   <- ZIO
                                    .fromOption(refreshed.find(_.path == path).map(_.id))
                                    .orElseFail(
                                      PersistenceError.QueryFailed(
                                        "ResultPersister.ensureArtifactFile",
                                        s"Failed to resolve created artifact file for run=$runId path=$path",
                                      )
                                    )
                   yield savedId
    yield id

  private def resolveFileId(runId: Long, path: String, name: String): IO[PersistenceError, Long] =
    for
      files <- repository.getFilesByRun(runId)
      file  <- ZIO
                 .fromOption(files.find(row => row.path == path || row.name == name))
                 .orElseFail(
                   PersistenceError.QueryFailed(
                     "ResultPersister.saveAnalysisResult",
                     s"No matching cobol_files row for run=$runId path=$path name=$name",
                   )
                 )
    yield file.id

  private def toDbFileType(fileType: DomainFileType): db.FileType = fileType match
    case DomainFileType.Program  => db.FileType.Program
    case DomainFileType.Copybook => db.FileType.Copybook
    case DomainFileType.JCL      => db.FileType.JCL
