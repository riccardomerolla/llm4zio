# 19. Generate tool JSON Schemas from Scala method signatures via scalameta

- Status: Accepted

## Context
Tool-calling requires a JSON Schema describing each tool's parameters. Hand-writing and maintaining a schema alongside every tool method is duplicative and drifts from the real signature, and the library already has Scala method signatures that carry the parameter names, types, optionality, and defaults the schema needs.

## Decision
Use scalameta (ToolSchemaGenerator.fromMethodSignature) to parse a Scala method signature string and derive a JSON Schema: Scala types map to JSON types, and Option/defaulted parameters become not-required. This sits in llm4zio.tools alongside Tool/ToolRegistry/BuiltInTools, with structured-output schema for executeStructured derived separately via SchemaDerivation. The single scalameta 4.13.6 dependency is added to the build for this purpose.

## Alternatives considered
Hand-author each tool's JSON Schema — rejected because it duplicates information already in the signature and drifts out of sync. Use runtime reflection to read parameter metadata — rejected as fragile on the JVM (erased generics, lost default/Option nuance) and against the typed, compile-time spirit of the codebase. A compile-time macro instead of parsing a signature string — a heavier alternative; scalameta parsing was chosen as a simpler, self-contained route that also pulls in a dependency the build then has to tidy (excluding the Scala-2.13 sourcecode it drags in).

## Consequences
Tool schemas stay derived from the real signatures, reducing drift and duplication, and Option/default semantics map naturally to required-ness. The cost is a scalameta dependency and a build workaround (excluding a Scala-2.13 transitive) plus reliance on signature strings being parseable; the mechanism is confined to the tools package and does not affect the core LlmService contract.
