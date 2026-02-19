# Technology Stack

**Analysis Date:** 2026-02-19

## Languages

**Primary:**
- Java 17 - Core framework and all modules (baseline)
- Java 21 - CI/CD matrix testing against latest LTS

## Runtime

**Environment:**
- JDK 17+ (OpenJDK Temurin distribution in CI)

**Package Manager:**
- Maven 3.x
- Lockfile: `pom-lock.xml` not used; `pom.xml` files are the source of truth
- Parent POM: `/Users/lee/Workspace/oss/outbox/pom.xml` (0.7.0-SNAPSHOT)

## Frameworks

**Core Libraries:**
- No external dependencies in `outbox-core` (zero-dependency core)
- JUnit Jupiter 5.10.2 - Testing framework for all modules

**Key Dependencies by Module:**

**outbox-core:**
- `ulid-creator` 5.2.3 - ULID generation for event IDs (`com.github.f4b6a3:ulid-creator`)

**outbox-jdbc:**
- `h2` 2.2.224 - In-memory test database (test scope only)
- Depends on `outbox-core`

**outbox-spring-adapter:**
- `spring-jdbc` 6.2.15 - Spring JDBC wrapper (provided scope - optional)
- `spring-tx` 6.2.15 - Spring transaction management (provided scope - optional)
- Depends on `outbox-core` and `outbox-jdbc` (test scope)

**outbox-micrometer:**
- `micrometer-core` 1.14.5 - Metrics collection (provided scope - optional)
- Depends on `outbox-core`

**Benchmarks Module:**
- `jmh-core` 1.37 - JMH microbenchmark framework
- `jmh-generator-annprocess` 1.37 - Annotation processor for JMH (provided scope)
- `maven-shade-plugin` 3.5.1 - UberJAR creation for benchmarks
- Runtime connectors: `mysql-connector-j` 8.3.0, `postgresql` 42.7.3, `HikariCP` 5.1.0

**Build/Dev Tools:**

- `maven-compiler-plugin` 3.15.0 - Java compilation
- `maven-surefire-plugin` 3.5.4 - Test execution (no module path)
- `maven-source-plugin` 3.4.0 - Source JAR generation
- `maven-javadoc-plugin` 3.12.0 - Javadoc generation (source 17, doclint disabled)
- `exec-maven-plugin` 3.1.0 - Direct Java execution (demos)
- `versions-maven-plugin` 2.21.0 - Dependency version updates

## Database Drivers

**Supported Databases:**

- **H2** 2.2.224 - In-memory/file-based test database (default in samples)
- **MySQL** `mysql-connector-j` 8.3.0 - Optional runtime JDBC driver
- **PostgreSQL** `postgresql` 42.7.3 - Optional runtime JDBC driver

**Connection Pooling:**

- **HikariCP** 5.1.0 - Optional high-performance JDBC pool (benchmarks and optional for production)

## Configuration

**Java Properties:**

- Compiler: Java 17 source/target
- Encoding: UTF-8 (project-wide)
- Testing: JUnit Jupiter 5.10.2 via Maven Surefire
- Javadoc: Java 17 source, doclint disabled, quiet mode

**Build Configuration Files:**

- Parent POM: `/Users/lee/Workspace/oss/outbox/pom.xml` (defines dependency management, plugin management, distribution repo)
- Module POMs: Each module has its own `pom.xml` inheriting from parent
- Samples: Spring Boot 3.4.2 parent (outbox-spring-demo only; others inherit from outbox-samples parent)

**Sample Application Configuration:**

- `outbox-spring-demo`: `/Users/lee/Workspace/oss/outbox/samples/outbox-spring-demo/src/main/resources/application.properties`
  - H2 in-memory (MySQL mode compatibility)
  - Server port 8080
  - H2 Console enabled at `/h2-console`
  - Schema auto-initialization from `schema.sql`

## Platform Requirements

**Development:**

- Java 17 or 21 installed
- Maven 3.6+ installed
- Unix-like environment (tests use H2 in-memory by default)

**Production:**

- Java 17+ runtime
- One of: H2 (file-mode), MySQL 5.7+, PostgreSQL 10+
- Optional: Micrometer-compatible metrics backend (Prometheus, Grafana, Datadog, etc.)
- Optional: Spring Framework 6.2.15+ (for SpringTxContext integration)

## External Services & APIs

**GitHub:**

- Publishing: Maven packages to `https://maven.pkg.github.com/leonlee/outbox`
- Auth: `GITHUB_TOKEN` via GitHub Actions secrets
- Releases: Auto-generated release notes on `v*` tags

**Build & CI/CD:**

- GitHub Actions (workflows in `.github/workflows/`)
  - CI: `ci.yml` - Java 17 and 21 matrix, all tests
  - Publish: `publish.yml` - Deploy on version tags to GitHub Packages
  - Benchmarks: `benchmark.yml` - JMH report generation
  - Docs: `docs.yml` - Path-filtered documentation updates
  - Schema Diff: `schema-diff.yml` - Database schema comparison

**Dependency Management:**

- Dependabot: `.github/dependabot.yml` - Automated dependency updates for GitHub Actions and Maven

## Distribution

**Published Modules:**

- `outbox-core`
- `outbox-jdbc`
- `outbox-spring-adapter`
- `outbox-micrometer`

**NOT Published:**

- `benchmarks` (internal testing)
- `samples/*` (examples only)

**Repository:**

- Destination: `https://maven.pkg.github.com/leonlee/outbox`
- Scope: Private to GitHub organization (`leonlee`)
- Auth: Repository requires `GITHUB_TOKEN` in Maven `settings.xml` (auto-configured by `setup-java` action with `server-id: github`)

---

*Stack analysis: 2026-02-19*
