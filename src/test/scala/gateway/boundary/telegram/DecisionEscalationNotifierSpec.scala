package gateway.boundary.telegram

import java.time.Instant

import zio.Scope
import zio.test.*

import decision.entity.*
import shared.ids.Ids.{ DecisionId, IssueId }

object DecisionEscalationNotifierSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-05-15T15:00:00Z")

  private def decisionFor(id: String, custom: List[QuickOption] = Nil): Decision =
    Decision(
      id = DecisionId(id),
      title = "Spec needs approval",
      context = "Spec body for issue-42 is ready for review",
      action = DecisionAction.ReviewIssue,
      source = DecisionSource(
        kind = DecisionSourceKind.IssueReview,
        referenceId = "issue-42",
        summary = "Review required",
        issueId = Some(IssueId("issue-42")),
      ),
      urgency = DecisionUrgency.High,
      status = DecisionStatus.Escalated,
      deadlineAt = None,
      createdAt = now,
      updatedAt = now,
      quickReplyOptions = custom,
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("DecisionEscalationNotifier")(
    test("callbackData round-trips through parseCallbackData") {
      val id     = DecisionId("decision-abc")
      val key    = "approve"
      val packed = DecisionEscalationNotifier.callbackData(id, key)
      assertTrue(
        packed == "decision:resolve:decision-abc:approve",
        DecisionEscalationNotifier.parseCallbackData(packed).contains(id -> key),
      )
    },
    test("parseCallbackData rejects payloads that aren't the resolve variant") {
      assertTrue(
        // Legacy 3-part escalate payload is handled elsewhere (InlineKeyboards)
        DecisionEscalationNotifier.parseCallbackData("decision:escalate:decision-abc").isEmpty,
        DecisionEscalationNotifier.parseCallbackData("run:run-1:cancel").isEmpty,
        DecisionEscalationNotifier.parseCallbackData("decision:resolve::approve").isEmpty,
        DecisionEscalationNotifier.parseCallbackData("not-our-callback").isEmpty,
      )
    },
    test("buildMessageText embeds title, context, reason, and quick-reply keys") {
      val decision = decisionFor("decision-1")
      val text     = DecisionEscalationNotifier.buildMessageText(decision, "deadline exceeded")
      assertTrue(
        text.contains("Spec needs approval"),
        text.contains("Spec body for issue-42 is ready for review"),
        text.contains("Reason: deadline exceeded"),
        // Default option keys present in the body so the supervisor can text-reply if needed
        text.contains("[approve]"),
        text.contains("[rework]"),
        text.contains("[ack]"),
      )
    },
    test("buildKeyboard renders one button per quick-reply option with our callback prefix") {
      val decision = decisionFor("decision-2")
      val keyboard = DecisionEscalationNotifier.buildKeyboard(decision)
      val buttons  = keyboard.toList.flatMap(_.inline_keyboard).flatten
      assertTrue(
        buttons.size == QuickOption.defaults.size,
        buttons.map(_.text) == QuickOption.defaults.map(_.label),
        buttons.flatMap(_.callback_data).forall(_.startsWith("decision:resolve:decision-2:")),
      )
    },
    test("buildKeyboard honours custom quick-reply options when set on the Decision") {
      val custom   = List(QuickOption.Approve, QuickOption.Escalate)
      val decision = decisionFor("decision-3", custom = custom)
      val keyboard = DecisionEscalationNotifier.buildKeyboard(decision)
      val buttons  = keyboard.toList.flatMap(_.inline_keyboard).flatten
      assertTrue(
        buttons.map(_.text) == List("Approve", "Escalate"),
        buttons.flatMap(_.callback_data) == List(
          "decision:resolve:decision-3:approve",
          "decision:resolve:decision-3:escalate",
        ),
      )
    },
  )
