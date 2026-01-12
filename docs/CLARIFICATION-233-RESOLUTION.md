# Clarification Resolution Summary

**Issue**: #233 - [CLARIFICATION] Origin #36: [BACKEND] [STORY] Ledger: Compute On-hand and Available-to-Promise by Location/Storage

**Status**: ✅ RESOLVED - All questions answered

**Resolution Date**: 2026-01-12

**Answered By**: @louisburroughs

---

## Question 1: ATP Formula Confirmation

**Question**: Is the official ATP formula for this implementation `On-Hand - Allocations` or `On-Hand - Allocations + Expected Receipts`?

**Answer**: 
- **ATP v1 = On-Hand - Allocations**
- Expected Receipts are **explicitly out of scope** for v1

**Rationale**:
- Expected receipts require purchasing/supplier integration and promise-date logic
- Keeping v1 deterministic and ledger-derived only

**Forward Compatibility**:
- API may return optional/nullable field `expectedReceiptsQty` for future use
- This field MUST NOT be included in ATP calculation in v1

---

## Question 2: UOM Handling Scope

**Question**: Does this mean all calculations are performed in the product's base UOM and the API reports that UOM? Or is there a requirement to handle requests/responses in different UOMs?

**Answer**: 
- All calculations are performed and returned in the product's **base UOM only**

**Implementation**:
- Ledger stores quantities in base UOM
- API returns:
  - `onHandQty` (base UOM)
  - `allocatedQty` (base UOM)
  - `atpQty` (base UOM)
  - `uom = baseUom`

**Out of Scope for v1**:
- Request/response UOM conversions (e.g., case vs each)

**Forward Compatibility**:
- Future versions may add `requestedUom` parameter and conversions via Product/UOM service

---

## Question 3: Performance SLA Definition

**Question**: Can we define a specific, measurable performance target for the P95 response time of this API endpoint?

**Answer**: 
- **P95 < 200ms** for single product, single location query (warm cache)

**Additional Targets**:
- P50 < 80ms
- P99 < 400ms
- Bulk queries (if supported): P95 < 500ms for up to 50 productIds

**Measurement Boundary**:
- Measured at the service boundary (application layer)
- Excludes caller network latency
- Assumes warm database connection pool

---

## Question 4: Definitive Ledger Event Types

**Question**: Which specific inventory ledger event types should be summed to calculate the definitive 'On-Hand' quantity?

**Answer**: 
On-hand is the net sum of **physical stock movements** (ins and outs) plus count variances. 
Allocations are **NOT** part of on-hand.

### Events INCLUDED in On-Hand (add/subtract by direction)

**Inbound (positive)**:
- `GOODS_RECEIPT` - receiving into stock
- `TRANSFER_IN` - inter-location transfer received
- `RETURN_TO_STOCK` - customer return accepted into stock
- `ADJUSTMENT_IN` - manual positive adjustment
- `COUNT_VARIANCE_IN` - cycle count increased inventory

**Outbound (negative)**:
- `GOODS_ISSUE` - issued/consumed to workorder
- `TRANSFER_OUT` - inter-location transfer shipped
- `SCRAP_OUT` - write-off, damage, shrink
- `ADJUSTMENT_OUT` - manual negative adjustment
- `COUNT_VARIANCE_OUT` - cycle count decreased inventory

### Events EXPLICITLY EXCLUDED from On-Hand

These affect availability/ATP but not physical on-hand:
- `RESERVATION_CREATED` / `RESERVATION_RELEASED`
- `ALLOCATION_CREATED` / `ALLOCATION_RELEASED`
- `BACKORDER_CREATED` / `BACKORDER_RESOLVED`
- `PICK_TASK_CREATED` / `PICK_TASK_COMPLETED` (unless it posts a `GOODS_ISSUE`/`TRANSFER_OUT`)

---

## Documentation Created

1. **ADR-0001**: `/docs/adr/0001-inventory-ledger-atp-computation.md`
   - Complete architectural decision record with context, decisions, and consequences

2. **Implementation Guide**: `/pos-inventory/docs/inventory-ledger-atp.md`
   - API contract specification
   - Database schema proposals
   - SQL query patterns
   - Testing strategy
   - Monitoring guidelines

3. **Code Artifacts**:
   - `InventoryLedgerEventType.java` - Enum with all event types categorized by direction and on-hand impact
   - `InventoryAvailabilityResponse.java` - DTO for API responses

---

## Next Steps for Story Authoring Agent

1. ✅ Update origin story #36 with these decisions
2. ✅ Remove `blocked:clarification` label from origin story #36
3. ✅ Set `status:ready-for-dev` on origin story #36 (if no other blockers remain)
4. ✅ Close clarification issue #233 as resolved

---

## References

- **Clarification Issue**: #233
- **Origin Story**: #36 - [BACKEND] [STORY] Ledger: Compute On-hand and Available-to-Promise by Location/Storage
- **ADR**: [ADR-0001](/docs/adr/0001-inventory-ledger-atp-computation.md)
- **Domain**: domain:inventory
- **Clarification Type**: clarification:domain
