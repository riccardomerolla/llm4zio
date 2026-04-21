package shared.web

import zio.json.*
import zio.test.*

import _root_.config.entity.{ AgentInfo, AgentType, WorkflowDefinition }
import taskrun.boundary.WorkflowsView

object WorkflowsViewSpec extends ZIOSpecDefault:
  final private case class RenderedAgent(name: String, display: String) derives JsonDecoder

  private val workflow = WorkflowDefinition(
    name = "wf",
    steps = Nil,
    isBuiltin = false,
  )

  private val agentWithEscapes = AgentInfo(
    name = "ops-agent",
    handle = "ops-agent",
    displayName = "Ops \"Primary\"\nAgent",
    description = "handles ops",
    agentType = AgentType.Custom,
    usesAI = true,
    tags = List("ops"),
  )

  def spec: Spec[TestEnvironment, Any] = suite("WorkflowsViewSpec")(
    test("workflow form embeds available agents as valid JSON") {
      val html   = WorkflowsView.form(
        title = "Create Workflow",
        formAction = "/workflows",
        workflow = workflow,
        availableAgents = List(agentWithEscapes),
      )
      val marker = "var availableAgents = "
      val json   = html.split(marker, 2).lift(1).map(_.takeWhile(_ != ';').trim)
      val parsed = json.flatMap(_.fromJson[List[RenderedAgent]].toOption)

      assertTrue(
        parsed.contains(List(RenderedAgent("ops-agent", "Ops \"Primary\"\nAgent"))),
        html.contains("JSON.stringify(String(value)).slice(1, -1)"),
      )
    }
  )
