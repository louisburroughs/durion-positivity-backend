# ADR 0001: Inventory Ledger ATP Computation

## Status
**ACCEPTED** - 2026-01-12

## Context

This ADR documents the architectural decisions made to resolve clarification issue #233, which blocked the implementation of user story #36: "Ledger: Compute On-hand and Available-to-Promise by Location/Storage".

The inventory management system requires a deterministic, performant, and auditable method to compute:
1. **On-Hand Quantity**: Physical inventory present at a location
2. **Available-to-Promise (ATP)**: Inventory available for new customer commitments

Four critical questions were raised by the Story Authoring Agent during story refinement, requiring explicit business and technical decisions before implementation could proceed.

## Decision

### 1. ATP Formula (v1)

**Decision**: ATP = On-Hand - Allocations

**Rationale**:
- Expected Receipts are **explicitly out of scope** for v1
- Including expected receipts would require:
  - Integration with purchasing/supplier systems
  - Promise-date logic and tracking
  - Handling of late/cancelled deliveries
- V1 focuses on deterministic, ledger-derived calculations only

**Forward Compatibility**:
- The API MAY return an optional/nullable field `expectedReceiptsQty` for future use
- This field MUST NOT be included in ATP calculation in v1
- Future versions may implement ATP = On-Hand - Allocations + Expected Receipts

### 2. Unit of Measure (UOM) Handling Scope

**Decision**: All calculations performed and returned in product's **base UOM only**

**Implementation**:
- Inventory ledger stores all quantities in base UOM
- API returns:
  - `onHandQty` (in base UOM)
  - `allocatedQty` (in base UOM)
  - `atpQty` (in base UOM)
  - `uom` (base UOM identifier, e.g., "EACH", "LB")

**Out of Scope for v1**:
- Request/response UOM conversions (e.g., "case" vs "each")
- Multi-UOM queries

**Forward Compatibility**:
- Future versions MAY add:
  - `requestedUom` parameter
  - UOM conversion via Product/UOM service integration

### 3. Performance SLA Definition

**Decision**: P95 < 200ms for single product, single location query (warm cache)

**Service-Level Objectives**:
- **P50**: < 80ms (median response time)
- **P95**: < 200ms (95th percentile)
- **P99**: < 400ms (99th percentile)

**Bulk Query SLAs** (if supported in v1 or later):
- Up to 50 productIds: P95 < 500ms

**Measurement Boundary**:
- Measured at the service boundary (application layer)
- Excludes caller network latency
- Assumes database connection pool is warm
- Measured under typical load conditions

**Performance Strategies**:
- Database indexes on: `productId`, `locationId`, `eventType`, `eventTimestamp`
- Consider materialized view or cache for frequently queried products
- Monitor and alert on P95 > 200ms threshold

### 4. Definitive Ledger Event Types for On-Hand Calculation

**Decision**: On-Hand = Net sum of physical stock movements and count variances

**Principle**: Allocations and reservations affect **availability** but NOT **physical on-hand**

#### Event Types INCLUDED in On-Hand (Inbound = Positive, Outbound = Negative)

**Inbound Events (Add to On-Hand)**:
- `GOODS_RECEIPT` - Receiving inventory into stock
- `TRANSFER_IN` - Inter-location transfer received
- `RETURN_TO_STOCK` - Customer return accepted into inventory
- `ADJUSTMENT_IN` - Manual positive adjustment (e.g., found inventory)
- `COUNT_VARIANCE_IN` - Cycle count revealed more inventory than recorded

**Outbound Events (Subtract from On-Hand)**:
- `GOODS_ISSUE` - Issued/consumed to work order or production
- `TRANSFER_OUT` - Inter-location transfer shipped
- `SCRAP_OUT` - Write-off due to damage, shrink, or obsolescence
- `ADJUSTMENT_OUT` - Manual negative adjustment (e.g., damaged, lost)
- `COUNT_VARIANCE_OUT` - Cycle count revealed less inventory than recorded

#### Event Types EXCLUDED from On-Hand

These events affect **availability/ATP** but do NOT change physical on-hand:
- `RESERVATION_CREATED` - Soft allocation for sales order
- `RESERVATION_RELEASED` - Reservation cancelled/expired
- `ALLOCATION_CREATED` - Hard allocation for pick/pack
- `ALLOCATION_RELEASED` - Allocation cancelled
- `BACKORDER_CREATED` - Recording demand that cannot be fulfilled
- `BACKORDER_RESOLVED` - Backorder fulfilled or cancelled
- `PICK_TASK_CREATED` - Picking instruction created
- `PICK_TASK_COMPLETED` - Picking completed (unless it also posts `GOODS_ISSUE` or `TRANSFER_OUT`)

**Implementation Note**: The system MUST sum quantities with appropriate signs based on event direction. Event types should be clearly categorized as INBOUND (+) or OUTBOUND (-) in the domain model.

## Consequences

### Positive
- **Deterministic**: ATP calculation relies only on ledger events, not external promises
- **Auditable**: All inventory changes tracked via explicit ledger events
- **Performant**: Single-table queries with proper indexes can meet SLA
- **Simple v1 Scope**: Avoids complex supplier/purchasing integration

### Negative
- **Limited Forward-Looking Visibility**: v1 cannot commit against expected receipts
- **No UOM Flexibility**: Clients must convert to base UOM before querying
- **Potential Feature Pressure**: Business may request expected receipts soon after v1 launch

### Mitigations
- Document API extension points for expected receipts and UOM conversion
- Design database schema to accommodate future fields without migration
- Monitor P95 latency in production and optimize proactively

## References

- **Origin Story**: [#36 - Ledger: Compute On-hand and Available-to-Promise by Location/Storage](https://github.com/louisburroughs/durion-positivity-backend/issues/36)
- **Clarification Issue**: [#233 - Clarification for Story #36](https://github.com/louisburroughs/durion-positivity-backend/issues/233)
- **Decision Date**: 2026-01-12
- **Decided By**: @louisburroughs
- **Story Authoring Agent Run ID**: 42e2f1b7-d183-4e13-bd5c-9799745b8f71

## Revision History

| Date       | Version | Author          | Changes                                      |
|------------|---------|-----------------|----------------------------------------------|
| 2026-01-12 | 1.0     | @louisburroughs | Initial decision captured from clarification |
