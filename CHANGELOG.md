# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Project template scaffolding for Scala 3 + ZIO 2.x libraries (typed errors, domain model, config helpers, service layer, tests, and sample app).
- Multi-provider `AIService` runtime factory (`AIService.fromConfig`) with provider selection for `gemini-cli`, `gemini-api`, `openai`, and `anthropic`.
- ADR-003 documenting the provider-agnostic AI architecture decision.

### Changed

- Replaced the previous implementation with the new `io.github.riccardomerolla.zio.quickstart` library starter structure.
- Migrated orchestrator and core AI agents to depend on `AIService` instead of `GeminiService`.
- Updated runtime wiring in `Main.scala` to compose `Client.default`, `HttpAIClient.live`, and provider-aware `AIService.fromConfig`.

### Deprecated

### Removed

### Fixed

### Security

## [0.1.0] - TBD

### Added

### Features

---

[Unreleased]: https://github.com/.../compare/v0.1.0...HEAD
[0.1.0]: https://github.com/.../releases/tag/v0.1.0
