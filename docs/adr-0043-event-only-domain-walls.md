# ADR-0043 Event-Only Domain Walls and Module Communication Policy

- Status: **Proposed** (candidate — issue [#823](https://github.com/louisburroughs/durion-positivity-backend/issues/823))
- Date: 2026-07-08
- Supersedes: the domain-to-domain portions of the service-discovery client policy
  (`docs/service-discovery-migration/client-policy-matrix.md`) and the undocumented
  "do not replicate address data" platform rule (see §Changes to other ADRs)
- Related: ADR-0011, ADR-0014, ADR-0016, ADR-0017, ADR-0021, ADR-0025, ADR-0026, ADR-0027,
  ADR-0040, ADR-0042
- Supporting analysis: `docs/module-coupling/issue-823-event-only-domain-walls-assessment.md`

Once accepted, promote this document to the canonical ADR repository as
`durion/docs/adr/0043-event-only-domain-walls.adr.md` and keep this copy as the backend-local
reference.

## Context

Domain modules are coupled by ~24 synchronous REST clients across 10 modules (full matrix in the
supporting analysis). Reads dominate (customer, location, and people reference data is fetched on
nearly every workorder/invoice/shop-manager flow), but several edges are writes: accounting applies
payments and credit memos against pos-invoice and triggers invoice regeneration in pos-workorder;
pos-customer performs full vehicle CRUD against pos-vehicle-inventory; pos-security-service writes
user↔person links into pos-people and pos-customer.

This coupling means: a callee outage cascades into caller request failures; deployment ordering
matters; and domain models leak across module boundaries through client DTOs.

The platform already contains the seed of the alternative: pos-workorder publishes a versioned JSON
event envelope to Kafka (`workorder.events.v1`), pos-customer consumes it, and pos-accounting /
pos-vehicle-inventory have Kafka listeners for topics (`payment.cleared.v1`, `vehicle.updates`,
`workorder.completed`) that nothing produces yet.

## Decision

Domain modules communicate with each other **only through asynchronous events on Kafka**.
Synchronous REST between modules is reserved for a small, named set of **utility modules**.
Consumers hold **read-only local replicas** of the reference data they need, kept in sync by events
from the owning module. Cross-module writes become **command events** with result events and
pending states.

### 1. Module classification

| Class | Modules | May be called synchronously? |
|---|---|---|
| **Utility** | `pos-api-gateway`, `pos-security-service`, `pos-documents`, `pos-image`, `pos-tax` (per ADR-0021), `pos-event-receiver`, `pos-price` | Yes — by any module |
| **Domain** | `pos-accounting`, `pos-catalog`, `pos-customer`, `pos-inquiry`, `pos-inventory`, `pos-invoice`, `pos-location`, `pos-order`, `pos-people` (HR), `pos-people-contact` (new), `pos-shop-manager`, `pos-vehicle-inventory`, `pos-vehicle-fitment`, `pos-vehicle-reference-*`, `pos-workorder`, `pos-bulk-loader` | No — events only |
| **Libraries / non-deployed** | `pos-events`, `pos-shared-dtos`, `pos-domain-events` (new), `pos-security-common`, `pos-tax-common`, `pos-bulk-ingest-lib`, `pos-document-helper`, `pos-dependencies`, `pos-archunit` | n/a |

`pos-tax` and `pos-price` are utilities because they are stateless *computation* (tax and price
determination), not data lookups — replicating their rule engines into callers would be worse than
the call. `pos-mcp-server` is a gateway client (bearer-token relay) and follows client rules, not
module rules.

### 2. Rules of separation

Normative language: MUST / MUST NOT / SHOULD per RFC 2119.

- **R1 — No domain-to-domain synchronous calls.** A domain module MUST NOT call another domain
  module's REST API, directly or via the gateway. This includes "just one small lookup."
- **R2 — Utility calls allowed.** Any module MAY call a utility module synchronously (direct
  Eureka discovery or the documented exception mechanism per the service-discovery policy).
  Startup-infra registrations (permissions → pos-security-service per ADR-0025, event types →
  pos-event-receiver, document templates → pos-documents) remain synchronous and best-effort.
- **R3 — Reads use local replicas.** When a domain module needs another domain's data, it maintains
  a read-only local replica populated exclusively by the owner's events. Replicas MUST copy the
  minimum fields required, MUST be tolerant of staleness, and MUST NOT be written by anything
  except the event consumer. Replica tables are named `ext_{owner}_{entity}` (e.g.
  `ext_location_address`) so ownership is visible in every schema.
- **R4 — Writes use command events.** When a domain module needs another domain to change state, it
  publishes a command event to the owner's command topic. The owner is the sole writer of its data,
  validates the command, and publishes a result event (applied/rejected). Initiating flows MUST
  model a pending state and MUST carry an idempotency key.
- **R5 — Kafka is the backbone.** Domain and command events flow over Kafka. The
  `@EmitEvent` → `pos-event-receiver` pipeline remains **audit-only** and MUST NOT be used for
  module-to-module data flow.
- **R6 — One owner per fact.** Every data element has exactly one owning module; only the owner
  publishes events about it. Consumers never re-publish replica data as their own events.

### 3. Event contract standard

A new non-deployed library **`pos-domain-events`** holds the envelope and all versioned payload
DTOs. It is importable by every module (ArchUnit allowance identical to `pos-shared-dtos`).

Envelope (extends the existing pos-workorder `KafkaProducer` envelope):

```json
{
  "eventId":          "<UUIDv7>",
  "eventType":        "customer.party.updated",
  "schemaVersion":    1,
  "aggregateId":      "<UUID of the owning aggregate>",
  "aggregateVersion": 42,
  "occurredAtUtc":    "2026-07-08T12:00:00Z",
  "sourceService":    "pos-customer",
  "correlationId":    "<propagated from the initiating request when available>",
  "actor":            "<user id or service name, for audit only>",
  "payload":          { }
}
```

- Topics: `{domain}.events.v1` (facts) and `{domain}.commands.v1` (requests to the owner). Keyed by
  `aggregateId` so per-aggregate ordering is preserved.
- Identifiers in payloads are UUID-typed per ADR-0027; `eventId` is UUIDv7 per ADR-0013.
- Payload changes within a version MUST be additive-only. Breaking changes require a new topic
  version (`.v2`), with the owner dual-publishing during the migration window.
- `aggregateVersion` is a monotonic per-aggregate sequence; consumers use it to detect gaps and to
  ignore out-of-date updates.

### 4. Reliability mechanisms (mandatory before a module migrates)

- **Transactional outbox.** Producers MUST NOT publish directly from business transactions. Each
  producer module adds an `event_outbox` table (Flyway) written in the same transaction as the
  state change, drained by a background publisher. At-least-once delivery is the guarantee.
- **Idempotent consumers.** Each consumer module keeps a `processed_events` table keyed by
  `eventId` (checked in the same transaction as the replica update). Redelivery MUST be harmless.
- **Retry and DLQ.** Transient consumer failures retry with backoff; poison messages go to
  `{topic}.dlq` and alert. A DLQ'd command MUST surface as a failed/pending item, not silently drop.
- **Bootstrap and backfill.** Owners MUST provide a replay mechanism (snapshot export endpoint or
  administrative re-emit-all) to seed new replicas and repair drift.
- **Reconciliation.** A scheduled job per consumer compares replica `aggregateVersion`s (or
  count/checksum) against the owner and triggers targeted re-sync on drift. Duplication without
  reconciliation is not permitted.
- **Kafka as tier-1 infrastructure.** Kafka becomes a required runtime dependency for all domain
  modules in docker/alpha/prod profiles (no more `@ConditionalOnProperty` opt-in for domain flows),
  with consumer-lag and DLQ monitoring in the observability stack.

### 5. Security model for the event channel

Events bypass the gateway, so gateway JWT validation and `X-Authorities` (ADR-0011 / ADR-0040) do
not apply on this channel. The trust model is:

- The broker is reachable only on the internal network; there are no external producers or
  consumers. Producer identity is asserted by `sourceService` and, where the deployment supports
  it, broker ACLs restrict which service may produce to which topic.
- Consumers authorize **command events by topic and producer**, not by user authorities. The
  `actor` field is audit metadata only — it MUST NOT be used to bypass or re-derive permission
  checks. User-permission enforcement happens once, at the edge where the initiating request
  entered the system (gateway → controller `@PreAuthorize`).
- Replica data inherits the read-permission posture of the consuming module's own endpoints.

### 6. Domain-specific decisions

- **Accounting is event-only** (inbound and outbound). Its customer/invoice/workorder clients are
  retired; billing-rule and invoice read models are event-fed; payment application, payment
  reversal, credit-memo application, and invoice regeneration become command events consumed by
  pos-invoice / pos-workorder with result events back.
- **Vehicle owns its writes.** Vehicle create/update/delete moves out of pos-customer CRM; the
  frontend calls pos-vehicle-inventory through the gateway. pos-customer keeps a read-only vehicle
  mirror fed by `vehicle.events.v1`. pos-vehicle-fitment and the vehicle-reference modules are
  unchanged (external API lookups).
- **People splits into contact and HR.** New module `pos-people-contact` owns `Person`,
  `PersonContactPoint`, `UserPersonLink` and publishes contact events. `pos-people` retains HR
  (Employee, timekeeping, availability, staffing, work sessions) and publishes availability and
  assignment events. pos-security-service's user↔person linking becomes command + confirmation
  events.
- **Customer and location become publishers.** `customer.events.v1` and `location.events.v1`
  (including address data — see ADR-0016 note below) replace all remaining customer/location REST
  clients in inventory, invoice, people, shop-manager, and workorder.

### 7. Enforcement

- `pos-archunit` gains a cross-module rule: classes in `com.positivity.*.internal.client` MUST NOT
  target domain services — RestClient base URLs / service-ids are restricted to the utility
  whitelist. Rule ships report-only during migration and flips to build-failing when Phase 5
  completes (phases in the supporting analysis).
- Per-module `ArchitectureTest` classes gain the mirrored rule plus the `pos-domain-events` import
  allowance.
- The utility whitelist lives in one place (a constant list in `pos-archunit`) and changing it
  requires amending this ADR.

## Consequences

**Positive.** Domain modules deploy, fail, and evolve independently; read paths keep working
through producer outages; domain models stop leaking through client DTOs; the event stream becomes
a first-class integration surface (audit, analytics, future consumers).

**Negative / accepted.** Reads may lag the owner by seconds — validation against replicas is
best-effort and command flows need pending/compensation UX; storage and migration cost per replica;
event contracts become the platform's most rigid API and demand versioning discipline; Kafka
becomes tier-1 operational surface (lag, DLQ, partition management); one frontend contract change
(vehicle writes).

**Explicitly rejected alternatives.** Routing domain events through `pos-event-receiver` (single
point of failure, not designed as a broker); keeping sync reads with caching (does not remove the
runtime dependency or the model leak); synchronous write exceptions (would leave the strongest
coupling — accounting↔invoice/workorder — in place indefinitely).

## Changes required in other ADRs

| ADR | Subject (as referenced in this repo) | Required change |
|---|---|---|
| **ADR-0011** — API gateway security architecture | Gateway is the security boundary; JWT → `X-Authorities` | **Amend.** Add a section stating the gateway boundary governs synchronous/client traffic only; the asynchronous Kafka channel uses the trust model in ADR-0043 §5 (internal-only broker, producer identity, `actor` as audit metadata). No change to token or header semantics. |
| **ADR-0014** — Gateway whitelist routing; pos-tax non-registration | Route whitelist; pos-tax internal-only, no Eureka | **Amend.** Add gateway routes for the new `pos-people-contact` module and confirm the `VEHICLE-INVENTORY` route covers the vehicle write endpoints that move frontend-facing. pos-tax stance unchanged (reaffirmed by ADR-0043 utility classification). |
| **ADR-0016** — Location contract | Location domain contract (parent types, validation) | **Amend/supersede in part.** The platform rule "other services must not replicate address data: store the locationId and query pos-location" (currently embedded in `pos-invoice` `LocationServiceClient` and `pos-workorder` `LocationClient` javadoc, attributed to the location contract) is **reversed**: consumers MAY hold read-only address replicas fed by `location.events.v1` under ADR-0043 R3. If ADR-0016 codifies the rule, update its text; either way, update the two javadocs when those clients are retired. |
| **ADR-0017** — Controller HTTP response-code standard | Semantics for 400/401/403/404/409/422/500 | **Amend.** Add `202 Accepted` semantics for endpoints whose effect is enqueuing a command event (response carries a tracking/idempotency reference and a pending-state resource), and a convention for exposing async failure states (result event rejected → surfaced via status resource, not a late HTTP error). |
| **ADR-0021** — Tax API consumption policy | pos-tax internal-only direct calls | **No change.** Reaffirmed: pos-tax is classified as a utility; existing direct-call exception stands. |
| **ADR-0025** — Permissions manifest registration policy | Startup permission registration | **No change.** Startup-infra registration is explicitly outside ADR-0043's event mandate (R2). New modules (`pos-people-contact`) follow the existing registration pattern. |
| **ADR-0026** — Service contract boundary policy | Module boundaries: only `service.*` public; cross-module via REST or events | **Amend.** Narrow the cross-module interaction clause: domain↔domain is events-only; synchronous REST is limited to the ADR-0043 utility whitelist. Add `pos-domain-events` to the shared libraries modules may import (alongside `pos-shared-dtos`), and record the `ext_{owner}_{entity}` replica-table convention as part of the boundary contract. |
| **ADR-0027** — UUID-typed identifier contract policy | UUID-typed IDs in contracts | **No change** (extend applicability note): event envelope and payload identifiers are UUID-typed; `eventId` is UUIDv7 (ADR-0013). |
| **ADR-0040** — Authorization via trusted `X-Authorities` | Downstream services trust gateway-produced headers | **Amend.** Current text covers "standard internal API authorization flows." Add: event consumption is not authorized via `X-Authorities`; command events are authorized by topic/producer per ADR-0043 §5, with the initiating user's permission check performed at the original synchronous edge and the `actor` recorded for audit. |
| **ADR-0042** — OpenAPI rollout baseline | OpenAPI coverage and validation for REST endpoints | **Amend.** (a) Vehicle write endpoints on `pos-vehicle-inventory` become frontend-facing and must join the OpenAPI enforcement waves; add `pos-people-contact` to the module inventory when created. (b) Note that async event contracts are out of OpenAPI scope — contract documentation for topics lives in `pos-domain-events` (AsyncAPI adoption may be proposed separately). |
| **ADR-0013** — UUID v7 identifier strategy | UUIDv7 primary keys | **No change.** Envelope `eventId` complies. |

Also superseded (non-ADR): `docs/service-discovery-migration/client-policy-matrix.md` — its
`direct-discovery` classification no longer authorizes domain→domain calls; `startup-infra`,
`gateway-exception`, `tax-exemption`, and `external` categories remain valid.
