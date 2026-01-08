# Durion Processing Tracker

## Issue Details
- **Issue Number:** #206 Clarification
- **Issue Title:** [CLARIFICATION] Origin #206: [BACKEND] [STORY] Approval: Capture In-Person Customer Approval
- **Domain:** domain:workexec
- **Type:** clarification:workflow
- **Date:** 2026-01-08

## Problem Summary
This is a clarification request issue that provides answers to questions about implementing in-person customer approval for work orders/estimates. The clarification provides the following key decisions:

1. **Approval Granularity**: Approval is for entire work order/estimate. Editor can mark certain items as declined before approval completion.

2. **Approval Capture Method**: Configurable by location and customer. Default behavior is click to confirm.

3. **State Machine Definition**:
   - Approved → Estimate is approved by customer
   - Ready for work → Approved estimate + parts, mechanic and location are available or scheduled
   - Scheduled → parts, mechanic and location are available or scheduled

4. **Decline Workflow**:
   - A "Declined" estimate never becomes a work order
   - A "Declined" estimate can be changed to "Approved" within X days (X is configurable)
   - "Declined" behavior for a work order is separate from a "Declined" estimate
   - Behavior for a "Declined" estimate is a separate story

## Implementation Plan

### Phase 1: Create Estimate Entity and State Machine
- [x] Create Estimate entity with status field
- [x] Add state constants (Draft, Approved, Declined, Expired)
- [x] Add relationship to WorkOrder (one-to-one)
- [x] Add estimateId field to WorkOrder entity

### Phase 2: Add Configuration Support
- [x] Create ApprovalConfiguration entity for location and customer configuration
- [x] Add fields for approval method (CLICK_CONFIRM, SIGNATURE, etc.)
- [x] Add repository and service for configuration

### Phase 3: Add Line Item Decline Support
- [x] Add declined flag to WorkOrderService and WorkOrderPart entities
- [x] Allow marking items as declined before final approval

### Phase 4: Implement State Transitions
- [x] Add approval timestamp to Estimate
- [x] Add decline timestamp and expiry date to Estimate
- [x] Add service methods for state transitions (approve, decline, reopen)
- [x] Add validation logic for state changes

### Phase 5: Update Services and Controllers
- [x] Update WorkOrderService to check estimate approval status
- [x] Add EstimateService for managing estimates
- [x] Add EstimateController with approval endpoints
- [x] Add configuration endpoints

### Phase 6: Testing and Validation
- [ ] Add unit tests for state transitions
- [ ] Add unit tests for configuration retrieval
- [ ] Build and verify the module (requires Java 21)
- [x] Document the changes

## Notes
- This is implementing the data model and business logic based on clarification
- UI implementation is separate
- Integration with pos-customer and pos-location is via IDs only
- Configurable decline expiry period should default to a reasonable value (e.g., 30 days)
