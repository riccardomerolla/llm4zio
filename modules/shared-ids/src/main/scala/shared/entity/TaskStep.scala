package shared.entity

import zio.json.JsonCodec

type TaskStep = String
given JsonCodec[TaskStep] = JsonCodec.string.asInstanceOf[JsonCodec[TaskStep]]
