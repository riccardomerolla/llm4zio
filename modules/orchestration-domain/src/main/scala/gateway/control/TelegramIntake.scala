package gateway.control

import zio.*

import gateway.entity.NormalizedMessage
import issues.entity.{ IssueEvent, IssueRepository }
import shared.errors.PersistenceError
import shared.ids.Ids.IssueId
import workspace.entity.WorkspaceRepository

/** Outcome of filing a free-text Telegram message as a backlog issue. */
final case class TelegramIntakeOutcome(
  issueId: IssueId,
  title: String,
  workspaceId: String,
  workspaceName: String,
)

sealed trait TelegramIntakeError
object TelegramIntakeError:
  case object NoWorkspaceConfigured                 extends TelegramIntakeError
  case object EmptyMessage                          extends TelegramIntakeError
  final case class Storage(cause: PersistenceError) extends TelegramIntakeError

/** Files free-text Telegram messages straight to the board as Backlog issues,
  * bypassing the legacy IntentParser/LLM path. The caller (GatewayService)
  * decides when to invoke this and is responsible for the user-facing reply.
  */
trait TelegramIntake:
  def fileIssue(message: NormalizedMessage): IO[TelegramIntakeError, TelegramIntakeOutcome]

object TelegramIntake:

  // The first line of the message becomes the issue title; long lines are
  // truncated with an ellipsis. 80 chars keeps board cards readable and
  // matches typical PR-title norms.
  private[control] val MaxTitleLen: Int = 80

  // Telegram caps a single message at 4096 chars; we accept a bit more
  // because callers can concatenate caption + text. Anything beyond this
  // is almost certainly noise.
  private[control] val MaxDescriptionLen: Int = 8000

  private[control] def deriveTitle(body: String): String =
    val firstLine = body.linesIterator.find(_.trim.nonEmpty).getOrElse("").trim
    if firstLine.length <= MaxTitleLen then firstLine
    else firstLine.take(MaxTitleLen - 1).stripTrailing + "…"

  private[control] def deriveDescription(body: String): String =
    if body.length <= MaxDescriptionLen then body else body.take(MaxDescriptionLen)

  val live: ZLayer[WorkspaceRepository & IssueRepository, Nothing, TelegramIntake] =
    ZLayer.fromFunction(TelegramIntakeLive.apply)

final case class TelegramIntakeLive(
  workspaceRepository: WorkspaceRepository,
  issueRepository: IssueRepository,
) extends TelegramIntake:

  override def fileIssue(message: NormalizedMessage): IO[TelegramIntakeError, TelegramIntakeOutcome] =
    for
      body       <- ZIO
                      .fromOption(Option(message.content).map(_.trim).filter(_.nonEmpty))
                      .orElseFail(TelegramIntakeError.EmptyMessage)
      title       = TelegramIntake.deriveTitle(body)
      description = TelegramIntake.deriveDescription(message.content)
      workspaces <- workspaceRepository.list.mapError(TelegramIntakeError.Storage.apply)
      workspace  <- ZIO
                      .fromOption(workspaces.sortBy(_.createdAt).reverse.headOption)
                      .orElseFail(TelegramIntakeError.NoWorkspaceConfigured)
      now        <- Clock.instant
      issueId     = IssueId.generate
      created     = IssueEvent.Created(
                      issueId = issueId,
                      title = title,
                      description = description,
                      issueType = "task",
                      priority = "normal",
                      occurredAt = now,
                    )
      backlogged  = IssueEvent.MovedToBacklog(issueId = issueId, movedAt = now, occurredAt = now)
      _          <- issueRepository.append(created).mapError(TelegramIntakeError.Storage.apply)
      _          <- issueRepository.append(backlogged).mapError(TelegramIntakeError.Storage.apply)
    yield TelegramIntakeOutcome(issueId, title, workspace.id, workspace.name)
