package bankmod.graph.render

import bankmod.graph.model.Graph

/** Typeclass for interpreting a [[Graph]] into some output format.
  *
  * Each interpreter provides a named `val` instance (rather than a `given`) so that multiple `GraphInterpreter[String]`
  * instances can coexist without ambiguity.
  *
  * Usage:
  * {{{
  * import bankmod.graph.render.MermaidInterpreter
  * val mmd: String = MermaidInterpreter.interpreter.render(myGraph)
  * }}}
  */
trait GraphInterpreter[Out]:
  def render(g: Graph): Out

object GraphInterpreter:
  def apply[Out](using gi: GraphInterpreter[Out]): GraphInterpreter[Out] = gi
