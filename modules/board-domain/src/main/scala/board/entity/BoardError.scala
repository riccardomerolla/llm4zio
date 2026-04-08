package board.entity

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

enum BoardError derives JsonCodec, Schema:
  case BoardNotFound(workspacePath: String)
  case IssueNotFound(issueId: String)
  case IssueAlreadyExists(issueId: String)
  case InvalidColumn(value: String)
  case ParseError(message: String)
  case WriteError(path: String, message: String)
  case GitOperationFailed(operation: String, message: String)
  case DependencyCycle(issueIds: List[String])
  case ConcurrencyConflict(message: String)
