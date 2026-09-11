# pos-tenant

Tenant registry service for the Durion Positivity platform (ADR-0062 §7, plan WS2a). Owns the
master `tenant` table and the customer `account` that owns each tenancy, with its contacts and
billing profile. `pos-security-service` does not own the registry; it consumes it.

## Responsibilities

- Register tenants under an account and move them through the lifecycle
  (`PENDING` → `ACTIVE` ⇄ `SUSPENDED`, `DECOMMISSIONED` terminal)
- Publish the public tenant projection on `tenant.events.v1` so every module can keep an
  `ext_tenant` replica
- Consume `tenant.provisioned` from `pos-security-service` and activate the tenant
- Keep account, contact and billing data, which never leaves this module
- Bootstrap the reserved platform tenant (`01900000-0000-7000-8000-000000000000`, slug `platform`)

## Key Classes

- `TenantService` — registry, status machine, `tenant.provisioned` handling
- `AccountService` — accounts, contacts, billing profile
- `TenantFactPublisher` — envelopes on `tenant.events.v1` through the transactional outbox
- `TenantEventsListener` / `TenantProvisioningHandler` — `tenant.provisioned` consumer, idempotent
  by `eventId` through `processed_events`
- `PlatformTenantGuard` — 403 `PLATFORM_TENANT_REQUIRED` for any request bound to a tenant other
  than the platform tenant
- `InternalTenantRegistryController` / `TenantRegistrySecretFilter` — the shared-secret list
  endpoint other services' `RemoteTenantRegistry` polls (plan WS4-2)

## API Endpoints

Reached through the gateway under `/tenant` (`X-API-Version: 1`). Every operation needs a
`platform:tenant:*` or `platform:account:*` authority, which only the platform tenant's role
template grants.

- `POST /v1/platform/tenants` — register a tenant (`PENDING`, emits `tenant.created`)
- `GET /v1/platform/tenants?status=` — list tenants
- `GET /v1/platform/tenants/{id}` — one tenant
- `PATCH /v1/platform/tenants/{id}` — display name / cell (emits `tenant.updated`)
- `POST /v1/platform/tenants/{id}/suspend` | `/reactivate` | `/decommission`
- `POST /v1/platform/accounts`, `GET /v1/platform/accounts[/{id}]`, `PATCH /v1/platform/accounts/{id}`
- `POST /v1/platform/accounts/{id}/contacts`, `PUT|DELETE /v1/platform/accounts/{id}/contacts/{contactId}`
- `PUT /v1/platform/accounts/{id}/billing-profile`

### Internal registry endpoint (not through the gateway)

`GET /internal/v1/tenants[?status=ACTIVE]` returns a JSON array of the public projection
(`{tenantId, slug, displayName, status}`, the `TenantProjectionV1` shape) for every tenant in the
status, oldest first; `status` defaults to `ACTIVE`. It exists for `pos-tenancy-common`'s
`RemoteTenantRegistry` (`pos.tenancy.registry.mode=REMOTE`, plan WS4-2, decided 2026-09-10):
modules that run per-tenant scheduled work fetch the active tenants from here instead of keeping
an `ext_tenant` replica each.

- Authentication is the shared secret header `X-Tenant-Registry-Secret`, compared in constant
  time against `pos.tenant.registry.api-secret` (`POS_TENANT_REGISTRY_API_SECRET`). A wrong or
  missing header is a 401 `ApiError` `INVALID_TENANT_REGISTRY_SECRET`; an unconfigured secret
  refuses every call with 401 `TENANT_REGISTRY_SECRET_MISSING`.
- The path has its own security chain (`SecurityConfig`), is hidden from the OpenAPI document,
  needs no `platform:*` authority and emits no event.
- The caller carries no tenant, so the path is in `pos.tenancy.unenforced-paths` and the
  secret filter binds the platform tenant at the request edge (every registry row belongs to it under RLS).

Callers reach it by Eureka name (`http://tenant/internal/v1/tenants`) through a `@LoadBalanced` client, or by DNS host (`http://pos-tenant:8080/internal/v1/tenants` in Compose) with a plain one; never via the gateway.

## Events (`tenant.events.v1`, keyed by tenant id)

| Type | Producer | Payload |
| --- | --- | --- |
| `tenant.created` | pos-tenant | `TenantCreatedV1`: projection + `initialAdminEmail` (provisioning input) |
| `tenant.provisioned` | pos-security-service (plan WS2b) | `TenantProvisionedV1` |
| `tenant.updated`, `tenant.suspended`, `tenant.reactivated`, `tenant.decommissioned` | pos-tenant | `TenantProjectionV1`: `tenantId`, `slug`, `displayName`, `status` |

Consumers rebuild their `ext_tenant` row from every payload; account, contact and billing data
are never published.

## Multitenancy (ADR-0062 §7)

The module is born adopted: every entity extends `TenantScopedEntity`, and every row carries the
platform tenant's id, so row-level security protects the registry like any other table. There is no
unbound path:

- HTTP: `TenantContextFilter` binds the gateway's `X-Tenant-Id` (the platform default until plan
  WS2b delivers the `tid` claim); `PlatformTenantGuard` then refuses any other tenant with 403.
- Kafka: `tenant.provisioned` arrives bound to the provisioned tenant; the listener re-binds the
  work to the platform tenant (`TenantContext.runAs(PlatformTenant.ID, ...)`), the one documented
  place application code binds a tenant.
- Outbox poller: `@PlatformScoped`, drains the global `event_outbox` and stamps each row's tenant
  (the platform tenant) on the record header.

`pos.tenancy.default-tenant-id` defaults to the platform tenant and must never point at a customer
tenant. Global tables: `db/tenancy-global-tables.txt`.

## Configuration

| Property | Default | Purpose |
| --- | --- | --- |
| `pos.tenant.registry.api-secret` | (blank: refuse) | Shared secret for `GET /internal/v1/tenants` (`POS_TENANT_REGISTRY_API_SECRET`) |
| `pos.tenancy.unenforced-paths` | `/internal/v1/tenants` | Paths `TenantContextFilter` never refuses for lack of a tenant |
| `pos.tenant.kafka.enabled` | `false` | Outbox drain, fact publishing and the `tenant.provisioned` consumer |
| `pos.tenant.kafka.events-topic` | `tenant.events.v1` | Fact topic |
| `pos.tenant.kafka.events-consumer-group` | `pos-tenant-events` | Consumer group |
| `pos.tenant.outbox.poll-interval-ms` / `send-timeout-ms` | `1000` / `10000` | Outbox drain |

## Database

`pos_tenant_db`; Flyway `V1__baseline_tenant.sql` (schema with RLS) and `V2__seed_tenant.sql`
(platform account and tenant, plus the alpha default tenant `…0001` that every module's seed rows
already belong to). Compose runs the pool as `pos_app` with Flyway on the owner credential.

## Development

```bash
./mvnw -pl pos-tenant -am test                       # unit + web-slice tests (H2)
./mvnw -pl pos-tenant verify                         # + TenantIsolationIT / TenancySchemaConformanceIT (Docker)
cd pos-tenant && ../mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```
