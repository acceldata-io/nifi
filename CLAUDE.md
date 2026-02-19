# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build System

Apache NiFi uses Maven with the Maven Wrapper (`./mvnw`). JDK 21 is required.

```bash
# Full build
./mvnw clean install -T1C

# Build with code compliance checks (required before pushing)
./mvnw clean install -P contrib-check

# Build specific module (and its dependencies)
./mvnw clean install -T1C -pl :nifi-standard-processors -am

# Build only the runnable assembly
./mvnw install -T1C -am -pl :nifi-assembly
```

The binary distribution is produced at `nifi-assembly/target/nifi-*-bin.zip`.

## Testing

```bash
# Run unit tests for all modules
./mvnw test

# Run a single test class in a specific module
./mvnw test -pl :nifi-standard-processors -Dtest=MyProcessorTest

# Run a single test method
./mvnw test -pl :nifi-standard-processors -Dtest=MyProcessorTest#testMethod

# Skip tests during build
./mvnw install -DskipTests -T1C

# Run integration tests (requires Docker for some)
./mvnw verify -P integration-tests -P skip-unit-tests
```

## Code Compliance

```bash
# PMD static analysis only
./mvnw pmd:check -pl :target-module

# Full compliance check (PMD + Apache RAT license headers + Checkstyle)
./mvnw clean install -P contrib-check
```

Rules are defined in `checkstyle.xml` and `pmd-ruleset.xml` at the root.

## Project Architecture

NiFi is a data flow automation system. The main architectural layers:

**APIs (define contracts):**
- `nifi-api/` — Core interfaces: `Processor`, `ControllerService`, `Relationship`, `FlowFile`
- `nifi-framework-api/` — Framework-level APIs used by the engine
- `nifi-server-api/` — Server/HTTP layer APIs

**Framework (the engine):**
- `nifi-framework-bundle/nifi-framework/` — Core scheduler, flowfile repository, provenance, clustering
- `nifi-bootstrap/` — JVM startup and lifecycle management
- `nifi-stateless/` — Stateless execution mode (run flows without persistence)

**Shared Utilities:**
- `nifi-commons/` — 47+ utility modules: security (`nifi-security-*`), property encryption (`nifi-property-protection-*`), expression language (`nifi-expression-language`), record processing (`nifi-record*`), web utilities

**Extension Bundles (processors/services packaged as NARs):**
- `nifi-nar-bundles/` — 100+ legacy processor bundles (standard, AWS, Azure, Kafka, JDBC, etc.)
- `nifi-extension-bundles/` — Newer/modern processor bundles (GCP, Snowflake, GitHub, etc.)
- `nifi-mock/` — `MockProcessContext`, `TestRunner` and other test utilities for processor unit tests

**Frontend:**
- `nifi-frontend/` — Angular/TypeScript web UI, built via Maven Node.js plugin

**Related Projects (in same repo):**
- `nifi-registry/` — Flow version control registry
- `minifi/` — Lightweight edge agent
- `nifi-toolkit/` — CLI administration tools
- `c2/` — Command & Control service for MiNiFi

## Writing Processors

Processors implement `org.apache.nifi.processor.Processor` (usually extend `AbstractProcessor`). They belong in a NAR bundle under `nifi-nar-bundles/` or `nifi-extension-bundles/`. Use `nifi-mock` for unit tests via `TestRunners.newTestRunner(MyProcessor.class)`.

NAR bundles have a two-module structure: the processor implementation module and the NAR packaging module (with `-nar` suffix).

## Pull Request Requirements

- Every PR needs an Apache NiFi JIRA issue (`NIFI-XXXXX`)
- PR title and commit message must start with the JIRA number
- Commits must be GPG-signed (showing "Verified" on GitHub)
- PRs must be based on `main`; one commit per feature branch
- Build must pass with `./mvnw clean install -P contrib-check` on JDK 21 and JDK 25
- New dependencies must be Apache License 2.0 compatible and documented in `LICENSE`/`NOTICE` files
