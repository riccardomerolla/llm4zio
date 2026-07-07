package llm4zio.javaapi

import zio.Runtime

import llm4zio.flow.{ AdoPullRequest, AdoTool, WorkItem }

/** Azure DevOps side effects, Java-shaped. `WorkItem`/`AdoPullRequest` are case classes (Java accessors: `wi.title()`,
  * `pr.webUrl()`). Obtain one inside [[JavaFlow.withAdo]]; failures throw [[Llm4zioException]].
  */
final class JavaAdo private[javaapi] (runtime: Runtime[Any], ado: AdoTool):

  /** Read a work item by id. */
  def readWorkItem(id: Int): WorkItem = Bridge.runSync(runtime, ado.readWorkItem(id))

  /** Move the work item to `state` (board column). */
  def setState(id: Int, state: String): Unit = Bridge.runSync(runtime, ado.setState(id, state))

  /** Write the work item's acceptance-criteria field. */
  def setAcceptanceCriteria(id: Int, text: String): Unit =
    Bridge.runSync(runtime, ado.setAcceptanceCriteria(id, text))

  /** Comment on the work item. */
  def comment(id: Int, text: String): Unit = Bridge.runSync(runtime, ado.comment(id, text))

  /** Open a PR from `sourceRef` to `targetRef` (bare branch names). */
  def createPr(sourceRef: String, targetRef: String, title: String, body: String): AdoPullRequest =
    Bridge.runSync(runtime, ado.createPr(sourceRef, targetRef, title, body))

  /** Link a PR to a work item, so the board shows the development link. */
  def linkPr(workItemId: Int, pr: AdoPullRequest): Unit = Bridge.runSync(runtime, ado.linkPr(workItemId, pr))
