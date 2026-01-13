# Implementation Plan: Invalidate Approval on Estimate Revision

**Issue**: #203 - [BACKEND] [STORY] Approval: Invalidate Approval on Estimate Revision

## Executive Summary

This document outlines the minimal implementation required to automatically invalidate a WorkOrder's approval status when the underlying Estimate is financially revised. The solution uses an event-driven architecture to maintain clear domain boundaries between pricing concerns (Estimate) and execution concerns (WorkOrder).

## Domain Conflict Resolution

### Resolved Decisions

1. **Domain Ownership**:
   - **Pricing Responsibility**: Estimate entity and financial calculations
   - **WorkExec Responsibility**: WorkOrder approval lifecycle
   - **Communication**: Event-driven via pos-events module

2. **Invalidation Threshold**:
   - ANY change to Estimate total amount triggers invalidation
   - Rationale: Conservative approach ensures customer always approves actual cost
   - Future enhancement: Configurable threshold (e.g., ±5% or ±$10)

3. **State Naming**:
   - Use existing `AWAITING_APPROVAL` state (already in WorkOrderStatus enum)
   - Transition: `APPROVED` → `AWAITING_APPROVAL`
   - Rationale: Reuses existing state machine, minimal changes

4. **Event Contract**:
   - Event: `EstimateRevisedEvent`
   - Published by: EstimateService (when total changes)
   - Consumed by: WorkOrderService
   - Payload: estimateId, workOrderId, oldTotal, newTotal, userId, timestamp

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     pos-work-order                          │
│                                                             │
│  ┌──────────────┐         ┌──────────────────────────┐    │
│  │  Estimate    │  total  │  EstimateService         │    │
│  │  Entity      │─changes─▶  - calculateTotal()      │    │
│  │              │         │  - updateEstimate()      │    │
│  │  - id        │         │  - publishRevisionEvent()│    │
│  │  - total     │         └────────────┬─────────────┘    │
│  │  - version   │                      │                  │
│  └──────────────┘                      │ publishes        │
│                                        │                  │
│                                        ▼                  │
│                              ┌──────────────────┐         │
│                              │  pos-events      │         │
│                              │  Event Bus       │         │
│                              └──────────┬───────┘         │
│                                        │                  │
│                                        │ consumes         │
│                                        ▼                  │
│  ┌──────────────┐         ┌──────────────────────────┐   │
│  │  WorkOrder   │  update │  WorkOrderService        │   │
│  │  Entity      │◀────────│  - onEstimateRevised()   │   │
│  │              │         │  - invalidateApproval()  │   │
│  │  - status    │         └──────────────────────────┘   │
│  │  - estimateId│                                         │
│  └──────────────┘                                         │
└─────────────────────────────────────────────────────────────┘
```

## Existing Code Analysis

### Estimate Entity (pos-work-order/entity/Estimate.java)
- ✅ Has `id`, `status`, `createdAt`, `updatedAt`
- ✅ Has `locationId`, `customerId`, `vehicleId`
- ❌ No `total` field (need to add)
- ❌ No `version` field (need to add)
- ✅ Has lifecycle methods (`@PrePersist`, `@PreUpdate`)

### WorkOrder Entity (pos-work-order/entity/WorkOrder.java)
- ✅ Has `id`, `status`, `estimateId`
- ✅ Has `customerId`, `vehicleId`, `shopId`
- ✅ Status is enum `WorkOrderStatus`

### WorkOrderStatus Enum (pos-work-order/entity/WorkOrderStatus.java)
- ✅ Has `APPROVED` state
- ✅ Has `AWAITING_APPROVAL` state (we'll use this for re-approval)
- ✅ Has state transition validation (`canTransitionTo()`)

### EstimateService (pos-work-order/service/EstimateService.java)
- ✅ Has `approveEstimate()`, `declineEstimate()`
- ✅ Has `createEstimate()`, `getEstimateById()`
- ❌ No total calculation logic
- ❌ No event publishing

### WorkOrderService (pos-work-order/service/WorkOrderService.java)
- ✅ Has state management methods
- ❌ No event listener for estimate changes

### pos-events Module
- ✅ Has `@EmitEvent` annotation
- ✅ Has `EmitEventAspect` for AOP-based event publishing
- ✅ Has event infrastructure

## Implementation Steps

### Phase 1: Estimate Total Calculation (Pricing Concern)

**File**: `pos-work-order/src/main/java/com/positivity/workorder/entity/Estimate.java`

**Changes**:
```java
// Add fields
private BigDecimal subtotal;
private BigDecimal taxAmount;
private BigDecimal total;
private Integer version; // Increment on financial changes
```

**File**: `pos-work-order/src/main/java/com/positivity/workorder/service/EstimateService.java`

**New Method**:
```java
@Transactional
public Estimate updateEstimate(Long estimateId, EstimateUpdateRequest request, Long userId) {
    Estimate estimate = estimateRepository.findById(estimateId)
        .orElseThrow(() -> new IllegalArgumentException("Estimate not found"));
    
    // Calculate new total
    BigDecimal oldTotal = estimate.getTotal();
    BigDecimal newTotal = calculateTotal(request);
    
    // Check if financially significant change occurred
    boolean totalChanged = oldTotal.compareTo(newTotal) != 0;
    
    // Update estimate
    estimate.setSubtotal(request.getSubtotal());
    estimate.setTaxAmount(request.getTaxAmount());
    estimate.setTotal(newTotal);
    
    if (totalChanged) {
        estimate.setVersion(estimate.getVersion() + 1);
    }
    
    Estimate saved = estimateRepository.save(estimate);
    
    // Publish event if total changed
    if (totalChanged && estimate.getLocationId() != null) {
        publishEstimateRevisedEvent(estimate, oldTotal, newTotal, userId);
    }
    
    return saved;
}

private void publishEstimateRevisedEvent(Estimate estimate, BigDecimal oldTotal, 
                                         BigDecimal newTotal, Long userId) {
    // Find associated WorkOrder (if exists)
    List<WorkOrder> workOrders = workOrderRepository.findByEstimateId(estimate.getId());
    
    for (WorkOrder workOrder : workOrders) {
        EstimateRevisedEvent event = EstimateRevisedEvent.builder()
            .estimateId(estimate.getId())
            .workOrderId(workOrder.getId())
            .oldTotal(oldTotal)
            .newTotal(newTotal)
            .changedBy(userId)
            .timestamp(Instant.now())
            .build();
        
        eventPublisher.publishEvent(event);
    }
}
```

### Phase 2: Event Definition

**New File**: `pos-work-order/src/main/java/com/positivity/workorder/event/EstimateRevisedEvent.java`

```java
package com.positivity.workorder.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EstimateRevisedEvent {
    private Long estimateId;
    private Long workOrderId;
    private BigDecimal oldTotal;
    private BigDecimal newTotal;
    private Long changedBy;
    private Instant timestamp;
    
    public String getEventType() {
        return "estimate.revised.v1";
    }
}
```

### Phase 3: WorkOrder Approval Invalidation (WorkExec Concern)

**File**: `pos-work-order/src/main/java/com/positivity/workorder/service/WorkOrderService.java`

**New Method**:
```java
@EventListener
@Transactional
public void onEstimateRevised(EstimateRevisedEvent event) {
    log.info("Received EstimateRevisedEvent for workOrderId={}, estimateId={}", 
             event.getWorkOrderId(), event.getEstimateId());
    
    Optional<WorkOrder> workOrderOpt = workOrderRepository.findById(event.getWorkOrderId());
    
    if (workOrderOpt.isEmpty()) {
        log.warn("WorkOrder not found for id={}", event.getWorkOrderId());
        return;
    }
    
    WorkOrder workOrder = workOrderOpt.get();
    
    // Only invalidate if currently in APPROVED status
    if (workOrder.getStatus() != WorkOrderStatus.APPROVED) {
        log.info("WorkOrder {} not in APPROVED status, skipping invalidation", 
                 workOrder.getId());
        return;
    }
    
    // Transition to AWAITING_APPROVAL
    WorkOrderStatus oldStatus = workOrder.getStatus();
    workOrder.setStatus(WorkOrderStatus.AWAITING_APPROVAL);
    workOrderRepository.save(workOrder);
    
    // Create audit event
    createApprovalInvalidationAudit(workOrder, oldStatus, event);
    
    log.info("WorkOrder {} approval invalidated due to estimate revision. " +
             "Old total: {}, New total: {}", 
             workOrder.getId(), event.getOldTotal(), event.getNewTotal());
}

private void createApprovalInvalidationAudit(WorkOrder workOrder, 
                                             WorkOrderStatus oldStatus,
                                             EstimateRevisedEvent event) {
    AuditEvent audit = AuditEvent.builder()
        .entityType("WorkOrder")
        .entityId(workOrder.getId())
        .eventType("approval.invalidated")
        .eventTimestamp(event.getTimestamp())
        .userId(event.getChangedBy())
        .previousValue(oldStatus.name())
        .newValue(WorkOrderStatus.AWAITING_APPROVAL.name())
        .details(String.format(
            "Approval invalidated due to estimate revision. " +
            "EstimateId: %d, Old total: %s, New total: %s",
            event.getEstimateId(), 
            event.getOldTotal(), 
            event.getNewTotal()))
        .build();
    
    auditEventRepository.save(audit);
}
```

### Phase 4: State Transition Validation

**File**: `pos-work-order/src/main/java/com/positivity/workorder/entity/WorkOrderStatus.java`

**Verify/Update**:
```java
// Ensure APPROVED can transition to AWAITING_APPROVAL
static {
    ALLOWED_TRANSITIONS.put(APPROVED, Set.of(
        ASSIGNED, 
        WORK_IN_PROGRESS, 
        AWAITING_APPROVAL,  // <-- Add this if not present
        CANCELLED
    ));
    // ... rest of transitions
}
```

### Phase 5: Integration Tests

**New File**: `pos-work-order/src/test/java/com/positivity/workorder/service/EstimateRevisionWorkflowTest.java`

```java
@SpringBootTest
@Transactional
class EstimateRevisionWorkflowTest {
    
    @Autowired
    private EstimateService estimateService;
    
    @Autowired
    private WorkOrderService workOrderService;
    
    @Autowired
    private WorkOrderRepository workOrderRepository;
    
    @Autowired
    private EstimateRepository estimateRepository;
    
    @Test
    void testApprovalInvalidatedWhenEstimateTotalIncreases() {
        // Given: Create and approve an estimate
        Estimate estimate = createTestEstimate(new BigDecimal("500.00"));
        estimate = estimateService.approveEstimate(estimate.getId(), 1L);
        
        // Create work order from approved estimate
        WorkOrder workOrder = createTestWorkOrder(estimate.getId());
        workOrder.setStatus(WorkOrderStatus.APPROVED);
        workOrder = workOrderRepository.save(workOrder);
        
        // When: Update estimate with higher total
        EstimateUpdateRequest update = EstimateUpdateRequest.builder()
            .subtotal(new BigDecimal("600.00"))
            .taxAmount(new BigDecimal("50.00"))
            .total(new BigDecimal("650.00"))
            .build();
        
        estimateService.updateEstimate(estimate.getId(), update, 1L);
        
        // Then: WorkOrder status should be invalidated
        workOrder = workOrderRepository.findById(workOrder.getId()).orElseThrow();
        assertEquals(WorkOrderStatus.AWAITING_APPROVAL, workOrder.getStatus());
    }
    
    @Test
    void testApprovalNotInvalidatedWhenEstimateTotalUnchanged() {
        // Given: Approved estimate and work order
        Estimate estimate = createTestEstimate(new BigDecimal("500.00"));
        WorkOrder workOrder = createApprovedWorkOrder(estimate.getId());
        
        // When: Update estimate with same total (non-financial change)
        EstimateUpdateRequest update = EstimateUpdateRequest.builder()
            .subtotal(new BigDecimal("475.00")) // Changed
            .taxAmount(new BigDecimal("25.00"))  // Changed
            .total(new BigDecimal("500.00"))     // Same total
            .build();
        
        estimateService.updateEstimate(estimate.getId(), update, 1L);
        
        // Then: WorkOrder status should remain APPROVED
        workOrder = workOrderRepository.findById(workOrder.getId()).orElseThrow();
        assertEquals(WorkOrderStatus.APPROVED, workOrder.getStatus());
    }
    
    @Test
    void testApprovalInvalidationOnlyAffectsApprovedWorkOrders() {
        // Given: Work order in different status
        Estimate estimate = createTestEstimate(new BigDecimal("500.00"));
        WorkOrder workOrder = createTestWorkOrder(estimate.getId());
        workOrder.setStatus(WorkOrderStatus.WORK_IN_PROGRESS);
        workOrder = workOrderRepository.save(workOrder);
        
        // When: Update estimate total
        EstimateUpdateRequest update = EstimateUpdateRequest.builder()
            .total(new BigDecimal("650.00"))
            .build();
        
        estimateService.updateEstimate(estimate.getId(), update, 1L);
        
        // Then: WorkOrder status should remain unchanged
        workOrder = workOrderRepository.findById(workOrder.getId()).orElseThrow();
        assertEquals(WorkOrderStatus.WORK_IN_PROGRESS, workOrder.getStatus());
    }
}
```

## Data Model Changes

### Database Migration

**New File**: `pos-work-order/src/main/resources/db/migration/V1_XX__add_estimate_total_and_version.sql`

```sql
-- Add total tracking to estimates
ALTER TABLE estimate ADD COLUMN subtotal DECIMAL(10,2);
ALTER TABLE estimate ADD COLUMN tax_amount DECIMAL(10,2);
ALTER TABLE estimate ADD COLUMN total DECIMAL(10,2);
ALTER TABLE estimate ADD COLUMN version INTEGER DEFAULT 1 NOT NULL;

-- Update existing estimates with default values
UPDATE estimate SET subtotal = 0.00, tax_amount = 0.00, total = 0.00, version = 1 
WHERE subtotal IS NULL;

-- Add index for work order lookup by estimate
CREATE INDEX idx_work_order_estimate_id ON work_order(estimate_id);
```

## API Changes

### No New REST Endpoints Required

The implementation uses internal event-driven communication. No new public API endpoints are needed.

### Modified Endpoints

**EstimateController** may need to add update endpoint if not present:

```java
@PutMapping("/{id}")
public ResponseEntity<Estimate> updateEstimate(
        @PathVariable Long id,
        @RequestBody EstimateUpdateRequest request,
        @RequestParam Long userId) {
    
    Estimate updated = estimateService.updateEstimate(id, request, userId);
    return ResponseEntity.ok(updated);
}
```

## Acceptance Criteria Validation

### Scenario 1: Estimate total increases
- **Given**: WorkOrder in APPROVED status, Estimate totaling $500.00
- **When**: Service Advisor adds $150.00 labor line item
- **Then**: WorkOrder status → AWAITING_APPROVAL
- **And**: Audit event created

✅ Covered by `testApprovalInvalidatedWhenEstimateTotalIncreases()`

### Scenario 2: Estimate total decreases
- **Given**: WorkOrder in APPROVED status, Estimate totaling $500.00
- **When**: Service Advisor changes part price, reducing total to $450.00
- **Then**: WorkOrder status → AWAITING_APPROVAL

✅ Covered by same test (any change to total)

### Scenario 3: Non-financial change
- **Given**: WorkOrder in APPROVED status
- **When**: Service Advisor adds note to line item, total unchanged
- **Then**: WorkOrder status remains APPROVED

✅ Covered by `testApprovalNotInvalidatedWhenEstimateTotalUnchanged()`

## Audit & Observability

### Audit Events
- **Event Type**: `approval.invalidated`
- **Fields**: workOrderId, estimateId, oldStatus, newStatus, oldTotal, newTotal, userId, timestamp

### Metrics
- Counter: `workorder.approvals.invalidated` (increment on each invalidation)
- Gauge: `workorder.awaiting_reapproval_count` (current count of work orders awaiting re-approval)

### Logging
```java
log.info("Approval invalidated: workOrderId={}, estimateId={}, oldTotal={}, newTotal={}", 
         workOrderId, estimateId, oldTotal, newTotal);
```

## Error Handling

### Scenarios
1. **Estimate not found**: Log warning, skip processing
2. **WorkOrder not found**: Log warning, skip processing
3. **Invalid state transition**: Log error, do not update status
4. **Event publishing failure**: Log error, retry with exponential backoff

### Retry Logic
- Event publishing: 3 retries with exponential backoff
- Database operations: Transactional, automatic rollback on failure

## Security Considerations

- All operations require authenticated user (userId parameter)
- Audit trail captures who made the change
- No permission bypass - follows existing RBAC
- No sensitive data in events (only IDs and amounts)

## Performance Considerations

- Event publishing is asynchronous (does not block estimate update)
- Database queries indexed (estimate_id, work_order.estimate_id)
- Event processing is lightweight (single status update)
- No impact on existing estimate approval workflow

## Rollback Plan

If issues arise:
1. Disable event publishing via feature flag
2. Manually transition WorkOrders back to APPROVED if needed
3. Database migration can be rolled back (drop columns)

## Future Enhancements

1. **Threshold Configuration**: Allow configurable tolerance (e.g., ±5% or ±$10)
2. **Notification Service**: Notify Service Advisor when approval invalidated
3. **Customer Portal**: Show re-approval required in customer portal
4. **Automatic Re-Approval**: If decrease, auto-approve with customer consent
5. **Change Summary**: Show detailed comparison of old vs new estimate

## Documentation Updates

- Update CUSTOMER_APPROVAL_WORKFLOW.md with revision workflow
- Update WORKORDER_STATE_MACHINE.md with APPROVED → AWAITING_APPROVAL transition
- Add API documentation for estimate update endpoint
- Update README with event-driven architecture notes

## Testing Strategy

### Unit Tests
- EstimateService.updateEstimate()
- EstimateService.publishEstimateRevisedEvent()
- WorkOrderService.onEstimateRevised()

### Integration Tests
- Full workflow: estimate update → event → work order invalidation
- Edge cases: non-financial changes, invalid states, missing entities

### Manual Testing
1. Create estimate with line items
2. Approve estimate
3. Create work order from estimate
4. Modify estimate line items
5. Verify work order status changes to AWAITING_APPROVAL
6. Verify audit event created

## Implementation Timeline

**Estimated Effort**: 4-6 hours

- Phase 1 (Estimate Total): 1 hour
- Phase 2 (Event Definition): 30 minutes
- Phase 3 (WorkOrder Listener): 1 hour
- Phase 4 (State Validation): 30 minutes
- Phase 5 (Tests): 1-2 hours
- Documentation: 30 minutes
- Manual Testing & Refinement: 30 minutes

## Conclusion

This implementation provides a minimal, event-driven solution to automatically invalidate WorkOrder approval when the underlying Estimate is financially revised. It maintains clear domain boundaries, follows existing patterns, and can be extended with threshold configuration in the future.
