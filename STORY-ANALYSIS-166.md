# Story Analysis - Issue #166: Promotion: Create Workorder from Approved Estimate

## Executive Summary

**Story Status**: Requires Clarification  
**Domain**: workexec  
**Agent**: Story Authoring Agent  
**Date**: 2026-01-12

The user story for promoting an approved Estimate to a Workorder is **structurally well-written** but contains **significant data model inconsistencies** with the existing implementation. Several entities and fields referenced in the story do not exist in the current codebase, requiring clarification before implementation.

---

## Critical Findings

### 1. Missing Entity: `EstimateVersion`

**Story Reference**: The story references `EstimateVersion` as a distinct entity that tracks versions of estimates and is linked to `ApprovalRecord`.

**Current Implementation**: The `Estimate` entity does NOT have versioning support. There is no `EstimateVersion` entity in the codebase.

**Current `Estimate` Entity Structure**:
```java
@Entity
public class Estimate {
    private Long id;
    private Long shopId;
    private Long vehicleId;
    private Long customerId;
    private EstimateStatus status; // DRAFT, APPROVED, DECLINED, EXPIRED
    private LocalDateTime createdAt;
    private LocalDateTime approvedAt;
    private LocalDateTime declinedAt;
    private LocalDateTime expiresAt;
    private Long approvalConfigurationId;
    private String declineReason;
    private Long approvedBy;
}
```

**Impact**:
- The story's precondition "The specific `EstimateVersion` that was approved must be clearly identifiable via an `ApprovalRecord`" cannot be satisfied
- Data Requirements section references `sourceEstimateVersionId` which doesn't exist
- Business rule "A single `Approved EstimateVersion` can be promoted to exactly one `Workorder`" cannot be enforced without versioning

**Clarification Needed**:
1. Should Estimates support versioning? If so, what triggers a new version?
2. Should the system support multiple versions of the same estimate with independent approval states?
3. If versioning is not needed, should the story be simplified to reference `Estimate` directly instead of `EstimateVersion`?

---

### 2. Missing Entity: `ApprovalRecord`

**Story Reference**: The story assumes an `ApprovalRecord` entity that captures who approved an estimate and when.

**Current Implementation**: There is NO `ApprovalRecord` entity. The `Estimate` entity tracks approval inline:
```java
private LocalDateTime approvedAt;
private Long approvedBy;
```

**Impact**:
- Cannot create immutable link to `ApprovalRecord` as specified in story
- Data Requirements section references `sourceApprovalId` which doesn't exist
- Audit trail for approvals is limited to fields on Estimate entity

**Clarification Needed**:
1. Should approval events be tracked in a separate `ApprovalRecord` entity for better auditability?
2. If approval tracking remains inline in `Estimate`, should the story be updated to reflect this?
3. What level of approval audit detail is required for regulatory/compliance purposes?

---

### 3. Missing Entity: `WorkorderItem`

**Story Reference**: "The System creates `WorkorderItem` records for each line item present on the approved `EstimateVersion`."

**Current Implementation**: There is NO `WorkorderItem` entity. Instead, the system uses:
- `WorkOrderService` entity (for services/labor)
- `WorkOrderPart` entity (for parts)

Both entities have these fields:
```java
private Long changeRequestId;        // For tracking origin
private Boolean declined;            // If customer declined
private WorkOrderItemStatus status;  // OPEN, IN_PROGRESS, COMPLETED, etc.
private Boolean isEmergencySafety;
```

**Impact**:
- The story assumes a generic `WorkorderItem` concept that doesn't match the actual implementation
- Estimate line items need to be mapped to either services or parts
- No clarity on how estimate line items are structured or categorized

**Clarification Needed**:
1. What is the structure of line items on an Estimate? (services vs parts vs mixed)
2. Should the story be updated to use `WorkOrderService` and `WorkOrderPart` instead of `WorkorderItem`?
3. How should estimate line items be categorized when promoting to a workorder?
4. Is there a separate `EstimateLineItem` entity that needs to be defined?

---

### 4. Data Snapshotting Requirements

**Story Reference**: The story requires snapshotting customer and vehicle data in JSONB fields:
```
customerSnapshot (JSONB, contains customer details at time of creation)
vehicleSnapshot (JSONB, contains vehicle details at time of creation)
```

**Current Implementation**: The `WorkOrder` entity stores foreign key references only:
```java
private Long customerId;
private Long vehicleId;
```

**Current Snapshot Support**: The system has a `WorkOrderSnapshot` entity, but it captures **entire workorder state** for audit purposes, not customer/vehicle snapshots.

**Impact**:
- AC-4 requirement "Customer and Vehicle data is Snapshotted Correctly" cannot be satisfied with current schema
- Schema changes required to add JSONB/TEXT columns for snapshots
- Integration with pos-customer and pos-vehicle services needed to retrieve snapshot data

**Clarification Needed**:
1. What specific customer fields should be snapshotted? (name, address, contact info, payment terms?)
2. What specific vehicle fields should be snapshotted? (VIN, make, model, year, mileage?)
3. Should snapshots be stored as JSONB (PostgreSQL) or TEXT (universal)?
4. Should the existing `WorkOrderSnapshot` entity be enhanced, or should new snapshot fields be added to `WorkOrder`?

---

### 5. Missing Field: `sourceEstimateLineItemId`

**Story Reference**: The Data Requirements section includes:
```
WorkorderItem Entity:
  - sourceEstimateLineItemId (FK, immutable)
```

**Current Implementation**: Neither `WorkOrderService` nor `WorkOrderPart` has a field to track the source estimate line item. They only track `changeRequestId` for change request tracking.

**Impact**:
- Cannot maintain immutable reference to estimate line items
- Traceability from workorder items back to estimate is lost
- Cannot determine which estimate line items were promoted

**Clarification Needed**:
1. Should `WorkOrderService` and `WorkOrderPart` add an `sourceEstimateLineItemId` field?
2. How should this field interact with the existing `changeRequestId` field?
3. If there's no versioning, would `estimateId` be sufficient for tracking?

---

### 6. Initial Workorder Status Configuration

**Story Reference**: "The initial status of a new `Workorder` is determined by a system or shop-level policy."

**Current Implementation**: The `WorkOrder` entity defaults to `DRAFT` status:
```java
@Builder.Default
private WorkOrderStatus status = WorkOrderStatus.DRAFT;
```

**Available Statuses** (from `WorkOrderStatus` enum):
- DRAFT
- APPROVED
- ASSIGNED
- WORK_IN_PROGRESS
- AWAITING_PARTS
- AWAITING_APPROVAL
- READY_FOR_PICKUP
- COMPLETED
- CANCELLED

**Impact**:
- Story assumes `Ready for Scheduling` status which doesn't exist
- No configuration mechanism for initial status policy
- AC-1 expects `Ready for Scheduling` but system will create in `DRAFT`

**Clarification Needed**:
1. Should a new status `READY_FOR_SCHEDULING` be added to the enum?
2. Should promoted workorders start in `APPROVED` or `ASSIGNED` status instead of `DRAFT`?
3. Where should the initial status policy be configured? (application.properties, database config, per-shop config?)
4. Should the initial status depend on whether the estimate was approved via specific approval methods?

---

### 7. Idempotency Implementation

**Story Reference**: AC-2 requires idempotent promotion - attempting to promote the same estimate twice should return the existing workorder.

**Current Implementation**: The `WorkOrder` entity has an `estimateId` field but there's no unique constraint or query method to check for existing workorders from an estimate.

**Impact**:
- Cannot enforce one-to-one relationship between approved estimate and workorder
- Risk of duplicate workorders from the same estimate
- AC-2 cannot be validated without query support

**Clarification Needed**:
1. Should a database constraint enforce one workorder per estimate?
2. Should the `WorkOrderRepository` add a `findByEstimateId(Long estimateId)` method?
3. What should happen if an estimate is declined after a workorder is created?
4. Can a workorder be cancelled and the estimate re-promoted to a new workorder?

---

### 8. Audit Event Schema

**Story Reference**: "An event of type `WorkorderPromotedFromEstimate` must be created upon successful promotion."

**Current Implementation**: The system has audit/state transition support via:
- `WorkOrderStateTransition` entity (tracks status changes)
- `WorkOrderSnapshot` entity (captures state snapshots)

**Current Audit Infrastructure**:
```java
@Entity
public class WorkOrderStateTransition {
    private Long workOrderId;
    private String fromStatus;
    private String toStatus;
    private LocalDateTime transitionedAt;
    private Long transitionedBy;
    private String reason;
    private String metadata; // JSON
}
```

**Impact**:
- No dedicated event type for promotion from estimate
- Promotion event could be captured as a state transition, but semantics differ
- Story's audit requirements can be partially met but need clarification

**Clarification Needed**:
1. Should promotion events use the existing `WorkOrderStateTransition` infrastructure?
2. Should a separate `WorkOrderEvent` entity be created for non-status events?
3. Should the event include all fields from story requirement: `workorderId`, `sourceEstimateId`, `sourceEstimateVersionId`, `sourceApprovalId`, `promotingUserId`, `shopId`, `timestamp`?
4. Should events be published to an event bus (Kafka/RabbitMQ) or just stored in database?

---

### 9. Role-Based Visibility Rules

**Story Reference**: "System applies role-based visibility rules (e.g., hide prices for mechanics)."

**Current Implementation**: The system has a `pos-security-service` module, but the story doesn't specify:
- What data should be filtered by role
- At what layer filtering should occur (API, service, database)
- What roles exist in the system

**Impact**:
- Functional requirement is mentioned but not detailed in acceptance criteria
- No clear implementation guidance for developers
- May require coordination with security domain agent

**Clarification Needed**:
1. What specific data should be hidden from mechanics? (labor rates, part costs, markup percentages?)
2. What are the standard roles in the system? (Service Advisor, Mechanic, Shop Manager, etc.)
3. Should filtering happen in the API layer or should DTOs be role-aware?
4. Should this be deferred to a separate story for RBAC implementation?

---

## Alignment Issues

### Story vs. Implementation Terminology Mismatch

| Story Term | Implementation Term | Notes |
|------------|---------------------|-------|
| `WorkorderItem` | `WorkOrderService` + `WorkOrderPart` | Story uses generic term, implementation is specialized |
| `EstimateVersion` | `Estimate` (no versioning) | Story assumes versioning that doesn't exist |
| `ApprovalRecord` | Inline fields on `Estimate` | Story assumes separate entity |
| `Ready for Scheduling` | Not in enum | Story references non-existent status |
| `sourceEstimateLineItemId` | Not present | Story references non-existent field |

---

## Positive Findings

Despite the clarification needs, several aspects of the story are well-aligned:

1. **Existing Estimate Approval Flow**: The `EstimateService` has solid approval/decline/reopen logic that can be leveraged
2. **State Machine Infrastructure**: The `WorkOrderStateMachine` service provides excellent foundation for workorder lifecycle management
3. **Audit Trail Support**: The `WorkOrderStateTransition` and `WorkOrderSnapshot` entities provide robust audit capabilities
4. **Validation Logic**: The story's validation requirements (approved status, permissions) align with existing patterns

---

## Recommendations

### Immediate Actions Required

1. **Create Clarification Issue** with the following questions:
   - Versioning strategy for Estimates (needed or not?)
   - Structure of EstimateLineItem entity
   - Approval record tracking approach
   - Snapshot storage strategy (JSONB vs denormalized fields)
   - Initial workorder status configuration
   - RBAC implementation scope

2. **Defer to Domain Agent**: Consult with `workexec-domain-agent` on:
   - State model for promotion flow
   - Terminology standardization
   - Business invariants for estimate-to-workorder promotion

3. **Update Story** after clarifications are resolved to:
   - Use correct entity names from implementation
   - Add missing entity definitions
   - Align status values with existing enum
   - Clarify snapshot approach
   - Add data model diagrams

---

## Story Quality Assessment

| Criterion | Rating | Notes |
|-----------|--------|-------|
| **Story Intent** | ✅ Excellent | Clear actor, trigger, and outcome |
| **Acceptance Criteria** | ✅ Good | Well-structured BDD format |
| **Business Rules** | ⚠️ Needs Refinement | Some rules reference non-existent entities |
| **Data Requirements** | ❌ Incomplete | Significant misalignment with implementation |
| **Audit Requirements** | ✅ Good | Clear observability needs |
| **Error Flows** | ✅ Good | Comprehensive error handling |
| **Domain Alignment** | ❌ Poor | Terminology and entity mismatches |

**Overall**: The story demonstrates strong structure and thinking but requires **significant clarification and refinement** before implementation can begin.

---

## Next Steps

As the **Story Authoring Agent**, I will:

1. ✅ **STOP story finalization** per protocol section 7
2. 🔄 **Create clarification issue** with detailed questions
3. 🔄 **Mark story** with `blocked:clarification` label
4. 🔄 **Link clarification issue** to story
5. ⏳ **Wait for clarifications** to be resolved
6. ⏳ **Update story** based on clarification responses
7. ⏳ **Remove block** and mark `status:ready-for-dev` when complete

---

## Stop Phrase

**STOP: Insufficient domain information**

The story requires clarification on data model entities, versioning strategy, and terminology alignment before implementation can proceed safely.
