package llm4zio.javaapi

import zio.Runtime

import llm4zio.flow.Chat

/** A stateful conversation with the coder, Java-shaped: [[ask]] blocks until the reply is complete, streaming tokens to
  * the flow's listeners along the way, and returns the assistant's text. Wraps a flow-layer [[Chat]].
  */
final class JavaChat private[javaapi] (runtime: Runtime[Any], private[javaapi] val underlying: Chat):

  /** Send `prompt`, append it and the reply to the running history, and return the assistant's text. */
  def ask(prompt: String): String = Bridge.runSync(runtime, underlying.ask(prompt))
