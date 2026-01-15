## 🏷️ Labels (Proposed)
### Required
- type:story
- domain:people
- status:ready-for-dev

### Recommended
- agent:story-authoring
- agent:people
- agent:workexec

### Blocking / Risk
- none

**Rewrite Variant:** clarification-406-applied

## Story Intent
As a **Payroll Clerk**, I want the People/HR system to ingest finalized work session facts produced by Shop Management so that payroll timekeeping entries are complete, auditable, and ready for HR approval workflows.

## Actors & Stakeholders
- **Primary actor:** Payroll Clerk
- **Primary domain owner / SoR for payroll timekeeping:** People/HR (`domain:people`) owns `TimekeepingEntry`.
- **Upstream producer of operational work session facts:** Shop Management / Workexec (`domain:workexec`) publishes `WorkSessionCompleted`.
- **Stakeholders:** Compliance Officer, Shop Manager

## Preconditions
- `shopmgr` publishes an event when a work session is finalized: `WorkSessionCompleted` (v1).
- `WorkSessionCompleted` includes required idempotency fields: `tenantId` and `sessionId`.
- People/HR has (or will add) a `TimekeepingEntry` model with an approval lifecycle (e.g., `PENDING_APPROVAL`, `APPROVED`, `REJECTED`).

## Functional Behavior
### 1) Domain ownership (from Decision Record on #58)
- **Primary story owner:** `domain:people` owns ingestion, mapping/validation, idempotency, and persistence of `TimekeepingEntry`.
- `domain:workexec`/shopmgr remains the producer of operational work session facts and does not implement HR/payroll rules.

### 2) Integration contract (from Decision Record on #58)
- **Decision:** Asynchronous event ingestion from shopmgr → people.
- Producer publishes `WorkSessionCompleted` when a session is finalized/closed.
- People subscribes and ingests into `TimekeepingEntry`.

### 3) Idempotency + stability (resolved by Clarification #406)
- **Idempotency key:** `(tenantId, sessionId)`.
- **Stability:** `sessionId` is immutable and never reused.
- People MUST deduplicate on `(tenantId, sessionId)` and treat duplicate deliveries as no-op.

### 4) Ingestion flow (People-owned)
1. People receives `WorkSessionCompleted`.
2. Validate required fields:
   - Must include `tenantId` and `sessionId` (reject if missing).
3. Perform idempotency lookup using `(tenantId, sessionId)`.
4. If new:
   - Map event → `TimekeepingEntry`.
   - Default `approvalStatus` to `PENDING_APPROVAL`.
   - Persist the entry.
5. If duplicate:
   - Record metric/log duplicate and ack/complete without creating or modifying entries.

### 5) System of record + correction semantics (from Decision Record on #58, confirmed)
- After ingestion, `people.TimekeepingEntry` is the system of record for payroll.
- `WorkSessionCompleted` is treated as immutable/final for payroll purposes.
- Corrections are communicated by shopmgr via explicit events:
  - `WorkSessionCorrected` (preferred), or
  - `WorkSessionVoided` followed by a new `WorkSessionCompleted` with a new `sessionId`.
- People records corrections as an immutable adjustment entry (preferred) or as a versioned update history (must be explicitly chosen in implementation).

### 6) Out of scope (from Decision Record on #58)
- HR → shopmgr “approval feedback” updates are out of scope for this story.

## Alternate / Error Flows
- **Missing required fields (`tenantId` or `sessionId`)**: reject event, route to DLQ/alerting, and do not persist partial records.
- **Invalid payload/schema violation**: reject and route to DLQ/alerting; no record persisted.
- **Duplicate delivery**: no-op; record metric.
- **Mapping/transformation error**: log failure and do not persist partial record.

## Business Rules
- Ingestion MUST be idempotent: one finalized session results in exactly one `TimekeepingEntry` for the idempotency key.
- All newly created `TimekeepingEntry` records default to `PENDING_APPROVAL`.
- People is authoritative for payroll after ingestion; operational session tracking remains in shopmgr/workexec.

## Data Requirements
### Source event: `WorkSessionCompleted.v1`
**Guaranteed fields (Clarification #406):**
- `tenantId` (required)
- `sessionId` (required)
- `employeeId`
- `startTime`
- `endTime`

**Optional/contextual fields:**
- `shopId` / `locationId` (may be present but not required for idempotency)
- `workOrderId` (optional)

### Destination: `TimekeepingEntry` (people)
- `timekeepingEntryId` (UUID)
- `tenantId`
- `sourceSystem = 'shopmgr'`
- `sourceSessionId = sessionId`
- `employeeId`
- `sessionStartTime`, `sessionEndTime`
- `approvalStatus`
- `associatedWorkOrderId` (optional)

### Persistence constraint (idempotency)
- Enforce uniqueness on `(tenantId, sourceSystem, sourceSessionId)` (or equivalent) so duplicates cannot create double payroll.

## Acceptance Criteria
- **AC1: Successful ingestion**
  - Given no existing entry for `(tenantId, sessionId)`
  - When a valid `WorkSessionCompleted` is received
  - Then a new `TimekeepingEntry` is created with `approvalStatus = PENDING_APPROVAL`.

- **AC2: Duplicate delivery is idempotent**
  - Given an entry already exists for `(tenantId, sessionId)`
  - When a duplicate `WorkSessionCompleted` is received
  - Then no new entry is created and the existing entry is not modified.

- **AC3: Required fields enforced**
  - When `tenantId` or `sessionId` is missing
  - Then the event is rejected (DLQ/alerting) and no `TimekeepingEntry` is created.

- **AC4: Corrections handled via explicit events**
  - Given a `WorkSessionCompleted` has been ingested
  - When a `WorkSessionCorrected` or `WorkSessionVoided` is later received
  - Then People records an auditable correction (adjustment or version history) without silently overwriting the original finalized payroll fact.

## Audit & Observability
- Audit each `TimekeepingEntry` creation with source event identifiers, timestamp, and system principal.
- Metrics:
  - ingestion success count
  - ingestion failure count (tagged by reason: `missing_required_fields`, `schema_invalid`, `mapping_error`)
  - duplicates detected count

## Open Questions (if any)
- none (Clarification #406 resolved the remaining blocker)

## Original Story (Unmodified – For Traceability)
# Issue #58 — [BACKEND] [STORY] CrossDomain: HR Ingests Work Sessions from Shopmgr

## Current Labels
- backend
- story-implementation
- user

## Current Body
## Backend Implementation for Story

**Original Story**: [STORY] CrossDomain: HR Ingests Work Sessions from Shopmgr

**Domain**: user

### Story Description

/kiro
# User Story
## Narrative
As a **Payroll Clerk**, I want HR to receive work sessions from shopmgr so that payroll and compliance reporting can be produced.

## Details
- HR stores sessions with approval status.
- Reject/adjust supported.

## Acceptance Criteria
- Ingest idempotent.
- Visible for payroll.
- Approval tracked.

## Integrations
- Shopmgr→HR WorkSession events/API; optional HR→Shopmgr approval updates.

## Data / Entities
- TimekeepingEntry (hr domain)

## Classification (confirm labels)
- Type: Story
- Layer: Domain
- Domain: Shop Management


### Backend Requirements

- Implement Spring Boot microservices
- Create REST API endpoints
- Implement business logic and data access
- Ensure proper security and validation

### Technical Stack

- Spring Boot 3.2.6
- Java 21
- Spring Data JPA
- PostgreSQL/MySQL

---
*This issue was automatically created by the Durion Workspace Agent*
