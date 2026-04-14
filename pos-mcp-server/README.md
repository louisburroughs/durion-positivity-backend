# pos-mcp-server

MCP server for Durion Positivity. Discovers backend services via Eureka, registers their REST APIs as MCP tools, and stores system prompts.

## Schema management

Flyway manages runtime schema for this module.

- Baseline migration: `src/main/resources/db/migration/V1__baseline_mcp_schema.sql`
- Runtime JPA mode: `spring.jpa.hibernate.ddl-auto=validate` (`dev`, `alpha`, `prod`)

## Runtime configuration

Alpha/Prod profiles use PostgreSQL. Set these environment variables in the deployment manifest or process env:

- `POS_MCP_DB_HOST` – Postgres host (required)
- `POS_MCP_DB_PORT` – Postgres port (default `5432`)
- `POS_MCP_DB_NAME` – Database name (default `pos_mcp`)
- `POS_MCP_DB_USER` – Database user (default `pos_mcp`)
- `POS_MCP_DB_PASSWORD` – Database password (required)

Profiles:

- `spring.profiles.active=alpha` – PostgreSQL, Eureka enabled
- `spring.profiles.active=prod` – PostgreSQL, Eureka enabled
- `spring.profiles.active=dev` – In-memory H2 for local development
- `spring.profiles.active=test` – In-memory H2 for tests

Facade tool outbound base URLs (LangChain4j orchestration):

- `POS_INVENTORY_BASE_URL` – Inventory facade base URL (default `http://pos-inventory/v1/inventory`)
- `POS_ORDER_BASE_URL` – Order facade base URL (default `http://pos-order/v1/orders`)

## Known Limitations (Phase 1)

- **RAG store is empty on first deploy:** The pgvector store requires manual document seeding
  (Phase 2 includes document ingestion API).

## Quick local run

`pos-mcp-server` depends on sibling workspace modules such as `pos-events`,
`pos-security-common`, and `pos-shared-dtos`. Build and run it from the backend
repo root with `-am` so Maven includes those reactor dependencies.

```bash
cd /home/louis-burroughs/IdeaProjects/durion-positivity-backend
./mvnw -pl pos-mcp-server -am spring-boot:run -Dspring-boot.run.profiles=dev
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

## Phase 2 (Wave MCP-2) — Delivery Summary

This module has been extended for Wave MCP-2 (Phase 2). The following are the notable delivery items and runtime behaviour changes developers should know about.

### DB-Backed Tool Registry

- Flyway migrations added:
  - `V3` — schema: `mcp_tool`, `mcp_role`, `mcp_tool_workflow`, `mcp_tool_role`, `mcp_workflow_state` tables
  - `V4` — seeds: 16 tools, 5 roles, and workflow mappings
  - `V5` — corrective migration (Location + ShopManager role mappings)
  - `V6` — pgvector ivfflat index on `mcp_tool.embedding` and `mcp_tool_invocation_log` audit table
- New repository and persistence:
  - `ToolMetadataRepository` / `ToolMetadataRepositoryImpl` — JDBC-backed storage
  - Uses `pgvector` column (embedding vector(768)) with cosine-similarity `<=>` queries to find semantically relevant tools
- Runtime services and loader:
  - `ToolRegistryService` — deterministic scoring pipeline: role gating → embedding top-K → `ToolScorer` (semantic rank, priority boost, latency/cost penalties)
  - `ToolRegistryLoader` — now loads registry entries from the DB at startup and resolves `handlerBean` names to actual handler instances via `ApplicationContext.getBean()` (replaces Phase 1 in-memory stub)
- Domain types: `ToolMetadata`, `ToolSelectionContext` represent persisted metadata and per-request selection inputs

### Full 16-Tool Facade Catalog

Phase 1 exposed `InventoryFacadeTool` and `OrderFacadeTool`. Phase 2 adds the remaining facade tools (total 16):

- `CustomerFacadeTool` — pos-customer (customer)
- `PricingFacadeTool` — pos-price (pricing)
- `WorkorderFacadeTool` — pos-workorder (workorder)
- `CatalogFacadeTool` — pos-catalog (catalog)
- `VehicleFacadeTool` — pos-customer (customer)
- `AccountingFacadeTool` — pos-accounting (accounting)
- `InvoiceFacadeTool` — pos-invoice (invoice)
- `HrFacadeTool` — pos-people (hr)
- `ReportingFacadeTool` — pos-accounting (reporting)
- `LocationFacadeTool` — pos-location (location)
- `ShopManagerFacadeTool` — pos-shop-manager (shop)
- `TaxFacadeTool` — pos-tax (tax)
- `AdminFacadeTool` — pos-security-service (admin)
- `EventsFacadeTool` — pos-event-receiver (events)
- (plus `InventoryFacadeTool`, `OrderFacadeTool` from Phase 1)

### Role → Tool Mapping Summary

Tool availability is gated by role in the registry. At a high level:

- `ROLE_CASHIER`: Inventory, Order, Customer, Pricing — core checkout and customer lookup
- `ROLE_SERVICE_WRITER`: Workorder, Customer, Vehicle, Catalog, Pricing, Inventory, Location, ShopManager — service lane operations
- `ROLE_MANAGER`: All `SERVICE_WRITER` tools + Reporting, Accounting, Invoice, HR — management and financials
- `ROLE_ADMIN`: All 16 tools
- `ROLE_SUPPLIER`: Order, Inventory, Catalog — supplier-facing subset

### Profile & Test Notes

- `ToolMetadataRepositoryImpl` and `ToolRegistryService` are annotated with `@Profile("!test")`. The `test` profile supplies stubs via `SessionAgentManagerTestConfiguration` to keep tests hermetic.
- `ToolRegistryLoader` is profile-neutral (no `@Profile`) so the loader runs in all profiles; test configurations provide a `ToolMetadataRepository` stub bean for startup.

If you are extending or testing registry logic, ensure test fixtures provide the expected stub beans or run with a non-test profile against a Postgres test instance seeded with V4 data.

### MCP Tuning and Fallback Config

- `mcp.tuning.enabled` (default `true`) — enables scheduled adaptive tool-priority tuning
- `mcp.tuning.cron` (default `0 0 2 * * ?`) — tuning schedule
- `mcp.model.fallback.enabled` (default `false`) — enables fallback model behavior when implemented by runtime orchestration
- `mcp.model.fallback.secondary-model-name` (default `${OLLAMA_FALLBACK_MODEL:mistral:7b}`)

## MCP-3 API Surface Additions

- `POST /v1/mcp/documents`
  - Permission: `mcp:document:ingest`
  - Event: `MCP_DOCUMENT_INGEST`
  - Behavior: validates request payload and ingests document content + optional metadata into the RAG vector store
  - Response: `201 Created`

- `POST /v1/mcp/chat/stream`
  - Permission: `mcp:chat:stream`
  - Event: `MCP_CHAT_STREAM_EXECUTE`
  - Behavior: streams chat tokens as Server-Sent Events (`event: chat`) from the session agent manager
  - Response: `200 OK` with `text/event-stream`

## Phase 3 — Embedding index

- Flyway V6 adds an ivfflat index on `mcp_tool.embedding` using `vector_cosine_ops` with 100 lists to improve semantic tool selection latency for large registries.
- `ToolEmbeddingInitializer` (annotated `@Profile("alpha")`) runs at startup and populates null tool embeddings using the `nomic-embed-text` Ollama model when available.

## Phase 4 — Audit logging, adaptive tuning, and RAG document API

- Invocation audit: `ToolInvocationLog` domain record backed by `mcp_tool_invocation_log` (created in V6). Note: a follow-up migration (V7) makes `tool_id` nullable to support session-level audit rows.
- `ToolAuditService` (`@Profile("!test")`) is invoked on every `SessionAgentManager.chat()` and `StreamingSessionAgentManager.streamChat()` call. Exceptions are swallowed and logged at WARN to avoid degrading chat flow.
- `ToolPriorityTuningService` (`@ConditionalOnProperty: mcp.tuning.enabled=true`, `@Profile("!test")`) runs on a daily cron (`mcp.tuning.cron`, default `0 0 2 * * ?`) and computes adaptive priorities over a 7-day window using the formula:

  performanceScore = 0.6 × successRate + 0.3 × (1 - min(avgLatency/2000, 1)) - 0.2 × fallbackRate

  The updated priority uses exponential smoothing: `newPriority = currentPriority × 0.7 + performanceScore × 0.3`, clamped to the range [0.1, 1.0].
- Document ingestion: `DocumentIngestionService` (interface + impl) exposes `POST /v1/mcp/documents` (permission `mcp:document:ingest`). Ingested content is injected into the `mcp_document_embedding` pgvector RAG store, and the endpoint responds with `201 Created`.

## Phase 5 — Streaming SSE and model fallback

- Streaming agents: `StreamingPosAssistant` / `StreamingSessionAgentManager` provide a LangChain4j `TokenStream`-backed per-user agent cache. The streaming orchestration exposes `POST /v1/mcp/chat/stream` (permission `mcp:chat:stream`) and produces `text/event-stream` SSE responses.
- Model fallback: `ModelFallbackConfiguration` (`@ConditionalOnProperty: mcp.model.fallback.enabled=true`) configures an optional secondary Ollama model. The secondary model name is controlled by `mcp.model.fallback.secondary-model-name` (default `mistral:7b`).

## Configuration knobs (additions for Phases 3–5)

Add the following to your `application.yml` or environment overrides to enable tuning and fallback behavior:

```yaml
mcp.tuning.enabled=true
mcp.tuning.cron=0 0 2 * * ?
mcp.model.fallback.enabled=false
mcp.model.fallback.secondary-model-name=${OLLAMA_FALLBACK_MODEL:mistral:7b}
```

## Permissions and Gateway catalog

- New API permissions:
  - `mcp:document:ingest` — POST `/v1/mcp/documents`
  - `mcp:chat:stream` — POST `/v1/mcp/chat/stream`
  - `mcp:chat:execute` — POST `/v1/mcp/chat` (replaces prior `isAuthenticated()` guard)
- NLTI permissions registered in the GatewayPermissionCatalog at bits 221–223: `nlti:request:submit`, `nlti:request:read`, `nlti:audit:read`.
- `GatewayPermissionCatalog` and `PermissionCode` have been updated to `CATALOG_VERSION=3` (bits 221–226 now cover NLTI/MCP runtime permissions).
