package bankmod.graph.model

import zio.blocks.schema.json.JsonCodec
import zio.blocks.schema.{ Schema, SchemaError }

import bankmod.graph.model.Refinements.*

/** Central home for all `Schema[T]` instances in the bankmod graph model.
  *
  * Plain ADTs (`Port`, `Sla`, `SchemaRef`, `Edge`, `DataStore`, `Service`, `Graph`, `Protocol`, `Invariant`, `EdgeRef`,
  * and the four enums) have their schemas derived here via `Schema.derived`. Opaque types (`ServiceId`, `PortName`,
  * `TopicName`, `TableName`, `SchemaHash`) and Iron refined types (`UrlLikeR`, `LatencyMsR`, `PercentageR`,
  * `BoundedRetriesR`, `SemVerR`) use explicit `Schema.transform` so that codec-library replacement remains a one-file
  * change.
  *
  * Importing `Schemas.given` (or `import bankmod.graph.model.Schemas.*`) brings all instances into scope.
  *
  * ===Iron refined Int types and zio-blocks-schema macro===
  *
  * `Schema.derived` on a case class whose fields are Iron-refined Int types (`T :| C = IronType[T, C]`) produces broken
  * schemas. The zio-blocks-schema macro recognises `IronType` as an opaque type and synthesises a `Reflect.Wrapper`
  * around the underlying primitive schema. This wrapper uses object-register slots while the inner `Schema[Int]` uses
  * byte-register slots, so the deconstructor stores the value in the wrong slot and every field encodes as 0.
  *
  * Workaround (Approach B): for `Sla`, define a private raw proxy case class with plain `Int` fields, derive its schema
  * (trivially correct), then `Schema.transform` the result to `Sla` / back. Field names are preserved because the proxy
  * uses the same field names. The smart constructors validate on decode.
  */
object Schemas:

  // ── Opaque identifier schemas ─────────────────────────────────────────────
  // Each uses Schema[String].transform(from, to) — no raw wrapping.
  //
  // NB: zio-blocks-schema 0.0.33 exposes only a pure `transform(A => B, B => A)` —
  // there is no `transformOrFail`. On decode we must signal validation failure by
  // throwing `SchemaError`; the codec layer catches it and returns `Left[SchemaError]`
  // to the caller. So the error IS encoded in the public return type — the throw is
  // a library-mandated internal escape hatch, not an exception leaking to business code.
  // scalafix:off DisableSyntax.throw

  given schemaServiceId: Schema[ServiceId] =
    Schema[String].transform(
      to = s =>
        ServiceId.from(s) match
          case Right(id) => id
          case Left(err) => throw SchemaError.validationFailed(err),
      from = id => id.value,
    )

  given schemaPortName: Schema[PortName] =
    Schema[String].transform(
      to = s =>
        PortName.from(s) match
          case Right(p) => p
          case Left(e)  => throw SchemaError.validationFailed(e),
      from = p => p.value,
    )

  given schemaTopicName: Schema[TopicName] =
    Schema[String].transform(
      to = s =>
        TopicName.from(s) match
          case Right(t) => t
          case Left(e)  => throw SchemaError.validationFailed(e),
      from = t => t.value,
    )

  given schemaTableName: Schema[TableName] =
    Schema[String].transform(
      to = s =>
        TableName.from(s) match
          case Right(t) => t
          case Left(e)  => throw SchemaError.validationFailed(e),
      from = t => t.value,
    )

  given schemaSchemaHash: Schema[SchemaHash] =
    Schema[String].transform(
      to = s =>
        SchemaHash.from(s) match
          case Right(h) => h
          case Left(e)  => throw SchemaError.validationFailed(e),
      from = h => h.value,
    )

  // ── Iron refined-type schemas ─────────────────────────────────────────────
  // Iron types are opaque subtypes; we validate on decode via refineEither.

  given schemaUrlLike: Schema[UrlLikeR] =
    Schema[String].transform(
      to = s =>
        Refinements.UrlLike.from(s) match
          case Right(u) => u
          case Left(e)  => throw SchemaError.validationFailed(e),
      from = (u: UrlLikeR) => u: String,
    )

  given schemaLatencyMs: Schema[LatencyMsR] =
    Schema[Int].transform(
      to = n =>
        Refinements.LatencyMs.from(n) match
          case Right(l) => l
          case Left(e)  => throw SchemaError.validationFailed(e),
      from = (l: LatencyMsR) => l: Int,
    )

  given schemaPercentage: Schema[PercentageR] =
    Schema[Int].transform(
      to = n =>
        Refinements.Percentage.from(n) match
          case Right(p) => p
          case Left(e)  => throw SchemaError.validationFailed(e),
      from = (p: PercentageR) => p: Int,
    )

  given schemaBoundedRetries: Schema[BoundedRetriesR] =
    Schema[Int].transform(
      to = n =>
        Refinements.BoundedRetries.from(n) match
          case Right(r) => r
          case Left(e)  => throw SchemaError.validationFailed(e),
      from = (r: BoundedRetriesR) => r: Int,
    )

  given schemaSemVer: Schema[SemVerR] =
    Schema[String].transform(
      to = s =>
        Refinements.SemVer.from(s) match
          case Right(v) => v
          case Left(e)  => throw SchemaError.validationFailed(e),
      from = (v: SemVerR) => v: String,
    )

  // ── Plain enum schemas ────────────────────────────────────────────────────

  given schemaCriticality: Schema[Criticality]               = Schema.derived
  given schemaConsistency: Schema[Consistency]               = Schema.derived
  given schemaOrdering: Schema[bankmod.graph.model.Ordering] = Schema.derived
  given schemaOwnership: Schema[Ownership]                   = Schema.derived

  // ── Protocol (sealed trait) ───────────────────────────────────────────────

  given schemaProtocolRest: Schema[Protocol.Rest]       = Schema.derived
  given schemaProtocolGrpc: Schema[Protocol.Grpc]       = Schema.derived
  given schemaProtocolEvent: Schema[Protocol.Event]     = Schema.derived
  given schemaProtocolGraphql: Schema[Protocol.Graphql] = Schema.derived
  given schemaProtocolSoap: Schema[Protocol.Soap]       = Schema.derived
  given schemaProtocol: Schema[Protocol]                = Schema.derived

  // ── Service ADT schemas ───────────────────────────────────────────────────

  given schemaPort: Schema[Port] = Schema.derived

  // Sla fields are Iron-refined Ints. Schema.derived bypasses the custom Iron-type givens and generates broken
  // wrapper schemas (register-slot mismatch → all fields encode as 0). Fix: derive via plain-Int proxy and
  // transform. The proxy shares the same field names so JSON encoding is identical.
  private case class SlaEncoded(latencyP99Ms: Int, availabilityPct: Int, maxRetries: Int)
  private given Schema[SlaEncoded] = Schema.derived

  given schemaSla: Schema[Sla] =
    summon[Schema[SlaEncoded]].transform(
      to = raw =>
        Sla(
          latencyP99Ms =
            Refinements.LatencyMs.from(raw.latencyP99Ms).fold(e => throw SchemaError.validationFailed(e), identity),
          availabilityPct =
            Refinements.Percentage.from(raw.availabilityPct).fold(e => throw SchemaError.validationFailed(e), identity),
          maxRetries =
            Refinements.BoundedRetries.from(raw.maxRetries).fold(e => throw SchemaError.validationFailed(e), identity),
        ),
      from = sla =>
        SlaEncoded(
          latencyP99Ms = sla.latencyP99Ms: Int,
          availabilityPct = sla.availabilityPct: Int,
          maxRetries = sla.maxRetries: Int,
        ),
    )
  // scalafix:on DisableSyntax.throw

  given schemaSchemaRef: Schema[SchemaRef] = Schema.derived
  given schemaEdgeRef: Schema[EdgeRef]     = Schema.derived
  given schemaEdge: Schema[Edge]           = Schema.derived
  given schemaDataStore: Schema[DataStore] = Schema.derived
  given schemaService: Schema[Service]     = Schema.derived

  given schemaGraph: Schema[Graph] = Schema.derived

  // ── Invariant schema ──────────────────────────────────────────────────────

  given schemaInvariant: Schema[Invariant] = Schema.derived

  // ── Top-level convenience accessors ──────────────────────────────────────

  val graphSchema: Schema[Graph] = schemaGraph

  val graphCodec: JsonCodec[Graph] = graphSchema.jsonCodec
