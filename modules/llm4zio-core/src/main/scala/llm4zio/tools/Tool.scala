package llm4zio.tools

import scala.annotation.nowarn
import scala.meta.*

import zio.*
import zio.json.*
import zio.json.ast.Json

enum ToolExecutionError derives JsonCodec:
  case InvalidSchema(message: String)
  case InvalidParameters(message: String)
  case DuplicateToolName(name: String)
  case SandboxViolation(message: String)
  case ExecutionFailed(message: String)
  case SchemaGenerationFailed(message: String)

object ToolExecutionError:
  extension (error: ToolExecutionError)
    def message: String = error match
      case ToolExecutionError.InvalidSchema(msg)          => msg
      case ToolExecutionError.InvalidParameters(msg)      => msg
      case ToolExecutionError.DuplicateToolName(name)     => s"Tool already registered: $name"
      case ToolExecutionError.SandboxViolation(msg)       => msg
      case ToolExecutionError.ExecutionFailed(msg)        => msg
      case ToolExecutionError.SchemaGenerationFailed(msg) => msg

enum ToolSandbox derives JsonCodec:
  case WorkspaceReadWrite
  case WorkspaceReadOnly
  case Unrestricted

case class Tool(
  name: String,
  description: String,
  parameters: Json,
  execute: Json => IO[ToolExecutionError, Json],
  tags: Set[String] = Set.empty,
  sandbox: ToolSandbox = ToolSandbox.WorkspaceReadWrite,
)

object Tool:
  def fromMethodSignature(
    name: String,
    description: String,
    signature: String,
    execute: Json => IO[ToolExecutionError, Json],
    tags: Set[String] = Set.empty,
    sandbox: ToolSandbox = ToolSandbox.WorkspaceReadWrite,
  ): IO[ToolExecutionError, Tool] =
    ToolSchemaGenerator
      .fromMethodSignature(signature)
      .map(schema => Tool(name, description, schema, execute, tags, sandbox))

// Type alias for tools used in LlmService API
type AnyTool = Tool

case class ToolResult(
  toolCallId: String,
  result: Either[String, Json],
) derives JsonCodec

type JsonSchema = Json

object ToolSchemaGenerator:
  def fromMethodSignature(signature: String): IO[ToolExecutionError, JsonSchema] =
    ZIO
      .fromEither(parseParams(signature))
      .mapError(err => ToolExecutionError.SchemaGenerationFailed(err))
      .map(buildSchema)

  @nowarn("msg=method paramss in trait Def is deprecated")
  private def parseParams(signature: String): Either[String, List[Term.Param]] =
    given Dialect = dialects.Scala3
    signature.parse[Stat].toEither.left.map(_.message).flatMap {
      case definition: Defn.Def  => Right(definition.paramss.flatten)
      case declaration: Decl.Def => Right(declaration.paramss.flatten)
      case other                 =>
        Left(s"Unsupported signature. Expected a method declaration/definition, got: ${other.productPrefix}")
    }

  private def buildSchema(params: List[Term.Param]): JsonSchema =
    val properties = params.map { param =>
      val paramName = param.name.value
      val schema    = param.decltpe match
        case Some(tpe) => typeSchema(tpe)
        case None      => Json.Obj("type" -> Json.Str("string"))
      paramName -> schema
    }

    val required = params.collect {
      case param if param.default.isEmpty && !isOptional(param.decltpe) => Json.Str(param.name.value)
    }

    Json.Obj(
      "type"                 -> Json.Str("object"),
      "properties"           -> Json.Obj(properties*),
      "required"             -> Json.Arr(Chunk.fromIterable(required)),
      "additionalProperties" -> Json.Bool(false),
    )

  @nowarn("msg=method unapply in object Apply is deprecated")
  private def isOptional(tpe: Option[Type]): Boolean =
    tpe match
      case Some(Type.Apply(Type.Name("Option"), _)) => true
      case _                                        => false

  @nowarn("msg=method unapply in object Apply is deprecated")
  private def typeSchema(tpe: Type): Json =
    tpe match
      case Type.Name("String")                                                                                 => primitive("string")
      case Type.Name("Boolean")                                                                                => primitive("boolean")
      case Type.Name("Int") | Type.Name("Long") | Type.Name("Short") | Type.Name("Byte") | Type.Name("BigInt") =>
        primitive("integer")
      case Type.Name("Double") | Type.Name("Float") | Type.Name("BigDecimal")                                  =>
        primitive("number")
      case Type.Apply(Type.Name("Option"), List(inner))                                                        => typeSchema(inner)
      case Type.Apply(Type.Name("List"), List(inner))                                                          =>
        Json.Obj(
          "type"  -> Json.Str("array"),
          "items" -> typeSchema(inner),
        )
      case Type.Apply(Type.Name("Seq"), List(inner))                                                           =>
        Json.Obj(
          "type"  -> Json.Str("array"),
          "items" -> typeSchema(inner),
        )
      case Type.Apply(Type.Name("Vector"), List(inner))                                                        =>
        Json.Obj(
          "type"  -> Json.Str("array"),
          "items" -> typeSchema(inner),
        )
      case Type.Apply(Type.Name("Set"), List(inner))                                                           =>
        Json.Obj(
          "type"  -> Json.Str("array"),
          "items" -> typeSchema(inner),
        )
      case Type.Apply(Type.Name("Map"), List(_, valueType))                                                    =>
        Json.Obj(
          "type"                 -> Json.Str("object"),
          "additionalProperties" -> typeSchema(valueType),
        )
      case _                                                                                                   => primitive("object")

  private def primitive(jsonType: String): Json =
    Json.Obj("type" -> Json.Str(jsonType))
