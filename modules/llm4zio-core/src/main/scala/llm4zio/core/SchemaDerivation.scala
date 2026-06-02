package llm4zio.core

import scala.compiletime.{ constValue, erasedValue, summonFrom }
import scala.deriving.Mirror

import zio.json.ast.Json

/** Derives an *advisory* JSON-Schema (zio-json `Json` AST) from a product type. Covers String/Int/Long/Double/ Boolean,
  * Option[X] (unwrapped), List[X]/Seq[X] (array of X), and nested products (object). Sum types and unknown leaf types
  * fall back to a permissive `{"type":"object"}`. Advisory only — the real parser is `StructuredOutputs.parseFromText`;
  * backends that enforce schema (codex `--output-schema`) consume this.
  */
object SchemaDerivation:

  inline def derive[A](using m: Mirror.Of[A]): Json =
    inline m match
      case p: Mirror.ProductOf[A] =>
        val names         = labels[p.MirroredElemLabels]
        val schemas       = elemSchemas[p.MirroredElemTypes]
        Json.Obj(
          "type"       -> Json.Str("object"),
          "properties" -> Json.Obj(names.zip(schemas)*),
        )
      case _: Mirror.SumOf[A]     => Json.Obj("type" -> Json.Str("object"))

  inline private def labels[T <: Tuple]: List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => constValue[h].asInstanceOf[String] :: labels[t]

  inline private def elemSchemas[T <: Tuple]: List[Json] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => schemaFor[h] :: elemSchemas[t]

  inline private def schemaFor[A]: Json =
    inline erasedValue[A] match
      case _: String    => prim("string")
      case _: Int       => prim("integer")
      case _: Long      => prim("integer")
      case _: Double    => prim("number")
      case _: Boolean   => prim("boolean")
      case _: Option[t] => schemaFor[t]
      case _: List[t]   => arrayOf(schemaFor[t])
      case _: Seq[t]    => arrayOf(schemaFor[t])
      case _            => deriveOrObject[A]

  inline private def deriveOrObject[A]: Json =
    summonFrom {
      case m: Mirror.Of[A] => derive[A](using m)
      case _               => Json.Obj("type" -> Json.Str("object"))
    }

  private def prim(t: String): Json      = Json.Obj("type" -> Json.Str(t))
  private def arrayOf(items: Json): Json = Json.Obj("type" -> Json.Str("array"), "items" -> items)
