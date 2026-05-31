package triage.entity

import zio.*
import zio.json.*
import zio.test.*

import issues.entity.TicketLane

object TriageOutcomeSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TriageOutcome codec")(
    test("decodes the flat lane shape that the triage prompt asks for") {
      val raw    = """{"lane":"Custom","note":"docs-readme-update"}"""
      val parsed = raw.fromJson[TriageOutcome]
      assertTrue(
        parsed == Right(TriageOutcome.LaneAndNote(TicketLane.Custom, Some("docs-readme-update")))
      )
    },
    test("decodes the flat lane shape with title and description suggestions") {
      val raw    =
        """{"lane":"Frontend","note":"safari","titleSuggestion":"Fix Safari rendering","descriptionSuggestion":"Page breaks on iOS Safari 17 only"}"""
      val parsed = raw.fromJson[TriageOutcome]
      assertTrue(
        parsed == Right(
          TriageOutcome.LaneAndNote(
            TicketLane.Frontend,
            Some("safari"),
            Some("Fix Safari rendering"),
            Some("Page breaks on iOS Safari 17 only"),
          )
        )
      )
    },
    test("decodes the flat clarify shape") {
      val raw    = """{"clarify":"Frontend or backend?","options":["Frontend","Backend"]}"""
      val parsed = raw.fromJson[TriageOutcome]
      assertTrue(
        parsed == Right(TriageOutcome.Clarify("Frontend or backend?", List("Frontend", "Backend")))
      )
    },
    test("decodes clarify with no options") {
      val raw    = """{"clarify":"What is the deadline?"}"""
      val parsed = raw.fromJson[TriageOutcome]
      assertTrue(parsed == Right(TriageOutcome.Clarify("What is the deadline?", Nil)))
    },
    test("rejects a JSON object with neither lane nor clarify") {
      val raw    = """{"foo":"bar"}"""
      val parsed = raw.fromJson[TriageOutcome]
      assertTrue(parsed.isLeft, parsed.left.exists(_.contains("lane")), parsed.left.exists(_.contains("clarify")))
    },
    test("encoder round-trips LaneAndNote back to the flat shape (no wrapper)") {
      val outcome = TriageOutcome.LaneAndNote(TicketLane.Backend, Some("db"))
      val encoded = (outcome: TriageOutcome).toJson
      val decoded = encoded.fromJson[TriageOutcome]
      assertTrue(
        encoded.contains("\"lane\""),
        !encoded.contains("LaneAndNote"),
        decoded == Right(outcome),
      )
    },
  )
