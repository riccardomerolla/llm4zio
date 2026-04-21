package bankmod.graph.model

import zio.test.*

object ProtocolSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("Protocol")(
    test("rest smart constructor rejects invalid URL") {
      assertTrue(Protocol.rest("not-a-url").isLeft)
    },
    test("rest smart constructor accepts valid URL") {
      assertTrue(Protocol.rest("https://payments.svc/v1").isRight)
    },
    test("grpc smart constructor accepts valid URL") {
      assertTrue(Protocol.grpc("grpc://auth.svc:50051").isRight)
    },
    test("grpc smart constructor rejects URL with no TLD") {
      assertTrue(Protocol.grpc("grpc://auth-service:50051").isLeft)
    },
    test("event smart constructor accepts valid topic") {
      assertTrue(Protocol.event("payments.created").isRight)
    },
    test("event smart constructor rejects empty topic") {
      assertTrue(Protocol.event("").isLeft)
    },
    test("graphql smart constructor accepts valid URL") {
      assertTrue(Protocol.graphql("https://api.example.com/graphql").isRight)
    },
    test("graphql smart constructor rejects invalid URL") {
      assertTrue(Protocol.graphql("not-valid!!!").isLeft)
    },
    test("soap smart constructor accepts valid URL") {
      assertTrue(Protocol.soap("https://legacy.bank.com/accounts").isRight)
    },
    test("soap smart constructor rejects invalid URL") {
      assertTrue(Protocol.soap("not-a-url").isLeft)
    },
  )
