package eval.entity

import zio.json.*

enum EvalVerdict derives JsonCodec:
  case Pass
  case Fail
