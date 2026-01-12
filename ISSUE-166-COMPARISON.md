# Issue #166 - Before and After Comparison

## Quick Reference: Key Changes

| Aspect | Before | After |
|--------|--------|-------|
| **Estimate Linking** | `EstimateVersion` + `ApprovalRecord` | Direct `Estimate` + `sourceApprovalEventId` |
| **Line Items** | Generic `WorkorderItem` | `WorkOrderService` + `WorkOrderPart` |
| **Snapshots** | Inline JSONB on `Workorder` | Separate `WorkOrderSnapshot` entity |
| **Initial Status** | `Ready for Scheduling` | `APPROVED` |
| **Idempotency** | Implied DB constraint | Service-layer enforcement |
| **Audit Event** | `WorkorderPromotedFromEstimate` | `WorkOrderSnapshot` type `PROMOTION` |
| **RBAC** | In scope | Out of scope |
| **Acceptance Criteria** | 4 criteria | 5 criteria (added line item mapping) |

---

## Detailed Comparison

### Preconditions

#### Before
1. An `Estimate` must exist and be in the `Approved` state.
2. The specific `EstimateVersion` that was approved must be clearly identifiable via an `ApprovalRecord`.
3. The initiating user (Service Advisor) must have the `workorder:create` permission for the associated shop.

#### After
1. An `Estimate` must exist and be in the `Approved` state.
2. The approved estimate content must be captured as an immutable snapshot at approval time.
3. The initiating user (Service Advisor) must have the `workorder:create` permission for the associated shop.

**Changes:**
- ❌ Removed reference to `EstimateVersion` and `ApprovalRecord`
- ✅ Added requirement for immutable snapshot at approval time

---

### Functional Behavior - Source Linking

#### Before
4. **Source Linking:** The System creates an immutable link from the `Workorder` to the `EstimateVersion` and the `ApprovalRecord` that authorized the work.

#### After
3. **Source Linking:** The System creates an immutable link from the `Workorder` to:
   - The `Estimate` (`sourceEstimateId`)
   - The audit event ID that recorded the approval (`sourceApprovalEventId`)

**Changes:**
- ❌ Removed `EstimateVersion` and `ApprovalRecord`
- ✅ Added direct `Estimate` link
- ✅ Added `sourceApprovalEventId` for audit trail

---

### Functional Behavior - Task Population

#### Before
5. **Task Population:** The System creates `WorkorderItem` records for each line item present on the approved `EstimateVersion`.

#### After
4. **Task Population:** The System creates line item records for each item on the approved estimate:
   - Service lines → `WorkOrderService` records with `sourceEstimateLineItemId`
   - Part lines → `WorkOrderPart` records with `sourceEstimateLineItemId`

**Changes:**
- ❌ Removed generic `WorkorderItem`
- ✅ Added specific `WorkOrderService` and `WorkOrderPart` entities
- ✅ Added `sourceEstimateLineItemId` traceability

---

### Functional Behavior - Data Snapshotting

#### Before
3. **Data Snapshotting:** The System populates the `Workorder` with immutable customer and vehicle data copied from the approved `EstimateVersion` to ensure historical accuracy.

#### After
5. **Data Snapshotting:** The System captures customer and vehicle data in a `WorkOrderSnapshot` record with type `PROMOTION`, containing:
   - Customer snapshot: name, billing address, primary contact info, account type
   - Vehicle snapshot: VIN, year/make/model, mileage at promotion
   - Promotion metadata: workorderId, estimateId, sourceApprovalEventId, promotingUserId, shopId, timestamp

**Changes:**
- ❌ Removed inline JSONB fields on `Workorder`
- ✅ Added separate `WorkOrderSnapshot` entity
- ✅ Specified exactly what data to capture
- ✅ Added promotion metadata

---

### Functional Behavior - State Initialization

#### Before
6. **State Initialization:** The System sets the initial status of the new `Workorder` to the configured default (e.g., `Ready for Scheduling`).

#### After
6. **State Initialization:** The System sets the initial status of the new `Workorder` to `APPROVED`.

**Changes:**
- ❌ Removed configurable default status concept
- ✅ Hard-coded to `APPROVED` status

---

### Functional Behavior - Audit

#### Before
7. **Audit:** The System records a `WorkorderPromotedFromEstimate` event in the audit log.

#### After
*(Removed - audit now handled by WorkOrderSnapshot)*

**Changes:**
- ❌ Removed separate audit event step
- ✅ Audit now part of snapshot creation

---

### Alternate Flow - Idempotency

#### Before
- **Idempotent Promotion:**
  - If the action is triggered a second time for an `EstimateVersion` that has already been successfully promoted, the System MUST NOT create a duplicate `Workorder`. It should return a reference to the existing `Workorder` and a notification that the workorder already exists.

#### After
- **Idempotent Promotion:**
  - If the action is triggered a second time for an `Estimate` that has already been successfully promoted, the System MUST NOT create a duplicate `Workorder`. The system should check for an existing active workorder via `findByEstimateId` repository method and return a reference to the existing `Workorder` with a notification that the workorder already exists. Note: Cancelled workorders still count for idempotency; re-promotion requires a new estimate.

**Changes:**
- ❌ Removed `EstimateVersion` reference
- ✅ Added implementation detail: `findByEstimateId` method
- ✅ Clarified cancelled workorders count for idempotency

---

### Business Rules

#### Before
- A single `Approved EstimateVersion` can be promoted to exactly one `Workorder`.
- The `Workorder` must maintain an immutable reference to the `EstimateVersion` and `ApprovalRecord` from which it was generated.
- Customer and Vehicle data on the `Workorder` are snapshotted at the time of creation and MUST NOT be updated if the master `Customer` or `Vehicle` records change later.
- The initial status of a new `Workorder` is determined by a system or shop-level policy.
- Role-based access controls (RBAC) MUST be applied to the `Workorder` data. For example, a user with a `Technician` role may be prevented from viewing pricing information.

#### After
- A single `Approved Estimate` can be promoted to exactly one active `Workorder`.
- The `Workorder` must maintain an immutable reference to the `Estimate` and the approval audit event ID.
- Customer and Vehicle data are captured in a `WorkOrderSnapshot` at the time of promotion and MUST NOT be updated if the master `Customer` or `Vehicle` records change later.
- The initial status of a new `Workorder` is `APPROVED`.
- Idempotency is enforced at the service layer. One active workorder per estimate; cancelled workorders count for idempotency.
- Role-based access controls (RBAC) for viewing workorder data are **out of scope** for this story and will be addressed in a separate security/RBAC story.

**Changes:**
- ❌ Removed `EstimateVersion` and `ApprovalRecord` references
- ❌ Removed configurable initial status
- ❌ Removed RBAC as in-scope requirement
- ✅ Added specific `APPROVED` status
- ✅ Added service-layer idempotency clarification
- ✅ Moved RBAC to out-of-scope

---

### Data Requirements - Workorder Entity

#### Before
- **`Workorder` Entity:**
  - `workorderId` (PK)
  - `status` (e.g., `Ready for Scheduling`, `In Progress`)
  - `shopId` (FK)
  - `sourceEstimateId` (FK, immutable)
  - `sourceEstimateVersionId` (FK, immutable)
  - `sourceApprovalId` (FK, immutable)
  - `customerSnapshot` (JSONB, contains customer details at time of creation)
  - `vehicleSnapshot` (JSONB, contains vehicle details at time of creation)
  - `createdTimestamp`
  - `createdByUserId`

#### After
- **`Workorder` Entity (existing):**
  - `workorderId` (PK)
  - `status` (set to `APPROVED` initially)
  - `shopId` (FK)
  - `sourceEstimateId` (FK, immutable) - direct link to Estimate
  - `sourceApprovalEventId` (FK, immutable) - audit event ID that recorded the approval
  - `createdTimestamp`
  - `createdByUserId`

**Changes:**
- ❌ Removed `sourceEstimateVersionId`
- ❌ Removed `sourceApprovalId`
- ❌ Removed `customerSnapshot` JSONB field
- ❌ Removed `vehicleSnapshot` JSONB field
- ✅ Added `sourceApprovalEventId`
- ✅ Clarified existing entity vs new
- ✅ Specified exact initial status

---

### Data Requirements - Line Items

#### Before
- **`WorkorderItem` Entity:**
  - `workorderItemId` (PK)
  - `workorderId` (FK)
  - `sourceEstimateLineItemId` (FK, immutable)
  - `description`
  - `notes`

#### After
- **`WorkOrderService` Entity (existing):**
  - Add field: `sourceEstimateLineItemId` (FK, immutable) - links to service line on estimate
  - Existing fields: `workorderId`, `description`, `notes`, etc.
  
- **`WorkOrderPart` Entity (existing):**
  - Add field: `sourceEstimateLineItemId` (FK, immutable) - links to part line on estimate
  - Existing fields: `workorderId`, `description`, `notes`, etc.

**Changes:**
- ❌ Removed generic `WorkorderItem` entity
- ✅ Added specific `WorkOrderService` entity
- ✅ Added specific `WorkOrderPart` entity
- ✅ Clarified these are existing entities being enhanced

---

### Data Requirements - Snapshot

#### Before
*(Snapshots stored inline on Workorder entity)*

#### After
- **`WorkOrderSnapshot` Entity (existing, enhanced):**
  - `snapshotId` (PK)
  - `workorderId` (FK)
  - `snapshotType` (e.g., `PROMOTION`)
  - `snapshotData` (JSON/TEXT) containing:
    - Customer snapshot: name, billing address, primary contact info, account type
    - Vehicle snapshot: VIN, year, make, model, mileage at promotion
    - Promotion metadata: estimateId, sourceApprovalEventId, promotingUserId, shopId
  - `timestamp`

**Changes:**
- ✅ Added separate entity for snapshots
- ✅ Specified `PROMOTION` type
- ✅ Detailed exactly what goes in snapshot
- ✅ Clarified existing entity being enhanced

---

### Acceptance Criteria

#### Before (4 criteria)
- AC-1: Successful Workorder Creation from Approved Estimate
- AC-2: Idempotency of Promotion Action
- AC-3: Promotion is Rejected for Non-Approved Estimate
- AC-4: Customer and Vehicle Data is Snapshotted Correctly

#### After (5 criteria)
- AC-1: Successful Workorder Creation from Approved Estimate
- **AC-2: Line Items Mapped Correctly** *(NEW)*
- AC-3: Idempotency of Promotion Action
- AC-4: Promotion is Rejected for Non-Approved Estimate
- AC-5: Customer and Vehicle Data is Snapshotted Correctly

**Changes:**
- ✅ Added new AC-2 for line item mapping
- ✅ Renumbered subsequent criteria
- ✅ Updated AC-1 to reference `sourceApprovalEventId` and `WorkOrderSnapshot`
- ✅ Updated AC-5 to reference `WorkOrderSnapshot` instead of inline fields

---

### AC-1 Details

#### Before
- **Given** an `Estimate` is in the `Approved` state
- **And** the default initial workorder status is configured as `Ready for Scheduling`
- **When** a Service Advisor promotes the estimate to a Workorder
- **Then** a new `Workorder` record is created in the database
- **And** the `Workorder` status is `Ready for Scheduling`
- **And** the `Workorder` is linked to the correct `EstimateVersion` and `ApprovalRecord`
- **And** an audit event for the promotion is recorded.

#### After
- **Given** an `Estimate` is in the `Approved` state
- **And** the system is configured to create workorders with initial status `APPROVED`
- **When** a Service Advisor promotes the estimate to a Workorder
- **Then** a new `Workorder` record is created in the database
- **And** the `Workorder` status is `APPROVED`
- **And** the `Workorder` is linked to the `Estimate` via `sourceEstimateId`
- **And** the `Workorder` is linked to the approval audit event via `sourceApprovalEventId`
- **And** a `WorkOrderSnapshot` record with type `PROMOTION` is created containing customer and vehicle snapshots and promotion metadata.

**Changes:**
- ❌ Removed `EstimateVersion` and `ApprovalRecord` references
- ❌ Changed status from `Ready for Scheduling` to `APPROVED`
- ✅ Added `sourceEstimateId` reference
- ✅ Added `sourceApprovalEventId` reference
- ✅ Changed audit event to snapshot creation

---

### Audit & Observability

#### Before
- **Audit Event:** An event of type `WorkorderPromotedFromEstimate` must be created upon successful promotion.
- **Event Payload:** The event must contain `workorderId`, `sourceEstimateId`, `sourceEstimateVersionId`, `sourceApprovalId`, `promotingUserId`, `shopId`, and a `timestamp`.

#### After
- **Snapshot Record:** A `WorkOrderSnapshot` record with type `PROMOTION` must be created upon successful promotion.
- **Snapshot Payload:** The snapshot must contain:
  - Customer snapshot: name, billing address, primary contact info, account type
  - Vehicle snapshot: VIN, year, make, model, mileage at promotion
  - Promotion metadata: workorderId, estimateId, sourceApprovalEventId, promotingUserId, shopId, timestamp

**Changes:**
- ❌ Removed `WorkorderPromotedFromEstimate` event type
- ❌ Removed `sourceEstimateVersionId` and `sourceApprovalId` from payload
- ✅ Changed to `WorkOrderSnapshot` approach
- ✅ Added customer and vehicle snapshot details
- ✅ Added `sourceApprovalEventId`

---

### New Sections Added

#### Out of Scope Section (NEW)
- **Role-Based Access Controls (RBAC):** Filtering workorder data visibility based on user roles (e.g., hiding prices from mechanics) is explicitly **out of scope** for this story. This will be addressed in a separate security/RBAC story.

#### Implementation Notes Section (NEW)
- Use existing `WorkOrder`, `WorkOrderService`, `WorkOrderPart`, and `WorkOrderSnapshot` entities.
- Add `sourceEstimateLineItemId` field to `WorkOrderService` and `WorkOrderPart` entities.
- Add `sourceEstimateId` and `sourceApprovalEventId` fields to `WorkOrder` entity.
- Implement service-layer idempotency check using `findByEstimateId` repository method.
- Initial workorder status is hard-coded to `APPROVED` for this flow in v1.
- Promotion snapshot type is `PROMOTION`.

---

## Summary Statistics

- **Entities Removed:** 2 (`EstimateVersion`, `ApprovalRecord`)
- **Entities Added:** 0 (using existing entities)
- **Entities Modified:** 4 (`WorkOrder`, `WorkOrderService`, `WorkOrderPart`, `WorkOrderSnapshot`)
- **Fields Removed:** 4 (`sourceEstimateVersionId`, `sourceApprovalId`, `customerSnapshot`, `vehicleSnapshot`)
- **Fields Added:** 3 (`sourceApprovalEventId`, 2x `sourceEstimateLineItemId`)
- **Acceptance Criteria Added:** 1 (AC-2)
- **New Sections Added:** 2 (Out of Scope, Implementation Notes)
- **Business Rules Clarified:** 3 (idempotency, RBAC scope, initial status)

---

## Impact Assessment

### Low Impact Changes
- Terminology updates (EstimateVersion → Estimate)
- Status name change (semantically similar)
- Snapshot location (implementation detail)

### Medium Impact Changes
- Line item entity split (affects mapping logic)
- Idempotency implementation approach
- Audit event approach (snapshot vs event)

### High Impact Changes
- Removal of EstimateVersion concept (major simplification)
- RBAC moved out of scope (scope reduction)
- Service-layer vs DB-enforced idempotency (implementation strategy)

---

## Developer Action Items

When implementing, developers need to:

1. ✅ Add `sourceEstimateId` field to `WorkOrder` entity
2. ✅ Add `sourceApprovalEventId` field to `WorkOrder` entity
3. ✅ Add `sourceEstimateLineItemId` to `WorkOrderService` entity
4. ✅ Add `sourceEstimateLineItemId` to `WorkOrderPart` entity
5. ✅ Enhance `WorkOrderSnapshot` to support type `PROMOTION`
6. ✅ Implement `findByEstimateId` repository method
7. ✅ Hard-code initial status to `APPROVED`
8. ✅ Map service lines to `WorkOrderService` records
9. ✅ Map part lines to `WorkOrderPart` records
10. ✅ Capture customer/vehicle snapshot in `WorkOrderSnapshot`
11. ✅ Implement service-layer idempotency check
12. ❌ Do NOT implement RBAC filtering (out of scope)
13. ❌ Do NOT create `EstimateVersion` or `ApprovalRecord` entities
14. ❌ Do NOT add `customerSnapshot`/`vehicleSnapshot` JSONB fields

---

*This comparison document was generated as part of issue #166 clarification resolution process.*
