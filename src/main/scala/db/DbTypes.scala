package db

import java.time.Instant

import zio.json.*

given JsonCodec[Instant] = JsonCodec[String].transform(
  str => Instant.parse(str),
  instant => instant.toString,
)

case class DatabaseConfig(
  jdbcUrl: String
) derives JsonCodec
