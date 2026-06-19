# 18. Fail-fast strict compiler: -Werror, -Wunused:all, -explain

- Status: Accepted

## Context
The library is small, published, and contributor-facing, and leans on Scala 3 features (context functions, given resolution, derived JsonCodec). Latent warnings — especially unused imports — tend to accumulate and rot, and a wildcard import zio.* silently shadows the flow layer's own Task type. The project wanted these classes of mistake caught at compile time rather than in review or at runtime.

## Decision
Compile under sbt-tpolecat with strict flags: -Wunused:all and -Werror make unused imports (and other warnings) fatal, -explain improves diagnostics, and -Xmax-inlines 128 supports the derivation-heavy code. build.sbt deliberately excludes the Scala-2.13 sourcecode that scalameta drags in and silences the deprecated -Xfatal-warnings warning so the strict mode does not fail on itself. CI runs check (scalafix + scalafmt verify) plus the full test suites as a single sbt invocation.

## Alternatives considered
Leave warnings non-fatal — rejected because unused imports and shadowing (notably zio.Task vs flow.Task) would silently accumulate and slip past review. Enable warnings but not -Wunused:all — rejected as it misses the most common rot (dead imports) the project specifically wants gone. Defer style/lint enforcement to review only — rejected in favour of mechanical enforcement via scalafix/scalafmt in CI so reviewers spend attention on design, not formatting.

## Consequences
Whole classes of error (unused imports, accidental shadowing) cannot reach main, and diagnostics are richer via -explain. The cost is friction: a wildcard import zio.* breaks the build in files that name Task, so contributors must import specific zio names, and any warning halts compilation until resolved. CI is one strict gate (check ; testFull ; It/testFull).
