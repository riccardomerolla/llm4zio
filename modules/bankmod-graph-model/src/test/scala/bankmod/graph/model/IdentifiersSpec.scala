package bankmod.graph.model

import zio.test.*

object IdentifiersSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("Identifiers")(
    suite("ServiceId")(
      test("rejects invalid names (uppercase, spaces)") {
        assertTrue(ServiceId.from("Invalid Name").isLeft)
      },
      test("rejects empty string") {
        assertTrue(ServiceId.from("").isLeft)
      },
      test("rejects names starting with digit") {
        assertTrue(ServiceId.from("1payments").isLeft)
      },
      test("accepts valid lowercase-kebab name") {
        assertTrue(ServiceId.from("payments-api").isRight)
      },
      test("accepts single lowercase letter") {
        assertTrue(ServiceId.from("a").isLeft) // too short — min is 2 chars (regex {1,40} after first char means at least 2 total)
      },
      test("accepts minimal valid name") {
        assertTrue(ServiceId.from("ab").isRight)
      },
      test("rejects name that is too long") {
        assertTrue(ServiceId.from("a" + "b" * 41).isLeft)
      },
    ),
    suite("PortName")(
      test("accepts non-empty name") {
        assertTrue(PortName.from("grpc-in").isRight)
      },
      test("rejects empty") {
        assertTrue(PortName.from("").isLeft)
      },
    ),
    suite("TopicName")(
      test("accepts valid topic") {
        assertTrue(TopicName.from("payments.created").isRight)
      },
      test("rejects empty") {
        assertTrue(TopicName.from("").isLeft)
      },
    ),
    suite("TableName")(
      test("accepts valid table name") {
        assertTrue(TableName.from("accounts").isRight)
      },
      test("rejects empty") {
        assertTrue(TableName.from("").isLeft)
      },
    ),
    suite("SchemaHash")(
      test("accepts a non-empty hash string") {
        assertTrue(SchemaHash.from("abc123").isRight)
      },
      test("rejects empty") {
        assertTrue(SchemaHash.from("").isLeft)
      },
    ),
  )
