# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Durion Positivity Backend — a domain-driven, event-sourced POS platform built as ~30 independent Spring Boot
microservices (`pos-*`) plus shared libraries, all under one Maven multi-module reactor (`groupId: com.positivity`).
Each service owns its own database schema; there are no cross-service foreign keys.

- Language: Java 25 (Eclipse Temurin 25.0.2-tem, managed via SDKMAN! — `.sdkmanrc`)
- Framework: Spring Boot 4.0.x, Spring Cloud 2025.1.1 (Gateway, Eureka)
- DB: PostgreSQL 16 / TimescaleDB, Spring Data JPA + Hibernate + Flyway
- Messaging: Kafka (Spring Kafka)
- Build: Maven via `./mvnw` (root wrapper)

## Build, Test, and Lint Commands

```bash
# Build everything (skip tests)
./mvnw clean package -DskipTests

# Build one service + its in-repo dependencies
./mvnw -pl pos-order -am clean package

# Run all tests
./mvnw -DskipTests=false clean test

# Run tests for one module (and its dependencies)
./mvnw -pl pos-order -am test

# Run a single test class / a list of test classes
./mvnw -pl pos-order -Dtest=OrderServiceImplTest test
./mvnw -pl pos-price -Dtest=EligibilityEvaluationServiceImplTest,PromotionEligibilityRuleControllerTest test

# Cross-module ArchUnit architecture tests (run after touching package layout)
./mvnw -pl pos-archunit -am -Dtest=ArchitectureTests test

# Format code (Palantir Java Format via Spotless) — run before committing
./mvnw spotless:apply

# Run a single service locally (random port, registers with Eureka)
cd pos-order && ../mvnw spring-boot:run
```

Failsafe (`*IT.java`, `*ITCase.java`) integration tests run in `verify`, not `test`. Checkstyle, Spotless, and SpotBugs
(threshold `High`) all run as part of the build and will fail it on violations.

### Local stack (Docker Compose)

```bash
cp .env.example .env   # set POSTGRES_PASSWORD, POS_SECURITY_API_SECRET, POS_EVENTS_API_SECRET, etc.
docker-compose up -d
curl http://localhost:8080/actuator/health
```

Eureka dashboard: `http://localhost:8761`. Aggregated Swagger UI via gateway: `http://localhost:8080/swagger-ui.html`.

## Architecture

```
External Clients
     │ HTTPS :8080, header X-API-Version: N
     ▼
pos-api-gateway  (JWT validation, path rewrite /{domain}/vN/.., permission bitset → X-Authorities)
     │ lb://SERVICE_NAME (Eureka)
     ├── pos-security-service (JWT issuer, RBAC source of truth)
     ├── domain services (pos-order, pos-customer, pos-inventory, pos-accounting, pos-catalog, ...)
     └── pos-event-receiver (event ingestion hub, shared-secret auth, not public)
```

- **API Gateway is the security boundary.** It validates JWTs and decodes the permission bitset into
  `X-Authorities` / `X-User` / `X-User-Id` headers. Downstream services trust these headers and **must strip any
  inbound copies from external clients** (the gateway already does this for external traffic).
- **API versioning is header-driven.** Clients send `X-API-Version: 1`; the gateway rewrites
  `GET /customer/crm/accounts` → `lb://CUSTOMER /v1/crm/accounts`.
- **Ports**: gateway fixed at `8080`, Eureka at `8761`, bulk-loader at `8090`; every other domain service uses
  `server.port: 0` (ephemeral) and registers itself with Eureka.
- **UUID v7** for all primary keys (see `docs/UUID_V7_MIGRATION.md`).
- **Two cross-service call strategies**: through the gateway (`gateway.url`, header `X-API-Version`) for
  client-equivalent traffic, or direct `@LoadBalanced RestClient` to `http://<eureka-service-name>` for
  internal service-to-service calls. **Never** call another service's repository/DB directly.
- Errors use a standard `ApiError` envelope (`code`, `message`, `status`, `timestamp`, `correlationId`,
  optional `fieldErrors`/`referenceId`/`nextAction`/`supportAction`) — see `docs/ERROR_ENVELOPE.md`.
- Profiles: `dev` (H2, local JVM), `docker` (Compose/Postgres), `alpha` (staging EC2), `prod`. Legacy `local`/`preprod`
  names are retired.

### Shared (non-deployed) libraries

| Library | Purpose |
|---|---|
| `pos-events` | `@EmitEvent` AOP annotation + event publishing to `pos-event-receiver` |
| `pos-shared-dtos` | Shared request/response/error DTOs (`ApiError`, etc.) |
| `pos-security-common` | Shared security utilities |
| `pos-tax-common` | Shared tax DTOs/enums |
| `pos-bulk-ingest-lib` / `pos-document-helper` | Bulk import / document generation helpers |
| `pos-dependencies` | Internal BOM for internal artifact versions |
| `pos-archunit` | Cross-module ArchUnit rules (test-only, no production code) |

## Module Structure & Conventions (enforced by ArchUnit — non-negotiable)

Every `pos-{domain}` module follows this package layout under `com.positivity.{domain}`:

```
com.positivity.{domain}/
├── Pos{Domain}Application.java   ← @SpringBootApplication, MUST stay at this root level
├── service/                      ← PUBLIC API surface (interfaces) usable by other modules
│   └── model/
└── internal/                     ← everything else; PRIVATE, never imported by other modules
    ├── controller/   (thin REST endpoints)
    ├── service/       (business logic implementations)
    ├── repository/    (Spring Data JPA)
    ├── entity/        (JPA entities)
    ├── dto/
    ├── domain/
    ├── config/        (security, DB, messaging, event/permission registration)
    ├── enums/
    ├── client/        (RestClients to other services)
    ├── event/
    ├── exception/
    └── security/
```

- Only `service.*` packages may be referenced from outside the module. Cross-module access is REST (gateway or
  load-balanced) or async events — never direct repository/entity/DTO imports across modules.
- Each module has `src/test/java/{package}/ArchitectureTest.java` enforcing this; `pos-archunit` enforces it
  cross-module. Run `./mvnw -pl pos-archunit -am -Dtest=ArchitectureTests test` after restructuring packages.
- Use `@NonNull` (`org.jspecify.annotations.NonNull`) on all non-null service/DAO method params and return types
  (except `Optional`/`void`). Use `@NotNull` (`jakarta.validation.constraints`) only for request-body validation —
  don't combine the two on one parameter.

### `@EmitEvent` — required on state-changing endpoints

All controller methods that mutate state (POST/PUT/DELETE) or perform significant reads must be annotated:

```java
@PostMapping
@EmitEvent(id = "ORDER_PRICE_OVERRIDE_CREATE", apiVersion = "1")
public ResponseEntity<PriceOverride> createPriceOverride(@RequestBody PriceOverrideRequest request) { ... }
```

Each module also needs a `{Module}EventTypes` registry (`internal/config`) listing every event id with a threshold
preset (`fastRead`, `search`, `write`, `approval`), plus a `{Module}EventTypeInitializer`
(`ApplicationRunner`) that PUTs these to `pos-event-receiver` (`pos.events.base-url`, `X-Pos-Events-Secret`/
`pos.events.api-secret`) at startup, swallowing failures so startup never blocks. Modules using `@EmitEvent` must
depend on `pos-events`. Full templates: `AGENTS.md`.

### Permissions / RBAC — code-first registration

Permission names follow `domain:resource:action` (snake_case), e.g. `crm:party:view`, `order:shipment:cancel`,
`pos:order:create`. Each module defines a `{Module}PermissionRegistry` (built `Permission` list) and registers it
on startup against `pos-security-service` (`POST /security-service/v1/permissions/register`), mirroring the
`{Module}EventTypeInitializer` pattern. Controllers enforce with
`@PreAuthorize("hasAuthority('" + SomePermissions.SOME_ACTION + "')")`. See `docs/OPERATIONS_RUNBOOK.md`
("Permission Registration").

## Output Style — Caveman (token compression, default ON)

Chat responses in this repo default to compressed "caveman" style (adopted from the durion
`token-stack` skill; see `.claude/skills/caveman/SKILL.md`):

- Answer first. No preamble, no restating the question, no closing summary or sign-off.
- Telegraphic prose: short sentences, fragments fine, filler words dropped.
- Lists/tables over paragraphs; aim for one line per point.
- Don't echo file contents, diffs, or logs — cite `path:line` instead.
- Routine status updates: one line max. No emoji, no headers on short answers.
- **Scope: chat output only.** Commit messages, PR/issue bodies, code, comments, docs, and ADRs
  keep normal repo conventions and full quality.
- **Override:** drop to normal prose when the user asks ("verbose", "explain"), or when
  compression would make a nuanced finding ambiguous — correctness beats brevity.

## Further Reading

- `AGENTS.md` — full code templates for the patterns above (event registries, initializers, ArchUnit rules)
- `docs/ARCHITECTURE_GUIDE.md` — Docker, ports, inter-service communication, observability stack
- `docs/DEVELOPMENT_GUIDE.md` — OpenAPI generation, version bumping, Spring Boot 4 migration notes
- `docs/OPERATIONS_RUNBOOK.md` — RBAC, permission registration, troubleshooting
- `docs/ERROR_ENVELOPE.md` — `ApiError` schema and examples
- `docs/UUID_V7_MIGRATION.md` — UUID v7 primary key strategy
