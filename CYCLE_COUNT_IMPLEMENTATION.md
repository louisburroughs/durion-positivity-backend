# Cycle Count Adjustment Implementation

## Overview

This implementation provides a complete backend system for managing cycle count adjustments with approval workflows, based on the requirements clarified in issue #26.

## Business Requirements Implemented

### 1. Threshold Logic
- **Composite threshold model**: Approval required if ANY threshold is exceeded
- Three dimensions evaluated:
  - **Unit variance**: `|countedQty - onHandQty| >= unitThreshold`
  - **Value variance**: `|variance * unitCost| >= valueThreshold`
  - **Percentage variance**: `|variance| / onHandQty >= percentThreshold`

### 2. Below-Threshold Behavior
- Adjustments below all thresholds are **automatically approved and posted**
- Full audit trail maintained for auto-approved adjustments
- System user recorded as approver: `SYSTEM`
- Event emitted: `InventoryAdjustmentAutoApproved` (TODO)

### 3. Approval Tiers
- **Two-tier model**:
  - **Tier 1 - Manager**: For moderate-risk adjustments
  - **Tier 2 - Director**: For high-risk adjustments
- Tier assignment based on highest exceeded threshold
- Configurable thresholds per tier

### 4. Notification Mechanism
- In-app notification generation (TODO - marked in code)
- Dashboard endpoints provided:
  - `GET /api/v1/inventory/cycle-count-adjustments/pending` - List pending
  - `GET /api/v1/inventory/cycle-count-adjustments/pending/count` - Get count
  - `GET /api/v1/inventory/cycle-count-adjustments?status=PENDING_APPROVAL` - Filter by status

## Architecture

### Domain Model

#### Entities

1. **CycleCountAdjustment**
   - Core entity tracking adjustment lifecycle
   - Fields: stockItemId, quantityChange, cost, status, tier, timestamps, audit fields
   - Methods: `getVarianceValue()`, `getVariancePercentage()`

2. **InventoryLedgerEntry**
   - Immutable ledger of all inventory transactions
   - Fields: stockItemId, eventType, changeInQuantity, quantityAfter, timestamp
   - Linked to adjustment via adjustmentId

3. **ApprovalThresholdConfig**
   - Configuration for approval thresholds
   - Fields: approvalTier, unitVarianceThreshold, valueVarianceThreshold, percentageVarianceThreshold
   - Supports multiple active configurations

#### Enums

1. **AdjustmentStatus**
   - `PENDING_APPROVAL` - Awaiting manual approval
   - `AUTO_APPROVED` - Automatically approved (below thresholds)
   - `APPROVED` - Manually approved
   - `POSTED` - Posted to ledger
   - `REJECTED` - Rejected with reason
   - `FAILED` - Processing error

2. **ApprovalTier**
   - `TIER_1_MANAGER` - Manager-level approval
   - `TIER_2_DIRECTOR` - Director-level approval

3. **InventoryLedgerEventType** (extended)
   - Added: `ADJUST_CYCLE_COUNT` - For cycle count adjustments
   - Existing: `COUNT_VARIANCE_IN`, `COUNT_VARIANCE_OUT`

### Service Layer

#### CycleCountAdjustmentService
Main service orchestrating the adjustment lifecycle:

**Key Methods:**
- `createAdjustment(request)` - Creates and evaluates adjustment
- `approveAdjustment(id, request)` - Approves pending adjustment
- `rejectAdjustment(id, request)` - Rejects pending adjustment
- `getAdjustment(id)` - Retrieves adjustment details
- `listAdjustmentsByStatus(status)` - Queries adjustments
- `listPendingApprovals()` - Dashboard query
- `countPendingApprovals()` - Dashboard metric

**Business Logic:**
1. Calculate variance from counted vs system quantity
2. Evaluate against thresholds using ApprovalThresholdEvaluator
3. Auto-approve if below all thresholds, or set PENDING_APPROVAL
4. On approval: Post to ledger, update on-hand, emit event
5. On rejection: Record reason, no ledger changes

#### ApprovalThresholdEvaluator
Evaluates adjustments against configured thresholds:

**Key Method:**
- `evaluateRequiredApprovalTier(adjustment)` - Returns Optional<ApprovalTier>

**Logic:**
- Retrieves active threshold configurations
- Evaluates unit, value, and percentage thresholds
- Returns highest tier required, or empty for auto-approval
- Uses OR logic: approval if ANY threshold exceeded

### Repository Layer

1. **CycleCountAdjustmentRepository**
   - `findByStatus(status)` - Query by status
   - `findByStockItemId(id)` - Query by SKU
   - `countByStatus(status)` - Metric calculation

2. **InventoryLedgerEntryRepository**
   - `findByStockItemIdOrderByTimestampDesc(id)` - History
   - `findByAdjustmentId(id)` - Lookup ledger entry
   - `calculateOnHandQuantity(id)` - Sum all transactions

3. **ApprovalThresholdConfigRepository**
   - `findByApprovalTier(tier)` - Lookup by tier
   - `findByActiveTrueOrderByApprovalTierAsc()` - All active configs

### Controller Layer

**CycleCountAdjustmentController**
REST API with OpenAPI documentation:

**Endpoints:**
- `POST /api/v1/inventory/cycle-count-adjustments` - Create adjustment
- `POST /api/v1/inventory/cycle-count-adjustments/{id}/approve` - Approve
- `POST /api/v1/inventory/cycle-count-adjustments/{id}/reject` - Reject
- `GET /api/v1/inventory/cycle-count-adjustments/{id}` - Get details
- `GET /api/v1/inventory/cycle-count-adjustments` - List by status
- `GET /api/v1/inventory/cycle-count-adjustments/pending` - List pending
- `GET /api/v1/inventory/cycle-count-adjustments/pending/count` - Count pending

All endpoints include:
- Request/response validation
- OpenAPI documentation
- Error handling
- Logging

### DTOs

1. **CreateAdjustmentRequest**
   - stockItemId, reasonCode, countedQuantity, quantityOnHandBefore, costAtTimeOfAdjustment, createdByUserId
   - Validation: NotBlank, NotNull, Min, DecimalMin

2. **AdjustmentResponse**
   - Complete adjustment state including calculated variance metrics
   - Includes all audit fields and timestamps

3. **ApproveAdjustmentRequest**
   - approverUserId, optional notes

4. **RejectAdjustmentRequest**
   - rejectorUserId, rejectionReason (required)

## State Machine

```
        ┌──────────────────┐
        │  Create          │
        │  Adjustment      │
        └────────┬─────────┘
                 │
                 v
        ┌────────────────┐
        │  Evaluate      │
        │  Thresholds    │
        └────┬───────┬───┘
             │       │
    Below    │       │    Exceeds
  Threshold  │       │   Threshold
             v       v
    ┌────────────┐ ┌────────────────┐
    │AUTO_       │ │PENDING_        │
    │APPROVED    │ │APPROVAL        │
    └─────┬──────┘ └───┬────────┬───┘
          │            │        │
          │            │Approve │Reject
          │            v        v
          │      ┌──────────┐ ┌────────┐
          └─────>│APPROVED  │ │REJECTED│
                 └────┬─────┘ └────────┘
                      │
                      │Post to Ledger
                      v
                 ┌────────┐
                 │POSTED  │
                 └────────┘
```

## Security

### Permission Requirements
- **INVENTORY_ADJUSTMENT_APPROVE** - Required to approve/reject (TODO - marked in code)
- **Tier validation** - Verify approver has sufficient tier level (TODO)

### Audit Trail
All state transitions logged with:
- User ID of actor
- Timestamp
- Old and new status
- Reason for rejection (if applicable)

## Integration Points

### Events to Emit (TODO markers in code)
1. **InventoryAdjustmentPosted**
   - Published after successful posting to ledger
   - Contains: adjustmentId, stockItemId, quantityChange, ledgerEntryId
   - Consumed by: Accounting system

2. **InventoryAdjustmentAutoApproved**
   - Published for below-threshold adjustments
   - Contains: adjustmentId, stockItemId, quantityChange

3. **InventoryAdjustmentApprovalRequired**
   - Published when adjustment enters PENDING_APPROVAL
   - Contains: adjustmentId, requiredTier, stockItemId, varianceMetrics
   - Consumed by: Notification service

### Metrics (TODO)
- `inventory_adjustments.pending_approval.count` - Gauge
- `inventory_adjustments.posted.total` - Counter (by reason, direction)
- `inventory_adjustments.approval_duration.histogram` - Time to approve
- `inventory_adjustments.auto_approved.total` - Counter

## Database Schema

### Tables Created

#### cycle_count_adjustment
```sql
CREATE TABLE cycle_count_adjustment (
  adjustment_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  stock_item_id VARCHAR(255) NOT NULL,
  reason_code VARCHAR(255) NOT NULL,
  quantity_change INT NOT NULL,
  cost_at_time_of_adjustment DECIMAL(19,4) NOT NULL,
  quantity_on_hand_before INT NOT NULL,
  counted_quantity INT NOT NULL,
  status VARCHAR(50) NOT NULL,
  required_approval_tier VARCHAR(50),
  created_by_user_id VARCHAR(255) NOT NULL,
  approved_by_user_id VARCHAR(255),
  rejected_by_user_id VARCHAR(255),
  rejection_reason VARCHAR(1000),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  approved_at TIMESTAMP,
  rejected_at TIMESTAMP,
  posted_at TIMESTAMP,
  ledger_entry_id BIGINT,
  error_message VARCHAR(2000),
  INDEX idx_status (status),
  INDEX idx_stock_item (stock_item_id)
);
```

#### inventory_ledger_entry
```sql
CREATE TABLE inventory_ledger_entry (
  ledger_entry_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  stock_item_id VARCHAR(255) NOT NULL,
  adjustment_id BIGINT,
  event_type VARCHAR(50) NOT NULL,
  change_in_quantity INT NOT NULL,
  quantity_after INT NOT NULL,
  unit_cost DECIMAL(19,4),
  transaction_user_id VARCHAR(255) NOT NULL,
  timestamp TIMESTAMP NOT NULL,
  notes VARCHAR(2000),
  INDEX idx_stock_item_timestamp (stock_item_id, timestamp),
  INDEX idx_adjustment (adjustment_id)
);
```

#### approval_threshold_config
```sql
CREATE TABLE approval_threshold_config (
  config_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  approval_tier VARCHAR(50) NOT NULL UNIQUE,
  unit_variance_threshold INT NOT NULL,
  value_variance_threshold DECIMAL(19,2) NOT NULL,
  percentage_variance_threshold DECIMAL(5,2) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);
```

### Sample Configuration Data

```sql
-- Tier 1: Manager approval for moderate-risk
INSERT INTO approval_threshold_config 
  (approval_tier, unit_variance_threshold, value_variance_threshold, percentage_variance_threshold, active, created_at, updated_at)
VALUES 
  ('TIER_1_MANAGER', 3, 100.00, 5.00, true, NOW(), NOW());

-- Tier 2: Director approval for high-risk
INSERT INTO approval_threshold_config 
  (approval_tier, unit_variance_threshold, value_variance_threshold, percentage_variance_threshold, active, created_at, updated_at)
VALUES 
  ('TIER_2_DIRECTOR', 10, 1000.00, 25.00, true, NOW(), NOW());
```

## Example Usage

### 1. Create Adjustment (Below Threshold)

**Request:**
```json
POST /api/v1/inventory/cycle-count-adjustments
{
  "stockItemId": "SKU-12345",
  "reasonCode": "CYCLE_COUNT_SHRINK",
  "countedQuantity": 48,
  "quantityOnHandBefore": 50,
  "costAtTimeOfAdjustment": 10.50,
  "createdByUserId": "user123"
}
```

**Response:**
```json
{
  "adjustmentId": 1,
  "stockItemId": "SKU-12345",
  "quantityChange": -2,
  "status": "POSTED",
  "requiredApprovalTier": null,
  "approvedByUserId": "SYSTEM",
  "ledgerEntryId": 101,
  "varianceValue": 21.00,
  "variancePercentage": 4.00,
  "createdAt": "2026-01-12T23:30:00Z",
  "approvedAt": "2026-01-12T23:30:00Z",
  "postedAt": "2026-01-12T23:30:00Z"
}
```

### 2. Create Adjustment (Requires Approval)

**Request:**
```json
POST /api/v1/inventory/cycle-count-adjustments
{
  "stockItemId": "SKU-67890",
  "reasonCode": "CYCLE_COUNT_SHRINK",
  "countedQuantity": 40,
  "quantityOnHandBefore": 100,
  "costAtTimeOfAdjustment": 25.00,
  "createdByUserId": "user123"
}
```

**Response:**
```json
{
  "adjustmentId": 2,
  "stockItemId": "SKU-67890",
  "quantityChange": -60,
  "status": "PENDING_APPROVAL",
  "requiredApprovalTier": "TIER_2_DIRECTOR",
  "varianceValue": 1500.00,
  "variancePercentage": 60.00,
  "createdAt": "2026-01-12T23:31:00Z"
}
```

### 3. Approve Adjustment

**Request:**
```json
POST /api/v1/inventory/cycle-count-adjustments/2/approve
{
  "approverUserId": "manager456",
  "notes": "Verified physical count, approved"
}
```

**Response:**
```json
{
  "adjustmentId": 2,
  "status": "POSTED",
  "approvedByUserId": "manager456",
  "ledgerEntryId": 102,
  "approvedAt": "2026-01-12T23:35:00Z",
  "postedAt": "2026-01-12T23:35:00Z"
}
```

### 4. Reject Adjustment

**Request:**
```json
POST /api/v1/inventory/cycle-count-adjustments/3/reject
{
  "rejectorUserId": "manager456",
  "rejectionReason": "Count appears incorrect, recount requested"
}
```

**Response:**
```json
{
  "adjustmentId": 3,
  "status": "REJECTED",
  "rejectedByUserId": "manager456",
  "rejectionReason": "Count appears incorrect, recount requested",
  "rejectedAt": "2026-01-12T23:36:00Z"
}
```

## Testing Strategy

### Unit Tests (TODO)
- **ApprovalThresholdEvaluator**
  - Test each threshold dimension independently
  - Test composite OR logic
  - Test edge cases (zero on-hand, negative variance)
  
- **CycleCountAdjustmentService**
  - Test auto-approval path
  - Test manual approval path
  - Test rejection path
  - Test error handling
  - Mock repositories and evaluator

- **Controller**
  - Test validation
  - Test authorization
  - Test error responses

### Integration Tests (TODO)
- Full lifecycle: create → approve → post
- Full lifecycle: create → reject
- Auto-approval flow
- Concurrent adjustment handling
- Database transaction integrity

## Future Enhancements

1. **Multi-level Approval Workflow**
   - Support more than two tiers
   - Escalation rules
   - Delegation

2. **Bulk Operations**
   - Batch approval of multiple adjustments
   - Import adjustments from file

3. **Advanced Notifications**
   - Email integration
   - Slack/Teams integration
   - Escalation notifications for aging approvals

4. **Analytics**
   - Shrink analysis dashboards
   - Approval turnaround time metrics
   - Variance pattern detection

5. **Mobile Support**
   - Mobile-optimized approval interface
   - Push notifications

6. **Audit Reports**
   - Downloadable audit trails
   - Compliance reports
   - Variance analysis reports

## Deployment Notes

1. **Database Migrations**
   - Create tables: `cycle_count_adjustment`, `inventory_ledger_entry`, `approval_threshold_config`
   - Add indexes for performance
   - Seed default threshold configurations

2. **Configuration**
   - Configure threshold values based on business requirements
   - Set up event bus for notifications
   - Configure security roles and permissions

3. **Monitoring**
   - Monitor pending approval queue depth
   - Alert on old pending approvals
   - Track auto-approval rates

## References

- Original Issue: https://github.com/louisburroughs/durion-positivity-backend/issues/26
- Clarification Issue: #169 (hypothetical)
- Spring Boot Documentation: https://docs.spring.io/spring-boot/
- Spring Data JPA: https://docs.spring.io/spring-data/jpa/
