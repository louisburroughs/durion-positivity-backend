# pos-customer

CRM service for the Durion Positivity ETSMS platform. Manages the customer party model (persons, commercial parties, organisations), contact roles, communication preferences, party relationships, account tiers, promotion redemptions, and customer-linked vehicles.

## Responsibilities

- Create and manage customers as parties (person and commercial entities)
- Track contact roles and communication preferences per party
- Manage party relationships (e.g., fleet owner to vehicle)
- Assign and query account tiers for loyalty programs
- Record and validate promotion redemptions
- Maintain customer-linked vehicle associations (CRM vehicles); the vehicle records themselves are a read-only `ext_vehicle` replica fed by `vehicle.events.v1` from pos-vehicle-inventory (ADR-0044 §6, #843) — vehicle registry writes go directly to pos-vehicle-inventory through the gateway
- Project `vehicle.care-preference.updated` facts into a read-only `ext_vehicle_care_preference` replica (#1175); the `service.due` segment attribute and the `SERVICE_DUE_REMINDER` job resolve each vehicle's effective service interval as its replicated care-preference override, falling back to `pos.customer.crm.service-due-months`
- Project workorder facts from `workorder.events.v1` (`WorkorderEventsListener`): service completions into service history, declined service lines into follow-up tasks, and customer notes into `party_note` plus the `WORKORDER_NOTE` interaction timeline (#1584)
- Consume `vehicle.events.v1` (`VehicleEventsListener`, idempotent via `processed_events`) and reconcile the replica against `vehicle.manifest.v1` manifests (`VehicleManifestListener`)
- Support bulk customer import via `POST /v1/customer/bulk-ingest`

## Key Classes

- `CustomerService` — core customer lifecycle (create, read, update, deactivate)
- `PartyService` — generic party model shared by person and commercial entities
- `PersonService` — person-specific attributes linked to party
- `AccountTierService` — evaluates and assigns customer loyalty tiers
- `PromotionRedemptionService` — validates and records promotion code redemptions
- `CrmVehicleService` — read-side vehicle queries from the `ext_vehicle` replica; associations stay customer-owned per ADR-0012

## API Endpoints

- `GET /v1/customers/{id}` — retrieve a customer
- `POST /v1/customers` — create a customer
- `DELETE /v1/customers/{id}` — deactivate a customer
- `GET /v1/customers/{accountId}/tier` — get account tier
- `GET /v1/parties/{partyId}` — retrieve a party
- `GET /v1/crm/accounts/parties/duplicate-check?legalName=...` — check potential duplicate commercial parties
- `POST /v1/crm/accounts/parties:resolve` — batch-resolve party ids to display names (auth: `crm:party:view`); for sibling-service finder enrichment
- `GET /v1/parties/{partyId}/contacts` — list contact roles
- `GET /v1/parties/{partyId}/communicationPreferences` — communication preferences
- `PUT /v1/crm/accounts/parties/{partyId}/billing-rules` — upsert billing rules for a commercial party
- `GET /v1/crm/{customerId}/vehicles` — list vehicle summaries for a customer (replica-backed)
- `GET /v1/crm/{customerId}/vehicles/{vehicleId}` — get a linked vehicle (replica-backed; create/update/transfer/deactivate moved to pos-vehicle-inventory per ADR-0044)
- `POST /v1/customer/bulk-ingest` — bulk import customers (auth: `crm:party:create`)

### Unified Party Detail Behavior

- The browse endpoint `GET /v1/crm/accounts/parties` may include both `COMMERCIAL` and `PERSON` party types.
- Party-scoped detail reads now accept either party type for the same `partyId` domain.
- Endpoints that are commercial-account oriented (for example `GET /v1/crm/commercial-accounts/{partyId}/contacts`) return an empty contact list for person parties instead of `404`.

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |

## Multitenancy (ADR-0062, WS3 wave 6)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`, every scoped entity
extends `TenantScopedEntity` (`AbstractParty` carries it for both party subclasses), and the two global tables
(`event_outbox`, `processed_events`, listed in `src/main/resources/db/tenancy-global-tables.txt`) carry
`@TenantGlobal`. The request tenant is bound by `TenantContextFilter` from `X-Tenant-Id` (the gateway injects
it from the token's `tid`), the Kafka tenant by `TenantRecordInterceptor` from the `tenantId` record header on
every one of the module's consumers, and every connection checkout binds `app.current_tenant` for row-level
security. `pos.tenancy.default-tenant-id` still binds the alpha default tenant on every unbound path (tokens
issued before `tid`, records without the header).

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, `FlywayConfig`).

The outbox row carries the producing tenant as data (`tenant_id`, stamped from the bound tenant by both
`OutboxEventWriter` methods):

| Job | Classification | Why |
| --- | --- | --- |
| `OutboxPublisher.publishPending` | platform-scoped | Drains `event_outbox`; each row's `tenant_id` becomes the record header |
| `ManifestPublisher.publishDueManifest` | platform-scoped | Summarises `event_outbox` per window across tenants; per-tenant manifests are plan WS8 |
| `ServiceDueReminderJob.generateReminders` | per-tenant | `service_history` and `follow_up_task` are scoped; one run per tenant of the registry |

The one native query, `CommercialPartyRepository`'s `nextval('commercial_party_customer_number_seq')`, carries
`@TenantAudited`: it reads a platform-wide sequence, not a table.

Proof: `TenantIsolationIT` (tenant A's `party_tag` row is invisible to tenant B and to an unbound
connection, through the repository and through raw SQL) and `TenancySchemaConformanceIT` (every
non-whitelisted table has `tenant_id`, RLS enabled and forced, and the `tenant_isolation` policy; the pool is
`pos_app` with no bypass), both on Testcontainers Postgres (`./mvnw -pl pos-customer -am verify`), next to the
existing `FlywayMigrationIT` and `CommercialCustomerNumberIT` on the same strict `pg` profile.

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-tenancy-common` — ADR-0062 tenant context, connection binding, Hibernate resolver, Kafka propagation
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-shared-dtos` — shared vehicle DTOs
- `pos-domain-events` — ADR-0044 envelope, topics, and versioned payload contracts
- `pos-bulk-ingest-lib` — bulk-ingest base controller

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`: `V1__baseline_customer.sql` (the
2026-09-09 flattened baseline with the tenancy schema on every scoped table), `V2__event_outbox_tenant_id.sql`
(`tenant_id` as data on the global outbox table, see Multitenancy above) and the repeatable operational seed, which
binds the alpha default tenant for its own transaction.

## Development

```bash
./mvnw -pl pos-customer -am spring-boot:run
```
