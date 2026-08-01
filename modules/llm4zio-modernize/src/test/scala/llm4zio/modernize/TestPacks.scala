package llm4zio.modernize

import llm4zio.flow.*

/** Shared `Pack` fixtures for modernize specs — one builder so a case class field added to `Pack` (e.g. `programFiles`
  * in Task 6) needs updating in one place, not once per spec.
  */
object TestPacks:

  def packWith(programFiles: Option[String]): Pack =
    Pack(
      name = "test",
      source = "cobol",
      scaffold = None,
      sources = None,
      programs = None,
      programFiles = programFiles,
      specsDir = "docs/specs",
      featuresDir = "features",
      gates = Map.empty,
      replay = None,
      equivalence = ComparisonPolicy.default,
      judgeDimensions = Nil,
      coverage = Nil,
      survey = Nil,
      prompts = Map.empty,
      lenses = Nil,
      lessons = None,
      dir = java.nio.file.Path.of("."),
    )

  val minimal: Pack = packWith(None)
