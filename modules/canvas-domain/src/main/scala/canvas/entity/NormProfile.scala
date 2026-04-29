package canvas.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ NormProfileId, ProjectId }

final case class NormRule(
  id: String,
  category: String,
  rule: String,
) derives JsonCodec,
    Schema

final case class NormProfile(
  id: NormProfileId,
  projectId: ProjectId,
  name: String,
  version: Int,
  rules: List[NormRule],
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec,
    Schema
