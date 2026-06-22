package llm4zio.eval

import zio.{ IO, ZIO }

import llm4zio.core.LlmError

/** Scores an input into an [[EvalResult]]. Contravariant in `In` so a String-based check adapts to a richer input via
  * [[contramap]]. A deterministic evaluator simply never fails (`ZIO.succeed`); an LLM judge fails as its backend does.
  */
trait Evaluator[-In]:
  def evaluate(input: In): IO[LlmError, EvalResult]

  /** View this evaluator through a projection of a wider input — e.g. an `Evaluator[String]` over `_.response`. */
  def contramap[In2](f: In2 => In): Evaluator[In2] =
    (in2: In2) => evaluate(f(in2))

object Evaluator:
  /** Run every evaluator on the same input; concatenate their dimension scores and join non-empty summaries. */
  def all[In](es: Evaluator[In]*): Evaluator[In] =
    (in: In) =>
      ZIO.foreach(es.toList)(_.evaluate(in)).map { results =>
        EvalResult(
          scores = results.flatMap(_.scores),
          summary = results.map(_.summary).filter(_.nonEmpty).mkString("; "),
        )
      }
