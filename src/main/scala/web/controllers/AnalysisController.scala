package web.controllers

import zio.*
import zio.http.*

import db.*
import web.ErrorHandlingMiddleware
import web.views.HtmlViews

trait AnalysisController:
  def routes: Routes[Any, Response]

object AnalysisController:

  def routes: ZIO[AnalysisController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[AnalysisController](_.routes)

  val live: ZLayer[MigrationRepository, Nothing, AnalysisController] =
    ZLayer.fromFunction(AnalysisControllerLive.apply)

final case class AnalysisControllerLive(
  repository: MigrationRepository
) extends AnalysisController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "analysis"                    -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          runId    <- getLongQuery(req, "runId")
          files    <- repository.getFilesByRun(runId)
          analyses <- repository.getAnalysesByRun(runId)
        yield html(HtmlViews.analysisList(runId, files, analyses))
      }
    },
    Method.GET / "analysis" / long("fileId")   -> handler { (fileId: Long, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          found <- findAnalysisByFileId(fileId)
        yield html(HtmlViews.analysisDetail(found._1, found._2))
      }
    },
    Method.GET / "api" / "analysis" / "search" -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          runId <- getLongQuery(req, "runId")
          query <- getStringQuery(req, "q")
          files <- repository.getFilesByRun(runId)
          result = files.filter(_.name.toLowerCase.contains(query.toLowerCase))
        yield html(HtmlViews.analysisSearchFragment(result))
      }
    },
  )

  private def getLongQuery(req: Request, key: String): IO[PersistenceError, Long] =
    ZIO
      .fromOption(req.queryParam(key).flatMap(_.toLongOption))
      .orElseFail(PersistenceError.QueryFailed(s"query:$key", s"Missing or invalid query parameter '$key'"))

  private def getStringQuery(req: Request, key: String): IO[PersistenceError, String] =
    ZIO
      .fromOption(req.queryParam(key).map(_.trim).filter(_.nonEmpty))
      .orElseFail(PersistenceError.QueryFailed(s"query:$key", s"Missing query parameter '$key'"))

  private def findAnalysisByFileId(fileId: Long): IO[PersistenceError, (CobolFileRow, CobolAnalysisRow)] =
    for
      runs <- repository.listRuns(offset = 0, limit = 1000)
      pair <- ZIO
                .foreach(runs) { run =>
                  for
                    files    <- repository.getFilesByRun(run.id)
                    analyses <- repository.getAnalysesByRun(run.id)
                  yield files
                    .find(_.id == fileId)
                    .flatMap(file => analyses.find(_.fileId == fileId).map(analysis => (file, analysis)))
                }
                .map(_.flatten.headOption)
                .someOrFail(PersistenceError.NotFound("analysis.file", fileId))
    yield pair

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)
