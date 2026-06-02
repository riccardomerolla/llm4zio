package llm4zio.core

import zio.json.JsonCodec
import zio.json.ast.Json
import zio.test.*
import zio.Scope

object SchemaDerivationSpec extends ZIOSpecDefault:
  final case class Task(title: String, description: String, completed: Boolean) derives JsonCodec
  final case class Plan(epicId: String, tasks: List[Task]) derives JsonCodec
  final case class WithOpt(name: String, note: Option[String], count: Int) derives JsonCodec

  private def prop(s: Json, name: String): Option[Json] =
    s.asObject.flatMap(_.get("properties")).flatMap(_.asObject).flatMap(_.get(name))
  private def typeOf(s: Json): Option[String]           =
    s.asObject.flatMap(_.get("type")).flatMap(_.asString)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("SchemaDerivation")(
    test("derives an object schema with typed properties for a flat product") {
      val s = SchemaDerivation.derive[Task]
      assertTrue(
        typeOf(s).contains("object"),
        prop(s, "title").flatMap(typeOf).contains("string"),
        prop(s, "completed").flatMap(typeOf).contains("boolean"),
      )
    },
    test("nests array-of-object for List[CaseClass]") {
      val s     = SchemaDerivation.derive[Plan]
      val tasks = prop(s, "tasks")
      assertTrue(
        typeOf(s).contains("object"),
        tasks.flatMap(typeOf).contains("array"),
        tasks.flatMap(_.asObject).flatMap(_.get("items")).flatMap(typeOf).contains("object"),
      )
    },
    test("unwraps Option[X] to the schema of X and maps Int to integer") {
      val s = SchemaDerivation.derive[WithOpt]
      assertTrue(
        prop(s, "note").flatMap(typeOf).contains("string"),
        prop(s, "count").flatMap(typeOf).contains("integer"),
      )
    },
  )
