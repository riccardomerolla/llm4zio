package gateway.telegram

import zio.*

enum TelegramClientError:
  case InvalidConfig(reason: String)
  case InvalidResponse(message: String)
  case ParseError(message: String, raw: String)
  case RateLimited(retryAfter: Option[Duration], message: String)
  case Timeout(duration: Duration)
  case Network(message: String)
  case ApiError(code: Int, message: String)
