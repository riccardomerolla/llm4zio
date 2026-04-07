package shared.errors

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

enum PersistenceError derives JsonCodec, Schema:
  case StoreUnavailable(message: String)
  case QueryFailed(operation: String, cause: String)
  case NotFound(entity: String, id: String)
  case SerializationFailed(entity: String, cause: String)
