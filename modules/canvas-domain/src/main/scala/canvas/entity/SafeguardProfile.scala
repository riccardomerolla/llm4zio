package canvas.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ ProjectId, SafeguardProfileId }

enum SafeguardSeverity derives JsonCodec, Schema:
  case Blocking
  case Warning

final case class Safeguard(
  id: String,
  category: String,
  invariant: String,
  severity: SafeguardSeverity,
) derives JsonCodec,
    Schema

final case class SafeguardProfile(
  id: SafeguardProfileId,
  projectId: ProjectId,
  name: String,
  version: Int,
  invariants: List[Safeguard],
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec,
    Schema
