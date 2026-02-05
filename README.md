# zio-quickstart

A template repository for building new Scala 3 / ZIO 2.x libraries with opinionated defaults: effect-oriented programming, typed error channels, resource-safe layers, and ZIO Test coverage.

## Highlights

- Structured package `io.github.riccardomerolla.zio.quickstart` with a domain model, typed error ADT, configuration helpers, and a layered `GreetingService`.
- Example `QuickstartApp` showcasing `ZIOAppDefault`, dependency injection via `ZLayer`, and structured logging.
- ZIO Test specification covering success and failure paths for the service.
- Ready-to-publish `build.sbt` with ZIO core, streams, schema, JSON, and testing dependencies.

## Getting Started

1. Clone or use this template on GitHub.
2. Search for `zio.quickstart` to rename packages and modules that fit your domain.
3. Update the `GreetingService` (or replace it with your own services) while preserving the same layering and typed-error patterns.
4. Run the test suite: `sbt test`.

### Project Layout

```
src/
  main/
    scala/io/github/riccardomerolla/zio/quickstart/
      app/QuickstartApp.scala        // entry point example
      config/QuickstartConfig.scala  // configuration helpers + layers
      domain/Greeting.scala          // domain models (Audience, Greeting, Template)
      error/QuickstartError.scala    // typed, explicit errors
      service/GreetingService.scala  // service pattern + live/test layers
  test/
    scala/io/github/riccardomerolla/zio/quickstart/GreetingServiceSpec.scala
```

## Development Workflow

- Keep all side effects inside `ZIO.*` constructors.
- Model errors with sealed ADTs (`enum QuickstartError`).
- Inject dependencies with `ZLayer` and access them via `ZIO.serviceWithZIO`.
- Validate behavior via `zio-test` and the default `ZTestFramework`.

## Contributing

Contributions are welcome! Please:
1. Respect the coding guidelines in `AGENTS.md`.
2. Add or update tests for every change.
3. Document noteworthy behavior in `README.md` and `CHANGELOG.md`.
4. Run `sbt test` before opening a PR.

## License

MIT License – see [LICENSE](LICENSE) for details.
