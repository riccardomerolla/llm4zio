package taskrun.entity

import java.time.Instant

import zio.json.JsonCodec

final case class ProgressUpdate(
  runId: String,
  phase: String,
  itemsProcessed: Int,
  itemsTotal: Int,
  message: String,
  timestamp: Instant,
  status: String = "Running",
  percentComplete: Double = 0.0,
) derives JsonCodec
