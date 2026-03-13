package orchestration.control

import java.time.Instant

import issues.entity.IssueEvent

object ReworkPriority:

  final case class BoostStatus(active: Boolean, lastReworkAt: Option[Instant])

  def boostStatus(events: List[IssueEvent]): BoostStatus =
    val ordered        = events.sortBy(_.occurredAt)
    val latestReworkAt = ordered.collect { case event: IssueEvent.MovedToRework => event.occurredAt }.lastOption

    latestReworkAt match
      case None           =>
        BoostStatus(active = false, lastReworkAt = None)
      case Some(reworkAt) =>
        val latestTodoAfterRework     =
          ordered.collect {
            case event: IssueEvent.MovedToTodo if !event.occurredAt.isBefore(reworkAt) => event.occurredAt
          }.lastOption
        val manualOverrideAfterRework =
          priorityOverrideOccurredAfter(ordered, reworkAt)
        BoostStatus(
          active = latestTodoAfterRework.nonEmpty && !manualOverrideAfterRework,
          lastReworkAt = Some(reworkAt),
        )

  private def priorityOverrideOccurredAfter(events: List[IssueEvent], threshold: Instant): Boolean =
    final case class PriorityState(current: Option[String], overridden: Boolean)

    events.foldLeft(PriorityState(current = None, overridden = false)) {
      case (state, event: IssueEvent.Created)         =>
        state.copy(current = Some(event.priority.trim.toLowerCase))
      case (state, event: IssueEvent.MetadataUpdated) =>
        val nextPriority = event.priority.trim.toLowerCase
        val changed      = !event.occurredAt.isBefore(threshold) && state.current.exists(_ != nextPriority)
        PriorityState(current = Some(nextPriority), overridden = state.overridden || changed)
      case (state, _)                                 =>
        state
    }.overridden
