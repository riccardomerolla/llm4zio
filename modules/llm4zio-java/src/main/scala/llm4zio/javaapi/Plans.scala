package llm4zio.javaapi

import llm4zio.flow.{ Plan, Planner, Task }

/** Java-facing helpers for [[Plan]] values — the transforms and accessors Scala spells with named-arg `copy`, `Option`,
  * and Scala collections, which Java can't touch cleanly.
  */
object Plans:
  /** `plan` with `brief` attached, so `plan.taskPrompt(task)` prepends it to every task (the shared codebase brief the
    * sdd/pipeline flows persist as the spec).
    */
  def withBrief(plan: Plan, brief: String): Plan = plan.copy(brief = Option(brief))

  /** The plan's brief, empty string when absent. */
  def briefOf(plan: Plan): String = plan.brief.getOrElse("")

  /** Whether `task` is the plan's first task — the tests-first gate in sdd/pipeline flows branches on this. */
  def isFirstTask(plan: Plan, task: Task): Boolean = plan.tasks.headOption.contains(task)

  /** The library's default planning instructions, for callers composing their own (e.g. sdd's tests-first suffix). */
  def defaultPlanInstructions(): String = Planner.defaultInstructions
