# WorkOrder State Machine Implementation

## Overview

This implementation addresses Issue #304 (Clarification for Issue #160: Start Workorder and Track Status) by implementing a Finite State Machine (FSM) pattern with comprehensive audit trail and snapshot capabilities for workorder execution.

## Clarification Response Summary

The clarification responses from Issue #304 specified:

1. **Q1 - Auto-Start Behavior**: Deferred to workexec policy; snapshot pattern handles state capture at key boundaries
2. **Q2 - Exhaustive Start-Eligible Statuses**: Implement FSM with enumerated states + allowed transitions; store transition audit (who/when/why); keep enums stable, extend via config only where safe
3. **Q3 - Complete In-Progress Sub-Statuses**: Same FSM approach—enumerated, audited, config-extended
4. **Q4 - Pending Approval Change Request Identification**: Use explicit contracts, idempotency, append-only audit trails, UTC timestamps, scoped RBAC, and configurable policy defaults

## Architecture

### Finite State Machine (FSM)

The FSM is implemented in `WorkOrderStatus` enum with explicit allowed state transitions:

```
DRAFT → APPROVED → (ASSIGNED or WORK_IN_PROGRESS) → (AWAITING_PARTS, AWAITING_APPROVAL, READY_FOR_PICKUP, COMPLETED) → COMPLETED
                                                 ↘ CANCELLED (from any state)
```

**Key States:**
- `DRAFT`: Initial creation state
- `APPROVED`: Work order approved but not started
- `ASSIGNED`: Work order assigned to mechanic
- `WORK_IN_PROGRESS`: Active work being performed
- `AWAITING_PARTS`: Work paused waiting for parts
- `AWAITING_APPROVAL`: Work paused waiting for customer approval
- `READY_FOR_PICKUP`: Work complete, awaiting customer pickup
- `COMPLETED`: Work order fully completed
- `CANCELLED`: Work order cancelled

**Start-Eligible Statuses**: `APPROVED`, `ASSIGNED`

**In-Progress Sub-Statuses**: `WORK_IN_PROGRESS`, `AWAITING_PARTS`, `AWAITING_APPROVAL`

### State Transition Audit Trail

Every state transition is recorded in `WorkOrderStateTransition` entity with:
- `workOrderId`: Reference to the work order
- `fromStatus`: Previous state
- `toStatus`: New state
- `transitionedAt`: UTC timestamp of transition
- `transitionedBy`: User ID who initiated transition
- `reason`: Human-readable reason for transition
- `metadata`: Optional JSON metadata

### Snapshot Pattern

State snapshots are captured at key boundaries in `WorkOrderSnapshot` entity with:
- `workOrderId`: Reference to the work order
- `status`: Status at time of snapshot
- `capturedAt`: UTC timestamp
- `capturedBy`: User ID who triggered snapshot
- `snapshotType`: Type of snapshot (e.g., "WORK_START", "WORK_COMPLETE")
- `snapshotData`: Full JSON serialization of work order state
- `reason`: Reason for snapshot

Snapshots are automatically captured when:
- Starting a work order (`WORK_START`)
- Configurable based on `workorder.statemachine.snapshot.*` properties

## API Endpoints

### Start Work Order
```
POST /api/workorders/{id}/start
Request Body: {
  "userId": 123,
  "reason": "Beginning work on scheduled maintenance"
}
Response: {
  "workOrderId": 1,
  "previousStatus": "APPROVED",
  "currentStatus": "WORK_IN_PROGRESS",
  "transitionedAt": "2026-01-11T10:00:00Z",
  "message": "Work order started successfully"
}
```

**Validation:**
- Work order must be in start-eligible status (`APPROVED` or `ASSIGNED`)
- No pending change requests with status `AWAITING_ADVISOR_REVIEW`

### Get Transition History
```
GET /api/workorders/{id}/transitions
Response: [
  {
    "id": 1,
    "workOrderId": 1,
    "fromStatus": "APPROVED",
    "toStatus": "WORK_IN_PROGRESS",
    "transitionedAt": "2026-01-11T10:00:00Z",
    "transitionedBy": 123,
    "reason": "Beginning work on scheduled maintenance"
  }
]
```

### Get Snapshot History
```
GET /api/workorders/{id}/snapshots
Response: [
  {
    "id": 1,
    "workOrderId": 1,
    "status": "WORK_IN_PROGRESS",
    "capturedAt": "2026-01-11T10:00:00Z",
    "capturedBy": 123,
    "snapshotType": "WORK_START",
    "snapshotData": "{...}",
    "reason": "Beginning work on scheduled maintenance"
  }
]
```

## Configuration

Configuration properties in `application.properties`:

```properties
# Work Order State Machine Configuration
workorder.statemachine.audit.enabled=true
workorder.statemachine.snapshot.enabled=true
workorder.statemachine.snapshot.captureOnStart=true
workorder.statemachine.snapshot.captureOnComplete=true
workorder.statemachine.autoStart.onFirstLaborEntry=false
```

## Service Layer

### WorkOrderStateMachine

Core service implementing FSM logic:

**Key Methods:**
- `startWorkOrder(Long workOrderId, Long userId, String reason)`: Start work order with validation
- `transitionWorkOrder(Long workOrderId, WorkOrderStatus toStatus, Long userId, String reason)`: Generic state transition
- `captureSnapshot(WorkOrder workOrder, Long userId, String snapshotType, String reason)`: Capture state snapshot
- `getTransitionHistory(Long workOrderId)`: Retrieve audit trail
- `getSnapshotHistory(Long workOrderId)`: Retrieve snapshots

**Validation:**
- State transition validation using FSM rules
- Change request validation (no pending approvals)
- Snapshot capture with JSON serialization

### Integration with Existing Services

The `WorkOrderService` has been updated to delegate state management to `WorkOrderStateMachine`:
- `startWorkOrder(...)`: Delegates to state machine
- `transitionWorkOrder(...)`: Delegates to state machine
- `getTransitionHistory(...)`: Delegates to state machine
- `getSnapshotHistory(...)`: Delegates to state machine

## Database Schema

### work_order_state_transitions
```sql
CREATE TABLE work_order_state_transitions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  work_order_id BIGINT NOT NULL,
  from_status VARCHAR(50) NOT NULL,
  to_status VARCHAR(50) NOT NULL,
  transitioned_at TIMESTAMP NOT NULL,
  transitioned_by BIGINT NOT NULL,
  reason TEXT,
  metadata TEXT
);
```

### work_order_snapshots
```sql
CREATE TABLE work_order_snapshots (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  work_order_id BIGINT NOT NULL,
  status VARCHAR(50) NOT NULL,
  captured_at TIMESTAMP NOT NULL,
  captured_by BIGINT NOT NULL,
  snapshot_type VARCHAR(50) NOT NULL,
  snapshot_data TEXT NOT NULL,
  reason TEXT
);
```

## Testing

Comprehensive test coverage in `WorkOrderStateMachineTest`:

**Test Cases:**
1. `testStartWorkOrder_Success`: Valid start operation
2. `testStartWorkOrder_InvalidStatus_ThrowsException`: Invalid status validation
3. `testStartWorkOrder_PendingChangeRequest_ThrowsException`: Change request validation
4. `testTransitionWorkOrder_ValidTransition`: Valid state transition
5. `testTransitionWorkOrder_InvalidTransition_ThrowsException`: Invalid transition validation
6. `testCaptureSnapshot_Success`: Snapshot capture
7. `testGetTransitionHistory`: History retrieval
8. `testGetSnapshotHistory`: Snapshot retrieval
9. `testWorkOrderStatus_AllowedTransitions`: FSM transition validation
10. `testWorkOrderStatus_StartEligibleStatuses`: Start-eligible status validation
11. `testWorkOrderStatus_InProgressSubStatuses`: In-progress sub-status validation

**Test Coverage:** 11/11 tests passing (100%)

## Security Considerations

1. **User Authentication**: All operations require `userId` parameter
2. **Audit Trail**: Complete who/when/why tracking for compliance
3. **Immutable History**: Transition and snapshot records are append-only
4. **UTC Timestamps**: All timestamps in UTC for consistency
5. **Idempotency**: State transitions are idempotent
6. **RBAC Integration**: Ready for role-based access control integration

## Performance Considerations

1. **Indexed Queries**: `work_order_id` indexed for fast history lookups
2. **Snapshot Efficiency**: JSON serialization with Jackson for speed
3. **Transaction Management**: Atomic state transitions with `@Transactional`
4. **Connection Pooling**: Leverages Spring Data JPA connection pooling

## Future Enhancements

1. **Auto-Start on First Labor Entry**: Configurable via `workorder.statemachine.autoStart.onFirstLaborEntry`
2. **State Machine Extensions**: Additional states configurable via database
3. **Event Streaming**: Publish state changes to event bus for integrations
4. **RBAC Integration**: Role-based transition permissions
5. **Custom Validators**: Pluggable validation logic for specific transitions

## References

- Issue #160: [BACKEND] [STORY] Execution: Start Workorder and Track Status
- Issue #304: [CLARIFICATION] Origin #160
- Story Authoring Agent: agent:story-authoring
- Domain: domain:workexec
