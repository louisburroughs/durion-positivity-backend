# Clarification Resolution Summary - Issue #230

**Clarification Issue:** #230 - [CLARIFICATION] Origin #32: [BACKEND] [STORY] Putaway: Generate Put-away Tasks from Staging  
**Origin Story:** #32 - [BACKEND] [STORY] Putaway: Generate Put-away Tasks from Staging  
**Resolution Date:** 2026-01-12  
**Domain:** inventory  
**Status:** RESOLVED

---

## Quick Reference

All clarification decisions have been documented in:
`.github/docs/architecture/putaway-task-generation-clarification.md`

---

## Resolved Questions

### 1. Rule Precedence ✅

**Decision:** Strict most-specific-wins hierarchy

**Order (highest to lowest):**
1. Product-specific rule
2. Category-level rule  
3. Supplier / Receipt-type rule
4. Location default rule
5. System fallback

**Key Points:**
- Higher precedence always overrides lower
- Same-level conflicts are configuration errors (must be rejected at setup)
- System validates rule configurations at creation

---

### 2. Task Granularity ✅

**Decision:** One receipt line item = one putaway task (default)

**Optional Consolidation:** Allowed ONLY when ALL are true:
- Same `productId`
- Same `suggestedDestinationLocationId`
- Same receipt/session
- Same handling constraints (lot, expiry, serial rules)

**Rationale:**
- Preserves traceability
- Simplifies audit and reconciliation
- Clear one-to-one mapping

**Explicitly Disallowed:**
- Merging different SKUs
- Merging items with different lot/expiry constraints

---

### 3. Assignment Mechanism ✅

**Decision:** Shared pool with self-claim + optional manager assignment

**Default (Shared Pool):**
- Tasks created as UNASSIGNED
- Users with `CLAIM_PUTAWAY_TASK` permission may claim tasks

**Manager Capabilities:**
- Users with `ASSIGN_PUTAWAY_TASK` permission may:
  - Pre-assign tasks
  - Reassign tasks (even if claimed)
  - Override claims
  - Unassign tasks back to pool

**Rationale:**
- Matches warehouse work-stealing model
- Avoids assignment bottlenecks
- Allows supervisory control when needed

---

### 4. Exception Handling - Destination Unavailable ✅

**Decision:** Automatic fallback at generation time; manual intervention only as last resort

**Required Behavior:**
If suggested destination is full/unavailable/invalid:
1. Attempt next-best location using:
   - Same rule precedence
   - Same compatibility constraints
   - Same layout ranking logic
2. Record fallback decision:
   - `originalSuggestedLocationId`
   - `finalSuggestedLocationId`
   - `fallbackReason` enum

**Manual Intervention:** ONLY if no valid location exists
- Status: `REQUIRES_LOCATION_SELECTION`
- Requires `SELECT_PUTAWAY_LOCATION` permission
- Present compatibility scores for potential locations

**Explicitly Disallowed:**
- Generating tasks with invalid destinations
- Silent failures
- Null destinations

---

## Required Permissions

| Permission | Description | Typical Roles |
|------------|-------------|---------------|
| `CLAIM_PUTAWAY_TASK` | Claim unassigned task | Stock Clerk, Warehouse Associate |
| `ASSIGN_PUTAWAY_TASK` | Pre-assign/reassign tasks | Warehouse Manager, Supervisor |
| `SELECT_PUTAWAY_LOCATION` | Manually select location | Warehouse Manager, Inventory Controller |
| `EXECUTE_PUTAWAY_TASK` | Execute putaway move | Stock Clerk, Warehouse Associate |
| `CANCEL_PUTAWAY_TASK` | Cancel tasks | Warehouse Manager, Supervisor |

---

## Data Model Updates Required

### New/Modified Entities

**PutawayTask:**
- `originalSuggestedLocationId` (new)
- `finalSuggestedLocationId` (new)
- `fallbackReason` enum (new)
- `status` enum (expanded to include REQUIRES_LOCATION_SELECTION)

**PutawayTaskLineReference:** (new entity)
- Links tasks to original receipt line items
- Supports consolidated tasks

**PutawayRule:**
- `precedence` enum field
- Enhanced validation logic

### New Enums

- `PutawayRulePrecedence`: PRODUCT_SPECIFIC, CATEGORY_LEVEL, SUPPLIER_RECEIPT_TYPE, LOCATION_DEFAULT, SYSTEM_FALLBACK
- `TaskStatus`: UNASSIGNED, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED, REQUIRES_LOCATION_SELECTION
- `FallbackReason`: DESTINATION_FULL, DESTINATION_UNAVAILABLE, VALIDATION_FAILED, NO_CAPACITY, INCOMPATIBLE_CONSTRAINTS

---

## Implementation Guidance

### Phase 1: Core Task Generation
- Rule precedence evaluation
- Basic task creation (1:1 line-to-task)
- Shared pool assignment

### Phase 2: Intelligent Fallback
- Location validation
- Automatic fallback logic
- Manual intervention workflow

### Phase 3: Optimization
- Task consolidation (optional)
- Manager assignment capabilities
- Advanced location scoring

---

## Testing Requirements

### Unit Tests
- Rule precedence with multiple applicable rules
- Task consolidation scenarios
- Fallback location selection
- Permission enforcement

### Integration Tests
- End-to-end task generation from receipts
- Assignment and claim workflows
- Location validation and fallback
- Concurrent task claiming

### Property-Based Tests
- Rule precedence consistency
- Task granularity maintains traceability
- Fallback never produces invalid destinations

---

## Story Update Checklist

The Story Authoring Agent should update Issue #32 with:

- [ ] Add Business Rules section with:
  - [ ] BR-PUTAWAY-01: Rule precedence hierarchy
  - [ ] BR-PUTAWAY-02: Task granularity rules
  - [ ] BR-PUTAWAY-03: Assignment mechanism
  - [ ] BR-PUTAWAY-04: Exception handling/fallback logic

- [ ] Update Data Schema section with:
  - [ ] PutawayTask entity with new fields
  - [ ] PutawayTaskLineReference entity
  - [ ] PutawayRule entity with precedence
  - [ ] All new enums

- [ ] Add Acceptance Criteria:
  - [ ] AC-PUTAWAY-01: Rule precedence enforcement
  - [ ] AC-PUTAWAY-02: Task creation from receipt lines
  - [ ] AC-PUTAWAY-03: Self-claim functionality
  - [ ] AC-PUTAWAY-04: Manager assignment
  - [ ] AC-PUTAWAY-05: Automatic fallback when destination unavailable
  - [ ] AC-PUTAWAY-06: Manual location selection when no fallback available
  - [ ] AC-PUTAWAY-07: Fallback tracking and audit

- [ ] Add Permission Requirements section

- [ ] Add Testing Requirements section

- [ ] Remove `blocked:clarification` label

- [ ] Add `status:needs-review` label

---

## Next Steps

1. **Story Authoring Agent:** Update Issue #32 with clarification decisions
2. **Domain Review:** Inventory domain agent reviews updated story
3. **Technical Review:** Architecture and implementation agents review
4. **Ready for Development:** Change label to `status:ready-for-dev`

---

## References

- **Full Clarification Document:** `.github/docs/architecture/putaway-task-generation-clarification.md`
- **Origin Story:** [Issue #32](https://github.com/louisburroughs/durion-positivity-backend/issues/32)
- **Clarification Issue:** [Issue #230](https://github.com/louisburroughs/durion-positivity-backend/issues/230)
- **Domain Agent:** `.github/agents/domains/inventory-domain-agent.md`
- **Related Story:** [Issue #31 - Putaway: Execute Put-away Move](https://github.com/louisburroughs/durion-positivity-backend/issues/31)

---

**Status:** ✅ All clarification questions resolved and documented  
**Blocked Status:** ❌ No longer blocked  
**Ready For:** Story update and domain review
