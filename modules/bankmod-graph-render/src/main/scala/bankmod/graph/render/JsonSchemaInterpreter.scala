package bankmod.graph.render

import bankmod.graph.model.{ Graph, Schemas }

/** Thin wrapper that renders the JSON Schema for the [[Graph]] type.
  *
  * This interpreter is stateless with respect to the concrete `Graph` instance passed: the schema is derived at the
  * type level from [[Schemas.graphSchema]]. The `render` method accepts a `Graph` to satisfy the [[GraphInterpreter]]
  * contract but ignores the runtime value.
  *
  * The schema string is memoised at object initialisation time so repeated calls are O(1).
  */
object JsonSchemaInterpreter:

  /** Pre-computed JSON Schema string for [[Graph]]. */
  val jsonSchema: String =
    Schemas.graphSchema.toJsonSchema.toJson.toString

  val interpreter: GraphInterpreter[String] = (_: Graph) => jsonSchema
