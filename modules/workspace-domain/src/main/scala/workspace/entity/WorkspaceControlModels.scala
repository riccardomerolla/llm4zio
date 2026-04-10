package workspace.entity

import zio.json.*

case class AssignRunRequest(issueRef: String, prompt: String, agentName: String) derives JsonCodec

final case class RequirementCheck(
  requirement: String,
  passed: Boolean,
  details: String,
)
