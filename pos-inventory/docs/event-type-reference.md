# Inventory Ledger Event Type Reference Card

## Quick Decision Tree

```
Does this event represent physical inventory movement?
├── YES → It affects On-Hand
│   ├── Inventory coming IN? → INBOUND event (adds to On-Hand)
│   │   ├── GOODS_RECEIPT
│   │   ├── TRANSFER_IN
│   │   ├── RETURN_TO_STOCK
│   │   ├── ADJUSTMENT_IN
│   │   └── COUNT_VARIANCE_IN
│   │
│   └── Inventory going OUT? → OUTBOUND event (subtracts from On-Hand)
│       ├── GOODS_ISSUE
│       ├── TRANSFER_OUT
│       ├── SCRAP_OUT
│       ├── ADJUSTMENT_OUT
│       └── COUNT_VARIANCE_OUT
│
└── NO → It affects ATP only (NEUTRAL events)
    ├── RESERVATION_CREATED / RESERVATION_RELEASED
    ├── ALLOCATION_CREATED / ALLOCATION_RELEASED
    ├── BACKORDER_CREATED / BACKORDER_RESOLVED
    └── PICK_TASK_CREATED / PICK_TASK_COMPLETED
```

## Event Type Matrix

| Event Type | Direction | Affects On-Hand? | Affects ATP? | Sign | Use Case |
|------------|-----------|------------------|--------------|------|----------|
| **INBOUND EVENTS** | | | | | |
| GOODS_RECEIPT | INBOUND | ✅ Yes | ✅ Yes | +1 | Receiving from supplier/PO |
| TRANSFER_IN | INBOUND | ✅ Yes | ✅ Yes | +1 | Transfer received from another location |
| RETURN_TO_STOCK | INBOUND | ✅ Yes | ✅ Yes | +1 | Customer return accepted |
| ADJUSTMENT_IN | INBOUND | ✅ Yes | ✅ Yes | +1 | Positive adjustment (found inventory) |
| COUNT_VARIANCE_IN | INBOUND | ✅ Yes | ✅ Yes | +1 | Cycle count found more than expected |
| **OUTBOUND EVENTS** | | | | | |
| GOODS_ISSUE | OUTBOUND | ✅ Yes | ✅ Yes | -1 | Issued to work order/production |
| TRANSFER_OUT | OUTBOUND | ✅ Yes | ✅ Yes | -1 | Transfer shipped to another location |
| SCRAP_OUT | OUTBOUND | ✅ Yes | ✅ Yes | -1 | Write-off (damage/obsolete/shrink) |
| ADJUSTMENT_OUT | OUTBOUND | ✅ Yes | ✅ Yes | -1 | Negative adjustment (lost/damaged) |
| COUNT_VARIANCE_OUT | OUTBOUND | ✅ Yes | ✅ Yes | -1 | Cycle count found less than expected |
| **ALLOCATION/RESERVATION EVENTS** | | | | | |
| RESERVATION_CREATED | NEUTRAL | ❌ No | ✅ Yes | 0 | Soft allocation for sales order |
| RESERVATION_RELEASED | NEUTRAL | ❌ No | ✅ Yes | 0 | Reservation cancelled/expired |
| ALLOCATION_CREATED | NEUTRAL | ❌ No | ✅ Yes | 0 | Hard allocation for pick/pack |
| ALLOCATION_RELEASED | NEUTRAL | ❌ No | ✅ Yes | 0 | Allocation cancelled |
| BACKORDER_CREATED | NEUTRAL | ❌ No | ❌ No | 0 | Record unfulfilled demand |
| BACKORDER_RESOLVED | NEUTRAL | ❌ No | ❌ No | 0 | Backorder fulfilled/cancelled |
| PICK_TASK_CREATED | NEUTRAL | ❌ No | ❌ No | 0 | Pick instruction created |
| PICK_TASK_COMPLETED | NEUTRAL | ❌ No | ❌ No | 0 | Pick completed (may trigger GOODS_ISSUE) |

## Calculation Formulas

### On-Hand Quantity
```sql
SUM(
  CASE event_type
    WHEN 'GOODS_RECEIPT' THEN quantity
    WHEN 'TRANSFER_IN' THEN quantity
    WHEN 'RETURN_TO_STOCK' THEN quantity
    WHEN 'ADJUSTMENT_IN' THEN quantity
    WHEN 'COUNT_VARIANCE_IN' THEN quantity
    WHEN 'GOODS_ISSUE' THEN -quantity
    WHEN 'TRANSFER_OUT' THEN -quantity
    WHEN 'SCRAP_OUT' THEN -quantity
    WHEN 'ADJUSTMENT_OUT' THEN -quantity
    WHEN 'COUNT_VARIANCE_OUT' THEN -quantity
    ELSE 0
  END
)
```

### Allocated Quantity
```sql
SUM(quantity) 
WHERE status = 'ACTIVE' 
  AND allocation_type = 'HARD'
```

### Available-to-Promise (ATP)
```
ATP = On-Hand - Allocated
```

## Common Scenarios

### Scenario 1: Receiving Inventory from Supplier
```
Event: GOODS_RECEIPT
Quantity: +100
Result: On-Hand += 100, ATP += 100
```

### Scenario 2: Customer Places Order
```
Step 1 - Create Reservation:
  Event: RESERVATION_CREATED
  Quantity: 10
  Result: On-Hand unchanged, ATP -= 10 (reserved)

Step 2 - Pick and Allocate:
  Event: ALLOCATION_CREATED
  Quantity: 10
  Result: On-Hand unchanged, ATP -= 10 (now hard allocated)

Step 3 - Ship:
  Event: GOODS_ISSUE
  Quantity: -10
  Result: On-Hand -= 10, ATP unchanged (already allocated)
```

### Scenario 3: Transfer Between Locations
```
Location A (Source):
  Event: TRANSFER_OUT
  Quantity: -50
  Result: On-Hand -= 50, ATP -= 50

Location B (Destination):
  Event: TRANSFER_IN
  Quantity: +50
  Result: On-Hand += 50, ATP += 50
```

### Scenario 4: Cycle Count Adjustment
```
Physical Count: 95
System On-Hand: 100
Variance: -5

Event: COUNT_VARIANCE_OUT
Quantity: -5
Result: On-Hand = 95 (corrected), ATP -= 5
```

## Reference

- **ADR**: [ADR-0001: Inventory Ledger ATP Computation](/docs/adr/0001-inventory-ledger-atp-computation.md)
- **Implementation Guide**: [Inventory Ledger ATP Guide](/pos-inventory/docs/inventory-ledger-atp.md)
- **Enum Source**: `com.positivity.inventory.model.InventoryLedgerEventType`
