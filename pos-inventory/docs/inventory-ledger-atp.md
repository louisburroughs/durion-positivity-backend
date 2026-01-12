# Inventory Ledger: On-Hand and ATP Computation

## Overview

This document provides implementation guidance for computing On-Hand and Available-to-Promise (ATP) inventory quantities by location and storage, as defined in Story #36.

## Architecture Decision Record

All architectural decisions for this feature are documented in:
- **ADR**: [docs/adr/0001-inventory-ledger-atp-computation.md](/docs/adr/0001-inventory-ledger-atp-computation.md)

Please review the ADR for complete context and rationale.

## Quick Reference

### ATP Formula (v1)
```
ATP = On-Hand - Allocations
```

**Note**: Expected Receipts are out of scope for v1.

### Unit of Measure (UOM)
- All calculations and responses use the product's **base UOM**
- UOM conversion is out of scope for v1

### Performance SLA
- **P95**: < 200ms (single product, single location)
- **P50**: < 80ms
- **P99**: < 400ms

### Ledger Event Types

#### Events INCLUDED in On-Hand Calculation

**Inbound (Positive)**:
- `GOODS_RECEIPT`
- `TRANSFER_IN`
- `RETURN_TO_STOCK`
- `ADJUSTMENT_IN`
- `COUNT_VARIANCE_IN`

**Outbound (Negative)**:
- `GOODS_ISSUE`
- `TRANSFER_OUT`
- `SCRAP_OUT`
- `ADJUSTMENT_OUT`
- `COUNT_VARIANCE_OUT`

#### Events EXCLUDED from On-Hand (Affect ATP Only)
- `RESERVATION_CREATED` / `RESERVATION_RELEASED`
- `ALLOCATION_CREATED` / `ALLOCATION_RELEASED`
- `BACKORDER_CREATED` / `BACKORDER_RESOLVED`
- `PICK_TASK_CREATED` / `PICK_TASK_COMPLETED` (unless posting GOODS_ISSUE)

## API Contract (Proposed)

### GET /api/inventory/availability

#### Request
```
GET /api/inventory/availability?productId={productId}&locationId={locationId}
```

**Query Parameters**:
- `productId` (UUID, required): Product identifier
- `locationId` (UUID, required): Location identifier

#### Response (200 OK)
```json
{
  "productId": "550e8400-e29b-41d4-a716-446655440000",
  "locationId": "660e8400-e29b-41d4-a716-446655440000",
  "onHandQty": 100.0,
  "allocatedQty": 25.0,
  "atpQty": 75.0,
  "uom": "EACH",
  "asOfTimestamp": "2026-01-12T22:00:00Z"
}
```

**Response Fields**:
- `onHandQty`: Net sum of physical stock movements
- `allocatedQty`: Sum of active allocations (hard commitments)
- `atpQty`: On-Hand - Allocations
- `uom`: Base unit of measure for the product
- `asOfTimestamp`: Time at which calculation was performed

### Error Responses

#### 404 Not Found
```json
{
  "error": "PRODUCT_NOT_FOUND",
  "message": "Product with ID 550e8400-e29b-41d4-a716-446655440000 not found"
}
```

#### 400 Bad Request
```json
{
  "error": "INVALID_REQUEST",
  "message": "Both productId and locationId are required"
}
```

## Implementation Guidelines

### Database Schema

**InventoryLedgerEvent Table** (Proposed):
```sql
CREATE TABLE inventory_ledger_event (
    event_id UUID PRIMARY KEY,
    product_id UUID NOT NULL,
    location_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    quantity DECIMAL(18, 6) NOT NULL,
    uom VARCHAR(20) NOT NULL,
    event_timestamp TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    reference_type VARCHAR(50),
    reference_id UUID,
    notes TEXT,
    
    INDEX idx_product_location (product_id, location_id),
    INDEX idx_event_type (event_type),
    INDEX idx_event_timestamp (event_timestamp)
);
```

**InventoryAllocation Table** (Proposed):
```sql
CREATE TABLE inventory_allocation (
    allocation_id UUID PRIMARY KEY,
    product_id UUID NOT NULL,
    location_id UUID NOT NULL,
    quantity DECIMAL(18, 6) NOT NULL,
    uom VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    order_id UUID,
    order_line_id UUID,
    
    INDEX idx_product_location_status (product_id, location_id, status)
);
```

### Computing On-Hand

**SQL Query Pattern**:
```sql
SELECT 
    product_id,
    location_id,
    SUM(
        CASE 
            WHEN event_type IN ('GOODS_RECEIPT', 'TRANSFER_IN', 'RETURN_TO_STOCK', 
                                'ADJUSTMENT_IN', 'COUNT_VARIANCE_IN') THEN quantity
            WHEN event_type IN ('GOODS_ISSUE', 'TRANSFER_OUT', 'SCRAP_OUT', 
                                'ADJUSTMENT_OUT', 'COUNT_VARIANCE_OUT') THEN -quantity
            ELSE 0
        END
    ) as on_hand_qty
FROM inventory_ledger_event
WHERE product_id = ? 
  AND location_id = ?
GROUP BY product_id, location_id;
```

### Computing Allocations

**SQL Query Pattern**:
```sql
SELECT 
    product_id,
    location_id,
    SUM(quantity) as allocated_qty
FROM inventory_allocation
WHERE product_id = ?
  AND location_id = ?
  AND status = 'ACTIVE'
GROUP BY product_id, location_id;
```

### Computing ATP

**Service Layer**:
```java
public InventoryAvailability getAvailability(UUID productId, UUID locationId) {
    BigDecimal onHand = ledgerRepository.calculateOnHand(productId, locationId);
    BigDecimal allocated = allocationRepository.calculateAllocated(productId, locationId);
    BigDecimal atp = onHand.subtract(allocated);
    
    return InventoryAvailability.builder()
        .productId(productId)
        .locationId(locationId)
        .onHandQty(onHand)
        .allocatedQty(allocated)
        .atpQty(atp)
        .uom(getProductBaseUom(productId))
        .asOfTimestamp(Instant.now())
        .build();
}
```

## Testing Strategy

### Unit Tests
- Verify On-Hand calculation with various event type combinations
- Test ATP calculation: On-Hand - Allocations
- Validate edge cases: zero inventory, negative adjustments, large quantities

### Integration Tests
- End-to-end API tests with real database
- Verify performance SLA compliance (P95 < 200ms)
- Test concurrent reads/writes to ledger

### Performance Tests
- Load test with 1000 concurrent requests
- Measure P50, P95, P99 latencies
- Verify query performance with large ledger tables (millions of events)

## Monitoring and Observability

### Metrics to Track
- API response time (P50, P95, P99)
- Query execution time for On-Hand and Allocation queries
- Cache hit/miss rates (if caching is implemented)
- Error rates by error type

### Alerts
- P95 latency > 200ms (Warning)
- P95 latency > 400ms (Critical)
- Error rate > 1% (Warning)
- Database connection pool exhaustion (Critical)

## Future Enhancements (Out of Scope for v1)

1. **Expected Receipts**: ATP = On-Hand - Allocations + Expected Receipts
   - Requires integration with purchasing system
   - Needs promise-date tracking

2. **UOM Conversion**: Support queries in different units
   - Requires Product/UOM service integration
   - Example: Query in "cases", respond in "cases" (converted from base "each")

3. **Bulk Queries**: Support multiple products/locations in single request
   - Requires careful query optimization
   - May need separate SLA (e.g., P95 < 500ms for up to 50 items)

4. **Real-Time Streaming**: Publish ATP changes via event stream
   - Requires event bus integration
   - Enables real-time UI updates

## References

- **Story**: [#36 - Ledger: Compute On-hand and Available-to-Promise by Location/Storage](https://github.com/louisburroughs/durion-positivity-backend/issues/36)
- **Clarification**: [#233 - Clarification for Story #36](https://github.com/louisburroughs/durion-positivity-backend/issues/233)
- **ADR**: [ADR-0001: Inventory Ledger ATP Computation](/docs/adr/0001-inventory-ledger-atp-computation.md)
