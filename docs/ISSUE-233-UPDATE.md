# Issue #233 Update - Clarification Resolved

## Summary

All four clarification questions for story #36 have been answered and documented. The architectural decisions are now formally captured in ADR-0001, and implementation artifacts have been created to guide development.

## Quick Reference for Story #36 Implementation

### 1. ATP Formula
```
ATP = On-Hand - Allocations
```
Expected Receipts are out of scope for v1.

### 2. UOM Handling
All calculations and responses use the product's **base UOM only**. No UOM conversions in v1.

### 3. Performance Target
- **P95 < 200ms** (single product, single location)
- P50 < 80ms
- P99 < 400ms

### 4. On-Hand Event Types

**Include in On-Hand** (physical movements):
- ✅ `GOODS_RECEIPT`, `TRANSFER_IN`, `RETURN_TO_STOCK`, `ADJUSTMENT_IN`, `COUNT_VARIANCE_IN` (positive)
- ✅ `GOODS_ISSUE`, `TRANSFER_OUT`, `SCRAP_OUT`, `ADJUSTMENT_OUT`, `COUNT_VARIANCE_OUT` (negative)

**Exclude from On-Hand** (affect ATP only):
- ❌ `RESERVATION_*`, `ALLOCATION_*`, `BACKORDER_*`, `PICK_TASK_*`

## Documentation Created

1. **Architecture Decision Record**
   - File: `docs/adr/0001-inventory-ledger-atp-computation.md`
   - Contains: Full context, decisions, rationale, and consequences

2. **Implementation Guide**
   - File: `pos-inventory/docs/inventory-ledger-atp.md`
   - Contains: API contract, database schema, SQL patterns, testing strategy

3. **Code Artifacts**
   - `InventoryLedgerEventType.java` - Complete enum of event types with categorization
   - `InventoryAvailabilityResponse.java` - DTO for API responses

4. **Resolution Summary**
   - File: `docs/CLARIFICATION-233-RESOLUTION.md`
   - Contains: All answers in detailed format

## API Contract Example

```json
GET /api/inventory/availability?productId={id}&locationId={id}

Response (200 OK):
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

## Next Actions

### For Story Authoring Agent:
1. ✅ Update story #36 body with these decisions
2. ✅ Remove `blocked:clarification` label from story #36
3. ✅ Add label `status:ready-for-dev` to story #36
4. ✅ Close issue #233 as resolved

### For Development Team (when story #36 is implemented):
1. Review ADR-0001 for complete context
2. Reference implementation guide in `pos-inventory/docs/inventory-ledger-atp.md`
3. Use `InventoryLedgerEventType` enum for event type handling
4. Use `InventoryAvailabilityResponse` DTO for API responses
5. Implement database schema per the guide
6. Add performance monitoring for P95 SLA compliance

## Issue Labels to Update

**Remove from #36**:
- `blocked:clarification`

**Add to #36**:
- `status:ready-for-dev` (if no other blockers)

**Add to #233**:
- `resolution:answered`

**Close #233** with reference to this PR.

## Commit Reference

These decisions were documented in commit: [TBD - will be filled by git]

PR: [TBD - will be filled when PR is created]
