---
title: "Issue #823 — Event-Only Domain Walls: Scope Scan & Feasibility Assessment"
issue: 823
status: assessment
date: 2026-07-08
---

# Event-Only Domain Walls — Scope Scan & Feasibility Assessment

Assessment for [issue #823](https://github.com/louisburroughs/durion-positivity-backend/issues/823):
make communication with certain domain modules event-only, allow synchronous REST only toward
dedicated utility modules, and accept read-only data duplication kept in sync by events from the
owning module.

## Verdict

**Feasible and architecturally sound.** The proposal is classic event-carried state transfer, and
the codebase already contains working precedents: `pos-workorder` publishes a versioned JSON event
envelope to Kafka (`workorder.events.v1`), `pos-customer` consumes it, and `pos-accounting` /
`pos-vehicle-inventory` already have Kafka listeners waiting for topics (`payment.cleared.v1`,
`vehicle.updates`, `workorder.completed`) **that nothing produces yet** — the architecture has been
drifting this direction; this issue finishes the job.

The main costs are: (1) a set of platform mechanisms that do not exist yet (outbox, shared event
contracts, consumer idempotency, backfill, reconciliation), (2) eventual consistency in flows that
today validate synchronously, and (3) a handful of cross-module **writes** that read-model
duplication cannot solve and that must become async command events.

## Current module-to-module call graph (as scanned)

Synchronous REST business calls between domain modules, excluding startup registration
(permissions → security-service, event types → event-receiver) and external APIs (NHTSA, CarAPI,
Exa, external tax provider):

| Caller | Callee | Client class(es) | Read/Write |
|---|---|---|---|
| pos-accounting | pos-customer | `CustomerBillingRulesClient` | read |
| pos-accounting | pos-invoice | `InvoiceServiceClient` | read + **write** (apply-payment, reverse-payment, apply-credit-memo) |
| pos-accounting | pos-workorder | `WorkorderInvoiceClient` | **write** (generate-invoice) |
| pos-catalog | pos-inventory | `InventoryClientImpl` | read |
| pos-catalog | pos-price | `PricingClientImpl` | read (compute) |
| pos-customer | pos-people | `PeopleClient` (resolve, by-ids, contact-points) | read |
| pos-customer | pos-vehicle-inventory | `VehicleInventoryClient` | **full CRUD** (POST/GET/PUT/DELETE) |
| pos-inventory | pos-location | `SiteDefaultsClient`, `StorageLocationValidationClient`, `LocationRosterClient`, `StorageLocationTopologyClient` | read |
| pos-inventory | pos-workorder | `WorkorderValidationClient` | read |
| pos-invoice | pos-customer | `CustomerReferenceClient` | read |
| pos-invoice | pos-location | `LocationServiceClient` | read |
| pos-invoice | pos-people + pos-security-service | `ManagerApprovalClient` | read |
| pos-invoice | pos-workorder | `WorkorderReferenceClient` | read |
| pos-invoice | pos-tax | `TaxServiceClient` | read (compute) — utility |
| pos-invoice | pos-documents | `DocumentRenderClient` | utility |
| pos-location | pos-inventory | `LocationInventoryInquiryClient` | read |
| pos-location | pos-people | `PersonClient` | read |
| pos-people | pos-location | `LocationReferenceClient` | read |
| pos-people | pos-security-service | `SecurityServiceClient` | utility |
| pos-people | pos-workorder | `WorkexecJobTimeClient` | read |
| pos-security-service | pos-customer | `CustomerRegistrationClient` | read + **write** (registration/link) |
| pos-security-service | pos-people | `PeopleRegistrationClient` | read + **write** (user↔person link) |
| pos-shop-manager | pos-customer | `CrmCustomerClient`, `CrmVehicleClient` | read |
| pos-shop-manager | pos-people | `PersonClient`, `HrAvailabilityClient` | read |
| pos-shop-manager | pos-location | `LocationClient` | read |
| pos-shop-manager | pos-catalog | `ServiceEntityClient` | read |
| pos-workorder | pos-customer | `CustomerValidationClient` | read |
| pos-workorder | pos-inventory | `InventoryPickClient` | read + write (pick) |
| pos-workorder | pos-invoice | `InvoiceClient` | read + write |
| pos-workorder | pos-people | `PeopleAvailabilityClient`, `PeopleLocationClient` | read |
| pos-workorder | pos-location | `LocationClient` (tax address) | read |
| pos-workorder | pos-shop-manager | `ShopmgrOperationalContextClient` | read |
| pos-workorder | pos-tax | `TaxClient` | read (compute) — utility |
| pos-workorder | pos-documents | `DocumentClient` | utility |

Not affected:

- **pos-order** — its `internal/client` "ports" (`BillingPort`, `InventoryPort`, `PricingPort`,
  `SourceDocumentPort`, `WorkexecPort`) are in-memory default adapters; no live REST calls.
- **pos-price** — `AccountDataProvider` / `VehicleDataProvider` are stubs.
- **pos-mcp-server** — facade tools route through the gateway with end-user bearer tokens
  (client-equivalent traffic; documented gateway-exception).
- **pos-vehicle-fitment / pos-vehicle-reference-carapi / pos-vehicle-reference-nhtsa** — external
  API callers only.
- **pos-bulk-loader, pos-documents, pos-tax** — no outbound domain calls (startup-infra/external only).

### Existing async messaging (Kafka)

| Topic | Producer | Consumer(s) |
|---|---|---|
| `workorder.events.v1` | pos-workorder `KafkaProducer` (envelope: eventId UUIDv7, eventType, occurredAtUtc, sourceService, payload) | pos-customer `WorkorderEventHandler` |
| `workorder.commands.v1` | *(none in repo)* | pos-workorder `KafkaCommandListener` |
| `payment.cleared.v1` | **none** | pos-accounting `PaymentEventListenerConfig` |
| `vehicle.updates` | **none** | pos-vehicle-inventory `EventListenerConfig` |
| `workorder.completed` | **none** | pos-vehicle-inventory `EventListenerConfig` |

The `@EmitEvent` / `pos-events` / `pos-event-receiver` pipeline is REST-based audit/analytics
ingestion — it is **not** a module-to-module messaging channel and stays audit-only.

## Decisions (confirmed 2026-07-08)

1. **Event backbone: Kafka domain topics.** Each owning module publishes versioned topics
   (`{domain}.events.v1`) using the existing workorder envelope pattern. `pos-event-receiver`
   remains audit-only.
2. **Write flows become async command events.** E.g. accounting publishes a payment-cleared event;
   invoice consumes it, applies the payment, and emits `invoice.payment-applied` back. UI flows
   must tolerate pending states.
3. **Utility whitelist (sync calls allowed):** `pos-api-gateway`, `pos-security-service`,
   `pos-documents`, `pos-image`, `pos-tax` (per ADR-0021, stateless compute), `pos-event-receiver`,
   and `pos-price` (pricing is compute, not data lookup). Startup registrations (permissions,
   event types, document templates) stay as-is.
4. **Vehicle owns its writes.** Vehicle create/update/delete moves out of customer CRM entirely —
   the frontend talks to pos-vehicle-inventory through the gateway (`lb://VEHICLE-INVENTORY` route
   already exists); pos-customer keeps only a read-only vehicle mirror fed by vehicle events.
   pos-vehicle-fitment and the vehicle-reference modules are unaffected (external lookups).

## Gaps: mechanisms that must exist before any module migrates

The issue explicitly calls out staying in sync. None of the following exists today:

1. **Transactional outbox** — publishing to Kafka inside a `@Transactional` service method is not
   atomic with the DB commit. Each producer module needs an outbox table (Flyway migration) and a
   poller/publisher, or an equivalent reliable-publish mechanism. Without this, replicas silently
   diverge.
2. **Shared event contracts** — a new non-deployed library (e.g. `pos-domain-events`) holding the
   envelope record (add `schemaVersion` and `aggregateVersion` to the workorder envelope) and
   versioned payload DTOs per domain, so producers and consumers compile against the same contract.
   Cross-module DTO sharing via this lib needs an explicit ArchUnit allowance (like
   `pos-shared-dtos`).
3. **Idempotent consumers** — a `processed_events` table keyed by `eventId` per consumer module,
   plus retry and dead-letter-topic conventions. Kafka is at-least-once; replays must be harmless.
4. **Bootstrap/backfill** — replicas start empty. Owning modules need a snapshot export (bulk
   endpoint or re-emit-all), used both for initial seeding and for drift repair.
   `aggregateVersion` on events lets consumers detect gaps and order updates.
5. **Reconciliation** — a scheduled drift check (per-aggregate version/count/checksum comparison
   against the owner) with automated re-sync, so duplication stays trustworthy long-term.
6. **Kafka as a first-class runtime dependency** — today Kafka usage is optional
   (`@ConditionalOnProperty`). Event-only modules make it mandatory in docker-compose/alpha/prod
   for every participating service; ops/observability (lag monitoring, DLQ alerting) must follow.
7. **ArchUnit enforcement** — a cross-module rule in `pos-archunit`: `internal/client` REST clients
   may only target the utility whitelist. This makes the wall permanent rather than aspirational.

## Conflicts with existing documentation (to be superseded by the ADR)

- `pos-workorder .. LocationClient` javadoc states the platform rule: *"other services must not
  replicate address data: they store the locationId and query pos-location for the full address."*
  The new ADR **reverses** this rule — consumers hold read-only address/location mirrors.
- `docs/service-discovery-migration/client-policy-matrix.md` classifies nearly all of the calls
  above as `direct-discovery` (i.e. blessed sync calls). The ADR supersedes that classification for
  domain-to-domain traffic; the matrix remains valid for utility and startup-infra categories.

## Proposed phasing (module by module)

**Phase 0 — Foundations** (no behavior change): ADR; `pos-domain-events` contract lib; outbox
template + Flyway; consumer idempotency helper; DLQ/retry conventions; backfill pattern; Kafka in
compose/alpha for all participating modules; ArchUnit rule added in report-only mode.

**Phase 1 — Accounting event-only** (smallest blast radius; its listener already exists):
- pos-customer publishes billing-rules/account events → accounting local mirror (retires
  `CustomerBillingRulesClient`).
- pos-invoice publishes invoice lifecycle events → accounting read model (retires the read half of
  `InvoiceServiceClient`).
- Accounting's writes become commands: payment-cleared / credit-memo events consumed by pos-invoice;
  invoice-regeneration command consumed by pos-workorder; each emits a result event back.

**Phase 2 — Vehicle**:
- pos-vehicle-inventory publishes `vehicle.events.v1` (finally producing what its own listener
  design anticipated).
- Vehicle CRUD moves from pos-customer CRM to the frontend → gateway → pos-vehicle-inventory.
- pos-customer builds a read-only vehicle mirror; retires `VehicleInventoryClient`.
  (Frontend contract change — coordinate with durion frontend.)

**Phase 3 — People split (contact vs HR)**:
- New `pos-people-contact` module owning `Person`, `PersonContactPoint`, `UserPersonLink`
  (identity + contact). `pos-people` keeps HR: `Employee`, timekeeping, availability, staffing,
  work sessions.
- Contact module publishes person/contact events; pos-customer, pos-invoice, pos-location,
  pos-shop-manager, pos-workorder hold person-reference mirrors.
- security-service user↔person linking becomes command + confirmation events.
- people-HR publishes availability/assignment events for pos-workorder and pos-shop-manager;
  pos-workorder's job-time data reaches people-HR via `workorder.events.v1` (retires
  `WorkexecJobTimeClient`).

**Phase 4 — Customer & location decoupling**:
- pos-customer publishes `customer.events.v1` (party/account reference); pos-invoice,
  pos-shop-manager, pos-workorder retire their customer clients.
- pos-location publishes `location.events.v1` (profile, addresses, site defaults, storage
  topology); pos-inventory (4 clients), pos-people, pos-shop-manager, pos-workorder, pos-invoice
  retire their location clients.

**Phase 5 — Remaining edges + enforcement**:
- workorder↔invoice, workorder↔inventory (pick), inventory→workorder validation,
  location→inventory inquiry, workorder→shop-manager context, catalog→inventory availability
  (note: stock levels are high-churn; consider an event-fed cache with freshness bounds).
- Flip the ArchUnit rule from report-only to failing.

## Risks / trade-offs to state in the ADR

- **Eventual consistency**: validations that were synchronous (customer exists? location valid?)
  now read a local mirror that can lag by seconds; command flows need pending states and
  compensation events. Product must accept this explicitly.
- **Duplication overhead**: extra storage, migrations, and backfill per consumer — acceptable per
  the issue, but each replica should copy the *minimum* fields needed, not whole aggregates.
- **Schema evolution governance**: event contracts become the platform's most rigid API; versioning
  discipline (`.v1` topics, additive-only payload changes) is mandatory.
- **Operational surface**: Kafka becomes tier-1 infrastructure; consumer lag and DLQ monitoring are
  new operational duties.

## Estimated scope

~24 REST client classes retired or replaced across 10 modules, 6 new event-producer surfaces
(customer, invoice, location, people-contact, people-HR, vehicle-inventory — workorder already
produces), command consumers in invoice/workorder/vehicle-inventory, one new module
(`pos-people-contact`), one new shared library (`pos-domain-events`), outbox + idempotency + backfill
infrastructure, one frontend contract change (vehicle writes), and one ADR.
