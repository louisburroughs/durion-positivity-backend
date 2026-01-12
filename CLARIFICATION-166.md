# [CLARIFICATION] Issue #166 - Estimate to Workorder Promotion Data Model

## Issue Reference
- **Origin Story**: #166 - [BACKEND] [STORY] Promotion: Create Workorder from Approved Estimate
- **Domain**: workexec
- **Agent**: Story Authoring Agent
- **Severity**: Blocking - Cannot implement without clarification
- **Date Created**: 2026-01-12

---

## Overview

The user story for promoting approved Estimates to Workorders contains references to several entities and fields that do not exist in the current implementation. This clarification is **required** to ensure the story can be implemented without making unsafe assumptions about business logic, data models, or domain behavior.

---

## Critical Questions Requiring Clarification

### Q1: Estimate Versioning Strategy

**Story Assumption**: The story references `EstimateVersion` as a distinct entity that tracks versions of estimates.

**Current Reality**: The `Estimate` entity does NOT have versioning support. There is no `EstimateVersion` entity.

**Question**: Should Estimates support versioning?

**Options**:
- **Option A**: Implement full versioning with `EstimateVersion` entity
  - Each modification creates a new version
  - Historical versions are preserved
  - Approval is tied to specific version
  - Workorder links to specific version
  
- **Option B**: Simplify to single-version model
  - `Estimate` entity remains without versioning
  - Modifications update estimate in-place
  - Approval is tied to estimate
  - Workorder links directly to estimate
  - Consider: What happens if estimate is edited after workorder is created?

- **Option C**: Snapshot-only approach
  - No separate version entity
  - Capture immutable snapshot when approved
  - Store snapshot data in approval record or workorder
  - Original estimate can be edited without affecting workorder

**Impact if Unanswered**:
- Cannot implement data requirements section as written
- Cannot enforce business rule "A single `Approved EstimateVersion` can be promoted to exactly one `Workorder`"
- Cannot maintain traceability from workorder to approved estimate content

---

### Q2: Approval Record Tracking

**Story Assumption**: The story assumes an `ApprovalRecord` entity captures approval events.

**Current Reality**: Approval is tracked inline in `Estimate` entity:
```java
private LocalDateTime approvedAt;
private Long approvedBy;
```

**Question**: Should approval events be tracked in a separate entity?

**Options**:
- **Option A**: Create `ApprovalRecord` entity
  - Separate entity with approval metadata
  - Better audit trail
  - Can track multiple approval attempts
  - Structure:
    ```
    id, estimateId, estimateVersionId?, approvedBy, approvedAt, 
    approvalMethod, signatureData?, notes
    ```

- **Option B**: Keep approval inline
  - Simple model - just timestamp and user ID
  - Update story to remove `ApprovalRecord` references
  - Accept limited audit trail
  - Consider: Is this sufficient for compliance/regulatory needs?

- **Option C**: Hybrid approach
  - Store approval summary inline
  - Publish approval event to audit log
  - Reference audit event ID from estimate

**Impact if Unanswered**:
- Cannot create immutable link to `ApprovalRecord` as specified
- Data requirements reference `sourceApprovalId` which doesn't exist
- Unclear audit trail for compliance purposes

---

### Q3: Estimate Line Item Structure

**Story Assumption**: The story references creating `WorkorderItem` records "for each line item present on the approved `EstimateVersion`."

**Current Reality**: 
- No `EstimateLineItem` entity found
- No `WorkorderItem` entity found
- Workorder implementation uses `WorkOrderService` (labor) and `WorkOrderPart` (parts)

**Question**: What is the structure of Estimate line items?

**Sub-questions**:
1. **Does an `EstimateLineItem` entity exist?** If not, how are estimate line items structured?
2. **How are estimate line items categorized?**
   - Separate services and parts tables?
   - Single table with discriminator?
   - Different approach?
3. **Should workorder items reference estimate line items?**
   - Add `sourceEstimateLineItemId` to `WorkOrderService` and `WorkOrderPart`?
   - Or is `estimateId` sufficient?
4. **Should the story use `WorkorderItem` or the actual entities?**
   - Update story to reference `WorkOrderService` and `WorkOrderPart`?
   - Or create a generic `WorkorderItem` abstraction?

**Impact if Unanswered**:
- Cannot implement "System creates `WorkorderItem` records for each line item"
- Cannot determine mapping from estimate to workorder line items
- Cannot maintain traceability from workorder items to estimate items

---

### Q4: Customer and Vehicle Snapshot Strategy

**Story Requirement**: The story requires snapshotting customer and vehicle data in JSONB fields:
```
customerSnapshot (JSONB, contains customer details at time of creation)
vehicleSnapshot (JSONB, contains vehicle details at time of creation)
```

**Current Reality**: 
- `WorkOrder` stores foreign keys only: `customerId`, `vehicleId`
- `WorkOrderSnapshot` entity exists but captures **entire workorder state**, not customer/vehicle specifically

**Question**: How should customer and vehicle data be snapshotted?

**Sub-questions**:
1. **What customer fields should be snapshotted?**
   - Name, billing address, contact info?
   - Payment terms, credit status?
   - All fields or subset?

2. **What vehicle fields should be snapshotted?**
   - VIN, make, model, year?
   - Current mileage/odometer?
   - All fields or subset?

3. **Storage format?**
   - JSONB (PostgreSQL-specific but queryable)?
   - TEXT with JSON serialization (universal)?
   - Separate denormalized columns?

4. **Where should snapshots live?**
   - Add columns to `WorkOrder` entity?
   - Enhance `WorkOrderSnapshot` entity?
   - Create separate `CustomerSnapshot` and `VehicleSnapshot` entities?

5. **When should snapshots be captured?**
   - At workorder creation from estimate?
   - At workorder approval/start?
   - Both?

**Impact if Unanswered**:
- AC-4 "Customer and Vehicle data is Snapshotted Correctly" cannot be validated
- Schema changes cannot be designed
- Integration with pos-customer and pos-vehicle services unclear

---

### Q5: Initial Workorder Status Configuration

**Story Assumption**: "The initial status of a new `Workorder` is determined by a system or shop-level policy."

**Current Reality**:
- `WorkOrder` defaults to `DRAFT` status
- Available statuses: DRAFT, APPROVED, ASSIGNED, WORK_IN_PROGRESS, AWAITING_PARTS, AWAITING_APPROVAL, READY_FOR_PICKUP, COMPLETED, CANCELLED

**Story Reference**: AC-1 expects initial status of `Ready for Scheduling`

**Question**: What should be the initial status for promoted workorders?

**Sub-questions**:
1. **Should `READY_FOR_SCHEDULING` be added as a new status?**
   - Or should story use existing status like `APPROVED` or `ASSIGNED`?

2. **Where should initial status policy be configured?**
   - Application properties (`workorder.promotion.initialStatus=APPROVED`)?
   - Database configuration table?
   - Shop-level configuration?
   - Hard-coded in promotion logic?

3. **Should initial status vary by context?**
   - Depends on approval method (manual vs automatic)?
   - Depends on shop policy?
   - Depends on estimate attributes (emergency work, scheduled work)?

4. **Should promoted workorders always be ready for work?**
   - Or do they need additional approval/review steps?

**Impact if Unanswered**:
- AC-1 validation will fail (expects `Ready for Scheduling` which doesn't exist)
- Cannot implement configurable initial status logic
- May create workorders in wrong state for shop workflow

---

### Q6: Idempotency and One-to-One Relationship

**Story Requirement**: AC-2 requires idempotent promotion - same estimate should not create duplicate workorders.

**Current Reality**:
- `WorkOrder` has `estimateId` field but no unique constraint
- No repository method to find workorder by estimate ID

**Question**: How should one-to-one relationship be enforced?

**Sub-questions**:
1. **Should database enforce uniqueness?**
   - Add unique constraint on `WorkOrder.estimateId`?
   - Or allow multiple workorders from same estimate (with soft rules)?

2. **What if estimate is modified after workorder creation?**
   - Should relationship be to estimate version?
   - Should workorder be invalidated?
   - Should this trigger change request workflow?

3. **What if workorder is cancelled?**
   - Can estimate be re-promoted to new workorder?
   - Should cancelled workorders count toward one-to-one rule?

4. **What if estimate is declined after promotion?**
   - Should workorder be automatically cancelled?
   - Or should workorder remain independent?

**Impact if Unanswered**:
- Risk of duplicate workorders from same estimate
- AC-2 validation cannot be implemented
- Unclear behavior for edge cases (cancellation, decline)

---

### Q7: Audit Event Implementation

**Story Requirement**: "An event of type `WorkorderPromotedFromEstimate` must be created upon successful promotion."

**Current Infrastructure**:
- `WorkOrderStateTransition` entity (tracks status changes)
- `WorkOrderSnapshot` entity (captures state snapshots)

**Question**: How should promotion events be captured?

**Options**:
- **Option A**: Use `WorkOrderStateTransition`
  - Record transition from `null` → initial status
  - Store promotion metadata in `metadata` JSON field
  - Pros: Reuses existing infrastructure
  - Cons: Semantically different from status change

- **Option B**: Create `WorkOrderEvent` entity
  - Generic event entity for non-status events
  - Fields: `eventType`, `workorderId`, `eventData`, `timestamp`, `userId`
  - Pros: Clean separation of concerns
  - Cons: New entity and infrastructure

- **Option C**: Enhance `WorkOrderSnapshot`
  - Capture snapshot at promotion with type `PROMOTION`
  - Include promotion metadata
  - Pros: Reuses snapshot infrastructure
  - Cons: Snapshot may be heavyweight for simple event

**Required Event Payload**: `workorderId`, `sourceEstimateId`, `sourceEstimateVersionId?`, `sourceApprovalId?`, `promotingUserId`, `shopId`, `timestamp`

**Impact if Unanswered**:
- Cannot implement audit requirements
- Unclear how to query promotion history
- May not meet compliance needs

---

### Q8: Role-Based Visibility Implementation Scope

**Story Mention**: "System applies role-based visibility rules (e.g., hide prices for mechanics)."

**Current Reality**: Mentioned in functional flow but not detailed in acceptance criteria.

**Question**: Should RBAC filtering be part of this story or deferred?

**Sub-questions**:
1. **What data should be filtered by role?**
   - Labor rates, part costs, markup percentages?
   - Customer financial information?
   - Approval details?

2. **What roles exist in the system?**
   - Service Advisor, Mechanic, Shop Manager, Billing Clerk?
   - Should roles be defined now or in separate story?

3. **Where should filtering occur?**
   - API layer (controller response)?
   - Service layer (DTOs)?
   - View layer (UI responsibility)?

4. **Should this be a separate story?**
   - RBAC could be complex enough to warrant own story
   - Or should basic filtering be included in promotion story?

**Impact if Unanswered**:
- Unclear scope for this story
- May over-engineer or under-deliver on RBAC
- Cannot estimate effort accurately

---

## Summary of Blocking Issues

The following questions **MUST** be answered before story implementation:

1. ✋ **Q1**: Estimate versioning strategy (affects core data model)
2. ✋ **Q2**: Approval record tracking (affects audit trail)
3. ✋ **Q3**: Estimate line item structure (affects item promotion logic)
4. ✋ **Q4**: Snapshot strategy (affects schema and data capture)
5. ✋ **Q5**: Initial workorder status (affects AC-1 validation)

The following questions are **important but potentially deferrable**:

6. ⚠️ **Q6**: Idempotency enforcement (can be implemented as soft validation initially)
7. ⚠️ **Q7**: Audit event implementation (can use existing infrastructure)
8. ⚠️ **Q8**: RBAC scope (can be deferred to separate story)

---

## Recommended Approach for Resolution

1. **Immediate**: Schedule meeting with workexec domain agent and product owner
2. **Priority 1**: Resolve Q1-Q5 (blocking)
3. **Priority 2**: Resolve Q6-Q8 (important)
4. **Documentation**: Update story with clarification results
5. **Validation**: Review updated story with story authoring agent
6. **Handoff**: Mark story as `status:ready-for-dev` once complete

---

## Story Authoring Agent Actions

Per protocol, the Story Authoring Agent has:

1. ✅ Stopped story finalization
2. ✅ Created this clarification issue
3. 🔄 Will mark story with `blocked:clarification` label
4. 🔄 Will link this issue to story #166
5. ⏳ Awaiting clarification responses
6. ⏳ Will update story when clarifications are resolved
7. ⏳ Will remove block and mark `status:ready-for-dev` when complete

---

## Related Issues
- Origin Story: #166
- Related Implementation: WORKORDER_STATE_MACHINE.md
- Domain: workexec

---

## Labels to Apply
- `type:clarification`
- `priority:high`
- `blocks:#166`
- `domain:workexec`
- `agent:story-authoring`
