# Quick Start Guide: Implementing Story #36

> **Story**: [#36 - Ledger: Compute On-hand and Available-to-Promise by Location/Storage](https://github.com/louisburroughs/durion-positivity-backend/issues/36)
>
> **Status**: Ready for Development (clarification resolved)

## TL;DR

Implement an API endpoint that returns inventory availability (On-Hand, Allocations, ATP) for a product at a location.

**Formula**: `ATP = On-Hand - Allocations`

**Performance Target**: P95 < 200ms

## Before You Start

1. Read [ADR-0001](/docs/adr/0001-inventory-ledger-atp-computation.md) - understand the WHY
2. Review [Event Type Reference](/pos-inventory/docs/event-type-reference.md) - understand event categorization
3. Review [Implementation Guide](/pos-inventory/docs/inventory-ledger-atp.md) - detailed technical guide

## Step-by-Step Implementation

### 1. Database Schema

Create two main tables (or use existing equivalents):

```sql
-- Ledger of all inventory movements
CREATE TABLE inventory_ledger_event (
    event_id UUID PRIMARY KEY,
    product_id UUID NOT NULL,
    location_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    quantity DECIMAL(18, 6) NOT NULL,
    uom VARCHAR(20) NOT NULL,
    event_timestamp TIMESTAMP NOT NULL,
    -- ... other fields
    
    INDEX idx_product_location (product_id, location_id),
    INDEX idx_event_type (event_type)
);

-- Active allocations (hard commitments)
CREATE TABLE inventory_allocation (
    allocation_id UUID PRIMARY KEY,
    product_id UUID NOT NULL,
    location_id UUID NOT NULL,
    quantity DECIMAL(18, 6) NOT NULL,
    status VARCHAR(20) NOT NULL,
    -- ... other fields
    
    INDEX idx_product_location_status (product_id, location_id, status)
);
```

### 2. JPA Entities

Create entity classes for the tables above (or use existing ones).

### 3. Repository Layer

Create repositories with methods to calculate On-Hand and Allocations:

```java
public interface InventoryLedgerRepository extends JpaRepository<InventoryLedgerEvent, UUID> {
    
    @Query("SELECT SUM(" +
           "CASE " +
           "  WHEN e.eventType IN ('GOODS_RECEIPT', 'TRANSFER_IN', 'RETURN_TO_STOCK', " +
           "                       'ADJUSTMENT_IN', 'COUNT_VARIANCE_IN') THEN e.quantity " +
           "  WHEN e.eventType IN ('GOODS_ISSUE', 'TRANSFER_OUT', 'SCRAP_OUT', " +
           "                       'ADJUSTMENT_OUT', 'COUNT_VARIANCE_OUT') THEN -e.quantity " +
           "  ELSE 0 " +
           "END) " +
           "FROM InventoryLedgerEvent e " +
           "WHERE e.productId = :productId AND e.locationId = :locationId")
    BigDecimal calculateOnHand(@Param("productId") UUID productId, 
                               @Param("locationId") UUID locationId);
}

public interface InventoryAllocationRepository extends JpaRepository<InventoryAllocation, UUID> {
    
    @Query("SELECT COALESCE(SUM(a.quantity), 0) " +
           "FROM InventoryAllocation a " +
           "WHERE a.productId = :productId " +
           "  AND a.locationId = :locationId " +
           "  AND a.status = 'ACTIVE'")
    BigDecimal calculateAllocated(@Param("productId") UUID productId, 
                                   @Param("locationId") UUID locationId);
}
```

### 4. Service Layer

Create a service to compute ATP:

```java
@Service
public class InventoryAvailabilityService {
    
    private final InventoryLedgerRepository ledgerRepository;
    private final InventoryAllocationRepository allocationRepository;
    
    public InventoryAvailabilityResponse getAvailability(UUID productId, UUID locationId) {
        BigDecimal onHand = ledgerRepository.calculateOnHand(productId, locationId);
        BigDecimal allocated = allocationRepository.calculateAllocated(productId, locationId);
        BigDecimal atp = onHand.subtract(allocated);
        
        return InventoryAvailabilityResponse.builder()
            .productId(productId)
            .locationId(locationId)
            .onHandQty(onHand != null ? onHand : BigDecimal.ZERO)
            .allocatedQty(allocated != null ? allocated : BigDecimal.ZERO)
            .atpQty(atp)
            .uom(getProductBaseUom(productId)) // Get from Product service/entity
            .asOfTimestamp(Instant.now())
            .build();
    }
    
    private String getProductBaseUom(UUID productId) {
        // TODO: Retrieve from Product entity or service
        return "EACH";
    }
}
```

### 5. Controller Layer

Create a REST controller:

```java
@RestController
@RequestMapping("/api/inventory")
public class InventoryAvailabilityController {
    
    private final InventoryAvailabilityService service;
    
    @GetMapping("/availability")
    public ResponseEntity<InventoryAvailabilityResponse> getAvailability(
            @RequestParam UUID productId,
            @RequestParam UUID locationId) {
        
        InventoryAvailabilityResponse response = service.getAvailability(productId, locationId);
        return ResponseEntity.ok(response);
    }
}
```

### 6. Testing

#### Unit Tests
```java
@Test
void testAtpCalculation() {
    BigDecimal onHand = new BigDecimal("100.00");
    BigDecimal allocated = new BigDecimal("25.00");
    BigDecimal expectedAtp = new BigDecimal("75.00");
    
    when(ledgerRepo.calculateOnHand(productId, locationId)).thenReturn(onHand);
    when(allocationRepo.calculateAllocated(productId, locationId)).thenReturn(allocated);
    
    InventoryAvailabilityResponse response = service.getAvailability(productId, locationId);
    
    assertEquals(expectedAtp, response.getAtpQty());
}
```

#### Integration Tests
```java
@Test
void testAvailabilityEndpoint() {
    mockMvc.perform(get("/api/inventory/availability")
            .param("productId", productId.toString())
            .param("locationId", locationId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.onHandQty").value(100.0))
        .andExpect(jsonPath("$.allocatedQty").value(25.0))
        .andExpect(jsonPath("$.atpQty").value(75.0));
}
```

#### Performance Tests
- Verify P95 < 200ms with load testing tool (JMeter, Gatling, k6)
- Test with realistic data volume (millions of ledger events)

### 7. Monitoring

Add metrics tracking:

```java
@Timed(value = "inventory.availability.latency", description = "Time to compute ATP")
public InventoryAvailabilityResponse getAvailability(UUID productId, UUID locationId) {
    // ... implementation
}
```

Set up alerts:
- P95 latency > 200ms (Warning)
- P95 latency > 400ms (Critical)
- Error rate > 1% (Warning)

## Common Pitfalls to Avoid

❌ **Don't** include Expected Receipts in ATP v1
❌ **Don't** perform UOM conversions in v1
❌ **Don't** include RESERVATION_* or ALLOCATION_* events in On-Hand calculation
❌ **Don't** use `SELECT *` - select only needed columns
❌ **Don't** miss NULL handling for COALESCE in queries

✅ **Do** handle the case where no ledger events exist (onHand = 0)
✅ **Do** handle the case where no allocations exist (allocated = 0)
✅ **Do** use proper indexes on product_id, location_id, event_type
✅ **Do** validate productId and locationId parameters
✅ **Do** return 404 if product or location doesn't exist

## Quick Reference

### Event Types for On-Hand

**Include (Physical Movements)**:
- ✅ GOODS_RECEIPT, TRANSFER_IN, RETURN_TO_STOCK, ADJUSTMENT_IN, COUNT_VARIANCE_IN (positive)
- ✅ GOODS_ISSUE, TRANSFER_OUT, SCRAP_OUT, ADJUSTMENT_OUT, COUNT_VARIANCE_OUT (negative)

**Exclude (Availability Only)**:
- ❌ RESERVATION_*, ALLOCATION_*, BACKORDER_*, PICK_TASK_*

### Performance Checklist

- [ ] Database indexes created
- [ ] Query execution plan reviewed
- [ ] Connection pooling configured
- [ ] P95 latency tested and verified < 200ms
- [ ] Monitoring and alerts configured

## Need Help?

- **Architecture Questions**: Review [ADR-0001](/docs/adr/0001-inventory-ledger-atp-computation.md)
- **Event Type Questions**: Review [Event Type Reference](/pos-inventory/docs/event-type-reference.md)
- **Implementation Questions**: Review [Implementation Guide](/pos-inventory/docs/inventory-ledger-atp.md)
- **Testing Questions**: See testing section in [Implementation Guide](/pos-inventory/docs/inventory-ledger-atp.md)

## Artifacts Already Created

You can use these in your implementation:

- ✅ `InventoryLedgerEventType.java` - Enum with all event types
- ✅ `InventoryAvailabilityResponse.java` - Response DTO
- ✅ Database schema (proposed in implementation guide)
- ✅ SQL query patterns (in implementation guide)

## Success Criteria (From Story #36)

Your implementation is complete when:

1. API endpoint returns On-Hand, Allocations, ATP for product + location
2. ATP = On-Hand - Allocations (no expected receipts)
3. All quantities in base UOM
4. P95 latency < 200ms
5. Unit tests pass (on-hand calculation, ATP calculation)
6. Integration tests pass (API endpoint)
7. Performance tests pass (P95 SLA)
8. Monitoring configured and alerting

Good luck! 🚀
