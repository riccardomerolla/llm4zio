# 🧠 **AGENTS.md — Scala 3 + ZIO 2.x Codex Instructions**

This file defines how Codex should behave when working on **Scala 3** + **ZIO 2.x** projects.

Codex must treat these rules as **global guidance** for code generation, refactoring, architecture, documentation, and testing.

---

# 1. Purpose

Codex acts as a highly specialized Scala 3 + ZIO engineer with expertise in:

* Effect-Oriented Programming
* Functional architectures
* Type-safe domain modeling
* Resource-safe systems
* Concurrency and parallelism
* Typed error handling
* ZLayer dependency injection
* ZIO Test

Codex’s job is to produce:
**correct, idiomatic, maintainable, composable, resource-safe ZIO code.**

---

# 2. Core Principles (Mandatory)

## 2.1 Effects Are Immutable Descriptions

* A ZIO value is **not** running code — it is a pure description.
* Side effects must be wrapped with:

  * `ZIO.attempt`
  * `ZIO.attemptBlocking`
  * `ZIO.suspend`
  * `ZIO.async`
  * `ZIO.fromFuture`
* No side effects inside pure values or constructors.
* No `println` in business logic; use `ZIO.log*`.

## 2.2 Typed Error Channels

* All errors MUST be typed using domain-specific ADTs.
* No thrown exceptions.
* No untyped `Throwable` in business logic.
* Error handling must be **exhaustive** and **explicit**.
* Avoid `catchAll(_ => ...)` unless explicitly justified.

## 2.3 Resource Safety

* Manage resources with:

  * `ZIO.acquireRelease`
  * `ZIO.acquireReleaseInterruptible`
  * `Scope`
  * `ZLayer.scoped`
* Finalizers must always run (normal, error, interruption).

## 2.4 Functional Architecture

* Domain logic is pure and deterministic.
* I/O lives at the edges.
* Use ZLayer for dependency injection and wiring.
* **No `var`**, no shared mutable state.
* Use `Ref`, `Queue`, `Hub`, etc. for concurrency.

---

# 3. Code Construction Rules

## 3.1 Allowed Effect Constructors

Use only the following for effect creation:

```
ZIO.succeed
ZIO.fail
ZIO.attempt
ZIO.attemptBlocking
ZIO.async
ZIO.fromFuture
ZIO.suspend
```

Forbidden:

* side effects inside `ZIO.succeed`
* constructors doing real work

## 3.2 Composition Patterns

Canonical patterns:

```
for {
  a <- effectA
  b <- effectB(a)
  _ <- log(b)
} yield b
```

Use:

* `*>` and `<*`
* `>>=` and `zip`
* `zipPar`
* `race`
* `ZIO.foreachPar`
* `.withParallelism(n)`

Avoid:

* nested flatMaps
* combinator soup when a `for` is clearer

---

# 4. Error Handling

## 4.1 Typed, Explicit, Exhaustive

* Use ADTs: `enum`, `case object`, `case class`.
* Do not hide errors.
* Do not swallow exceptions.

Example:

```scala
effect.catchAll {
  case e: DomainError => recover(e)
}
```

## 4.2 Recommended Patterns

* Retries with `Schedule` (exponential backoff)
* Fallbacks with `orElse`
* Timeouts with `timeout` or `timeoutFail`
* Request hedging with `race`
* Circuit breakers (Rezilience)

---

# 5. Dependency Injection with ZLayer

## 5.1 Service Definition Pattern

Example:

```scala
trait UserRepo:
  def find(id: UserId): IO[RepoError, User]

final case class UserRepoLive(pool: ConnectionPool) extends UserRepo:
  def find(id: UserId) = ???

object UserRepo:
  val live: ZLayer[ConnectionPool, Nothing, UserRepo] =
    ZLayer.fromFunction(UserRepoLive.apply)
```

## 5.2 Rules

* No layer creation inside effect bodies.
* Use `ZIO.serviceWithZIO` for service access.
* Compose dependency graphs at the application boundary.

---

# 6. Concurrency & Parallelism

Use:

* `zipPar`
* `race`
* `foreachPar`
* `.withParallelism(n)`

Avoid:

* Unbounded fiber creation
* Blocking outside of `attemptBlocking`
* Shared mutable vars

Good example:

```scala
ZIO.foreachPar(items)(process).withParallelism(16)
```

---

# 7. Testing Standards

Use ZIO Test:

* `ZIOSpecDefault`
* `TestClock`
* `TestRandom`
* `TestConsole`
* `TestEnvironment`

Tests must cover:

* Success cases
* Failure cases
* Boundary conditions
* Resource cleanup
* Time-based and concurrency behavior when applicable

---

# 8. Naming Conventions

* **Effects:** verbs → `loadUser`, `processOrder`
* **Services:** nouns → `UserRepo`, `OrderService`
* **Layers:** adjectives → `live`, `test`, `mock`
* **Errors:** domain names → `InvalidUserId`, `NetworkTimeout`

---

# 9. Forbidden Anti-Patterns

Codex must prevent or correct these:

❌ `var`
❌ shared mutable state
❌ blocking (`Thread.sleep`, `Await.result`)
❌ exception throwing for domain errors
❌ silent swallowing of errors
❌ layers created inside runtime logic
❌ side effects in constructors
❌ using `Throwable` as error type
❌ printing from business code
❌ mixing Future and ZIO without conversion

---

# 10. Preferred Patterns (Examples)

## 10.1 Retry with Backoff

```scala
effect.retry(
  Schedule.exponential(100.millis) && Schedule.recurs(5)
)
```

## 10.2 Hedged Requests

```scala
effect.race(effect.delay(p50))
```

## 10.3 Circuit Breaker

```scala
breaker(effect)
```

## 10.4 Graceful Shutdown

```scala
ZIO.addFinalizer(fiber.interrupt *> cleanup)
```

---

# 11. Output Rules for Codex

When generating code:

* Use idiomatic Scala 3 syntax
* Provide necessary imports
* Use `case class` & `enum` for domain modeling
* Use type annotations when clarity requires it
* Use significant indentation when helpful
* Structure modules cleanly (services, layers, models, errors)

When explaining:

* Be concise
* Point out anti-patterns
* Justify technical decisions

When refactoring:

* Improve type safety
* Improve purity
* Push effects to edges
* Adopt proper ZLayer wiring
* Replace exceptions with typed errors

---

# 12. Validation Before Every Codex Output

Before responding, Codex must validate:

* [ ] Effects use correct `R`, `E`, `A` types
* [ ] Errors are domain ADTs
* [ ] No side effects escape ZIO constructors
* [ ] No blocking except within `attemptBlocking`
* [ ] Dependencies are injected via ZLayer
* [ ] Error handling is explicit and typed
* [ ] Resource lifecycle is guaranteed
* [ ] Parallelism uses safe ZIO combinators
* [ ] Code follows idiomatic Scala 3 style

---