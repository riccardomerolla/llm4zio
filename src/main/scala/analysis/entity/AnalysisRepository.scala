package analysis.entity

import zio.*

import shared.errors.PersistenceError
import shared.ids.Ids.AnalysisDocId

trait AnalysisRepository:
  def append(event: AnalysisEvent): IO[PersistenceError, Unit]
  def get(id: AnalysisDocId): IO[PersistenceError, AnalysisDoc]
  def listByWorkspace(workspaceId: String): IO[PersistenceError, List[AnalysisDoc]]
  def listByType(analysisType: AnalysisType): IO[PersistenceError, List[AnalysisDoc]]

object AnalysisRepository:
  def append(event: AnalysisEvent): ZIO[AnalysisRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[AnalysisRepository](_.append(event))

  def get(id: AnalysisDocId): ZIO[AnalysisRepository, PersistenceError, AnalysisDoc] =
    ZIO.serviceWithZIO[AnalysisRepository](_.get(id))

  def listByWorkspace(workspaceId: String): ZIO[AnalysisRepository, PersistenceError, List[AnalysisDoc]] =
    ZIO.serviceWithZIO[AnalysisRepository](_.listByWorkspace(workspaceId))

  def listByType(analysisType: AnalysisType): ZIO[AnalysisRepository, PersistenceError, List[AnalysisDoc]] =
    ZIO.serviceWithZIO[AnalysisRepository](_.listByType(analysisType))
