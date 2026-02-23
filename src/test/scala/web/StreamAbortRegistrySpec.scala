package shared.web

import zio.*
import zio.test.*

object StreamAbortRegistrySpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("StreamAbortRegistrySpec")(
    test("register and abort executes cancel effect") {
      for
        registry     <- Ref.make(Map.empty[Long, UIO[Unit]]).map(StreamAbortRegistryLive.apply)
        cancelled    <- Ref.make(false)
        _            <- registry.register(1L, cancelled.set(true))
        aborted      <- registry.abort(1L)
        wasCancelled <- cancelled.get
      yield assertTrue(aborted, wasCancelled)
    },
    test("abort returns false when no active stream") {
      for
        registry <- Ref.make(Map.empty[Long, UIO[Unit]]).map(StreamAbortRegistryLive.apply)
        aborted  <- registry.abort(999L)
      yield assertTrue(!aborted)
    },
    test("unregister removes cancel handle") {
      for
        registry     <- Ref.make(Map.empty[Long, UIO[Unit]]).map(StreamAbortRegistryLive.apply)
        cancelled    <- Ref.make(false)
        _            <- registry.register(1L, cancelled.set(true))
        _            <- registry.unregister(1L)
        aborted      <- registry.abort(1L)
        wasCancelled <- cancelled.get
      yield assertTrue(!aborted, !wasCancelled)
    },
    test("abort removes handle so second abort returns false") {
      for
        registry <- Ref.make(Map.empty[Long, UIO[Unit]]).map(StreamAbortRegistryLive.apply)
        _        <- registry.register(1L, ZIO.unit)
        first    <- registry.abort(1L)
        second   <- registry.abort(1L)
      yield assertTrue(first, !second)
    },
  )
