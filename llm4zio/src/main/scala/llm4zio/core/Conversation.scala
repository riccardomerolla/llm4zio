package llm4zio.core

import zio.*
import zio.json.*

import java.time.Instant

enum ConversationState derives JsonCodec:
  case InProgress, WaitingForTool, Completed, Failed

enum PromptRole derives JsonCodec:
  case System, User, Assistant, Tool

case class ConversationMessage(
  id: String,
  role: PromptRole,
  content: String,
  timestamp: Instant,
  tokens: Int = 0,
  model: Option[String] = None,
  costUsd: Option[Double] = None,
  metadata: Map[String, String] = Map.empty,
  important: Boolean = false,
) derives JsonCodec

object ConversationMessage:
  def fromCore(message: Message, timestamp: Instant, tokens: Int = 0, metadata: Map[String, String] = Map.empty): ConversationMessage =
    ConversationMessage(
      id = java.util.UUID.randomUUID().toString,
      role = message.role match
        case MessageRole.System    => PromptRole.System
        case MessageRole.User      => PromptRole.User
        case MessageRole.Assistant => PromptRole.Assistant
        case MessageRole.Tool      => PromptRole.Tool,
      content = message.content,
      timestamp = timestamp,
      tokens = tokens,
      metadata = metadata,
      important = message.role == MessageRole.System || message.role == MessageRole.Tool,
    )

  extension (message: ConversationMessage)
    def toCore: Message =
      Message(
        role = message.role match
          case PromptRole.System    => MessageRole.System
          case PromptRole.User      => MessageRole.User
          case PromptRole.Assistant => MessageRole.Assistant
          case PromptRole.Tool      => MessageRole.Tool,
        content = message.content,
      )

case class ConversationCheckpoint(
  id: String,
  state: ConversationState,
  messageCount: Int,
  createdAt: Instant,
  note: Option[String] = None,
) derives JsonCodec

case class ConversationThread(
  id: String,
  parentId: Option[String] = None,
  messages: Vector[ConversationMessage] = Vector.empty,
  state: ConversationState = ConversationState.InProgress,
  checkpoints: Vector[ConversationCheckpoint] = Vector.empty,
  metadata: Map[String, String] = Map.empty,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec:

  def append(message: ConversationMessage, newState: ConversationState = state): ConversationThread =
    copy(
      messages = messages :+ message,
      state = newState,
      updatedAt = message.timestamp,
    )

  def checkpoint(at: Instant, note: Option[String] = None): ConversationThread =
    val cp = ConversationCheckpoint(
      id = java.util.UUID.randomUUID().toString,
      state = state,
      messageCount = messages.length,
      createdAt = at,
      note = note,
    )

    copy(checkpoints = checkpoints :+ cp, updatedAt = at)

  def fork(newId: String, at: Instant): ConversationThread =
    copy(
      id = newId,
      parentId = Some(id),
      state = ConversationState.InProgress,
      checkpoints = Vector.empty,
      createdAt = at,
      updatedAt = at,
    )

  def exportJson: String = this.toJson

object ConversationThread:
  def create(id: String, at: Instant, metadata: Map[String, String] = Map.empty): ConversationThread =
    ConversationThread(
      id = id,
      createdAt = at,
      updatedAt = at,
      metadata = metadata,
    )

  def importJson(json: String): Either[String, ConversationThread] =
    json.fromJson[ConversationThread]

case class PromptTemplate(
  name: String,
  version: Int,
  template: String,
  description: Option[String] = None,
  tags: List[String] = List.empty,
  createdAt: Instant,
  active: Boolean = true,
) derives JsonCodec:
  def render(variables: Map[String, String]): String =
    variables.foldLeft(template) { case (acc, (key, value)) =>
      acc.replace(s"{{$key}}", value)
    }

case class PromptTemplateRef(
  name: String,
  version: Option[Int] = None,
) derives JsonCodec

trait PromptRegistry:
  def register(template: PromptTemplate): IO[String, Unit]
  def resolve(ref: PromptTemplateRef): IO[String, PromptTemplate]
  def render(ref: PromptTemplateRef, variables: Map[String, String]): IO[String, String]
  def compose(parts: List[PromptTemplateRef], variables: Map[String, String]): IO[String, String]
  def rollback(name: String, toVersion: Int): IO[String, Unit]
  def chooseVariant(name: String, variantNames: List[String], key: String): IO[String, PromptTemplateRef]
  def list(name: Option[String] = None): UIO[List[PromptTemplate]]

object PromptRegistry:
  def inMemory: UIO[PromptRegistry] =
    Ref.make(Map.empty[String, Vector[PromptTemplate]]).map(InMemoryPromptRegistry.apply)

private final case class InMemoryPromptRegistry(
  state: Ref[Map[String, Vector[PromptTemplate]]]
) extends PromptRegistry:

  override def register(template: PromptTemplate): IO[String, Unit] =
    if template.name.trim.isEmpty then ZIO.fail("Template name must be non-empty")
    else
      state.modify { current =>
        val templates = current.getOrElse(template.name, Vector.empty)
        if templates.exists(_.version == template.version) then
          (ZIO.fail(s"Template ${template.name} version ${template.version} already exists"), current)
        else
          (ZIO.unit, current.updated(template.name, (templates :+ template).sortBy(_.version)))
      }.flatten

  override def resolve(ref: PromptTemplateRef): IO[String, PromptTemplate] =
    state.get.flatMap { current =>
      val templates = current.getOrElse(ref.name, Vector.empty)
      val resolved = ref.version match
        case Some(version) => templates.find(_.version == version)
        case None          => templates.filter(_.active).sortBy(_.version).lastOption.orElse(templates.sortBy(_.version).lastOption)

      ZIO.fromOption(resolved).orElseFail(s"Template not found: ${ref.name}${ref.version.map(v => s"@$v").getOrElse("")}")
    }

  override def render(ref: PromptTemplateRef, variables: Map[String, String]): IO[String, String] =
    resolve(ref).map(_.render(variables))

  override def compose(parts: List[PromptTemplateRef], variables: Map[String, String]): IO[String, String] =
    ZIO.foreach(parts)(render(_, variables)).map(_.mkString("\n\n"))

  override def rollback(name: String, toVersion: Int): IO[String, Unit] =
    state.modify { current =>
      current.get(name) match
        case None            => (ZIO.fail(s"Template not found: $name"), current)
        case Some(templates) =>
          if !templates.exists(_.version == toVersion) then
            (ZIO.fail(s"Template $name version $toVersion not found"), current)
          else
            val updated = templates.map(template => template.copy(active = template.version == toVersion))
            (ZIO.unit, current.updated(name, updated))
    }.flatten

  override def chooseVariant(name: String, variantNames: List[String], key: String): IO[String, PromptTemplateRef] =
    if variantNames.isEmpty then ZIO.fail("Variant list cannot be empty")
    else
      val index = math.abs(s"$name:$key".hashCode) % variantNames.length
      ZIO.succeed(PromptTemplateRef(variantNames(index), None))

  override def list(name: Option[String] = None): UIO[List[PromptTemplate]] =
    state.get.map { current =>
      name match
        case Some(templateName) => current.getOrElse(templateName, Vector.empty).toList.sortBy(_.version)
        case None               => current.values.flatten.toList.sortBy(template => (template.name, template.version))
    }

trait ConversationStore:
  def save(thread: ConversationThread): IO[String, Unit]
  def load(id: String): IO[String, Option[ConversationThread]]
  def list: UIO[List[ConversationThread]]
  def delete(id: String): IO[String, Unit]

object ConversationStore:
  def inMemory: UIO[ConversationStore] =
    Ref.make(Map.empty[String, ConversationThread]).map(InMemoryConversationStore.apply)

private final case class InMemoryConversationStore(
  state: Ref[Map[String, ConversationThread]]
) extends ConversationStore:
  override def save(thread: ConversationThread): IO[String, Unit] =
    state.update(_.updated(thread.id, thread)).unit

  override def load(id: String): IO[String, Option[ConversationThread]] =
    state.get.map(_.get(id))

  override def list: UIO[List[ConversationThread]] =
    state.get.map(_.values.toList.sortBy(_.updatedAt.toEpochMilli).reverse)

  override def delete(id: String): IO[String, Unit] =
    state.modify { current =>
      if current.contains(id) then (ZIO.unit, current - id)
      else (ZIO.fail(s"Conversation not found: $id"), current)
    }.flatten
