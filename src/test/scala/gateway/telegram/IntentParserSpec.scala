package gateway.telegram

import zio.test.*

object IntentParserSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("IntentParserSpec")(
    test("routes direct analysis intent") {
      val decision = IntentParser.parse(
        message = "Please analyze this COBOL code",
        state = IntentConversationState(),
      )

      assertTrue(decision == IntentDecision.Route("cobolAnalyzer", "matched keywords for cobolAnalyzer"))
    },
    test("returns clarification when no strong intent is present") {
      val decision = IntentParser.parse(
        message = "hello there",
        state = IntentConversationState(),
      )

      assertTrue(
        decision match
          case IntentDecision.Clarify(question, options) =>
            question.nonEmpty && options.nonEmpty
          case _                                         => false
      )
    },
    test("resolves clarification by numeric selection") {
      val decision = IntentParser.parse(
        message = "2",
        state = IntentConversationState(
          pendingOptions = List("cobolAnalyzer", "dependencyMapper", "javaTransformer")
        ),
      )

      assertTrue(decision == IntentDecision.Route("dependencyMapper", "selected option 2"))
    },
  )
