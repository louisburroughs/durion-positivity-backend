## 🏷️ Labels (Proposed)
### Required
- type:story
- domain:audit
- status:ready-for-dev

### Recommended
- agent:story-authoring
- agent:audit
- agent:inventory

### Blocking / Risk
- none

**Rewrite Variant:** decision-record-applied

## Story Intent
As a **Compliance Auditor** and **Support Engineer**, I need an **immutable audit trail** of **inventory movements** and **workorder link/unlink events** so that we can prove what happened (who/what/when), support compliance export requests, and drill down from audit records to the authoritative source entities.

## Actors & Stakeholders
- **Audit Service / Audit Domain**: System of record for immutable storage, query, export, retention.
- **Inventory Service / Inventory Domain**: Authoritative producer of movement + workorder-link events.
- **Auditor / Compliance**: Consumes audit trail via query + export.
- **Support / Operations**: Investigates disputes and operational anomalies.
- **API Gateway / Frontend**: Presents audit list/detail and navigates to source entities.

## Preconditions
- Inventory emits append-only domain events for:
  - `MovementCreated`, `MovementAdjusted`
  - `WorkOrderLinked`, `WorkOrderUnlinked`
- A replayable transport exists (preferred: Kafka) and the Audit service can consume from it.
- Events include required audit metadata (tenant, actor, correlationId, timestamps).

## Functional Behavior
1) **Event production (Inventory-owned)**
- Inventory publishes versioned audit-ingestion events to Kafka.
- Partition key is the `aggregate.id` (e.g., `movementId` or `workOrderId`) to preserve per-aggregate ordering.
- Event envelope contains at minimum (per Decision Record):
  - `schemaVersion`, `eventId`, `eventType`, `occurredAt`, `emittedAt`, `sourceSystem`
  - `tenantId`
  - `actor` (type + id + displayName)
  - `correlationId`
  - `aggregate` (type + id)
  - `payload` (event-specific fields)

2) **Ingestion + persistence (Audit-owned)**
- Audit consumes events and persists them in a WORM-like (append-only) manner.
- Audit stores both:
  - **Normalized fields** (for filtering/search)
  - **Raw payload** (for exact replay / legal/compliance inspection)
- Ingestion is idempotent based on `eventId` (duplicate deliveries do not create duplicate stored records).

3) **Query + drilldown (Audit-owned)**
- Audit provides a list endpoint with filters suitable for auditing (minimum):
  - `tenantId`, date/time range, `eventType`, `actor.id`, `aggregate.type`, `aggregate.id`, `correlationId`
- Audit provides a detail endpoint by `eventId` that returns:
  - normalized fields + raw payload
  - `schemaVersion`
  - deep-link metadata to the authoritative source entity (movement/workorder)

4) **Export (Audit-owned)**
- MVP export format: **CSV**.
- Export response includes export metadata and a **SHA-256 digest** over exported content (manifest).
- Signed export (KMS-backed signing) is explicitly optional and out of MVP scope unless separately required.

5) **Retention (Audit-owned)**
- Default retention is **7 years**, implemented as a configurable setting with a safe default.
- During the retention window: no deletion from the primary store.
- Post-retention: events may be archived to cold storage and removed from hot storage if cost requires.

## Alternate / Error Flows
- **Kafka unavailable / consumption failure**: Audit retries with backoff; after threshold, message is routed to a dead-letter mechanism with alerting.
- **Missing required fields**: Audit rejects ingestion, routes to dead-letter, and emits a metric + structured log with `eventId` (if present) and `correlationId`.
- **Unknown `schemaVersion` / incompatible schema**: Audit routes to dead-letter and records a compatibility error for review.
- **Duplicate delivery**: Audit deduplicates by `eventId` and does not create duplicate records.

## Business Rules
- **Domain ownership (Decision Record):**
  - `domain:audit` owns storage, immutability guarantees, querying, export, retention.
  - `domain:inventory` owns authoritative event production for movements and workorder links.
- **Immutability:** stored audit events are append-only for the configured retention window.
- **Least privilege:** application roles must not have update/delete privileges on persisted audit records.

## Data Requirements
- Required event envelope fields (see Functional Behavior).
- Audit persistence must capture:
  - `eventId` (unique), `schemaVersion`, `eventType`, `occurredAt`, `emittedAt`, `sourceSystem`
  - `tenantId`, `actor.*`, `correlationId`
  - `aggregate.type`, `aggregate.id`
  - raw `payload` (JSON) and optionally raw full envelope
- Recommended indexing for query performance:
  - `(tenantId, occurredAt)`
  - `(tenantId, eventType, occurredAt)`
  - `(tenantId, aggregate.type, aggregate.id)`
  - `(tenantId, correlationId)`

## Acceptance Criteria
- Inventory publishes events for movement + workorder link/unlink changes with `schemaVersion` and stable `eventId`.
- Audit ingests these events and persists them immutably (append-only) with idempotency on `eventId`.
- Audit exposes query endpoints that filter by tenant and time range and can drill down to an audit detail record by `eventId`.
- Audit export endpoint produces CSV plus a SHA-256 manifest describing the export (filters, exportedAt, exportedBy, rowCount, digest).
- Deep-link metadata is provided for UI navigation back to the authoritative Inventory entity.

## Audit & Observability
- **Metrics:** ingestion success/failure counts, dead-letter counts, consumer lag, export counts.
- **Logging:** structured logs include `eventId`, `correlationId`, `tenantId`, and error category.
- **Audit of export action:** exporting is itself auditable (who exported, when, filters used, digest).

## Open Questions (if any)
- Confirm whether retention must default to **7 years** or is tenant-specific (implementation uses a configurable setting with safe default).
- Confirm whether signed exports (KMS signature) are required for the MVP.

## Original Story (Unmodified – For Traceability)
The original issue body was accidentally overwritten during automation. The authoritative intent and domain-ownership resolution is preserved in the Decision Record comment on this issue (Decision Record — “Immutable Audit Trail for Movements + Workorder Links”).
