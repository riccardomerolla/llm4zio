package shared.web

import zio.json.JsonCodec

enum FileType derives JsonCodec:
  case Program, Copybook, JCL, Unknown
