# ADR-0044 Event-Only Domain Walls and Module Communication Policy

- Status: **Proposed** (candidate — issue [#823](https://github.com/louisburroughs/durion-positivity-backend/issues/823))
- Date: 2026-07-08
- Supersedes: the domain-to-domain portions of the service-discovery client policy
  (`docs/service-discovery-migration/client-policy-matrix.md`) and the javadoc-only
  "do not replicate address data" platform rule (see §Changes to other ADRs)
- Related: ADR-0006, ADR-0009, ADR-0011, ADR-0012, ADR-0014, ADR-0015, ADR-0017, ADR-0020,
  ADR-0021, ADR-0022, ADR-0025, ADR-0026, ADR-0027, ADR-0040, ADR-0042, ADR-0043
- Supporting analysis: `docs/module-coupling/issue-823-event-only-domain-walls-assessment.md`

Once accepted, promote this document to the canonical ADR repository as
`durion/docs/adr/0044-event-only-domain-walls.adr.md` (0043 is taken by the user–person linkage
authority ADR) and keep this copy as the backend-local reference.

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
| **Utility** | `pos-api-gateway`, `pos-security-service`, `pos-documents` (per ADR-0020), `pos-image`, `pos-tax` (per ADR-0021), `pos-event-receiver`, `pos-price` | Yes — by any module |
| **Domain** | `pos-accounting`, `pos-catalog`, `pos-customer`, `pos-inquiry`, `pos-inventory`, `pos-invoice`, `pos-location`, `pos-order`, `pos-people` (HR), `pos-people-contact` (new), `pos-shop-manager`, `pos-vehicle-inventory`, `pos-vehicle-fitment`, `pos-vehicle-reference-*`, `pos-workorder`, `pos-bulk-loader` | No — events only |
| **Libraries / non-deployed** | `pos-events`, `pos-shared-dtos`, `pos-domain-events` (new), `pos-security-common`, `pos-tax-common`, `pos-bulk-ingest-lib`, `pos-document-helper`, `pos-dependencies`, `pos-archunit` | n/a |

`pos-tax` and `pos-price` are utilities because they are stateless *computation* (tax and price
determination), not data lookups — replicating their rule engines into callers would be worse than
the call. `pos-documents` is a utility because ADR-0020 mandates centralized document creation via
its render API. `pos-mcp-server` is a gateway client (bearer-token relay) and follows client rules,
not module rules.

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
- **Vehicle owns its writes.** Vehicle registry create/update/delete moves out of pos-customer CRM;
  the frontend calls pos-vehicle-inventory through the gateway. pos-customer keeps a read-only
  vehicle mirror fed by `vehicle.events.v1` to serve its cross-cutting queries. Per ADR-0012,
  **vehicle-party associations remain owned by pos-customer** and party-association events continue
  to originate there; only registry ownership of the vehicle record itself is affected.
  pos-vehicle-fitment and the vehicle-reference modules are unchanged (external API lookups).
- **People splits into contact and HR.** New module `pos-people-contact` owns `Person`,
  `PersonContactPoint`, and the authoritative `user_person_links` store (ADR-0015, ADR-0043) and
  publishes contact/link events. `pos-people` retains HR (Employee, timekeeping per ADR-0006,
  availability, staffing, work sessions) and publishes availability and assignment events.
  pos-security-service's user↔person linking becomes command + confirmation events, and its
  `users.person_id` becomes the event-fed projection that ADR-0043 §2 already sanctions as an
  alternative (see §Changes to other ADRs).
- **Customer and location become publishers.** `customer.events.v1` and `location.events.v1`
  (including address data — see the note on the javadoc platform rule below) replace all remaining
  customer/location REST clients in inventory, invoice, people, shop-manager, and workorder.
  Callers of pos-tax source the `destinationAddress` required by ADR-0021 from their local
  location replicas.

### 7. Enforcement

- `pos-archunit` gains a cross-module rule: classes in `com.positivity.*.internal.client` MUST NOT
  target domain services — RestClient base URLs / service-ids are restricted to the utility
  whitelist. Rule ships report-only during migration and flips to build-failing when Phase 5
  completes (phases in the supporting analysis).
- Per-module `ArchitectureTest` classes gain the mirrored rule plus the `pos-domain-events` import
  allowance. This extends, and does not alter, the intra-module package boundary rules of ADR-0026.
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

Verified against the canonical texts in `durion/docs/adr/` (2026-07-08).

### Amendments required

| ADR | Subject | Required change |
|---|---|---|
| **ADR-0009** — Backend domain responsibilities guide | Domain responsibility matrix with "Integrates With" columns and integration patterns | Add a `pos-people-contact` row; split the current pos-people row into contact vs HR responsibilities; update "Integrates With" entries so domain↔domain integration is described as event topics rather than REST calls (e.g. pos-accounting integrates via `invoice.events.v1` / `accounting.commands.v1`, not REST to pos-order/pos-inventory); reflect accounting as event-only. |
| **ADR-0011** — API gateway security architecture | Gateway-enforced security; pos-security-service ownership; trust model | Add a section stating the gateway trust model governs synchronous/client traffic only; the asynchronous Kafka channel uses the trust model in ADR-0044 §5 (internal-only broker, producer identity, `actor` as audit metadata). No change to token or header semantics. |
| **ADR-0012** — Vehicle-party relationships belong in pos-customer | Associations owned by pos-customer; pos-customer references vehicle IDs | Association ownership is **unchanged**, but add a clarifying note: vehicle *registry* CRUD is no longer proxied through pos-customer — the frontend calls pos-vehicle-inventory via the gateway, and pos-customer serves its cross-cutting queries ("vehicles owned by a party") from a read-only `ext_vehicle_*` replica fed by `vehicle.events.v1`. Party-association events still originate from pos-customer. |
| **ADR-0014** — Internal service security via gateway route control | Secure-by-default: no gateway route unless whitelisted | Add explicit routes for `pos-people-contact` and confirm the vehicle-inventory route covers the registry write endpoints that become frontend-facing. pos-tax non-registration stance unchanged. |
| **ADR-0015** — Identity entity relationships | Person definition; Person↔User invariants (I5–I7) | Invariants unchanged; update the owning-module references: `Person`, `PersonContactPoint`, and `user_person_links` move from pos-people to `pos-people-contact`. |
| **ADR-0017** — API controller HTTP response codes | Canonical response matrix (no 202 today) | Add `202 Accepted` semantics for endpoints whose effect is enqueuing a command event (response carries a tracking/idempotency reference and a pending-state resource), plus a convention for surfacing async rejection (result event rejected → status resource, not a late HTTP error). |
| **ADR-0040** — Roles/JWT permission governance | Permission-based backend authorization; token claim contract | Add: event consumption is not authorized via permissions/`X-Authorities`; command events are authorized by topic/producer per ADR-0044 §5, with the initiating user's permission check performed at the original synchronous edge and `actor` recorded for audit. |
| **ADR-0042** — OpenAPI annotation standards | Mandatory OpenAPI annotations, MCP discovery | Add `pos-people-contact` and the newly frontend-facing pos-vehicle-inventory write endpoints to the enforcement inventory (backend rollout baseline `docs/adr-0042-openapi-rollout-baseline.md` likewise). Note that async event contracts are out of OpenAPI scope — topic contracts live in `pos-domain-events`; AsyncAPI adoption may be proposed separately. |
| **ADR-0043** — User–person linkage authority and translation | `pos-people.user_person_links` is sole source of truth; §2 prefers removing `users.person_id` and resolving via sync `GET /v1/people/users/{userId}/person` at token-issue time | Two amendments: (a) module references move from pos-people to `pos-people-contact` (link store ownership follows the split); (b) **flip the §2 preference** — under event-only walls, the *preferred* option becomes the one ADR-0043 already sanctions as the alternative: `pos-security-service.users.person_id` retained strictly as a projection written only from the link event (`people-contact.events.v1` link-changed), never by user-CRUD code. Link creation/removal initiated by security flows becomes command + confirmation events. Token-issue-time derivation then reads the local projection (no sync call), preserving the ADR-0022 claim contract and its fallback/metric rules. |

### Reviewed — no change required (reaffirmed)

| ADR | Why no change |
|---|---|
| **ADR-0006** — Workexec domain ownership boundaries | Timekeeping stays in the people/HR domain; the split does not move any ADR-0006 assignment. Cross-domain integration contracts it references now flow over events per this ADR. |
| **ADR-0013** — UUID v7 identifier strategy | Envelope `eventId` complies. |
| **ADR-0016** — Location entity semantics | Verified: it does **not** codify the "do not replicate address data" rule. That rule exists only as javadoc in `pos-invoice` `LocationServiceClient` and `pos-workorder` `LocationClient` ("Per the platform rule, other services must not replicate address data") and is superseded directly by R3; remove the javadoc when those clients are retired. |
| **ADR-0020** — Documents centralized creation | Reaffirmed: pos-documents is a utility; synchronous render calls remain the mandated pattern. |
| **ADR-0021** — Tax API consumption and internal access policy | Reaffirmed: pos-tax is a utility with direct internal calls; its `destinationAddress` contract is satisfied from callers' location replicas. |
| **ADR-0022** — Stable person identifier claim policy | Claim contract unchanged; derivation flows through the amended ADR-0043 mechanism. Update the link-store module reference alongside ADR-0043's. |
| **ADR-0025** — Permissions manifest registration policy | Startup-infra registration is explicitly exempt (R2); `pos-people-contact` follows the existing pattern. |
| **ADR-0026** — Service contract boundary policy | Scope is intra-module package boundaries (`service` vs `internal`), which this ADR does not alter. Optionally add a pointer to ADR-0044 for cross-module transport rules. |
| **ADR-0027** — UUID-typed identifier contract policy | Event payload identifiers are UUID-typed; compliant. |

### Superseded non-ADR documents

- `docs/service-discovery-migration/client-policy-matrix.md` — its `direct-discovery`
  classification no longer authorizes domain→domain calls; `startup-infra`, `gateway-exception`,
  `tax-exemption`, and `external` categories remain valid.
- The javadoc "platform rule" against replicating address data (see ADR-0016 row above).
