package llm4zio.flow

import zio.Scope
import zio.test.*

/** Pure Azure DevOps REST request-builders + response parsers (the parts of [[AdoTool]] that don't do I/O). */
object AdoToolSpec extends ZIOSpecDefault:

  private val cfg = AdoConfig(
    orgUrl = "https://dev.azure.com/acme",
    project = "Widgets",
    repository = "widgets-repo",
    pat = "tok",
  )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("AdoTool")(
    test("authHeader is HTTP Basic of ':<pat>'") {
      // base64(":tok") = "OnRvaw=="
      assertTrue(cfg.authHeader == ("Authorization", "Basic OnRvaw=="))
    },
    test("readWorkItemReq targets the work item with relations expanded") {
      val r = cfg.readWorkItemReq(42)
      assertTrue(
        r.method == "GET",
        r.url == "https://dev.azure.com/acme/Widgets/_apis/wit/workitems/42?$expand=relations&api-version=7.1",
        r.body.isEmpty,
      )
    },
    test("wiqlReq posts a WIQL query") {
      val r = cfg.wiqlReq("SELECT [System.Id] FROM workitems WHERE [System.State]='Refine'")
      assertTrue(
        r.method == "POST",
        r.url == "https://dev.azure.com/acme/Widgets/_apis/wit/wiql?api-version=7.1",
        r.body.exists(_.contains("[System.State]='Refine'")),
      )
    },
    test("setFieldsReq is a json-patch PATCH with one op per field") {
      val r = cfg.setFieldsReq(7, Map("System.State" -> "Approved"))
      assertTrue(
        r.method == "PATCH",
        r.url == "https://dev.azure.com/acme/Widgets/_apis/wit/workitems/7?api-version=7.1",
        r.contentType == "application/json-patch+json",
        r.body.exists(b => b.contains("\"op\":\"add\"") && b.contains("/fields/System.State") && b.contains("Approved")),
      )
    },
    test("commentReq posts to the work item discussion") {
      val r = cfg.commentReq(7, "spec drafted — please review")
      assertTrue(
        r.method == "POST",
        r.url == "https://dev.azure.com/acme/Widgets/_apis/wit/workItems/7/comments?api-version=7.1-preview.4",
        r.body.exists(_.contains("spec drafted")),
      )
    },
    test("createWorkItemReq is a json-patch POST to the typed create endpoint") {
      val r = cfg.createWorkItemReq(
        "Task",
        "Port fee calculation",
        Map("System.Description" -> "R5-R7", "Microsoft.VSTS.Common.AcceptanceCriteria" -> "AC1"),
      )
      assertTrue(
        r.method == "POST",
        r.url == "https://dev.azure.com/acme/Widgets/_apis/wit/workitems/$Task?api-version=7.1",
        r.contentType == "application/json-patch+json",
        r.body.exists(b =>
          b.contains("/fields/System.Title") && b.contains("Port fee calculation") &&
          b.contains("/fields/System.Description") && b.contains("R5-R7")
        ),
      )
    },
    test("createPrReq posts source/target refs, title and body") {
      val r = cfg.createPrReq("refs/heads/llm4zio/wi-7", "refs/heads/main", "Title", "Body")
      assertTrue(
        r.method == "POST",
        r.url == "https://dev.azure.com/acme/Widgets/_apis/git/repositories/widgets-repo/pullrequests?api-version=7.1",
        r.body.exists(b =>
          b.contains("refs/heads/llm4zio/wi-7") && b.contains("refs/heads/main") && b.contains("Title")
        ),
      )
    },
    test("linkPrReq adds an ArtifactLink relation with the vstfs PR artifact url") {
      val pr = AdoPullRequest(id = 9, repoId = "repo-guid", projectId = "proj-guid", webUrl = "http://x/pr/9")
      val r  = cfg.linkPrReq(7, pr)
      assertTrue(
        r.method == "PATCH",
        r.contentType == "application/json-patch+json",
        r.body.exists(b =>
          b.contains("ArtifactLink") &&
          b.contains("vstfs:///Git/PullRequestId/proj-guid%2Frepo-guid%2F9")
        ),
      )
    },
    test("parseWorkItem reads title, description, acceptance criteria, state and tags") {
      val json =
        """{"id":7,"fields":{"System.Title":"Add multiply","System.Description":"do it",
          |"Microsoft.VSTS.Common.AcceptanceCriteria":"AC1; AC2","System.State":"Refine",
          |"System.Tags":"alpha; llm4zio-claimed"}}""".stripMargin
      val wi   = AdoTool.parseWorkItem(json)
      assertTrue(
        wi.map(_.id).contains(7),
        wi.map(_.title).contains("Add multiply"),
        wi.map(_.acceptanceCriteria).contains("AC1; AC2"),
        wi.map(_.state).contains("Refine"),
        wi.map(_.tags).contains(List("alpha", "llm4zio-claimed")),
      )
    },
    test("parseWiqlIds extracts the work-item ids") {
      val json = """{"workItems":[{"id":7},{"id":9}]}"""
      assertTrue(AdoTool.parseWiqlIds(json) == Right(List(7, 9)))
    },
    test("parsePr reads pullRequestId, repository id and project id") {
      val json = """{"pullRequestId":9,"repository":{"id":"repo-guid","project":{"id":"proj-guid"}}}"""
      val pr   = AdoTool.parsePr(json, cfg)
      assertTrue(
        pr.map(_.id).contains(9),
        pr.map(_.repoId).contains("repo-guid"),
        pr.map(_.projectId).contains("proj-guid"),
        pr.map(_.webUrl).contains("https://dev.azure.com/acme/Widgets/_git/widgets-repo/pullrequest/9"),
      )
    },
  )
