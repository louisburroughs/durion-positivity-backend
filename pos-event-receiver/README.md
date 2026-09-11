# pos-event-receiver

Central event aggregation and storage service for the Durion Positivity ETSMS platform. Receives `@EmitEvent` emissions from all microservices, persists them, computes hourly roll-ups, and exposes summary and query endpoints for observability dashboards and audit tooling.

## Responsibilities

- Receive and persist emitted events from all `pos-*` services
- Register and manage event type definitions with performance thresholds
- Compute hourly event count roll-ups for reporting
- Expose event summary endpoints (last hour, last day, last week)
- Authenticate inbound event writes using a shared API secret (avoids circular JWT dependency)
- Pre-register own event types at startup via `EventTypeInitializer`

## Key Classes

- `EmitEventService` — processes inbound event emission requests and persists to `emitted_event`
- `EventTypeService` — CRUD for event type registrations (type code, thresholds, description)
- `EventSummaryService` — aggregates and returns event count summaries by time window
- `EmitEventController` — handles `POST /v1/events` and `GET /v1/events/{id}`
- `EventTypeController` — manages event type catalog via `PUT /v1/eventTypes/code/{typeCode}`
- `EventsApiSecurityFilter` — validates the `X-Events-Api-Secret` header on write endpoints

## API Endpoints

- `POST /v1/events` — emit an event
- `GET /v1/events/{id}` — retrieve an event by ID
- `GET /v1/events/active` — list active events
- `DELETE /v1/events/{id}` — delete an event record
- `GET /v1/eventTypes` — list all event type registrations
- `GET /v1/eventTypes/code/{typeCode}` — get event type by code
- `PUT /v1/eventTypes/code/{typeCode}` — register or update an event type
- `GET /v1/events/summary/lastHour` — event counts for last hour
- `GET /v1/events/summary/lastDay` — event counts for last day
- `GET /v1/events/summary/lastWeek` — event counts for last week

The three summary endpoints take an optional `tenantId` (platform-tenant callers only; see "Per-tenant
statistics" below).

## Configuration

| Property                | Default  | Description                                  |
| ----------------------- | -------- | -------------------------------------------- |
| `pos.events.api-secret` | (empty)  | Shared secret for event write authentication |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL                    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL                 |

## Multitenancy (ADR-0062, WS3 wave 13)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`. The request tenant is bound by
`TenantContextFilter` from `X-Tenant-Id` (the gateway injects it from the token's `tid` on every
`/event-receiver/**` call), and every connection checkout binds `app.current_tenant`. `pos.tenancy.default-tenant-id`
still binds the alpha default tenant on every unbound path.

`emitted_event` is the platform's one **exception to row-level security** (ADR-0062, decided 2026-09-10): TimescaleDB
refuses compression and continuous aggregates on a hypertable with row security (`compression cannot be used on
table with row security`, `cannot create continuous aggregate on hypertable with row security`), and the stream
keeps both. `V1_1__emitted_event_no_row_security.sql` drops the policy (out of order on databases that already
carry `V2`, hence `spring.flyway.out-of-order: true`), the table is whitelisted in
`src/main/resources/db/tenancy-global-tables.txt`, and `EmittedEvent` is `@TenantGlobal` with `tenant_id` as a
data column: `EventDaoImpl` stamps it from the bound request on every row, and every query in
`EmittedEventRepository` names it; the repository is a marker `Repository`, not a `JpaRepository`, so no inherited
`findAll()`/`findById()` can read across tenants, and `V1_1` leads the entity-lookup index with `tenant_id`.
**Never query `emitted_event` without the tenant predicate.** The event-type
registry (`event_type`, `preregistered_event`) is global as before.

### Per-tenant statistics with global rollups (plan WS6, decided 2026-09-10)

The `emitted_event_hourly` continuous aggregate (`V2`) is grouped by `tenant_id` as well as event type: a tenant's
hourly statistics are its own rows, and the global view is the sum across tenants. Like the hypertable it is
built on, the aggregate has no row-level security (TimescaleDB excludes it), so `tenant_id` is a data column and
the query shape is the isolation. `EmittedEventHourlyRepository` is a marker `Repository` with exactly two reads:
`summarizeSince(tenantId, since)`, which names the tenant, and the `@TenantAudited` rollup
`summarizeAcrossTenantsSince(since)`; `EmittedEventHourlyRepositoryQueryShapeTest` holds that shape.

`EventSummaryServiceImpl` picks the scope for `GET /v1/events/summary/{lastHour,lastDay,lastWeek}`:

| Caller bound to (`X-Tenant-Id`) | `?tenantId=` | Reads |
| --- | --- | --- |
| an ordinary tenant | omitted | its own tenant's counts |
| an ordinary tenant | any value (even its own id) | 403 `FORBIDDEN` (`TenantScopeForbiddenException`, via the shared handler) |
| the platform tenant (`PlatformTenant.ID`) | omitted | the global rollup, summed across tenants |
| the platform tenant | one tenant | that tenant's counts |

The refresh policy (`add_continuous_aggregate_policy`, hourly with a one-hour end offset) and the compression
policy are unchanged; compression now segments by `(tenant_id, id)` so per-tenant reads of compressed chunks stay
selective. `V2` is edited in place (`docs/TENANCY_SCHEMA.md`): a database that already carries the tenant-less
aggregate is reset, not migrated. The H2 `dev` profile builds the view as a plain table from `EmittedEventHourly`
(`ddl-auto: create-drop`), so the JPQL reads work there unchanged.

Every log line of this module carries the bound tenant in Boot's correlation bracket,
`[<trace_id>,<span_id>,<tenantId>]`, supplied by `pos-tenancy-common`'s `TenantLogPatternEnvironmentPostProcessor`
and extracted by Promtail as the Loki `tenant` label (`observability/README.md`).

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, `FlywayConfig`), which the TimescaleDB steps in `V2` need anyway.

Ingest is batched: `EventDaoImpl` stamps the request's tenant on each event as it is queued, and the one scheduled
job, `flushEventBatch`, is platform-scoped because it drains the batch for every tenant; the stamped column is each
row's tenant (`EventDaoImplTenantTest`). There are no native queries.

Proof: `TenantIsolationIT` (both tenant-bound queries return only their tenant's rows, raw SQL sees both, a row
without a tenant is refused, the hypertable still carries compression and the continuous aggregate, and the
aggregate holds one row per tenant with the per-tenant read and the rollup agreeing on the sum) and
`TenancySchemaConformanceIT` (every non-whitelisted table has `tenant_id`, RLS enabled and forced, and the
`tenant_isolation` policy; the whitelisted ones have neither; the pool is `pos_app` with no bypass), both on a
Testcontainers TimescaleDB (`./mvnw -pl pos-event-receiver -am verify`).

## Dependencies

No internal `pos-*` module dependencies (avoids circular dependency with `pos-security-service`).

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-event-receiver -am spring-boot:run
```
