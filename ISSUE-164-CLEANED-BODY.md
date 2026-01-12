## 🏷️ Labels (Proposed)
### Required
- type:story
- domain:workexec
- status:draft

### Recommended
- agent:workexec
- agent:story-authoring

### Blocking / Risk
- none

**Rewrite Variant:** workexec-structured

## Story Intent
**As a** System,
**I want to** enforce that the promotion of a specific estimate snapshot to a work order is an idempotent operation,
**so that** system retries, network issues, or user double-clicks do not create duplicate work orders and corrupt operational data.

## Actors & Stakeholders
- **System**: The primary actor responsible for executing the promotion logic and ensuring idempotency.
- **Service Advisor**: The user who initiates the promotion of an estimate to a work order via a UI or API call.
- **Auditor**: A stakeholder who requires a clear and traceable record of promotion events, including retries and outcomes.

## Preconditions
1.  A valid, finalized `Estimate` exists in the system.
2.  A specific `snapshotVersion` of the estimate has been identified for promotion.
3.  The initiating actor (e.g., Service Advisor) has the required permissions to create work orders.
4.  The system can establish a database transaction to ensure atomicity of the operation.

## Functional Behavior
### Scenario 1: First-Time Promotion
1.  **Trigger**: The System receives a request to promote `estimateId` at `snapshotVersion`.
2.  The System constructs an idempotency key based on the unique combination of `(estimateId, snapshotVersion)`.
3.  The System queries for an existing `PromotionRecord` using the idempotency key. None is found.
4.  The System begins a transaction.
5.  Inside the transaction, it creates a new `PromotionRecord` with a status of `PENDING`.
6.  It then proceeds to create the corresponding `WorkOrder` entity from the estimate snapshot data.
7.  Upon successful `WorkOrder` creation, the System updates the `PromotionRecord`, setting its `status` to `COMPLETED` and linking the new `workorderId`.
8.  The transaction is committed.
9.  **Outcome**: The System returns a success response containing the reference to the newly created `WorkOrder`.

### Scenario 2: Retried Promotion (Previously Completed)
1.  **Trigger**: The System receives a duplicate request to promote the same `estimateId` and `snapshotVersion`.
2.  The System constructs the idempotency key and queries for an existing `PromotionRecord`.
3.  A `PromotionRecord` is found with a status of `COMPLETED` and a valid `workorderId`.
4.  **Outcome**: The System immediately returns a success response containing the reference to the *existing* `WorkOrder` without performing any new write operations.

### Scenario 3: Retried Promotion (Previously Failed before Work Order Creation)
1.  **Trigger**: The System receives a duplicate request for a promotion that previously failed after creating the `PromotionRecord` but before creating the `WorkOrder`.
2.  The System finds an existing `PromotionRecord` with a status of `PENDING` or `FAILED` and a `null` `workorderId`.
3.  The System re-attempts the logic from Scenario 1, Step 6 onwards, using the existing `PromotionRecord`.
4.  **Outcome**: If successful, the `PromotionRecord` is updated to `COMPLETED`, and the system returns the new `WorkOrder` reference.

## Alternate / Error Flows
- **Flow 1: Corrupted Data Link**
  - **Condition**: A `PromotionRecord` exists with `status: COMPLETED` and a `workorderId`, but a lookup for that `workorderId` fails (i.e., the Work Order has been deleted or was never committed).
  - **Outcome**: The System MUST NOT proceed. It should log a critical error, flag the `PromotionRecord` as `CORRUPTED`, and return a hard failure response. This state requires manual administrative intervention.
- **Flow 2: Invalid Input**
  - **Condition**: The provided `estimateId` or `snapshotVersion` does not correspond to a valid, promotable estimate.
  - **Outcome**: The System rejects the request with a `404 Not Found` or `422 Unprocessable Entity` error.

## Business Rules
- A unique combination of `(estimateId, snapshotVersion)` MUST map to exactly one `WorkOrder`. This relationship is canonical and immutable once established.
- The `PromotionRecord` serves as the authoritative source of truth for the link between an estimate snapshot and its resulting work order.
- The creation of the `WorkOrder` and the final update of the `PromotionRecord` to `COMPLETED` must be performed within a single, atomic transaction to prevent partial success states.

## Data Requirements
- **Entity: `PromotionRecord`**
  - `promotionId` (Primary Key, e.g., UUID)
  - `estimateId` (Foreign Key to Estimate, Indexed)
  - `snapshotVersion` (Integer, Indexed)
  - `workorderId` (Foreign Key to WorkOrder, Nullable, Indexed)
  - `status` (Enum: `PENDING`, `COMPLETED`, `FAILED`, `CORRUPTED`)
  - `createdAt` (Timestamp)
  - `updatedAt` (Timestamp)
  - **Constraint**: A database-level unique constraint MUST be placed on `(estimateId, snapshotVersion)`. This is the primary mechanism for enforcing idempotency.

- **Entity: `WorkOrder`**
  - `workorderId` (Primary Key)
  - ... other work order fields.

- **Entity: `Estimate`**
  - `estimateId` (Primary Key)
  - ... other estimate fields.

## Acceptance Criteria
1.  **Given** a valid `estimateId` "E-123" at `snapshotVersion` 1
    **And** no `PromotionRecord` exists for this combination
    **When** the system receives a request to promote the estimate
    **Then** a new `WorkOrder` "WO-456" is created
    **And** a new `PromotionRecord` is created linking "E-123", version 1, and "WO-456" with status `COMPLETED`
    **And** the system returns a success response with the reference to "WO-456".

2.  **Given** a `PromotionRecord` exists for `estimateId` "E-123" at `snapshotVersion` 1
    **And** it is linked to `WorkOrder` "WO-456" with status `COMPLETED`
    **When** the system receives a *second* request to promote the same estimate snapshot
    **Then** no new `WorkOrder` is created
    **And** no new `PromotionRecord` is created
    **And** the system returns a success response with the reference to the existing "WO-456".

3.  **Given** a `PromotionRecord` exists for `estimateId` "E-123" at `snapshotVersion` 1
    **And** its status is `PENDING` and its `workorderId` is `null`
    **When** the system receives a request to promote the estimate
    **Then** a new `WorkOrder` "WO-789" is created
    **And** the *existing* `PromotionRecord` is updated with `workorderId` "WO-789" and status `COMPLETED`
    **And** the system returns a success response with the reference to "WO-789".

4.  **Given** a `PromotionRecord` exists for `estimateId` "E-123" at `snapshotVersion` 1
    **And** its `workorderId` is "WO-999", but no `WorkOrder` with that ID exists in the database
    **When** the system receives a request to promote the estimate
    **Then** the system returns a server error response (e.g., `500 Internal Server Error`)
    **And** the existing `PromotionRecord`'s status is updated to `CORRUPTED`
    **And** a critical alert is logged.

## Audit & Observability
- **Audit Log**: Every promotion attempt (initial or retry) MUST generate a structured audit event.
  - Event Name: `promotion.attempted`
  - Attributes: `estimateId`, `snapshotVersion`, `isFirstAttempt` (boolean), `outcome` (`CREATED`, `RETRIEVED`, `FAILED`), `workorderId` (if applicable), `correlationId`.
- **Metrics**:
  - `promotions.created.count`: Counter for new work orders created via promotion.
  - `promotions.retrieved.count`: Counter for idempotent retrievals.
  - `promotions.failed.count`: Counter for failed promotion attempts.
- **Alerting**:
  - A high-priority alert MUST be triggered if the `CORRUPTED` state is encountered, as this indicates data inconsistency requiring immediate attention.

## Original Story (Unmodified – For Traceability)
# Issue louisburroughs/durion-positivity-backend#164 — [BACKEND] [STORY] Promotion: Enforce Idempotent Promotion

## Current Labels
- backend
- story-implementation
- user

## Current Body
## Backend Implementation for Story

**Original Story**: [STORY] Promotion: Enforce Idempotent Promotion

**Domain**: user

### Story Description

/kiro
Produce implementation-ready acceptance criteria, validations, and edge cases. Keep Moqui state transitions and audit requirements explicit.

# Functional Requirement

## Classification (confirm labels)
- Type: story
- Layer: functional
- Domain: workexec

## Actor
System

## Trigger
Promotion is executed multiple times due to retries or double actions.

## Main Flow
1. System checks for an existing promotion record for (estimateId, snapshotVersion).
2. If a workorder exists, system returns the existing workorder reference instead of creating a duplicate.
3. If promotion was partially completed, system completes missing pieces safely.
4. System records retry event for diagnostics/audit (optional).
5. User sees a single canonical workorder link.

## Alternate / Error Flows
- Promotion record exists but workorder deleted/corrupted → require admin intervention and block.

## Business Rules
- Promotion must be idempotent under retries.
- Promotion record is the authoritative link between estimate snapshot and workorder.

## Data Requirements
- Entities: PromotionRecord, Workorder, Estimate, AuditEvent
- Fields: promotionKey, estimateId, snapshotVersion, workorderId, status, retryCount

## Acceptance Criteria
- [ ] Repeated promotion attempts do not create duplicate workorders.
- [ ] System returns the same workorder URL/number for the same snapshot.
- [ ] Partial promotion can be safely completed.

## Notes for Agents
Idempotency prevents data integrity nightmares—treat it as non-negotiable.


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
