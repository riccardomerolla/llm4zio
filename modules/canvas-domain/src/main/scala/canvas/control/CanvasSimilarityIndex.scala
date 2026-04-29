package canvas.control

import zio.*

import canvas.entity.{ CanvasStatus, ReasonsCanvas, ReasonsCanvasRepository }
import shared.errors.PersistenceError
import shared.ids.Ids.{ CanvasId, ProjectId }

/** Find approved Canvases similar to a query, for asset-reuse context at the start of /spdd-analysis.
  *
  * Algorithm: Jaccard similarity over normalized term sets (lowercased, alphanumerics, length >= 3, with a small
  * stop-list). Score = |intersection| / |union|; only Approved Canvases are considered. The intent is "good enough"
  * context retrieval, not semantic search — when a project grows past a few hundred Canvases, swap the implementation
  * for embeddings without changing the trait.
  */
final case class CanvasSimilarityHit(
  canvasId: CanvasId,
  projectId: ProjectId,
  title: String,
  score: Double,
  matchedTerms: List[String],
)

trait CanvasSimilarityIndex:
  def findSimilar(
    query: String,
    projectId: Option[ProjectId] = None,
    limit: Int = 5,
  ): IO[PersistenceError, List[CanvasSimilarityHit]]

object CanvasSimilarityIndex:
  def findSimilar(
    query: String,
    projectId: Option[ProjectId] = None,
    limit: Int = 5,
  ): ZIO[CanvasSimilarityIndex, PersistenceError, List[CanvasSimilarityHit]] =
    ZIO.serviceWithZIO[CanvasSimilarityIndex](_.findSimilar(query, projectId, limit))

  val live: ZLayer[ReasonsCanvasRepository, Nothing, CanvasSimilarityIndex] =
    ZLayer.fromFunction(CanvasSimilarityIndexLive.apply)

  private val stopWords: Set[String] =
    Set(
      "the", "and", "for", "are", "with", "this", "that", "from", "into", "than", "such",
      "have", "has", "had", "but", "not", "any", "all", "use", "can", "may", "should",
      "must", "will", "shall", "would", "could", "when", "what", "which", "who", "whom",
      "how", "why", "where", "after", "before", "while", "during", "over", "under", "via",
    )

  private[canvas] def tokenize(text: String): Set[String] =
    text.toLowerCase
      .split("[^a-z0-9]+")
      .iterator
      .filter(_.length >= 3)
      .filterNot(stopWords)
      .toSet

  private[canvas] def jaccard(a: Set[String], b: Set[String]): Double =
    val union = a.union(b)
    if union.isEmpty then 0.0 else a.intersect(b).size.toDouble / union.size.toDouble

  private[canvas] def canvasTerms(canvas: ReasonsCanvas): Set[String] =
    val s = canvas.sections
    tokenize(canvas.title) ++
      tokenize(s.requirements.content) ++
      tokenize(s.entities.content) ++
      tokenize(s.approach.content) ++
      tokenize(s.structure.content) ++
      tokenize(s.operations.content)

final case class CanvasSimilarityIndexLive(canvasRepo: ReasonsCanvasRepository) extends CanvasSimilarityIndex:
  override def findSimilar(
    query: String,
    projectId: Option[ProjectId],
    limit: Int,
  ): IO[PersistenceError, List[CanvasSimilarityHit]] =
    val queryTerms = CanvasSimilarityIndex.tokenize(query)
    if queryTerms.isEmpty then ZIO.succeed(Nil)
    else
      canvasRepo.list.map { canvases =>
        canvases.iterator
          .filter(_.status == CanvasStatus.Approved)
          .filter(c => projectId.forall(_ == c.projectId))
          .map { canvas =>
            val terms        = CanvasSimilarityIndex.canvasTerms(canvas)
            val score        = CanvasSimilarityIndex.jaccard(queryTerms, terms)
            val matchedTerms = queryTerms.intersect(terms).toList.sorted
            CanvasSimilarityHit(
              canvasId = canvas.id,
              projectId = canvas.projectId,
              title = canvas.title,
              score = score,
              matchedTerms = matchedTerms,
            )
          }
          .filter(_.score > 0.0)
          .toList
          .sortBy(hit => (-hit.score, hit.canvasId.value))
          .take(limit.max(0))
      }
