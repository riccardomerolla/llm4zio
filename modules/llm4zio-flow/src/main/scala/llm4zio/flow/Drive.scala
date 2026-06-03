package llm4zio.flow

import java.nio.file.Path

import zio.{ IO, Scope, ZIO }

import llm4zio.core.{ AgentSession, LlmError, SessionEvent, SessionResult }

/** Drives a live [[AgentSession]] for one turn: subscribes to the session's event stream, relays each [[SessionEvent]]
  * to the flow's [[FlowEvents]] sink (so the terminal renders the agent's thinking, tool calls, and token use live),
  * answers [[SessionEvent.AskUser]] by bridging to the human via [[Interaction]], then sends the user message and
  * awaits the turn's [[SessionResult]].
  *
  * The result comes from [[AgentSession.awaitResult]] (a race-free promise), so it is reliable even if the event relay
  * misses an early frame; the relay is best-effort display.
  */
object Drive:

  private def liftErr(e: LlmError): FlowError = FlowError.Llm(e.toString)

  /** Send `userMessage` to the session and run it to its next `Done`, relaying events and bridging questions. */
  def run(session: AgentSession, interaction: Interaction, userMessage: String)(using FlowEvents)
    : IO[FlowError, SessionResult] =
    for
      consumer <- session.events
                    .takeUntil {
                      case SessionEvent.Done(_) => true
                      case _                    => false
                    }
                    .runForeach(relay(session, interaction, _))
                    .fork
      _        <- session.sendUserMessage(userMessage).mapError(liftErr)
      result   <- session.awaitResult.mapError(liftErr)
      _        <- consumer.join.mapError(liftErr) // surfaces a failed Interaction/respondToAsk
    yield result

  private def relay(
    session: AgentSession,
    interaction: Interaction,
    event: SessionEvent,
  )(using
    events: FlowEvents
  ): IO[LlmError, Unit] =
    event match
      case SessionEvent.TextDelta(text)          => events.publish(FlowEvent.AssistantMessage(text))
      case SessionEvent.ToolUse(tool, raw, _)    => events.publish(FlowEvent.ToolUse(tool, raw))
      case SessionEvent.ToolResult(_, status, c) =>
        ZIO.when(status == "error")(events.publish(FlowEvent.Info(s"tool error: ${c.take(200)}"))).unit
      case SessionEvent.AskUser(question)        =>
        interaction.ask(question).mapError(askErr).flatMap(session.respondToAsk)
      case SessionEvent.ApprovalRequest(_, _, _) => ZIO.unit // approval gating wired in a later task
      case SessionEvent.Usage(usage, model)      => events.publish(FlowEvent.TokensUsed("coder", model, usage))
      case SessionEvent.Done(_)                  => ZIO.unit

  // Interaction failures travel the LlmError channel here so they fail the event relay (and thus the turn).
  private def askErr(e: FlowError): LlmError = LlmError.ProviderError(s"interaction failed: ${e.message}", None)

/** Like [[implementTaskLoop]], but each task is implemented by a live, steerable [[AgentSession]] (a held CLI coding
  * agent) rather than a one-shot [[Chat]]. For every incomplete task: open a scoped session, [[Drive.run]] it with the
  * task description (relaying events and answering questions through `interaction`), then hand the [[SessionResult]] to
  * `onResult` (typically a commit). Progress persists to `planPath` after each task, so a crashed run resumes.
  *
  * A fresh session per task keeps result-handling race-free (one `Done` per session) and isolates failures; cross-task
  * continuity comes from the plan and commit history, as in the autonomous loop.
  */
def implementTaskLoopLive[R, E >: FlowError](
  planPath: Path,
  plan: Plan,
  interaction: Interaction,
  openSession: Task => ZIO[Scope, E, AgentSession],
)(
  onResult: (Task, SessionResult) => ZIO[R, E, Unit]
)(using FlowEvents
): ZIO[R, E, Plan] =
  implementTaskLoop(planPath, plan) { task =>
    ZIO.scoped {
      for
        session <- openSession(task)
        result  <- Drive.run(session, interaction, task.description)
        _       <- onResult(task, result)
      yield ()
    }
  }
