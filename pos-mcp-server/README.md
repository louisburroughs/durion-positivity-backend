# pos-mcp-server

MCP server for Durion Positivity. Discovers backend services via Eureka, registers their REST APIs as MCP tools, and stores system prompts.

## Schema management

Flyway manages runtime schema for this module.

- Baseline migration: `src/main/resources/db/migration/V1__baseline_mcp_schema.sql`
- Runtime JPA mode: `spring.jpa.hibernate.ddl-auto=validate` (default, preprod, prod)

## Runtime configuration

Preprod/Prod profiles use PostgreSQL. Set these environment variables in the deployment manifest or process env:

- `POS_MCP_DB_HOST` – Postgres host (required)
- `POS_MCP_DB_PORT` – Postgres port (default `5432`)
- `POS_MCP_DB_NAME` – Database name (default `pos_mcp`)
- `POS_MCP_DB_USER` – Database user (default `pos_mcp`)
- `POS_MCP_DB_PASSWORD` – Database password (required)

Profiles:

- `spring.profiles.active=preprod` – PostgreSQL, Eureka enabled
- `spring.profiles.active=prod` – PostgreSQL, Eureka enabled
- `spring.profiles.active=test` – In-memory H2 for tests
- default (no profile) – In-memory H2 for local/dev

## Quick local run

```bash
./mvnw -pl pos-mcp-server -am spring-boot:run
# or
SPRING_PROFILES_ACTIVE=test ./mvnw -pl pos-mcp-server -am test
```

## NLTI API Scaffold (Phase 1)

Compile-only NLTI API surface has been scaffolded in this module.

- `POST /v1/nlt/requests` in `NltiController` (event: `NLTI_REQUEST_SUBMIT`, permission: `nlti:request:submit`)
- `GET /v1/nlt/audit` in `AuditController` (event: `NLTI_AUDIT_QUERY`, permission: `nlti:audit:read`)
- DTOs are in `src/main/java/com/positivity/mcp/internal/dto`
- Service interfaces exposed for API wiring are in `src/main/java/com/positivity/mcp/service`

These controller methods are now implemented and wired to their corresponding services; behavior is governed by the underlying service implementations.

## LLM orchestration draft

The first-pass LLM/tool orchestration specification lives in [docs/llm-tool-orchestration-spec.md](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/docs/llm-tool-orchestration-spec.md).

It is designed to work with the existing [docs/tool-registry-implementation.md](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/docs/tool-registry-implementation.md) guide:

- the tool registry narrows candidates before model planning
- local development defaults to Ollama in `application.yml`
- preprod and prod keep provider-driven overrides through profile configuration
