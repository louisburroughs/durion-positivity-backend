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
- Keep exactly one system **CASH house account** per tenant — the registered customer a walk-in sale paid in full is recorded against (see [CASH house account](#cash-house-account-cap550-s7-2505))

## Key Classes

- `CustomerService` — core customer lifecycle (create, read, update, deactivate)
- `PartyService` — generic party model shared by person and commercial entities
- `PersonService` — person-specific attributes linked to party
- `AccountTierService` — evaluates and assigns customer loyalty tiers
- `PromotionRedemptionService` — validates and records promotion code redemptions
- `CrmVehicleService` — read-side vehicle queries from the `ext_vehicle` replica; associations stay customer-owned per ADR-0012
- `HouseAccountProvisioner` / `HouseAccountProvisioningService` — create each tenant's CASH house account at startup and on a sweep
- `HouseAccountGuard` — the one service-layer check that refuses every write to a house account

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

### Creating customers

- An individual is created with `POST /v1/crm/persons` (`createCrmPerson`): the name and contact points go to pos-people and a person party links to them. The response carries `partyId` (what `getParty`, vehicles, estimates and appointments take) and `personId` (what `getPerson` takes).
- A business is created with `POST /v1/crm/accounts/parties` (`createCrmCommercialAccount`). Its optional `partyType` must be `COMMERCIAL`; anything else, `PERSON` included, is a `400`. A commercial row typed `PERSON` has no person behind it, so it would list as an individual with no name, contact points or `personId`.

## Error codes

Every non-2xx response carries the platform `ApiError` envelope (see
[`durion/docs/architecture/api/ERROR_ENVELOPE.md`](../../durion/docs/architecture/api/ERROR_ENVELOPE.md)).
This table lists the replica-lag code and the house-account code; the module's other codes are defined
beside the `CrmExceptionHandler` that mints them.

| Code | Status | Description |
|------|--------|-------------|
| `VEHICLE_REPLICATION_PENDING` | 503 | `GET /v1/crm/{customerId}/vehicles/{vehicleId}`: the vehicle is not in the `ext_vehicle` replica yet (it arrives by `vehicle.events.v1`, and a vehicle id cannot be mapped to a VIN any other way). Carries `Retry-After` and `referenceId` = the vehicle id; not-yet, not no, so retry (#1994). A vehicle that is present but not associated with the customer stays `404 RESOURCE_NOT_FOUND` |
| `HOUSE_ACCOUNT_IMMUTABLE` | 409 | The target party is the tenant's system CASH house account, which no request may change, merge, delete or attach data to. Answered by every guarded write listed under [CASH house account](#cash-house-account-cap550-s7-2505), before any change is made and before any fact is queued. Not retryable |

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |
| `pos.customer.house-account.enabled` (`POS_CUSTOMER_HOUSE_ACCOUNT_ENABLED`) | `true` | Provision the CASH house account for every active tenant, at startup and on the sweep. `false` removes the provisioner (test contexts switch it off) |
| `pos.customer.house-account.sweep-interval-ms` (`POS_CUSTOMER_HOUSE_ACCOUNT_SWEEP_INTERVAL_MS`) | `3600000` | Fixed delay between provisioning sweeps; the sweep is what gives a tenant added while the service runs its account |

## CASH house account (CAP:550 S7, #2505)

Every tenant has exactly one system party that a walk-in sale paid in full is recorded against, so no
module has to invent a person for it (accounting workspace spec §4.4 item 2, decisions AW12 and AW13).

**Shape.** A `CommercialParty` with `houseAccount = CASH_SALE` (column `commercial_party.house_account`),
customer number `CASH`, legal and display name `Walk-in customer`, `status = ACTIVE`,
`lifecycleStage = ACTIVE`, tier `STANDARD` under a manual override, and the account marketing gate shut. It
has no tax id, address, billing rules, contacts, relationships, vehicles, tags, consents or communication
preferences. Its identity is the flag: never recognise it by name or number, and show a localised label
from the flag rather than the stored name.

**Provisioning.** `HouseAccountProvisioner` runs once at startup (failures are logged and never block
startup) and then every `pos.customer.house-account.sweep-interval-ms`. It visits each active tenant through
`TenantIterator` — tenant bound, one transaction per tenant, never the platform tenant — creates the account
when the tenant has none, and queues its `customer.party.updated` fact through the outbox in the same
transaction. It is idempotent: at most one per tenant is enforced by the partial unique index
`commercial_party_house_account_uk (tenant_id, house_account) WHERE house_account IS NOT NULL`, and an
instance that loses a race on it treats the tenant as already provisioned and queues no second fact. A
tenant whose transaction fails is logged at WARN and retried on the next sweep. The sweep stands in until
pos-customer consumes `tenant.events.v1`. Provisioning only creates the account; no earlier sale, invoice or
party is reassigned to it (AW13). Counter: `customer.house_account.provisioned{outcome=created|existing|failed}`.

Three log lines call for an operator:

- **ERROR `House account provisioning is blocked for tenant … party … already holds customer number CASH`.** The
  number is unique per tenant and the legacy `POST /v1/crm` / `PUT /v1/crm/{id}` paths take a caller-supplied
  number as given. Renumber the named party; every sweep fails for that tenant until then.
- **WARN `… was created while fact publication is disabled`.** The account was created in a profile with no
  outbox writer, so no `customer.party.updated` fact was queued and no sweep will queue one later. Once
  publication is on, call `POST /v1/crm/accounts/facts/replay` so consumers learn of the account.
- **WARN `The tenant registry snapshot was incomplete …`.** The tenants the registry listed were provisioned; one
  it was missing (`pos-tenant` unreachable at that moment) gets its account from the next sweep that sees it.

**Fact and reads.** `CustomerPartyUpdatedV1` carries `houseAccount` (`"CASH_SALE"` for the house account,
`null` for every other party; additive within schema version 1, and fact replay re-emits it).
`GetPartyResponse`, `SearchPartiesResponse.PartySummary` and `CustomerDTO` carry the same field. Browse and
search still return the house account; the client decides whether to show it.

**Guards.** `HouseAccountGuard.requireNotHouseAccount(partyId)` is called from the service method behind each
of these writes, so every one answers `409 HOUSE_ACCOUNT_IMMUTABLE` when its target is a house account:

- `PUT /v1/crm/{id}`, `DELETE /v1/crm/{id}`
- `POST /v1/crm/accounts/parties/{partyId}/merge` (as survivor in the path or as `losingPartyId` in the body),
  `POST …/communicationPreferences`, `POST …/vehicles`, `PUT …/billing-rules`
- `POST /v1/crm/parties/{partyId}/communicationPreferences`
- `PUT /v1/crm/parties/{partyId}/marketing-consent`, `PUT …/marketing-consent/account-gate`
- `PUT /v1/crm/parties/{partyId}/contacts/{contactId}/roles`
- `POST /v1/crm/parties/{partyId}/follow-ups`, `POST /v1/crm/parties/{partyId}/interactions`
- `POST /v1/crm/commercial-accounts/{partyId}/relationships`, `PUT …/relationships/{relationshipId}/primary-billing`,
  `DELETE …/relationships/{relationshipId}` (the path party, and the account the relationship belongs to)
- `POST /v1/crm/parties/{partyId}/tags`, `DELETE /v1/crm/parties/{partyId}/tags/{tagId}`
- `POST /v1/crm/inquiries/{inquiryId}/convert` when `existingPartyId` names the house account (an inquiry carries
  a person's contact details)
- `POST /v1/crm/segments/{segmentId}/members` (any listed party), `DELETE /v1/crm/segments/{segmentId}/members/{partyId}`

The guard's lookup runs under the bound tenant, so another tenant's house account id is simply not found
(ADR-0062). A new write that takes a party id must call the guard too.

Facts are not requests, so they are never refused: when a `vehicle.events.v1` fact names the house account as
a vehicle's owner, `VehicleEventsListener` still writes the `ext_vehicle` replica row but skips the party
association and logs a WARN, rather than failing the consumer.

**Left out of pos-customer's own analytics.** Segment candidates, attribute previews and static membership
(`SegmentResolutionService`), the duplicate check (`checkPartyDuplicates`), and tier resolution
(`resolveAccountTier` answers the stored tier without recalculating). Money measures by customer are served
by pos-invoice and pos-accounting, which exclude it from their own replicas of the flag.

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
| `ManifestPublisher.publishDueManifest` | platform-scoped | Groups the window's `event_outbox` rows by `tenant_id` and publishes one manifest per tenant, stamped with that tenant; every active tenant of the registry gets one, zero-count when it published nothing |
| `ServiceDueReminderJob.generateReminders` | per-tenant | `service_history` and `follow_up_task` are scoped; one run per tenant of the registry |
| `HouseAccountProvisioner.sweep` | per-tenant | `commercial_party` is scoped; one transaction per tenant of the registry, at startup and on the sweep |

The one native query, `CommercialPartyRepository`'s `nextval('commercial_party_customer_number_seq')`, carries
`@TenantAudited`: it reads a platform-wide sequence, not a table.

Proof: `TenantIsolationIT` (tenant A's `party_tag` row is invisible to tenant B and to an unbound
connection, through the repository and through raw SQL) and `TenancySchemaConformanceIT` (every
non-whitelisted table has `tenant_id`, RLS enabled and forced, and the `tenant_isolation` policy; the pool is
`pos_app` with no bypass; the house-account unique index leads with `tenant_id`), both on Testcontainers Postgres (`./mvnw -pl pos-customer -am verify`), next to the
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
(`tenant_id` as data on the global outbox table, see Multitenancy above), `V3__commercial_party_house_account.sql`
(the `house_account` marker, its check constraint and the one-per-tenant partial unique index) and the repeatable
operational seed, which binds the alpha default tenant for its own transaction.

## Development

```bash
./mvnw -pl pos-customer -am spring-boot:run
```

## Reconciliation manifest replay requests (#2452)

A manifest listener that finds drift sends the owner's `outbox.replay-requested` command through
`OutboxReplayRequests`, which waits up to 30s for the broker's acknowledgement. A request that cannot
be handed to Kafka, that the broker rejects, or that is not acknowledged in time propagates to `KafkaErrorHandlingConfig`, which retries the manifest with backoff and then dead-letters it to `{topic}.dlq`.
Swallowing it would lose the repair for good, because each owner publishes a window's manifest once
and no later manifest covers that window again. Redelivery is safe: a manifest writes nothing, the
comparison only reads, and the replay command is keyed by window start. A manifest that does not parse
is still dropped.
