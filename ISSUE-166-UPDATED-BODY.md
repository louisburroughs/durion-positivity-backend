## 🏷️ Labels (Proposed)
### Required
- type:story
- domain:workexec
- status:ready-for-dev

### Recommended
- agent:workexec
- agent:story-authoring

### Blocking / Risk
- none

---
**Rewrite Variant:** workexec-structured
---

## Story Intent
As a Service Advisor, I want to create a formal Workorder from an approved Estimate so that I can schedule, assign, and track the execution of authorized customer work.

## Actors & Stakeholders
- **Service Advisor (Primary Actor):** Initiates the promotion of an approved Estimate to a Workorder.
- **System (Executing Actor):** Performs the creation, linking, and state transition of the Workorder.
- **Mechanic / Technician:** Consumes the created Workorder to perform the required tasks.
- **Back Office / Shop Manager:** Audits and reports on work execution based on created Workorders.

## Preconditions
1. An `Estimate` must exist and be in the `Approved` state.
2. The approved estimate content must be captured as an immutable snapshot at approval time.
3. The initiating user (Service Advisor) must have the `workorder:create` permission for the associated shop.

## Functional Behavior
### Happy Path: Promoting an Approved Estimate
1. **Trigger:** A Service Advisor initiates the "Promote to Workorder" action on an `Approved` `Estimate`.
2. **Workorder Creation:** The System creates a new `Workorder` header record.
3. **Source Linking:** The System creates an immutable link from the `Workorder` to:
   - The `Estimate` (`sourceEstimateId`)
   - The audit event ID that recorded the approval (`sourceApprovalEventId`)
4. **Task Population:** The System creates line item records for each item on the approved estimate:
   - Service lines → `WorkOrderService` records with `sourceEstimateLineItemId`
   - Part lines → `WorkOrderPart` records with `sourceEstimateLineItemId`
5. **Data Snapshotting:** The System captures customer and vehicle data in a `WorkOrderSnapshot` record with type `PROMOTION`, containing:
   - Customer snapshot: name, billing address, primary contact info, account type
   - Vehicle snapshot: VIN, year/make/model, mileage at promotion
   - Promotion metadata: workorderId, estimateId, sourceApprovalEventId, promotingUserId, shopId, timestamp
6. **State Initialization:** The System sets the initial status of the new `Workorder` to `APPROVED`.
7. **Confirmation:** The System returns a success confirmation with the new `Workorder ID` to the user.

## Alternate / Error Flows
- **Estimate Not in Approved State:**
  - If the "Promote to Workorder" action is attempted on an `Estimate` that is not in the `Approved` state, the System MUST reject the request with an error message (e.g., "Estimate must be approved before creating a workorder."). The operation MUST be rolled back, and no `Workorder` should be created.

- **Idempotent Promotion:**
  - If the action is triggered a second time for an `Estimate` that has already been successfully promoted, the System MUST NOT create a duplicate `Workorder`. The system should check for an existing active workorder via `findByEstimateId` repository method and return a reference to the existing `Workorder` with a notification that the workorder already exists. Note: Cancelled workorders still count for idempotency; re-promotion requires a new estimate.

- **System Configuration Error:**
  - If any system configuration required for workorder creation is missing or invalid, the transaction MUST fail and be completely rolled back. An error MUST be logged for system administrators with high severity.

## Business Rules
- A single `Approved Estimate` can be promoted to exactly one active `Workorder`.
- The `Workorder` must maintain an immutable reference to the `Estimate` and the approval audit event ID.
- Customer and Vehicle data are captured in a `WorkOrderSnapshot` at the time of promotion and MUST NOT be updated if the master `Customer` or `Vehicle` records change later.
- The initial status of a new `Workorder` is `APPROVED`.
- Idempotency is enforced at the service layer. One active workorder per estimate; cancelled workorders count for idempotency.
- Role-based access controls (RBAC) for viewing workorder data are **out of scope** for this story and will be addressed in a separate security/RBAC story.

## Data Requirements
- **`Workorder` Entity (existing):**
  - `workorderId` (PK)
  - `status` (set to `APPROVED` initially)
  - `shopId` (FK)
  - `sourceEstimateId` (FK, immutable) - direct link to Estimate
  - `sourceApprovalEventId` (FK, immutable) - audit event ID that recorded the approval
  - `createdTimestamp`
  - `createdByUserId`
  
- **`WorkOrderService` Entity (existing):**
  - Add field: `sourceEstimateLineItemId` (FK, immutable) - links to service line on estimate
  - Existing fields: `workorderId`, `description`, `notes`, etc.
  
- **`WorkOrderPart` Entity (existing):**
  - Add field: `sourceEstimateLineItemId` (FK, immutable) - links to part line on estimate
  - Existing fields: `workorderId`, `description`, `notes`, etc.
  
- **`WorkOrderSnapshot` Entity (existing, enhanced):**
  - `snapshotId` (PK)
  - `workorderId` (FK)
  - `snapshotType` (e.g., `PROMOTION`)
  - `snapshotData` (JSON/TEXT) containing:
    - Customer snapshot: name, billing address, primary contact info, account type
    - Vehicle snapshot: VIN, year, make, model, mileage at promotion
    - Promotion metadata: estimateId, sourceApprovalEventId, promotingUserId, shopId
  - `timestamp`

## Acceptance Criteria
- **AC-1: Successful Workorder Creation from Approved Estimate**
  - **Given** an `Estimate` is in the `Approved` state
  - **And** the system is configured to create workorders with initial status `APPROVED`
  - **When** a Service Advisor promotes the estimate to a Workorder
  - **Then** a new `Workorder` record is created in the database
  - **And** the `Workorder` status is `APPROVED`
  - **And** the `Workorder` is linked to the `Estimate` via `sourceEstimateId`
  - **And** the `Workorder` is linked to the approval audit event via `sourceApprovalEventId`
  - **And** a `WorkOrderSnapshot` record with type `PROMOTION` is created containing customer and vehicle snapshots and promotion metadata.

- **AC-2: Line Items Mapped Correctly**
  - **Given** an approved estimate has 2 service lines and 3 part lines
  - **When** the estimate is promoted to a workorder
  - **Then** 2 `WorkOrderService` records are created with `sourceEstimateLineItemId` linking back to the estimate service lines
  - **And** 3 `WorkOrderPart` records are created with `sourceEstimateLineItemId` linking back to the estimate part lines.

- **AC-3: Idempotency of Promotion Action**
  - **Given** an `Approved Estimate` has already been promoted to `Workorder-123`
  - **When** a user attempts to promote the same `Approved Estimate` again
  - **Then** the system does not create a new `Workorder`
  - **And** the system returns a response indicating `Workorder-123` already exists for this estimate.

- **AC-4: Promotion is Rejected for Non-Approved Estimate**
  - **Given** an `Estimate` exists in a `Draft` state
  - **When** a user attempts to promote this estimate to a Workorder
  - **Then** the system rejects the operation
  - **And** no `Workorder` is created
  - **And** an error message is returned to the user.

- **AC-5: Customer and Vehicle Data is Snapshotted Correctly**
  - **Given** an approved estimate for "John Doe" at "123 Main St" is promoted to `Workorder-456`
  - **And** the promotion creates a `WorkOrderSnapshot` with customer address "123 Main St"
  - **When** the master `Customer` record for "John Doe" is updated to "456 Oak Ave"
  - **Then** the customer address stored in the `WorkOrderSnapshot` for `Workorder-456` remains "123 Main St".

## Audit & Observability
- **Snapshot Record:** A `WorkOrderSnapshot` record with type `PROMOTION` must be created upon successful promotion.
- **Snapshot Payload:** The snapshot must contain:
  - Customer snapshot: name, billing address, primary contact info, account type
  - Vehicle snapshot: VIN, year, make, model, mileage at promotion
  - Promotion metadata: workorderId, estimateId, sourceApprovalEventId, promotingUserId, shopId, timestamp
- **Metrics:**
  - A counter metric `workorder_creation_total` should be incremented.
  - The metric should be tagged with `source:estimate` and `shopId`.
- **Logging:** Log an error with high severity if the promotion fails due to a system misconfiguration or validation error.

## Out of Scope
- **Role-Based Access Controls (RBAC):** Filtering workorder data visibility based on user roles (e.g., hiding prices from mechanics) is explicitly **out of scope** for this story. This will be addressed in a separate security/RBAC story.

## Implementation Notes
- Use existing `WorkOrder`, `WorkOrderService`, `WorkOrderPart`, and `WorkOrderSnapshot` entities.
- Add `sourceEstimateLineItemId` field to `WorkOrderService` and `WorkOrderPart` entities.
- Add `sourceEstimateId` and `sourceApprovalEventId` fields to `WorkOrder` entity.
- Implement service-layer idempotency check using `findByEstimateId` repository method.
- Initial workorder status is hard-coded to `APPROVED` for this flow in v1.
- Promotion snapshot type is `PROMOTION`.

## Original Story (Unmodified – For Traceability)
# Issue #166 — [BACKEND] [STORY] Promotion: Create Workorder from Approved Estimate

## Current Labels
- backend
- story-implementation
- order

## Current Body
## Backend Implementation for Story

**Original Story**: [STORY] Promotion: Create Workorder from Approved Estimate

**Domain**: order

### Story Description

/kiro
Produce implementation-ready acceptance criteria, validations, and edge cases. Keep Moqui state transitions and audit requirements explicit.

# Functional Requirement

## Classification (confirm labels)
- Type: story
- Layer: functional
- Domain: workexec

## Actor
Service Advisor / Back Office

## Trigger
Promotion preconditions are satisfied for an approved estimate.

## Main Flow
1. System creates a Workorder header using customer and vehicle context from the estimate snapshot.
2. System links workorder to estimate version and approval record.
3. System sets workorder initial state (e.g., Ready or Scheduled) per configuration.
4. System applies role-based visibility rules (e.g., hide prices for mechanics).
5. System records the promotion event for audit.

## Alternate / Error Flows
- Workorder creation fails due to validation/config error → rollback and report error.
- Promotion retried after partial failure → idempotent recovery.

## Business Rules
- Workorder must reference estimate snapshot and approval record.
- Initial state is policy-driven.
- Promotion must be auditable.

## Data Requirements
- Entities: Workorder, Estimate, ApprovalRecord, AuditEvent
- Fields: workorderId, status, estimateId, estimateVersion, approvalId, shopId, createdBy, createdDate

## Acceptance Criteria
- [ ] A workorder is created and linked to the approved estimate snapshot.
- [ ] Initial workorder state matches configuration.
- [ ] Audit trail shows who promoted and when.

## Notes for Agents
Keep promotion atomic: either you have a valid workorder, or you have nothing.


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
