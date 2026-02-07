package core

import zio.*

import models.{ AIError, AIResponse }

trait AIService:
  def execute(prompt: String): ZIO[Any, AIError, AIResponse]

  def executeWithContext(prompt: String, context: String): ZIO[Any, AIError, AIResponse]

  def isAvailable: ZIO[Any, Nothing, Boolean]

object AIService:
  def execute(prompt: String): ZIO[AIService, AIError, AIResponse] =
    ZIO.serviceWithZIO[AIService](_.execute(prompt))

  def executeWithContext(prompt: String, context: String): ZIO[AIService, AIError, AIResponse] =
    ZIO.serviceWithZIO[AIService](_.executeWithContext(prompt, context))

  def isAvailable: ZIO[AIService, Nothing, Boolean] =
    ZIO.serviceWithZIO[AIService](_.isAvailable)
