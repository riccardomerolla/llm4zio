# GitHub Copilot Agent Instructions for ZIO + Scala 3 Project

## Project Overview

You are an AI assistant specialized in developing Scala 3 projects using ZIO 2.x and Effect-Oriented Programming principles

## Core Expertise & Philosophy

### Effect-Oriented Programming Principles

**Effects as Blueprints**
- Treat ZIO effects as immutable blueprints for concurrent workflows, not running computations
- Effects describe *what* should happen, separated from *how* and *when* it executes
- Use deferred execution to enable composition, transformation, and superpowers (retry, timeout, fallback)

**Type-Safe Error Handling**
- Model all failures explicitly in the error channel: `ZIO[R, E, A]` where E represents typed errors
- Never use thrown exceptions - convert them to typed errors using `ZIO.attempt`
- Use exhaustive pattern matching with `catchAll` to handle all error cases
- Leverage compile-time guarantees - the compiler ensures all error cases are handled

**Resource Safety**
- Use `ZIO.acquireRelease` for all resource management (connections, file handles, clients)
- Leverage `Scope` for composable resource lifetimes
- Resources acquired are *always* released, even during interruption or failure
- Use `ZLayer.scoped` with `withFinalizer` for automatic cleanup

### ZIO Best Practices

**1. Effect Construction**
```scala
// For side-effecting synchronous code
ZIO.attempt(blockingCode)

// For async callbacks
ZIO.async[R, E, A] { callback =>
  // Register callback, convert to ZIO
}

// For async Future conversion
ZIO.fromFuture { implicit ec =>
  Future(computation)
}

// Never execute effects outside ZIO - wrap immediately
```

**2. Sequential Composition**
```scala
// Use for-comprehension for readable sequential effects
for {
  user <- getUserById(id)
  profile <- getProfile(user)
  _ <- saveAuditLog(user, profile)
} yield profile

// Use zipRight (>>) when discarding left result
effect1 >> effect2

// Use zipLeft (<<) when discarding right result
effect1 << effect2
```

**3. Error Handling Patterns**
```scala
// Typed error handling with exhaustive matching
effect.catchAll {
  case NetworkError(msg) => ZIO.succeed(fallbackValue)
  case ValidationError(err) => ZIO.fail(DomainError(err))
}

// Retry with exponential backoff
effect.retry(Schedule.exponential(100.millis) && Schedule.recurs(5))

// Timeout with custom error
effect.timeoutFail(TimeoutError("Operation timed out"))(30.seconds)

// Fallback to alternative
primaryEffect.orElse(secondaryEffect)
```

**4. Dependency Injection with ZLayer**
```scala
// Define service as trait
trait DatabaseService {
  def query(sql: String): ZIO[Any, DbError, ResultSet]
}

// Implement service
case class DatabaseServiceLive(pool: ConnectionPool) extends DatabaseService {
  def query(sql: String): ZIO[Any, DbError, ResultSet] = ???
}

// Create ZLayer in companion object
object DatabaseService {
  val live: ZLayer[ConnectionPool, Nothing, DatabaseService] =
    ZLayer.fromFunction(DatabaseServiceLive.apply _)
}

// Compose layers
val appLayer = DatabaseService.live ++ HttpClient.live ++ ConfigService.live

// Access service in effect
def myEffect: ZIO[DatabaseService, DbError, Result] =
  ZIO.serviceWithZIO[DatabaseService](_.query("SELECT * FROM users"))
```

**5. Concurrent Programming**
```scala
// Parallel execution with zipPar
val result = effect1.zipPar(effect2)

// Race multiple effects
effect1.race(effect2)

// Parallel collection processing
ZIO.foreachPar(users)(user => processUser(user))

// Control parallelism with bounded parallelism
ZIO.foreachPar(users)(user => processUser(user)).withParallelism(10)
```

**6. Testing Patterns**
```scala
import zio.test._
import zio.test.Assertion._

object MySpec extends ZIOSpecDefault {
  def spec = suite("MyService")(
    test("should process valid input") {
      for {
        result <- MyService.process(validInput)
      } yield assertTrue(result == expected)
    },
    test("should handle errors") {
      for {
        result <- MyService.process(invalidInput).exit
      } yield assert(result)(fails(isSubtype[ValidationError](anything)))
    }
  ).provide(MyService.test, TestDeps.layer)
}

// Use TestRandom and TestClock for deterministic tests
test("random behavior") {
  for {
    _ <- TestRandom.feedInts(1, 2, 3)
    result <- myRandomEffect
  } yield assertTrue(result == expected)
}
```

## Guidelines

### Architecture Principles

**1. Functional Core, Imperative Shell**
- Keep domain logic pure and in the core
- Push side effects to the boundaries
- Use ZIO effects to describe operations as composable values

**2. Composable Services**
- Use ZLayer for dependency injection
- Use `ZLayer.scoped` with `withFinalizer` for resource management
- Use `ZIO.serviceWithZIO` for dependency injection
- Use `ZIO.attempt` to convert exceptions to typed errors

**3. Testability**
- Use `ZIOSpecDefault` for unit tests
- Use `ZIOTest` for property-based tests
- Use `TestClock` for time-based tests
- Use `TestRandom` for random tests
- Use `TestEnvironment` for environment-based tests



## Code Quality Standards

### 1. Naming Conventions
- Effects are **verbs**: `fetchUser`, `processOrder`, `sendEmail`
- Services are **nouns**: `UserService`, `OrderRepository`, `EmailClient`
- ZLayer instances use **adjectives**: `UserService.live`, `ConfigService.test`
- Error types use **descriptive names**: `NetworkTimeout`, `InvalidSymbol`, `InsufficientFunds`

### 2. Error Handling
- **Never swallow errors** - always propagate or handle explicitly
- **Never use Exception** - define domain-specific error ADTs
- **Use typed errors** - make error types as specific as possible
- **Handle vs. Fail-fast** - handle recoverable errors, fail-fast on programming errors

```scala
// Good: Typed errors with recovery
effect.catchSome {
  case NetworkTimeout => retryWithBackoff
  case RateLimited => scheduleForLater
}

// Bad: Catching everything
effect.catchAll(_ => ZIO.succeed(defaultValue))
```

### 3. Testing
- Write **property-based tests** for business logic using `Gen` and `check`
- Use **TestClock** for time-based effects
- Use **TestRandom** for random effects
- Provide **test layers** with mock implementations
- Test **error cases** explicitly

```scala
test("order execution respects rate limits") {
  check(Gen.listOfN(100)(orderGen)) { orders =>
    for {
      start <- TestClock.currentTime(TimeUnit.MILLISECONDS)
      _ <- ZIO.foreachPar(orders)(executor.execute).withParallelism(50)
      end <- TestClock.currentTime(TimeUnit.MILLISECONDS)
      duration = end - start
    } yield assertTrue(duration >= expectedMinDuration)
  }
}.provide(OrderExecutor.test, RateLimiter.make(10))
```

### 4. Performance
- Use **Ref** for lock-free shared state
- Use **Queue** for inter-fiber communication
- Prefer **zipPar** over sequential composition when possible
- Use **bounded parallelism** to prevent resource exhaustion
- Monitor **fiber count** - avoid creating millions of fibers

### 5. Documentation
- Document **public APIs** with Scaladoc
- Include **usage examples** in documentation
- Document **error cases** and how to handle them
- Document **resource lifecycle** for scoped services

## Common Patterns

### Retry with Exponential Backoff
```scala
effect
  .retry(
    Schedule.exponential(100.millis, 2.0) &&
    Schedule.recurs(5) &&
    Schedule.recurWhile[ExecutionError] {
      case NetworkTimeout | RateLimited => true
      case _ => false
    }
  )
```

### Circuit Breaker Pattern
```scala
import nl.vroste.rezilience.CircuitBreaker

val breaker = CircuitBreaker.make(
  trippingStrategy = TrippingStrategy.failureCount(5),
  resetPolicy = Schedule.exponential(1.second)
)

breaker(effect)
```

### Request Hedging
```scala
val hedged = effect.race(
  effect.delay(p50ResponseTime)
)
```

### Graceful Shutdown
```scala
val program = for {
  fiber <- longRunningProcess.fork
  _ <- ZIO.addFinalizer(
    ZIO.logInfo("Shutting down gracefully...") *>
    fiber.interrupt *>
    cleanup
  )
} yield fiber
```

## Anti-Patterns to Avoid

### ❌ Don't Block
```scala
// BAD
Thread.sleep(1000)
Await.result(future, Duration.Inf)

// GOOD
ZIO.sleep(1.second)
ZIO.fromFuture(_ => future)
```

### ❌ Don't Use Var
```scala
// BAD
var counter = 0
ZIO.foreach(items) { item =>
  ZIO.succeed(counter += 1)
}

// GOOD
Ref.make(0).flatMap { counter =>
  ZIO.foreach(items) { item =>
    counter.update(_ + 1)
  }
}
```

### ❌ Don't Mix Side Effects
```scala
// BAD
def process: ZIO[Any, Nothing, Unit] = ZIO.succeed {
  println("Processing...")  // Side effect!
  writeToDatabase()  // Side effect!
}

// GOOD
def process: ZIO[Any, DbError, Unit] = for {
  _ <- Console.printLine("Processing...")
  _ <- database.write()
} yield ()
```

### ❌ Don't Catch and Ignore
```scala
// BAD
effect.catchAll(_ => ZIO.unit)

// GOOD
effect.catchAll {
  case recoverable: RecoverableError =>
    ZIO.logWarning(s"Recovered from: $recoverable") *>
    fallbackLogic
  case fatal: FatalError =>
    ZIO.logError(s"Fatal error: $fatal") *>
    ZIO.fail(fatal)
}
```

### ❌ Don't Create Layers Inside Effects
```scala
// BAD
def doSomething: ZIO[Any, Throwable, Unit] = {
  val layer = Service.live
  operation.provide(layer)
}

// GOOD
def doSomething: ZIO[Service, Throwable, Unit] =
  operation

// Provide at application entry point
doSomething.provide(Service.live)
```

## Final Checklist

Before committing code, ensure:

- [ ] All effects are properly typed with `R`, `E`, and `A`
- [ ] Resources are managed with `acquireRelease` or `Scope`
- [ ] Errors are domain-specific types, not `Throwable`
- [ ] No blocking operations outside `ZIO.attempt`
- [ ] No `var` or mutable state outside `Ref`/`Queue`
- [ ] Tests cover both success and failure cases
- [ ] Services are provided via `ZLayer`, not constructed directly
- [ ] Parallel operations use `zipPar`, `foreachPar`, or `race`
- [ ] Long-running fibers have proper interruption handling
- [ ] Logging uses `ZIO.log*` instead of `println`

---

**Remember**: ZIO is about building composable, type-safe, and resilient systems through effect-oriented programming. Every effect is a blueprint that can be transformed, composed, and tested independently.
