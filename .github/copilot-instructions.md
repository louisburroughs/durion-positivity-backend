# GitHub Copilot Instructions – Durion Positivity Backend

These instructions guide AI coding agents working in this repository.

## Big Picture

- This repo is the backend for the Durion Positivity POS system, implemented as a **Java 21 / Spring Boot microservice suite** under the `pos-*` directories (for example [pos-accounting](../pos-accounting/README.md), [pos-agent-framework](../pos-agent-framework/README.md)).
- The architecture follows a **modular POS design**: each `pos-*` module is an independently deployable service with its own database and REST API, coordinated via an API gateway and shared security/metrics as described in the top-level [README](../README.md) and [architecture docs](./docs/architecture/README.md).
- Architectural decisions and clarifications live in [docs/](../docs/README.md) and module-level `docs/` folders; prefer following existing ADRs and clarification resolutions over inventing new patterns.

## Module Conventions

- Treat each `pos-*` directory as a standard Spring Boot service with this basic structure:
  - `controller/` – REST endpoints (HTTP contracts). Keep controllers thin.
  - `service/` – business logic orchestration.
  - `repository/` – Spring Data JPA access to the module’s database.
  - `model/` / `entity/` – JPA entities and domain types.
  - `config/` – Spring configuration (security, DB, messaging).
- When adding features, **mirror existing patterns in the same module** before introducing new abstractions. For example, follow [pos-accounting](../pos-accounting/README.md) for how entities, services, and controllers are named and wired.
- Many modules emit domain events (for example `OverridePriceCreated`, `RefundCreated` in [pos-accounting](../pos-accounting/README.md)); when adding new behavior, prefer **extending the existing event model** instead of inventing ad‑hoc messaging.

## Builds, Tests, and Running Services

- Use Maven via the wrapper from the repo root:
  - Build a specific module and its dependencies: `./mvnw clean compile -pl pos-accounting -am` (swap `pos-accounting` for other modules).
  - Run a module locally: `./mvnw spring-boot:run -pl pos-accounting` and use the module README for port and endpoints.
- Some services expose **Actuator** endpoints for health and diagnostics; prefer using existing health checks when adding monitoring or readiness logic.
- The **POS Agent Framework** module ([pos-agent-framework](../pos-agent-framework/README.md)) contains tests that can call GitHub; to run them you must configure `GITHUB_TOKEN` via Maven settings as documented there. Don’t hardcode tokens or secrets.

## Documentation and Decisions

- Before changing cross-cutting behavior (security, event schemas, accounting rules, inventory calculations), **check for existing ADRs** in [docs/adr](../docs/adr/README.md) and related module docs (for example [pos-inventory/docs](../pos-inventory/docs/)). Implementations should align with those decisions.
- Architectural and workflow clarifications (for example customer approval flows) are documented under [.github/docs/architecture](./docs/architecture/README.md); consult these before inventing new state machines or approval rules.

## Security, Accounting, and Events

- Security, auditing, and accounting are treated as **first‑class concerns**:
  - Follow patterns in [pos-accounting](../pos-accounting/README.md) for immutable audit trails and event emission (`AuditTrailService`, event types, status fields) instead of ad‑hoc logging.
  - When touching security or auth, look for shared security or gateway modules (for example `pos-security-service`, `pos-api-gateway`) and reuse their filters and conventions rather than duplicating logic in leaf services.
- When adding or modifying HTTP APIs, keep them **consistent with existing REST shapes** in the same module (URI patterns, request/response schemas, validation rules) and update the module’s README if you change externally visible contracts.

## Working with Agents and Tools

- The [pos-agent-framework](../pos-agent-framework/README.md) defines specialized agents (architecture, testing, observability, etc.). When generating code that relies on these agents, reference their documented responsibilities and keep responsibilities aligned (for example, don’t mix observability concerns into business‑domain agents).
- When generating or updating documentation, link new docs into the appropriate README (for example [docs/README.md](../docs/README.md) or module `docs/README.md`) so that other agents and humans can discover them.
