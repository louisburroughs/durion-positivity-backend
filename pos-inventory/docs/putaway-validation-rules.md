# Putaway Validation Business Rules

## Overview

This document defines the business rules for executing putaway moves from staging to storage locations, as clarified in GitHub issue #229 (clarification for story #31).

## Context

- **Origin Story:** [Issue #31 - Putaway: Execute Put-away Move (Staging → Storage)](https://github.com/louisburroughs/durion-positivity-backend/issues/31)
- **Clarification Issue:** [Issue #229 - Clarification Origin #31](https://github.com/louisburroughs/durion-positivity-backend/issues/229)
- **Resolution Date:** 2026-01-12
- **Resolved By:** @louisburroughs

## Business Rules

### 1. Destination Location: SKU Compatibility

**Default Behavior (Mandatory):**
- **Block the putaway transaction** if destination location is invalid for SKU
- Display clear error: `LOCATION_NOT_VALID_FOR_SKU`
- Clerk **must select a different location**

**Invalid Destination Examples:**
- SKU not allowed in that zone
- Temperature class mismatch
- Hazardous/non-hazardous rules violation
- Not an authorized bin

**Override Policy:**
- **No override by default**
- Optional, tightly controlled override:
  - Requires permission: `OVERRIDE_LOCATION_COMPATIBILITY`
  - Requires mandatory reason code and free-text justification
  - Emits audit event: `PutawayOverrideLocationRule`
- Overrides should be disabled at launch unless business explicitly requires them

**Rationale:** Allowing overrides here easily leads to unsafe storage, regulatory violations, and downstream picking errors.

### 2. Destination Location: Capacity Validation

**Default Behavior:**
- **Block the putaway** if location is at full capacity
- Prompt clerk to:
  - Choose another valid location, or
  - Split quantity across multiple locations

**Optional Override (More Permissive Than Compatibility):**
- Allowed **only if:**
  - Permission `OVERRIDE_LOCATION_CAPACITY` is present
  - Overfill is within configured tolerance (e.g., ≤ 5-10%)
  - Justification is captured

**Audit Requirements (If Overridden):**
- `previousCapacity`
- `newCapacity`
- `overrideReasonCode = CAPACITY_OVERRIDE`
- `approvedBy`

**Rationale:** Capacity violations are sometimes operationally tolerable short-term, but must be visible and auditable.

### 3. Source Location: On-Hand Validation

**Default Behavior (Mandatory):**
- **Block the putaway transaction** if source location shows zero quantity
- Display error: `NO_ON_HAND_AT_SOURCE_LOCATION`
- System must **NOT** silently create inventory

**Recovery / Reconciliation Flow:**

Provide a **guided reconciliation path**, not a blind override.

**Allowed Recovery Actions (Permission-Gated):**

1. **Trigger a cycle count / recount**
   - Permission: `INITIATE_CYCLE_COUNT`
   - Creates a reconciliation task for the source location

2. **Inventory adjustment (exceptional)**
   - Permission: `ADJUST_INVENTORY`
   - Requires:
     - Explicit reason code (`MISPLACED_STOCK`, `UNRECORDED_RECEIPT`, etc.)
     - Manager approval if above threshold
   - Adjustment must complete **before** putaway proceeds

**Explicitly Disallowed:**
- Proceeding with putaway without correcting inventory records
- "Assume quantity exists" behavior

**Rationale:** This condition indicates shrink, mis-scan, or missed receipt. Letting it pass corrupts inventory accuracy system-wide.

## Permission Model

| Permission | Description | Typical Use Case |
|-----------|-------------|------------------|
| `OVERRIDE_LOCATION_COMPATIBILITY` | Override location/SKU compatibility rules | Exceptional placement when no alternative exists |
| `OVERRIDE_LOCATION_CAPACITY` | Override location capacity limits | Temporary overfill within tolerance |
| `INITIATE_CYCLE_COUNT` | Trigger cycle count for reconciliation | Resolve data consistency issues |
| `ADJUST_INVENTORY` | Make inventory adjustments | Correct system records to match physical reality |

## Error Codes

| Error Code | Description | Resolution |
|-----------|-------------|------------|
| `LOCATION_NOT_VALID_FOR_SKU` | Destination location is not compatible with SKU | Select different location or request override |
| `LOCATION_AT_CAPACITY` | Destination location is at full capacity | Select different location or request capacity override |
| `NO_ON_HAND_AT_SOURCE_LOCATION` | Source location has zero on-hand inventory | Initiate reconciliation (cycle count or adjustment) |

## Implementation

### Exception Classes
- `PutawayValidationException` - Base exception for validation errors
- `LocationNotValidForSkuException` - SKU compatibility violation
- `LocationAtCapacityException` - Capacity limit violation
- `NoOnHandAtSourceLocationException` - Source inventory data consistency error

### Services
- `PutawayValidationService` - Validation logic interface
- `PutawayValidationServiceImpl` - Default implementation

### DTOs
- `PutawayExecutionRequest` - Request with override flags and justification
- `ValidationResult` - Validation outcome with errors and warnings

### Security
- `PutawayPermissions` - Permission constant definitions

### Domain Models
- `OverrideReasonCode` - Enum for override justifications
- `ReconciliationReasonCode` - Enum for inventory adjustment reasons

## Testing Considerations

### Unit Tests Required
- Location compatibility validation with various invalid scenarios
- Capacity validation with tolerance checks
- Source on-hand validation with zero quantity
- Override logic with and without permissions
- Comprehensive validation combining all checks

### Integration Tests Required
- End-to-end putaway execution with valid data
- Putaway blocked by compatibility rules
- Putaway blocked by capacity rules
- Putaway blocked by missing source inventory
- Override flows with proper permissions

### Edge Cases
- Negative quantities
- Null location IDs
- Non-existent SKUs
- Concurrent putaways to same location
- Override without justification
- Override with insufficient tolerance

## References

- [GitHub Issue #31 - Putaway: Execute Put-away Move](https://github.com/louisburroughs/durion-positivity-backend/issues/31)
- [GitHub Issue #229 - Clarification for Issue #31](https://github.com/louisburroughs/durion-positivity-backend/issues/229)
- Architecture Decision Records (ADRs) - TBD
- Inventory Ledger Event Type Definitions: `InventoryLedgerEventType.java`

## Change Log

| Date | Author | Change |
|------|--------|--------|
| 2026-01-12 | System | Initial business rules documentation based on clarification #229 |
