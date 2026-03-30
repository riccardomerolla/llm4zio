package workspace.entity

import java.time.Instant

import zio.*
import zio.schema.*
import zio.schema.codec.JsonCodec
import zio.test.*

/** Regression test: verifies that RunMode.Host survives a ZIO Schema JSON codec round-trip and still matches in a Scala
  * pattern match. This is the exact codec path used by SchemaBinaryCodec inside WorkspaceRepositoryES.
  */
object RunModeSchemaRoundTripSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("RunModeSchemaRoundTripSpec")(
    test("RunMode.Host round-trips via ZIO Schema JsonCodec and matches in pattern match") {
      val schema  = summon[Schema[RunMode]]
      val codec   = JsonCodec.jsonCodec(schema)
      val host    = RunMode.Host
      val json    = codec.encodeJson(host, None).toString
      val decoded = codec.decodeJson(json)
      assertTrue(
        decoded.isRight,
        decoded.exists(_ == RunMode.Host),
        decoded.exists {
          case RunMode.Host => true
          case _            => false
        },
      )
    },
    test("WorkspaceEvent.Created with RunMode.Host round-trips via ZIO Schema JsonCodec") {
      val evSchema = summon[Schema[WorkspaceEvent]]
      val codec    = JsonCodec.jsonCodec(evSchema)
      val now      = Instant.parse("2026-02-27T10:00:00Z")
      val ev       = WorkspaceEvent.Created(
        workspaceId = "ws-1",
        name = "my-api",
        localPath = "/tmp/my-api",
        defaultAgent = Some("gemini"),
        description = None,
        cliTool = "gemini",
        runMode = RunMode.Host,
        occurredAt = now,
      )
      val json     = codec.encodeJson(ev, None).toString
      val decoded  = codec.decodeJson(json)
      assertTrue(
        decoded.isRight,
        decoded.exists {
          case WorkspaceEvent.Created(_, _, _, _, _, _, runMode, _) =>
            runMode match
              case RunMode.Host => true
              case _            => false
          case _                                                    => false
        },
      )
    },
    test("RunMode.Host pattern match via equals works") {
      // Verify the bytecode-level check: RunMode$Host$.MODULE$.equals(x)
      val decoded: RunMode = RunMode.Host
      val result           = decoded match
        case RunMode.Host => "matched"
        case _            => "failed"
      assertTrue(result == "matched")
    },
    test("WorkspaceEvent.DefaultBranchChanged round-trips via ZIO Schema JsonCodec") {
      val schema  = summon[Schema[WorkspaceEvent]]
      val codec   = JsonCodec.jsonCodec(schema)
      val event   = WorkspaceEvent.DefaultBranchChanged(
        workspaceId = "ws-1",
        defaultBranch = "develop",
        occurredAt = Instant.parse("2026-03-30T08:00:00Z"),
      )
      val json    = codec.encodeJson(event, None).toString
      val decoded = codec.decodeJson(json)
      assertTrue(decoded == Right(event))
    },
    test("RunMode.Cloud round-trips via ZIO Schema JsonCodec") {
      val schema  = summon[Schema[RunMode]]
      val codec   = JsonCodec.jsonCodec(schema)
      val cloud   = RunMode.Cloud(
        provider = "aws-fargate",
        image = "ghcr.io/riccardomerolla/llm4zio-agent:latest",
        region = Some("eu-west-1"),
        network = Some("none"),
      )
      val json    = codec.encodeJson(cloud, None).toString
      val decoded = codec.decodeJson(json)
      assertTrue(decoded == Right(cloud))
    },
  )
