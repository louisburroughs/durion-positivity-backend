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

## Agent integration draft

The current external-agent integration specification lives in [docs/llm-tool-orchestration-spec.md](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/docs/llm-tool-orchestration-spec.md).

It now assumes:

- `Onyx` is the orchestration layer
- `Ollama` is the model backend used by Onyx
- `pos-mcp-server` is the MCP tool host and governance boundary
- [docs/tool-registry-implementation.md](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/docs/tool-registry-implementation.md) remains the reference for curated tool metadata and future registry-based narrowing

The server no longer treats embedded LLM orchestration as the primary architecture. Keep this module focused on MCP transport, tool exposure, backend integration, security, and observability.

Practical bring-up docs:

- [docs/docker-compose.onyx-ollama-mcp.yml](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/docs/docker-compose.onyx-ollama-mcp.yml) - sample local stack
- [docs/onyx-ollama-integration-checklist.md](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/docs/onyx-ollama-integration-checklist.md) - phased validation checklist
- [docker-compose.onyx.yml](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/docker-compose.onyx.yml) - root-level compose override for layering `ollama` and `pos-mcp-server` onto the main backend stack so an official Onyx Docker deployment can connect to them

The root override now expects a local `pos-mcp-server` jar build and uses this module's [Dockerfile](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/Dockerfile).

Operationally, the expected flow is:

- start `ollama` and `pos-mcp-server` from this repo
- install and start Onyx using its official Docker flow
- use the generated Onyx deployment under `onyx_data/deployment`
- log into the local Onyx instance and configure Ollama plus MCP connectivity there
