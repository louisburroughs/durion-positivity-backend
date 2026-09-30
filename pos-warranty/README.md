# pos-warranty

Warranty registrations, claims, settlements, vendor reimbursements and part returns
(`docs/PRD-warranty-claims-module.md`). Conventions and commands: `AGENTS.md` at the repository root.

## Error codes

Every non-2xx response carries the platform `ApiError` envelope (see
[`durion/docs/architecture/api/ERROR_ENVELOPE.md`](../../durion/docs/architecture/api/ERROR_ENVELOPE.md)).
This table lists only the replica-lag code; the module's other codes are defined beside the
`WarrantyExceptionHandler` that mints them.

| Code | Status | Description |
|------|--------|-------------|
| `WORKORDER_REPLICATION_PENDING` | 503 | `POST /v1/warranty/claims/{id}/settlements` with `REPLACEMENT_WORKORDER`: the replacement workorder is not in the `ext_workorder` replica yet (it arrives by `workorder.events.v1`). Nothing is recorded. Carries `Retry-After` and `referenceId` = the workorder id; not-yet, not no, so retry (#1994). Replaces the former `422 WARRANTY_SETTLEMENT_WORKORDER_NOT_FOUND` |

## Multitenancy (ADR-0062, WS3 wave 8)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`, every scoped entity
extends `TenantScopedEntity`, and the two global tables listed in
`src/main/resources/db/tenancy-global-tables.txt` (`event_outbox`, `processed_events`) carry
`@TenantGlobal`. The request tenant is bound by `TenantContextFilter` from `X-Tenant-Id` (the gateway
injects it from the token's `tid`), the Kafka tenant by `TenantRecordInterceptor` from the `tenantId` record
header on all five consumers (catalog, invoice, location, vehicle and workorder events), and every
connection checkout binds `app.current_tenant` for row-level security. `pos.tenancy.default-tenant-id`
still binds the alpha default tenant on every unbound path (tokens issued before `tid`, records without
the header).

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, `FlywayConfig`). The `V2` seed binds the alpha default tenant for its own
transaction, so it runs on the owner credential unchanged.

The outbox row carries the producing tenant as data (`tenant_id`, stamped from the bound tenant by
`OutboxEventWriter`, added by `V3__event_outbox_tenant_id.sql`); the one scheduled job,
`OutboxPublisher.publishPending`, is platform-scoped (it drains the global `event_outbox` and puts each
row's `tenant_id` on the record header). There are no native queries.

The H2 slices (`ddl-auto=create-drop`) generate `tenant_id` from the mapping and run as the alpha default
tenant; they prove nothing about isolation.

Proof: `TenantIsolationIT` (tenant A's `warranty_provider` row is invisible to tenant B and to an unbound
connection, through the repository and through raw SQL) and `TenancySchemaConformanceIT` (every
non-whitelisted table has `tenant_id`, RLS enabled and forced, and the `tenant_isolation` policy; the pool is
`pos_app` with no bypass), both on Testcontainers Postgres (`./mvnw -pl pos-warranty -am verify`).
