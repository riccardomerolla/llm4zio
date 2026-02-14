package llm4zio.core

import zio.*
import zio.test.*

import java.time.Instant

object ConversationSpec extends ZIOSpecDefault:
  private val now = Instant.parse("2026-02-14T00:00:00Z")

  private def sampleMessage(id: String, role: PromptRole, content: String, at: Instant): ConversationMessage =
    ConversationMessage(
      id = id,
      role = role,
      content = content,
      timestamp = at,
      tokens = 10,
    )

  def spec = suite("Conversation")(
    test("thread export/import roundtrip") {
      val thread = ConversationThread
        .create("t1", now)
        .append(sampleMessage("m1", PromptRole.User, "hello", now.plusSeconds(1)))

      val json = thread.exportJson
      val decoded = ConversationThread.importJson(json)

      assertTrue(decoded.contains(thread))
    },
    test("fork creates branch preserving history and parent id") {
      val thread = ConversationThread
        .create("t1", now)
        .append(sampleMessage("m1", PromptRole.User, "hello", now.plusSeconds(1)))

      val fork = thread.fork("t2", now.plusSeconds(2))

      assertTrue(
        fork.id == "t2",
        fork.parentId.contains("t1"),
        fork.messages == thread.messages,
        fork.state == ConversationState.InProgress,
      )
    },
    test("prompt registry supports versioning, rendering, and rollback") {
      for
        registry <- PromptRegistry.inMemory
        _ <- registry.register(
               PromptTemplate(
                 name = "cobol.analyze",
                 version = 1,
                 template = "Analyze {{file}}",
                 createdAt = now,
               )
             )
        _ <- registry.register(
               PromptTemplate(
                 name = "cobol.analyze",
                 version = 2,
                 template = "Analyze file {{file}} with {{style}}",
                 createdAt = now.plusSeconds(10),
                 active = true,
               )
             )
        renderedV2 <- registry.render(PromptTemplateRef("cobol.analyze", None), Map("file" -> "A.cbl", "style" -> "strict"))
        _ <- registry.rollback("cobol.analyze", 1)
        renderedV1 <- registry.render(PromptTemplateRef("cobol.analyze", None), Map("file" -> "A.cbl"))
      yield assertTrue(
        renderedV2.contains("strict"),
        renderedV1 == "Analyze A.cbl",
      )
    },
    test("prompt composition and A/B variant selection") {
      for
        registry <- PromptRegistry.inMemory
        _ <- registry.register(PromptTemplate("base", 1, "Base {{x}}", createdAt = now))
        _ <- registry.register(PromptTemplate("rules", 1, "Rules {{y}}", createdAt = now))
        composed <- registry.compose(
                      List(PromptTemplateRef("base"), PromptTemplateRef("rules")),
                      Map("x" -> "X", "y" -> "Y"),
                    )
        variant <- registry.chooseVariant("experiment", List("base", "rules"), key = "user-123")
      yield assertTrue(
        composed.contains("Base X"),
        composed.contains("Rules Y"),
        Set("base", "rules").contains(variant.name),
      )
    },
    test("in-memory conversation store save/load/delete") {
      for
        store <- ConversationStore.inMemory
        thread = ConversationThread.create("thread-1", now)
        _ <- store.save(thread)
        loaded <- store.load("thread-1")
        _ <- store.delete("thread-1")
        missing <- store.load("thread-1")
      yield assertTrue(
        loaded.contains(thread),
        missing.isEmpty,
      )
    },
  )
