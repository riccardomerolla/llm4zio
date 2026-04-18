package bankmod.graph.model

import Refinements.*

/** Communication protocol carried on an edge in the service graph.
  *
  * Each variant carries typed fields built from validated refinements. The companion object provides smart constructors
  * that accept raw strings and return `Either[String, Protocol]`.
  */
sealed trait Protocol

object Protocol:

  final case class Rest(baseUrl: UrlLikeR)       extends Protocol
  final case class Grpc(endpoint: UrlLikeR)      extends Protocol
  final case class Event(topic: TopicName)        extends Protocol
  final case class Graphql(endpoint: UrlLikeR)   extends Protocol

  // ── Smart constructors ────────────────────────────────────────────────────

  def rest(baseUrl: String): Either[String, Protocol] =
    UrlLike.from(baseUrl).map(Rest.apply)

  def grpc(endpoint: String): Either[String, Protocol] =
    UrlLike.from(endpoint).map(Grpc.apply)

  def event(topic: String): Either[String, Protocol] =
    TopicName.from(topic).map(Event.apply)

  def graphql(endpoint: String): Either[String, Protocol] =
    UrlLike.from(endpoint).map(Graphql.apply)
