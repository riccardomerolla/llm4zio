package demo.entity

import zio.*
import zio.test.*
import zio.test.Assertion.*

object MockIssueCatalogSpec extends ZIOSpecDefault:

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("MockIssueCatalog")(
    test("sample returns exactly the requested number of issues") {
      val count  = 20
      val issues = MockIssueCatalog.sample(count)
      assertTrue(issues.size == count)
    },
    test("sample with count equal to full catalog size returns all issues") {
      val all = MockIssueCatalog.sample(Int.MaxValue)
      assertTrue(all.size == MockIssueCatalog.allIssues.size)
    },
    test("sample(1) returns exactly one issue") {
      val issues = MockIssueCatalog.sample(1)
      assertTrue(issues.size == 1)
    },
    test("sample(0) returns at least 1 issue due to minimum clamp") {
      val issues = MockIssueCatalog.sample(0)
      assertTrue(issues.size >= 1)
    },
    test("all issue IDs are unique") {
      val ids = MockIssueCatalog.allIssues.map(_.id)
      assertTrue(ids.distinct.size == ids.size)
    },
    test("blockedBy IDs reference other issues in the catalog") {
      val allIds   = MockIssueCatalog.allIssues.map(_.id).toSet
      val dangling = MockIssueCatalog.allIssues.flatMap(_.blockedBy.filterNot(allIds.contains))
      assertTrue(dangling.isEmpty)
    },
    test("sample fixes up blockedBy to only reference included issues") {
      val sampled    = MockIssueCatalog.sample(5)
      val sampledIds = sampled.map(_.id).toSet
      val dangling   = sampled.flatMap(_.blockedBy.filterNot(sampledIds.contains))
      assertTrue(dangling.isEmpty)
    },
    test("all issues have non-empty title") {
      val blank = MockIssueCatalog.allIssues.filter(_.title.isBlank)
      assertTrue(blank.isEmpty)
    },
    test("all issues have non-empty body") {
      val blank = MockIssueCatalog.allIssues.filter(_.body.isBlank)
      assertTrue(blank.isEmpty)
    },
    test("catalog contains at least 25 issues to support default demo config") {
      assertTrue(MockIssueCatalog.allIssues.size >= 25)
    },
  )
