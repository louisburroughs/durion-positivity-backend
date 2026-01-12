# Issue #166 Update Summary

## Overview
This document summarizes the changes made to Issue #166 based on the clarification decisions provided in the review comment dated 2026-01-12.

## Changes Applied

### 1. Estimate Versioning (Q1)
**Decision:** Snapshot-only approach
- ✅ **REMOVED:** All references to `EstimateVersion` entity
- ✅ **REPLACED WITH:** "approved estimate snapshot" terminology
- ✅ **UPDATED:** Direct link from `Workorder` to `Estimate.id` via `sourceEstimateId`

**Affected Sections:**
- Preconditions
- Functional Behavior (Source Linking)
- Data Requirements

### 2. Approval Record Tracking (Q2)
**Decision:** Hybrid approach - inline metadata + audit event
- ✅ **REMOVED:** All references to `ApprovalRecord` entity
- ✅ **REPLACED WITH:** `sourceApprovalEventId` field on `Workorder`
- ✅ **CLARIFIED:** Approval metadata stored inline on `Estimate` with audit event emission

**Affected Sections:**
- Preconditions
- Functional Behavior (Source Linking)
- Data Requirements (`Workorder` entity)
- Business Rules

### 3. Line Item Structure (Q3)
**Decision:** Use existing concrete entities instead of generic `WorkorderItem`
- ✅ **REMOVED:** Generic `WorkorderItem` entity
- ✅ **REPLACED WITH:** Specific entities:
  - `WorkOrderService` for service lines
  - `WorkOrderPart` for part lines
- ✅ **ADDED:** `sourceEstimateLineItemId` field to both entities for traceability
- ✅ **ADDED:** New acceptance criterion AC-2 for line item mapping

**Affected Sections:**
- Functional Behavior (Task Population)
- Data Requirements
- Acceptance Criteria (added AC-2)
- Implementation Notes

### 4. Customer/Vehicle Snapshot Strategy (Q4)
**Decision:** Use enhanced `WorkOrderSnapshot` instead of inline JSON fields
- ✅ **REMOVED:** `customerSnapshot` and `vehicleSnapshot` JSONB fields from `Workorder` entity
- ✅ **REPLACED WITH:** `WorkOrderSnapshot` entity with type `PROMOTION`
- ✅ **CLARIFIED:** Snapshot contains:
  - Customer subset: name, billing address, primary contact info, account type
  - Vehicle subset: VIN, year/make/model, mileage at promotion
  - Promotion metadata
- ✅ **UPDATED:** AC-5 to reference `WorkOrderSnapshot` instead of inline fields

**Affected Sections:**
- Functional Behavior (Data Snapshotting)
- Data Requirements (removed inline fields, added `WorkOrderSnapshot` entity)
- Acceptance Criteria (AC-1, AC-5)
- Audit & Observability

### 5. Initial Workorder Status (Q5)
**Decision:** Use existing `APPROVED` status instead of new `READY_FOR_SCHEDULING`
- ✅ **CHANGED:** Initial status from `Ready for Scheduling` to `APPROVED`
- ✅ **CLARIFIED:** Status is hard-coded for this flow in v1 (not configurable)
- ✅ **UPDATED:** AC-1 to expect `APPROVED` status

**Affected Sections:**
- Functional Behavior (State Initialization)
- Business Rules
- Acceptance Criteria (AC-1)
- Implementation Notes

### 6. Idempotency and One-to-One Relationship (Q6)
**Decision:** Service-layer enforcement with specific rules
- ✅ **CLARIFIED:** One active workorder per estimate
- ✅ **ADDED:** Cancelled workorders still count for idempotency
- ✅ **SPECIFIED:** Implementation using `findByEstimateId` repository method
- ✅ **NOTED:** No DB constraint in v1, enforced at service layer

**Affected Sections:**
- Alternate/Error Flows (Idempotent Promotion)
- Business Rules
- Implementation Notes

### 7. Audit Event Implementation (Q7)
**Decision:** Use `WorkOrderSnapshot` with type `PROMOTION` instead of separate audit event
- ✅ **REMOVED:** Reference to `WorkorderPromotedFromEstimate` audit event type
- ✅ **REPLACED WITH:** `WorkOrderSnapshot` record with type `PROMOTION`
- ✅ **UPDATED:** Snapshot payload to include all promotion metadata
- ✅ **MAINTAINED:** Metrics and logging requirements

**Affected Sections:**
- Functional Behavior (removed separate audit step)
- Data Requirements (`WorkOrderSnapshot` entity)
- Audit & Observability (complete rewrite)

### 8. Role-Based Visibility Scope (Q8)
**Decision:** RBAC is out of scope for this story
- ✅ **REMOVED:** RBAC implementation from Functional Behavior
- ✅ **REMOVED:** RBAC from Business Rules
- ✅ **ADDED:** New "Out of Scope" section explicitly stating RBAC is deferred
- ✅ **CLARIFIED:** This story only handles data creation, not presentation filtering

**Affected Sections:**
- Business Rules (removed RBAC bullet)
- New "Out of Scope" section added

## New Sections Added

1. **Out of Scope** - Explicitly documents that RBAC is not part of this story
2. **Implementation Notes** - Provides clear technical guidance for developers
3. **AC-2: Line Items Mapped Correctly** - New acceptance criterion for service/part mapping

## Sections Significantly Updated

1. **Functional Behavior** - Streamlined from 8 steps to 7, with clearer entity references
2. **Data Requirements** - Complete rewrite with correct entity names and fields
3. **Acceptance Criteria** - AC-1 and AC-5 updated, AC-2 added
4. **Audit & Observability** - Rewritten to use snapshot approach
5. **Business Rules** - Clarified idempotency rules, removed RBAC, simplified versioning

## Verification Checklist

- [x] All 8 clarification questions addressed
- [x] EstimateVersion references removed
- [x] ApprovalRecord references replaced with sourceApprovalEventId
- [x] WorkorderItem replaced with WorkOrderService and WorkOrderPart
- [x] Customer/Vehicle snapshots moved to WorkOrderSnapshot entity
- [x] Initial status changed to APPROVED
- [x] Idempotency rules clarified
- [x] Audit approach changed to snapshot-based
- [x] RBAC explicitly marked as out of scope
- [x] Implementation Notes section added
- [x] All Acceptance Criteria updated
- [x] Original story preserved for traceability

## Next Steps

The updated story body is available in `ISSUE-166-UPDATED-BODY.md` and should be used to update GitHub issue #166.

After updating the issue:
1. Remove the `clarification:domain` label
2. Verify the `status:ready-for-dev` label is present
3. Ensure assignees include `@github-copilot` for implementation
4. Consider adding a comment summarizing the clarification resolutions applied
