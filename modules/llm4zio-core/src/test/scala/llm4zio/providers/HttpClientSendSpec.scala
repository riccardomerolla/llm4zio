package llm4zio.providers

import zio.*
import zio.http.*
import zio.test.*

import llm4zio.core.LlmError

/** The generic `HttpClient.send` (arbitrary method + content-type + headers), the basis for the REST AdoTool. */
object HttpClientSendSpec extends ZIOSpecDefault:

  private def clientReturning(status: Status, body: String, captured: Ref[Option[Request]]): HttpClient =
    HttpClient.fromRequestExecutor(req =>
      captured.set(Some(req)).as(Response(status = status, body = Body.fromString(body)))
    )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("HttpClient.send")(
    test("issues the given method, content-type, headers and body; returns the body on 2xx") {
      for
        cap   <- Ref.make[Option[Request]](None)
        client = clientReturning(Status.Ok, """{"id":1}""", cap)
        out   <- client.send(
                   method = "PATCH",
                   url = "https://dev.azure.com/org/proj/_apis/wit/workitems/1?api-version=7.1",
                   body = Some("""[{"op":"add","path":"/fields/System.State","value":"Approved"}]"""),
                   headers = Map("Authorization" -> "Basic dG9rZW4="),
                   contentType = "application/json-patch+json",
                   timeout = 5.seconds,
                 )
        req   <- cap.get.map(_.get)
        sent  <- req.body.asString
      yield assertTrue(
        out == """{"id":1}""",
        req.method == Method.PATCH,
        req.headers.get("Content-Type").contains("application/json-patch+json"),
        req.headers.get("Authorization").contains("Basic dG9rZW4="),
        sent == """[{"op":"add","path":"/fields/System.State","value":"Approved"}]""",
      )
    },
    test("treats 201 Created as success and returns the body") {
      for
        cap   <- Ref.make[Option[Request]](None)
        client = clientReturning(Status.Created, """{"pullRequestId":42}""", cap)
        out   <- client.send("POST", "https://dev.azure.com/x", Some("{}"), Map.empty, "application/json", 5.seconds)
      yield assertTrue(out == """{"pullRequestId":42}""")
    },
    test("reliableClient config disables the zio-http idle timeout (slow local models)") {
      // zio-http's default idle timeout (50s) drops a connection while a slow local model is still
      // processing a prompt before the first response byte. We disable it; config.timeout is the real bound.
      assertTrue(HttpClient.reliableClientConfig.idleTimeout.isEmpty)
    },
    test("fails typed on 401 and on 404") {
      for
        cap  <- Ref.make[Option[Request]](None)
        e401 <- clientReturning(Status.Unauthorized, "no", cap)
                  .send("GET", "https://x", None, Map.empty, "application/json", 5.seconds)
                  .either
        e404 <- clientReturning(Status.NotFound, "missing", cap)
                  .send("GET", "https://x", None, Map.empty, "application/json", 5.seconds)
                  .either
      yield assertTrue(
        e401.left.exists(_.isInstanceOf[LlmError.AuthenticationError]),
        e404.left.exists(_.isInstanceOf[LlmError.InvalidRequestError]),
      )
    },
  )
