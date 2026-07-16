package llm4zio.flow

import zio.Scope
import zio.test.*

object GhToolSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("GhTool")(
    test("prCreateArgs builds title/body args, omitting base and draft by default") {
      assertTrue(
        GhTool.prCreateArgs("My PR", "Body text", base = None, draft = false) ==
          List("pr", "create", "--title", "My PR", "--body", "Body text")
      )
    },
    test("prCreateArgs includes --base and --draft when given") {
      assertTrue(
        GhTool.prCreateArgs("T", "B", base = Some("main"), draft = true) ==
          List("pr", "create", "--title", "T", "--body", "B", "--base", "main", "--draft")
      )
    },
    test("PullRequest.fromUrl parses owner/repo/number") {
      assertTrue(
        PullRequest.fromUrl("https://github.com/acme/widgets/pull/7")
          .contains(PullRequest("acme", "widgets", 7, "https://github.com/acme/widgets/pull/7")),
        PullRequest.fromUrl("not a url").isEmpty,
      )
    },
    test("outcomeFromChecksJson derives the outcome from statusCheckRollup") {
      def rollup(items: String*) = s"""{"statusCheckRollup":[${items.mkString(",")}]}"""
      val passedRun              = """{"__typename":"CheckRun","status":"COMPLETED","conclusion":"SUCCESS"}"""
      val failedRun              = """{"__typename":"CheckRun","status":"COMPLETED","conclusion":"FAILURE"}"""
      val runningRun             = """{"__typename":"CheckRun","status":"IN_PROGRESS","conclusion":""}"""
      val okContext              = """{"__typename":"StatusContext","state":"SUCCESS"}"""
      val failContext            = """{"__typename":"StatusContext","state":"FAILURE"}"""
      val pendingContext         = """{"__typename":"StatusContext","state":"PENDING"}"""
      assertTrue(
        GhTool.outcomeFromChecksJson(rollup(passedRun, okContext)) == Right(BuildOutcome.Success),
        GhTool.outcomeFromChecksJson(rollup(passedRun, failedRun)) == Right(BuildOutcome.Failure),
        GhTool.outcomeFromChecksJson(rollup(failContext)) == Right(BuildOutcome.Failure),
        // pending wins over failure: wait for the full picture before declaring the build red
        GhTool.outcomeFromChecksJson(rollup(failedRun, runningRun)) == Right(BuildOutcome.Pending),
        GhTool.outcomeFromChecksJson(rollup(pendingContext)) == Right(BuildOutcome.Pending),
        // no checks configured → nothing to wait on
        GhTool.outcomeFromChecksJson(rollup()) == Right(BuildOutcome.Success),
        GhTool.outcomeFromChecksJson("not json").isLeft,
      )
    },
    test("arg-builders target the right repo/number") {
      val ref = IssueRef("acme", "widgets", 42)
      val pr  = PullRequest("acme", "widgets", 7, "u")
      assertTrue(
        GhTool.issueViewArgs(ref) ==
          List("issue", "view", "42", "--repo", "acme/widgets", "--json", "title,body,author"),
        GhTool.issueCommentArgs(ref, "hi") ==
          List("issue", "comment", "42", "--repo", "acme/widgets", "--body", "hi"),
        GhTool.prPatchArgs(pr, "T", "B") ==
          List("api", "--method", "PATCH", "repos/acme/widgets/pulls/7", "-f", "title=T", "-f", "body=B"),
        // a --json read: a network blip is a non-zero exit (retryable), never mistaken for a red build
        GhTool.prChecksArgs(pr) ==
          List("pr", "view", "7", "--repo", "acme/widgets", "--json", "statusCheckRollup"),
      )
    },
    test("parseIssue decodes gh's title/body/author.login JSON") {
      val json = """{"title":"Bug","body":"It breaks","author":{"login":"octocat"}}"""
      assertTrue(GhTool.parseIssue(json) == Right(Issue("Bug", "It breaks", "octocat")))
    },
    test("prViewArgs queries the current branch's PR url") {
      assertTrue(GhTool.prViewArgs == List("pr", "view", "--json", "url", "--jq", ".url"))
    },
  )
