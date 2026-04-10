package board.entity

import java.time.Instant

import zio.json.{ JsonCodec, JsonFieldDecoder, JsonFieldEncoder }
import zio.schema.{ Schema, derived }

import shared.ids.Ids.BoardIssueId

enum BoardColumn derives JsonCodec, Schema:
  case Backlog
  case Todo
  case InProgress
  case Review
  case Done
  case Archive

object BoardColumn:
  extension (column: BoardColumn)
    def folderName: String =
      column match
        case BoardColumn.Backlog    => "backlog"
        case BoardColumn.Todo       => "todo"
        case BoardColumn.InProgress => "in-progress"
        case BoardColumn.Review     => "review"
        case BoardColumn.Done       => "done"
        case BoardColumn.Archive    => "archive"

  def fromFolderName(value: String): Option[BoardColumn] =
    value.trim.toLowerCase match
      case "backlog"     => Some(BoardColumn.Backlog)
      case "todo"        => Some(BoardColumn.Todo)
      case "in-progress" => Some(BoardColumn.InProgress)
      case "review"      => Some(BoardColumn.Review)
      case "done"        => Some(BoardColumn.Done)
      case "archive"     => Some(BoardColumn.Archive)
      case _             => None

  given JsonFieldEncoder[BoardColumn] = JsonFieldEncoder.string.contramap(_.folderName)
  given JsonFieldDecoder[BoardColumn] =
    JsonFieldDecoder.string.mapOrFail(value => fromFolderName(value).toRight(s"Invalid board column key '$value'"))

enum TransientState derives JsonCodec, Schema:
  case None
  case Assigned(agent: String, at: Instant)
  case Merging(at: Instant)
  case Rework(reason: String, at: Instant)

enum IssuePriority derives JsonCodec, Schema:
  case Critical
  case High
  case Medium
  case Low

enum IssueEstimate derives JsonCodec, Schema:
  case XS
  case S
  case M
  case L
  case XL

final case class IssueFrontmatter(
  id: BoardIssueId,
  title: String,
  priority: IssuePriority,
  assignedAgent: Option[String],
  requiredCapabilities: List[String],
  blockedBy: List[BoardIssueId],
  tags: List[String],
  acceptanceCriteria: List[String],
  estimate: Option[IssueEstimate],
  proofOfWork: List[String],
  transientState: TransientState,
  branchName: Option[String],
  failureReason: Option[String],
  completedAt: Option[Instant],
  createdAt: Instant,
) derives JsonCodec,
    Schema

final case class BoardIssue(
  frontmatter: IssueFrontmatter,
  body: String,
  column: BoardColumn,
  directoryPath: String,
) derives JsonCodec,
    Schema

final case class Board(
  workspacePath: String,
  columns: Map[BoardColumn, List[BoardIssue]],
) derives JsonCodec,
    Schema

final case class BoardConfig(
  defaultAgent: String,
  autoDispatch: Boolean,
  ciCommand: String,
) derives JsonCodec,
    Schema
