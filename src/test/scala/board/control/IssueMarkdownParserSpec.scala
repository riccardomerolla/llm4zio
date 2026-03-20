package board.control

import java.time.Instant

import zio.test.*

import board.entity.*
import shared.ids.Ids.BoardIssueId

object IssueMarkdownParserSpec extends ZIOSpecDefault:

  private val parser = IssueMarkdownParserLive()

  private val frontmatter = IssueFrontmatter(
    id = BoardIssueId("fix-auth-timeout"),
    title = "Fix authentication timeout on session refresh",
    priority = IssuePriority.High,
    assignedAgent = Some("coder-agent"),
    requiredCapabilities = List("scala", "zio", "auth"),
    blockedBy = List(BoardIssueId("infra-token-refresh"), BoardIssueId("gateway-session-api")),
    tags = List("bug", "auth"),
    acceptanceCriteria = List("Session refresh extends token by 30 min", "Expired tokens return 401"),
    estimate = Some(IssueEstimate.M),
    proofOfWork = List("Tests pass for SessionService", "No regression in auth flow"),
    transientState = TransientState.Assigned("coder-agent", Instant.parse("2026-03-20T10:10:00Z")),
    branchName = Some("agent/fix-auth-timeout"),
    failureReason = None,
    completedAt = None,
    createdAt = Instant.parse("2026-03-20T10:00:00Z"),
  )

  private val body =
    """# Fix Authentication Timeout
      |
      |## Context
      |Users report session drops after 30 minutes of inactivity...
      |""".stripMargin

  def spec: Spec[TestEnvironment, Any] = suite("IssueMarkdownParserSpec")(
    test("render/parse roundtrip preserves all frontmatter fields and body") {
      for
        raw           <- parser.render(frontmatter, body)
        parsed        <- parser.parse(raw)
        (parsedFm, md) = parsed
      yield assertTrue(parsedFm == frontmatter, md == body)
    },
    test("parse handles optional null fields") {
      val raw =
        """---
          |id: fix-auth-timeout
          |title: Fix authentication timeout on session refresh
          |priority: high
          |assignedAgent: null
          |requiredCapabilities: [scala, zio, auth]
          |blockedBy: []
          |tags: [bug, auth]
          |acceptanceCriteria: [Session refresh extends token by 30 min, Expired tokens return 401]
          |estimate: null
          |proofOfWork: [Tests pass for SessionService, No regression in auth flow]
          |transientState: none
          |branchName: null
          |failureReason: null
          |completedAt: null
          |createdAt: 2026-03-20T10:00:00Z
          |---
          |Body
          |""".stripMargin

      for
        parsed        <- parser.parse(raw)
        (parsedFm, md) = parsed
      yield assertTrue(
        parsedFm.assignedAgent.isEmpty,
        parsedFm.estimate.isEmpty,
        parsedFm.branchName.isEmpty,
        parsedFm.failureReason.isEmpty,
        parsedFm.completedAt.isEmpty,
        parsedFm.transientState == TransientState.None,
        md == "Body\n",
      )
    },
    test("updateFrontmatter rewrites yaml and preserves body") {
      for
        raw     <- parser.render(frontmatter, body)
        updated <-
          parser.updateFrontmatter(raw, _.copy(priority = IssuePriority.Critical, estimate = Some(IssueEstimate.L)))
        parsed  <- parser.parse(updated)
      yield assertTrue(
        parsed._1.priority == IssuePriority.Critical,
        parsed._1.estimate.contains(IssueEstimate.L),
        parsed._2 == body,
      )
    },
    test("parse fails gracefully on malformed input") {
      val malformed =
        """---
          |id: fix-auth-timeout
          |priority: high
          |---
          |Body
          |""".stripMargin

      for result <- parser.parse(malformed).either
      yield assertTrue(result.left.exists {
        case BoardError.ParseError(message) => message.contains("title")
        case _                              => false
      })
    },
    test("parse accepts legacy review transientState and normalizes it") {
      val raw =
        """---
          |id: fix-auth-timeout
          |title: Fix authentication timeout on session refresh
          |priority: high
          |assignedAgent: null
          |requiredCapabilities: []
          |blockedBy: []
          |tags: []
          |acceptanceCriteria: []
          |estimate: null
          |proofOfWork: []
          |transientState: review(code-agent,2026-03-20T15:22:43.644853Z)
          |branchName: null
          |failureReason: null
          |completedAt: null
          |createdAt: 2026-03-20T10:00:00Z
          |---
          |Body
          |""".stripMargin

      for
        parsed        <- parser.parse(raw)
        (parsedFm, md) = parsed
      yield assertTrue(
        parsedFm.transientState == TransientState.None,
        md == "Body\n",
      )
    },
  )
