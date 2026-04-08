package taskrun.entity

type TaskStep = shared.entity.TaskStep

given zio.json.JsonCodec[TaskStep] = shared.entity.given_JsonCodec_TaskStep
