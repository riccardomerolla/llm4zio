package llm4zio.flow

import java.nio.file.Path

import zio.Scope
import zio.json.*
import zio.test.*
import zio.test.magnolia.*

/** Property-based laws for the flow layer's pure surfaces: serialization round-trips, parser totality, and the
  * invariants of the marker / workspace / diagram helpers. These complement the example-based specs by exercising
  * thousands of generated shapes (unicode, empty, adversarial) the hand-written cases never reach.
  */
object FlowPropertySpec extends ZIOSpecDefault:

  // ── Generators ────────────────────────────────────────────────────────────────
  // Arbitrary values for JSON round-trips (JSON handles any string).
  private val genTask: Gen[Sized, Task]                 = DeriveGen[Task]
  private val genPlan: Gen[Sized, Plan]                 = DeriveGen[Plan]
  private val genTriage: Gen[Sized, Triage]             = DeriveGen[Triage]
  private val genTrace: Gen[Sized, TraceLine]           = DeriveGen[TraceLine]
  private val genReviewIssue: Gen[Sized, ReviewIssue]   = DeriveGen[ReviewIssue]
  private val genReviewResult: Gen[Sized, ReviewResult] = DeriveGen[ReviewResult]
  private val genPrSummary: Gen[Sized, PrSummary]       = DeriveGen[PrSummary]
  private val genVerdict: Gen[Sized, Verdict[String]]   =
    Gen.oneOf(Gen.string.map(Verdict.Proceed(_)), Gen.string.map(Verdict.Blocked(_)))

  // Constrained values for the Markdown round-trip: single-line, stripped, no `## ` / `# Brief` collisions.
  private val token                            = Gen.stringBounded(1, 10)(Gen.alphaNumericChar)
  private val oneLine                          = Gen.listOfBounded(1, 4)(token).map(_.mkString(" "))
  private val multiLine                        = Gen.listOfBounded(0, 3)(token).map(_.mkString("\n"))
  private val wellFormedTask: Gen[Sized, Task] =
    for
      title <- oneLine
      desc  <- multiLine
      done  <- Gen.boolean
    yield Task(title, desc, done)
  private val wellFormedPlan: Gen[Sized, Plan] =
    for
      epic  <- token
      tasks <- Gen.listOfBounded(0, 5)(wellFormedTask)
      brief <- Gen.option(Gen.listOfBounded(1, 3)(token).map(_.mkString("\n")))
    yield Plan(epic, tasks, brief)

  private def roundTrips[A: JsonCodec](a: A): TestResult =
    assertTrue(a.toJson.fromJson[A] == Right(a))

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("flow property laws")(
    suite("JSON round-trips (encode∘decode == id)")(
      test("Task")(check(genTask)(roundTrips(_))),
      test("Plan")(check(genPlan)(roundTrips(_))),
      test("Triage")(check(genTriage)(roundTrips(_))),
      test("TraceLine")(check(genTrace)(roundTrips(_))),
      test("ReviewIssue")(check(genReviewIssue)(roundTrips(_))),
      test("ReviewResult")(check(genReviewResult)(roundTrips(_))),
      test("PrSummary")(check(genPrSummary)(roundTrips(_))),
      test("Verdict[String]")(check(genVerdict)(roundTrips(_))),
    ),
    test("Plan Markdown render/parse round-trips for well-formed plans") {
      check(wellFormedPlan)(p => assertTrue(Plan.parse(p.render) == Right(p)))
    },
    suite("ApprovalGate")(
      test("approve is idempotent")(check(Gen.string)(s =>
        assertTrue(ApprovalGate.approve(ApprovalGate.approve(s)) == ApprovalGate.approve(s))
      )),
      test("withDraftMarker is idempotent")(
        check(Gen.string)(s =>
          assertTrue(ApprovalGate.withDraftMarker(ApprovalGate.withDraftMarker(s)) == ApprovalGate.withDraftMarker(s))
        )
      ),
      test("a fresh draft is never already approved")(
        check(Gen.alphaNumericString)(s => assertTrue(!ApprovalGate.isApproved(ApprovalGate.withDraftMarker(s))))
      ),
      test("approving a draft makes it approved")(
        check(Gen.alphaNumericString)(s =>
          assertTrue(ApprovalGate.isApproved(ApprovalGate.approve(ApprovalGate.withDraftMarker(s))))
        )
      ),
    ),
    suite("Workspace")(
      test("repoId is empty exactly when repo == workspace") {
        check(Gen.alphaNumericStringBounded(1, 8), Gen.alphaNumericStringBounded(1, 8)) { (a, b) =>
          val ws   = Path.of("/w", a)
          val repo = Path.of("/w", b)
          assertTrue(Workspace.repoId(ws, repo).isEmpty == (a == b))
        }
      },
      test("llm4zioDir always sits under <workspace>/.llm4zio") {
        check(Gen.alphaNumericStringBounded(1, 8), Gen.alphaNumericStringBounded(1, 8)) { (a, b) =>
          val ws  = Path.of("/w", a)
          val dir = Workspace.llm4zioDir(ws, Path.of("/r", b))
          assertTrue(dir.startsWith(ws.resolve(".llm4zio")))
        }
      },
    ),
    suite("Mermaid")(
      test("fromEvents never throws and always yields a flowchart") {
        check(Gen.listOf(genFlowEvent)) { events =>
          val out = Mermaid.fromEvents(events)
          assertTrue(out.startsWith("flowchart TD"))
        }
      },
      test("liveUrl base64 round-trips any diagram") {
        check(Gen.string) { d =>
          val url     = Mermaid.liveUrl(d)
          val encoded = url.substring(url.lastIndexOf('/') + 1)
          val decoded =
            new String(java.util.Base64.getUrlDecoder.decode(encoded), java.nio.charset.StandardCharsets.UTF_8)
          assertTrue(decoded == d)
        }
      },
    ),
  )

  private val genFlowEvent: Gen[Sized, FlowEvent] =
    Gen.oneOf(
      Gen.alphaNumericString.map(FlowEvent.StageStarted(_)),
      Gen.alphaNumericString.map(FlowEvent.StageCompleted(_)),
      (Gen.alphaNumericString <*> Gen.alphaNumericString).map((s, m) => FlowEvent.StageFailed(s, m)),
      Gen.alphaNumericString.map(FlowEvent.Info(_)),
    )
