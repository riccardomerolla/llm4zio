package agent.boundary

import java.time.{ Duration, Instant }

import zio.test.*

import _root_.config.entity.{ AgentInfo, AgentType }
import agent.entity.api.AgentMetricsSummary
import agent.entity.{ Agent, EmployeeRole }
import config.boundary.AgentsControllerLive
import shared.ids.Ids.AgentId

object AgentsViewFilterSpec extends ZIOSpecDefault:

  // Builders ───────────────────────────────────────────────────────────────

  private val epoch: Instant = Instant.EPOCH

  private def info(name: String, displayName: String): AgentInfo =
    AgentInfo(
      name = name,
      handle = name,
      displayName = displayName,
      description = s"$name does $name things.",
      agentType = AgentType.BuiltIn,
      usesAI = false,
      tags = Nil,
    )

  private def agent(name: String, role: EmployeeRole): Agent =
    Agent(
      id = AgentId(name),
      name = name,
      description = name,
      cliTool = "claude-code",
      capabilities = Nil,
      defaultModel = None,
      systemPrompt = None,
      maxConcurrentRuns = 1,
      envVars = Map.empty,
      timeout = Duration.ofMinutes(30),
      enabled = true,
      createdAt = epoch,
      updatedAt = epoch,
      role = role,
    )

  private val zeroMetrics: AgentMetricsSummary =
    AgentMetricsSummary(0, 0, 0, 0, 0.0)

  private def card(name: String, role: Option[EmployeeRole], displayName: String): AgentsView.AgentCard =
    AgentsView.AgentCard(
      info = info(name, displayName),
      registryAgent = role.map(agent(name, _)),
      metrics = zeroMetrics,
      activeRuns = Nil,
      bindings = Nil,
    )

  private val pat   = card("pat", Some(EmployeeRole.PM), "Pat")
  private val alex  = card("alex", Some(EmployeeRole.FrontendEng), "Alex")
  private val ben   = card("ben", Some(EmployeeRole.BackendEng), "Ben")
  private val dana  = card("dana", Some(EmployeeRole.QA), "Dana")
  private val rex   = card("rex", Some(EmployeeRole.Reviewer), "Rex")
  private val planner = card("task-planner", Some(EmployeeRole.Custom), "Task Planner")
  private val search  = card("web-search-agent", None, "Web Search Agent")

  // Tests ──────────────────────────────────────────────────────────────────

  def spec: Spec[TestEnvironment, Any] = suite("AgentsView filter")(
    test("partitionByEmployeeRole separates typed-role employees from Custom/none") {
      val (employees, legacy) =
        AgentsControllerLive.partitionByEmployeeRole(List(planner, pat, search, alex, ben, dana, rex))
      assertTrue(
        employees.map(_.info.name).toSet == Set("pat", "alex", "ben", "dana", "rex"),
        legacy.map(_.info.name).toSet == Set("task-planner", "web-search-agent"),
      )
    },
    test("default view (no legacy passed) renders only employee table — no Legacy heading, no footer") {
      val html = AgentsView.list(employees = List(alex, pat, ben))
      assertTrue(
        html.contains("Pat"),
        html.contains("Alex"),
        !html.contains(">Legacy built-ins<"),
        !html.contains("legacy agents hidden"),
        !html.contains("legacy agent hidden"),
      )
    },
    test("hidden-legacy view renders footer with count and a singular/plural-aware show-all link") {
      val htmlMany = AgentsView.list(employees = List(pat), legacyHiddenCount = 3)
      val htmlOne  = AgentsView.list(employees = List(pat), legacyHiddenCount = 1)
      assertTrue(
        htmlMany.contains("3 legacy agents hidden"),
        htmlMany.contains("/agents?show=all"),
        htmlMany.contains("show all"),
        htmlOne.contains("1 legacy agent hidden"),
      )
    },
    test("show-all view renders the Legacy heading and the legacy table") {
      val html = AgentsView.list(
        employees = List(pat, alex),
        legacyVisible = List(planner, search),
        legacyHiddenCount = 0,
      )
      assertTrue(
        html.contains("Pat"),
        html.contains(">Legacy built-ins<"),
        html.contains("Task Planner"),
        html.contains("Web Search Agent"),
        !html.contains("legacy agents hidden"),
      )
    },
    test("employees render in EmployeeRole order regardless of input order") {
      val html = AgentsView.list(employees = List(rex, alex, dana, pat, ben))
      // Find each name's first byte offset; assert canonical role ordering.
      val order = List("Pat", "Alex", "Ben", "Dana", "Rex").map(name => name -> html.indexOf(s">$name<"))
      val sortedByOffset = order.sortBy(_._2).map(_._1)
      assertTrue(
        order.forall(_._2 >= 0),
        sortedByOffset == List("Pat", "Alex", "Ben", "Dana", "Rex"),
      )
    },
    test("empty input renders the empty-state CTA") {
      val html = AgentsView.list(employees = Nil)
      assertTrue(
        html.contains("No agents configured yet"),
        html.contains("Create Agent"),
      )
    },
  )
