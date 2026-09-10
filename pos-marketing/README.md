# pos-marketing

Campaigns, message templates, audiences and sends. Conventions and commands: `AGENTS.md` at the repository root.

## Multitenancy (ADR-0062, WS3 wave 10)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`, every scoped entity extends `TenantScopedEntity`, and the two global tables (`event_outbox`, `processed_events`, listed in
`src/main/resources/db/tenancy-global-tables.txt`) carry `@TenantGlobal`. The request tenant is
bound by `TenantContextFilter` from `X-Tenant-Id` (the gateway injects it from the token's `tid`), the Kafka tenant by `TenantRecordInterceptor` from the `tenantId` record header on the
three consumers, and every
connection checkout binds `app.current_tenant` for row-level security. `pos.tenancy.default-tenant-id` still binds
the alpha default tenant on every unbound path.

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, `FlywayConfig`).

The outbox row carries the producing tenant as data (`tenant_id`, stamped from the bound tenant by
`OutboxEventWriter`, added by `V2__event_outbox_tenant_id.sql`); the one scheduled job, `OutboxPublisher.publishPending`, is
platform-scoped (it drains the global `event_outbox` and puts each row's `tenant_id` on the record header). There
are no native queries.

Proof: `TenantIsolationIT` (tenant A's `message_template` row is invisible to tenant B and to an unbound
connection, through the repository and through raw SQL) and `TenancySchemaConformanceIT` (every
non-whitelisted table has `tenant_id`, RLS enabled and forced, and the `tenant_isolation` policy; the pool is
`pos_app` with no bypass), both on Testcontainers Postgres (`./mvnw -pl pos-marketing -am verify`).

