package llm4zio.flow

import java.nio.file.Files

import zio.*
import zio.test.*

object ApprovalGateSpec extends ZIOSpecDefault:

  private def cannedInteraction(answer: String): Interaction =
    new Interaction:
      def ask(question: String): IO[FlowError, String] = ZIO.succeed(answer)

  private def tempFile(content: String): zio.UIO[java.nio.file.Path] =
    ZIO
      .attemptBlocking {
        val dir = Files.createTempDirectory("gate")
        val f   = dir.resolve("spec.md")
        Files.writeString(f, content)
        f
      }
      .orDie

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ApprovalGate markers")(
    test("isApproved is true for a flipped checkbox") {
      assertTrue(
        ApprovalGate.isApproved("- [x] Approved"),
        ApprovalGate.isApproved("- [X] Approved"),
        ApprovalGate.isApproved("# Plan: feature\n\nsome text\n\n- [x] Approved\n"),
      )
    },
    test("isApproved is false for an unchecked or absent marker") {
      assertTrue(
        !ApprovalGate.isApproved("- [ ] Approved"),
        !ApprovalGate.isApproved("# Plan: feature\n\nno marker here"),
        !ApprovalGate.isApproved(""),
      )
    },
    test("withDraftMarker appends an unchecked marker to a marker-less artifact") {
      val out = ApprovalGate.withDraftMarker("# Spec\n\nbody")
      assertTrue(out.contains(ApprovalGate.DraftMarker), !ApprovalGate.isApproved(out))
    },
    test("withDraftMarker leaves an artifact that already has a marker unchanged") {
      assertTrue(
        ApprovalGate.withDraftMarker("x\n\n- [ ] Approved\n") == "x\n\n- [ ] Approved\n",
        ApprovalGate.withDraftMarker("x\n\n- [x] Approved\n") == "x\n\n- [x] Approved\n",
      )
    },
    test("approve flips an unchecked marker to approved") {
      assertTrue(
        ApprovalGate.isApproved(ApprovalGate.approve("- [ ] Approved")),
        ApprovalGate.isApproved(ApprovalGate.approve("# Plan\n\n- [ ] Approved\n")),
      )
    },
    test("approve leaves an already-approved artifact unchanged") {
      assertTrue(ApprovalGate.approve("- [x] Approved") == "- [x] Approved")
    },
    suite("gate")(
      test("proceeds when the artifact is already approved (no interaction needed)") {
        for
          f  <- tempFile("# Spec\n\n- [x] Approved\n")
          ev <- FlowEvents.collecting
          _  <- {
            given FlowEvents = ev
            ApprovalGate.gate(f, Interaction.noninteractive)
          }
        yield assertCompletes
      },
      test("an interactive yes flips the marker on disk and proceeds") {
        for
          f     <- tempFile("# Spec\n\n- [ ] Approved\n")
          ev    <- FlowEvents.collecting
          _     <- {
            given FlowEvents = ev
            ApprovalGate.gate(f, cannedInteraction("y"))
          }
          after <- ZIO.attemptBlocking(Files.readString(f)).orDie
        yield assertTrue(ApprovalGate.isApproved(after))
      },
      test("an interactive no halts with FlowError.Aborted") {
        for
          f    <- tempFile("# Spec\n\n- [ ] Approved\n")
          ev   <- FlowEvents.collecting
          exit <- {
            given FlowEvents = ev
            ApprovalGate.gate(f, cannedInteraction("n")).exit
          }
        yield assertTrue(exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[FlowError.Aborted]))
      },
      test("non-interactive without approval halts and publishes an Aborted event") {
        for
          f    <- tempFile("# Spec\n\n- [ ] Approved\n")
          ev   <- FlowEvents.collecting
          exit <- {
            given FlowEvents = ev
            ApprovalGate.gate(f, Interaction.noninteractive).exit
          }
          rec  <- ev.recorded
        yield assertTrue(
          exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[FlowError.Aborted]),
          rec.exists {
            case _: FlowEvent.Aborted => true
            case _                    => false
          },
        )
      },
    ),
  )
