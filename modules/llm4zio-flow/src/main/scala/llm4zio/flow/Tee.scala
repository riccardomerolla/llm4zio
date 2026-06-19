package llm4zio.flow

import zio.*

import llm4zio.observability.StreamRecorder

/** A [[StreamRecorder]] that fans every signal out to a `primary` recorder and a string `sink`. Used to tee raw
  * provider output to the live terminal (at debug verbosity) while still recording it to the flight-recorder file. The
  * sink is a plain function, so this stays verbosity- and terminal-agnostic.
  */
final class Tee(primary: StreamRecorder, sink: String => UIO[Unit]) extends StreamRecorder:
  def rawLine(provider: String, model: Option[String], line: String): UIO[Unit] =
    primary.rawLine(provider, model, line) *> sink(s"$provider: $line")

  def streamError(provider: String, model: Option[String], message: String): UIO[Unit] =
    primary.streamError(provider, model, message) *> sink(s"$provider error: $message")
