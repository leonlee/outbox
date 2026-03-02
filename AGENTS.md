# Repository Guidelines

## Project Structure & Module Organization

- `outbox-core/src/main/java/io/outbox`: core APIs and runtime (Outbox composite builder, writer, dispatcher, poller,
  registry, SPI contracts).
- `outbox-jdbc/src/main/java/io/outbox/jdbc`: JDBC implementations (event store, transaction helpers, connection
  providers).
- `outbox-spring-adapter/src/main/java/io/outbox/spring`: Spring `TxContext` adapter.
- Tests live in `*/src/test/java` (currently in `outbox-jdbc` and `outbox-spring-adapter`).
- Generated build output is under `*/target`.
- Reference docs: `README.md` (usage), `SPEC.md` (behavioral contract).

## Build, Test, and Development Commands

- `mvn test`: run all module tests from the repo root.
- `mvn -pl outbox-jdbc test`: run JDBC module tests only.
- `mvn -pl outbox-core -am package`: build the core module and its dependencies.
- `mvn -DskipTests package`: build all jars without tests.

Java 17 is the baseline (see root `pom.xml`).

## Coding Style & Naming Conventions

- Indentation is 2 spaces; braces are on the same line as declarations.
- Package names follow `io.outbox.<feature>` (for example, `io.outbox.dispatch`, `io.outbox.spi`).
- Classes use `UpperCamelCase`, methods use `lowerCamelCase`, constants use `UPPER_SNAKE_CASE`.
- There is no formatter or linter configured; keep changes consistent with existing files.

## Testing Guidelines

- Tests use JUnit Jupiter (`org.junit.jupiter`).
- Name tests with a `*Test` suffix; integration tests use `*IntegrationTest` (see `SpringAdapterIntegrationTest`).
- H2 is used in test dependencies; avoid external DB dependencies in unit tests.

## Commit & Pull Request Guidelines

- This branch has no commit history, so no established commit convention exists. Use short, imperative summaries and
  include a module scope when helpful (for example, `core: validate dispatcher args`).
- PRs should include a concise description, rationale, and how tests were run.
- If you change public APIs or delivery semantics, update `README.md` and `spec.md` in the same PR.
