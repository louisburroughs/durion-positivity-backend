# [STORY] [ACCOUNTING] Cost Business Logic - Calculate and Maintain Last/Average Cost

## Story Type
User Story - Domain: Accounting

## Parent/Related Stories
- **Split From**: #196 - Cost: Maintain Standard/Last/Average Cost with Audit
- **Related Story**: [INVENTORY] Item Cost Data Model (created)
- **Clarification**: CLARIFICATION-ISSUE-196-DOMAIN-OWNERSHIP.md

## Labels (Proposed)
- `type:story`
- `domain:accounting`
- `status:draft`
- `blocked:clarification`
- `priority:high`
- `layer:business-logic`

---

## Story Intent
**As the** Accounting service,
**I want** to automatically calculate and update Last Cost and Average Cost for inventory items based on Purchase Order receipt events,
**so that** the business has accurate, auditable inventory valuation data for financial reporting and COGS calculations.

## Actors & Stakeholders
- **Primary Actor**: Accounting Service (system)
- **Trigger**: Purchase Order Received event (from Order/Receiving system)
- **Consumer**: Inventory Service (receives cost update commands)
- **Stakeholder**: Finance Team (relies on accurate cost calculations for COGS and valuation)
- **Stakeholder**: Auditors (require immutable audit trail of cost changes)

## Preconditions
- Inventory Item entity includes cost fields (standardCost, lastCost, averageCost) - see related Inventory story
- Purchase Order system publishes "PO Received" events to event bus
- Accounting service can read current cost values and quantity on hand from Inventory service
- Accounting service can publish cost update commands/events to Inventory service

## Functional Behavior

### 1. Subscribe to Purchase Order Received Events
- The Accounting service subscribes to the `PurchaseOrderReceived` event from the event bus
- Event payload must include:
  - `purchaseOrderId` (String/UUID)
  - `itemId` (String/UUID)
  - `receivedQuantity` (Integer, must be positive)
  - `receivedUnitCost` (Decimal, cost per unit)
  - `receiveTimestamp` (ISO 8601 timestamp)

### 2. Validate Purchase Order Event
- **Pre-calculation validation**:
  - `receivedQuantity` must be > 0
  - `receivedUnitCost` must be > 0
  - `itemId` must reference a valid inventory item
- If validation fails:
  - Log error with PO ID and reason
  - Do NOT update costs
  - Emit failure metric
  - Optionally: publish `CostUpdateFailed` event

### 3. Calculate Last Cost
- **Business Rule**: Last Cost is the most recent purchase unit cost
- **Action**: Set the item's `lastCost` to the `receivedUnitCost` from the event
- **Formula**: `newLastCost = receivedUnitCost`

### 4. Calculate Average Cost (Weighted Average)
- **Business Rule**: Average Cost is the weighted average of all units in stock
- **Inputs**:
  - `oldQtyOnHand` - current quantity on hand (from Inventory service)
  - `oldAverageCost` - current average cost (from Inventory service)
  - `receivedQty` - quantity received (from event)
  - `receivedUnitCost` - unit cost of received items (from event)
  
- **Formula**:
  ```
  newAverageCost = ((oldQtyOnHand * oldAverageCost) + (receivedQty * receivedUnitCost)) 
                   / (oldQtyOnHand + receivedQty)
  ```

- **Rounding**: Result must be rounded to 4 decimal places (e.g., 11.2567)
  - Use HALF_UP rounding mode (standard accounting practice)

- **Edge Case - Zero Initial Quantity**:
  - If `oldQtyOnHand` = 0, then `newAverageCost = receivedUnitCost`

### 5. Update Inventory Item Costs
- After calculation, the Accounting service must update the Inventory service with new cost values
- **Option A**: Call Inventory REST API
  - `PUT /api/inventory/items/{itemId}/costs/system-update`
  - Request body:
    ```json
    {
      "lastCost": 12.5000,
      "averageCost": 11.2567,
      "sourceEvent": "PurchaseOrderReceived",
      "sourceId": "PO-12345"
    }
    ```
  
- **Option B**: Publish `UpdateItemCost` command event to event bus
  - Inventory service consumes the event and updates its data

### 6. Create Audit Log Entries
- For **every** cost change (Last Cost and Average Cost), create an immutable audit log entry
- Two audit entries per PO receipt (one for Last, one for Average)

**Audit Entry Structure** (`ItemCostAudit` entity in Accounting database):
```java
{
  "auditId": UUID,
  "itemId": UUID,
  "timestamp": ISO-8601 timestamp,
  "costTypeChanged": "LAST" | "AVERAGE",
  "oldValue": BigDecimal,
  "newValue": BigDecimal,
  "changeSourceType": "PURCHASE_ORDER",
  "changeSourceId": "PO-12345",
  "actor": "system"
}
```

### 7. Transaction Atomicity
- All operations for a single PO receipt must occur in a **single transaction**:
  1. Calculate Last Cost
  2. Calculate Average Cost
  3. Update Inventory service (via API or event)
  4. Create audit log entry for Last Cost
  5. Create audit log entry for Average Cost
  
- **Failure Handling**: If any step fails, roll back the entire transaction
  - Do NOT partially update costs
  - Log the failure with full context

## Alternate / Error Flows

### Error Flow 1: Receiving Item with Invalid Cost (Zero or Negative)
- **Trigger**: `receivedUnitCost` ≤ 0 in the PO Received event
- **Action**:
  - Reject the cost update transaction
  - Log error: "Invalid unit cost for PO {poId}, Item {itemId}: cost={cost}"
  - Emit `accounting.cost.update.failed` metric (label: reason=invalid_cost)
  - Do NOT update Last or Average costs
  - Optionally: Publish `CostUpdateFailed` event for monitoring

### Error Flow 2: Item Not Found
- **Trigger**: `itemId` in event does not exist in Inventory service
- **Action**:
  - Log error: "Item not found: {itemId} for PO {poId}"
  - Emit metric: `accounting.cost.update.failed` (label: reason=item_not_found)
  - Skip cost update for this item

### Error Flow 3: Inventory Service Unavailable
- **Trigger**: API call to Inventory service fails (timeout, connection error)
- **Action**:
  - Log error with retry count
  - Implement retry logic with exponential backoff (max 3 retries)
  - If all retries fail:
    - Log critical error
    - Optionally: Send to dead-letter queue for manual review
  - Emit metric: `accounting.cost.update.failed` (label: reason=inventory_service_unavailable)

### Error Flow 4: Invalid Quantity
- **Trigger**: `receivedQuantity` ≤ 0
- **Action**: Same as Error Flow 1 (invalid cost)

### Error Flow 5: Audit Log Write Failure
- **Trigger**: Database write error when creating audit entry
- **Action**:
  - Roll back the ENTIRE transaction (including cost updates)
  - Log critical error: "Audit log write failed for PO {poId}"
  - This is a critical failure - audit trail must be complete

## Business Rules

1. **Weighted Average Formula**: Must use the exact formula specified in Functional Behavior section
2. **Rounding**: All cost values must be rounded to 4 decimal places using HALF_UP mode
3. **Atomicity**: Cost updates and audit log creation must be atomic (all-or-nothing)
4. **Determinism**: Given the same inputs, the calculation must produce the same output (no randomness)
5. **Validation**: Costs and quantities must be positive (> 0)
6. **Audit Requirement**: Every cost change must have a corresponding audit entry
7. **System-Only Updates**: Last Cost and Average Cost can ONLY be updated by the system via this logic
   - Manual updates are NOT permitted for Last/Average costs
   - Only Standard Cost can be manually updated (see Inventory story)

## Data Requirements

### ItemCostAudit Entity (Accounting Domain)

**New Table**: `item_cost_audit`

```sql
CREATE TABLE item_cost_audit (
    audit_id UUID PRIMARY KEY,
    item_id UUID NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    cost_type_changed VARCHAR(10) NOT NULL CHECK (cost_type_changed IN ('STANDARD', 'LAST', 'AVERAGE')),
    old_value DECIMAL(19, 4) NOT NULL,
    new_value DECIMAL(19, 4) NOT NULL,
    change_source_type VARCHAR(20) NOT NULL CHECK (change_source_type IN ('MANUAL', 'PURCHASE_ORDER')),
    change_source_id VARCHAR(100) NOT NULL,
    actor VARCHAR(100) NOT NULL,
    CONSTRAINT idx_item_cost_audit_timestamp INDEX (timestamp),
    CONSTRAINT idx_item_cost_audit_item_id INDEX (item_id)
);
```

**Java Entity**:
```java
@Entity
@Table(name = "item_cost_audit")
public class ItemCostAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "audit_id")
    private UUID auditId;
    
    @Column(name = "item_id", nullable = false)
    private UUID itemId;
    
    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "cost_type_changed", nullable = false)
    private CostType costTypeChanged;
    
    @Column(name = "old_value", precision = 19, scale = 4, nullable = false)
    private BigDecimal oldValue;
    
    @Column(name = "new_value", precision = 19, scale = 4, nullable = false)
    private BigDecimal newValue;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "change_source_type", nullable = false)
    private ChangeSourceType changeSourceType;
    
    @Column(name = "change_source_id", nullable = false)
    private String changeSourceId;
    
    @Column(name = "actor", nullable = false)
    private String actor;
    
    // Getters and Setters
}

public enum CostType {
    STANDARD, LAST, AVERAGE
}

public enum ChangeSourceType {
    MANUAL, PURCHASE_ORDER
}
```

### PurchaseOrderReceived Event (Input)

```java
public class PurchaseOrderReceivedEvent {
    private UUID eventId;
    private Instant timestamp;
    private UUID purchaseOrderId;
    private UUID itemId;
    private Integer receivedQuantity;
    private BigDecimal receivedUnitCost;
    // Getters and Setters
}
```

## Acceptance Criteria

### Scenario 1: Receiving PO Updates Last and Average Cost
- **Given** an inventory item with:
  - `itemId` = "ITEM-001"
  - `qtyOnHand` = 100
  - `lastCost` = 5.00
  - `averageCost` = 5.50
- **When** a `PurchaseOrderReceived` event is published with:
  - `itemId` = "ITEM-001"
  - `receivedQuantity` = 50
  - `receivedUnitCost` = 6.00
- **Then** the Accounting service calculates:
  - `newLastCost` = 6.00
  - `newAverageCost` = ((100 * 5.50) + (50 * 6.00)) / (100 + 50) = 5.6667
- **And** the Inventory service is updated with `lastCost` = 6.0000 and `averageCost` = 5.6667
- **And** two `ItemCostAudit` records are created:
  1. LAST cost change: old=5.00, new=6.00, source=PURCHASE_ORDER, sourceId=PO-{id}
  2. AVERAGE cost change: old=5.50, new=5.6667, source=PURCHASE_ORDER, sourceId=PO-{id}

### Scenario 2: Receiving PO with Zero Cost is Rejected
- **Given** an inventory item with `lastCost` = 5.00 and `averageCost` = 5.50
- **When** a `PurchaseOrderReceived` event is published with `receivedUnitCost` = 0.00
- **Then** the cost update transaction is rejected
- **And** an error is logged: "Invalid unit cost for PO..."
- **And** the item's costs remain unchanged (lastCost=5.00, averageCost=5.50)
- **And** a metric `accounting.cost.update.failed` is emitted with label `reason=invalid_cost`

### Scenario 3: First Receipt for Item (Zero Initial Quantity)
- **Given** a new inventory item with:
  - `qtyOnHand` = 0
  - `lastCost` = 0.00 (or NULL)
  - `averageCost` = 0.00 (or NULL)
- **When** a `PurchaseOrderReceived` event is published with:
  - `receivedQuantity` = 20
  - `receivedUnitCost` = 8.00
- **Then** the Accounting service calculates:
  - `newLastCost` = 8.00
  - `newAverageCost` = 8.00 (since oldQty = 0)
- **And** the Inventory service is updated accordingly
- **And** audit records are created

### Scenario 4: Inventory Service API Failure Triggers Retry
- **Given** a valid `PurchaseOrderReceived` event
- **When** the API call to Inventory service fails with a connection timeout
- **Then** the Accounting service retries the call with exponential backoff
- **And** if retries succeed, the costs are updated and audit logs created
- **And** if all retries fail, the event is sent to a dead-letter queue

### Scenario 5: Transaction Rollback on Audit Failure
- **Given** a valid `PurchaseOrderReceived` event
- **And** the cost calculation is successful
- **When** the audit log write fails (database error)
- **Then** the entire transaction is rolled back
- **And** the Inventory service costs are NOT updated
- **And** a critical error is logged

### Scenario 6: Negative Quantity is Rejected
- **Given** a `PurchaseOrderReceived` event with `receivedQuantity` = -10
- **When** the event is processed
- **Then** the cost update is rejected
- **And** an error is logged
- **And** costs remain unchanged

## Audit & Observability

### Audit Trail
- The `item_cost_audit` table is the **primary audit log** for all cost changes
- This table must be **append-only** (no updates or deletes)
- Queries:
  - By item ID: `SELECT * FROM item_cost_audit WHERE item_id = ? ORDER BY timestamp DESC`
  - By date range: `SELECT * FROM item_cost_audit WHERE timestamp BETWEEN ? AND ?`

### Logging
- **INFO**: Log every successful cost update: "Cost updated for item {itemId}: Last={lastCost}, Avg={avgCost}, PO={poId}"
- **WARN**: Log validation failures: "Invalid cost update rejected: PO={poId}, reason={reason}"
- **ERROR**: Log transaction failures: "Cost update failed for PO {poId}: {errorMessage}"
- **CRITICAL**: Log audit write failures: "AUDIT FAILURE: Cost update rolled back for PO {poId}"

### Metrics
- Counter: `accounting.cost.updates.total` (labels: `status=[success|failure]`, `reason`)
- Counter: `accounting.cost.audit.entries.created` (labels: `cost_type=[LAST|AVERAGE]`)
- Histogram: `accounting.cost.calculation.duration` (time to process event)
- Gauge: `accounting.cost.events.processing` (current events being processed)

### Alerting
- Alert if `accounting.cost.update.failed` rate exceeds threshold
- Alert on critical errors (audit write failures)
- Alert if event processing latency exceeds SLA

## Open Questions

1. **Integration Pattern**: Should Accounting call Inventory REST API or publish command events? (Blocked - needs architectural decision)
2. **Retry Strategy**: How many retries for Inventory service failures? What is the backoff strategy? (Recommendation: 3 retries, exponential backoff starting at 1s)
3. **Dead Letter Queue**: Where should failed events go? (Kafka DLQ, database table, external monitoring?)
4. **Event Ordering**: How do we handle out-of-order PO events? (e.g., PO-002 arrives before PO-001)
5. **Manual Cost Override**: If finance manually corrects a cost, does that create an audit entry? (Assumption: Yes, via Inventory story)

## Implementation Notes

### Service Architecture
- Implement as an event listener/consumer in the Accounting service
- Use Spring Kafka (or RabbitMQ) for event consumption
- Use Spring Data JPA for audit log persistence
- Use RestTemplate or WebClient for Inventory service API calls

### Testing Strategy
- Unit tests: Test cost calculation formulas with various inputs
- Unit tests: Test validation logic (negative costs, zero quantities)
- Integration tests: Test event consumption and audit log creation
- Integration tests: Test Inventory service API integration (with mocks)
- Contract tests: Verify event schema compatibility with PO system
- Performance tests: Ensure event processing meets latency SLA

### Dependencies
- Requires Inventory service API contract (or command event schema)
- Requires Purchase Order system event schema
- Requires event bus infrastructure (Kafka/RabbitMQ)

---

## Original Story Reference
This story is split from #196: "Cost: Maintain Standard/Last/Average Cost with Audit"

**Scope of this story**: Cost calculation logic and audit trail (Accounting domain responsibility)

**Out of scope**: Cost data storage and CRUD APIs (Inventory domain responsibility - see related story)
