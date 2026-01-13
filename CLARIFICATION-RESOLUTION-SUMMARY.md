# Clarification Resolution Summary for Issue #28

## Overview
This document provides the clarification decisions to be integrated into issue #28: [BACKEND] [STORY] Fulfillment: Create Pick List / Pick Tasks for Workorder

## Source
- **Clarification Request**: Origin issue #28, clarification type: domain:inventory
- **Responses Provided By**: @louisburroughs
- **Date**: 2026-01-05

## Clarification Decisions

### OQ1 — Priority & Due Time Logic (Pick Tasks)

**Decision:**
- **Authority and Inheritance:** Base priority and due time are inherited from the Work Order SLA
- **Inventory-Specific Modifiers:** Inventory applies bounded adjustments (only if applicable):
  - **Stock risk** (low on-hand for the item): +1 priority
  - **Backorder resolution** (this pick unblocks waiting work): +1 priority
  - **Critical part type** (safety/immobilizing component): +1 priority
- **Priority Formula:** `EffectivePriority = min(workOrderPriority + inventoryModifiers, MAX_PRIORITY)`
- **Due Time Calculation:**
  - Default: `pickDueAt = workOrder.scheduledStartAt − pickLeadTimeBuffer`
  - `pickLeadTimeBuffer` default: **30 minutes**
  - If no scheduled start: inherit `workOrder.dueAt`
  - Inventory **must not** delay beyond the work order's SLA

**Rationale:** WorkExec defines urgency; Inventory refines execution sequencing.

### OQ2 — Sorting Logic (Route / Location)

**Decision:**
Use a **deterministic, layout-aware sort**, not route optimization.

**Required Model:**
- Warehouse layout: Zone → Aisle → Rack → Bin
- Each location stores: `zoneOrder`, `aisleOrder`, `rackOrder`, `binOrder`

**Sorting Algorithm (stable, in order):**
1. `zoneOrder ASC`
2. `aisleOrder ASC`
3. `rackOrder ASC`
4. `binOrder ASC`
5. `locationCode ASC` (final tie-breaker)

**Explicit Non-Goals (v1):**
- No shortest-path optimization
- No picker-specific routing
- No dynamic reordering mid-pick

**Rationale:** Deterministic ordering is auditable, explainable, and sufficient at launch. Route optimization can be added later without breaking contracts.

### OQ3 — Location Suggestion (Single "Suggested" Pick Location)

**Decision Hierarchy (strict, deterministic):**
When multiple storage locations exist for a product:

1. **Dedicated Pick Zone**
   - If any location is flagged `isPickZone = true` and has sufficient quantity, select it

2. **FEFO / FIFO Compliance**
   - If lot/expiry controlled, select location with earliest expiry or receipt date

3. **Sufficient Quantity**
   - Prefer a single location that can fulfill the entire required quantity

4. **Proximity in Layout**
   - Lowest `(zoneOrder, aisleOrder, rackOrder, binOrder)`

5. **Highest On-Hand Quantity**
   - Final tie-breaker

**Partial Fulfillment:**
- If no single location can fulfill:
  - Suggest the **best primary location** (above rules)
  - Generate **additional pick tasks** for remaining quantity using the same rules

**Explicit Exclusions:**
- Do not split picks unnecessarily
- Do not pick from reserve/bulk locations unless no pick-zone stock exists

## Changes Required to Issue #28

### 1. Remove from "Open Questions" Section
Delete the following questions as they have been answered:
- OQ1 (Priority & Due Time Logic)
- OQ2 (Sorting Logic)
- OQ3 (Location Suggestion)

### 2. Update "Preconditions" Section
Add:
```
4. Storage locations have layout ordering attributes (zoneOrder, aisleOrder, rackOrder, binOrder) defined.
```

### 3. Update "Functional Behavior - Happy Path" Step 3
Replace references to OQ1, OQ2, OQ3 with actual decision text:
- Priority assignment: "using the priority calculation formula (base work order priority + inventory modifiers, capped at MAX_PRIORITY)"
- Due time: "calculated as `workOrder.scheduledStartAt − pickLeadTimeBuffer` (default buffer: 30 minutes)"
- Location suggestion: "using the location selection hierarchy (see Business Rules below)"

### 4. Update "Functional Behavior - Happy Path" Step 5
Replace sorting reference with:
"based on the deterministic warehouse layout sorting algorithm (zoneOrder → aisleOrder → rackOrder → binOrder → locationCode)"

### 5. Add New Section "Business Rules" (after existing Business Rules)
Add comprehensive business rules for:
- Priority Determination (with formula and modifiers)
- Due Time Determination  
- Location Selection Hierarchy (5-tier decision tree)
- Location Selection Rules (no unnecessary splits, pick zone preference)
- Sorting Algorithm (deterministic, layout-aware)
- Explicit Non-Goals for v1

### 6. Update "Data Requirements - PickTask Entity" Table
Update notes for:
- `suggestedLocationId`: "Determined by location selection hierarchy"
- `sortOrder`: "Determined by layout-aware sorting algorithm"
- `priority`: "Calculated: base + modifiers, capped at MAX_PRIORITY"
- `dueTime`: "`workOrder.scheduledStartAt − pickLeadTimeBuffer`"

### 7. Add New Section "Data Requirements - StorageLocation Entity"
Add required attributes table including:
- `locationId`, `locationCode`
- `zoneOrder`, `aisleOrder`, `rackOrder`, `binOrder`
- `isPickZone`, `onHandQuantity`

### 8. Expand "Acceptance Criteria"
Add new scenarios:
- **Scenario 4: Priority Calculation with Modifiers**
- **Scenario 5: Location Selection from Pick Zone**
- **Scenario 6: Pick Task Sorting by Layout**

### 9. Update "Audit & Observability - Metrics"
Add:
- `pick_task_priority_modifiers_applied`: Counter for each type of priority modifier
- `location_selection_by_rule`: Counter for which rule in the hierarchy was used

### 10. Update Labels
Remove:
- `blocked:clarification`
- `status:draft`

Add:
- `status:needs-review` (or `status:ready-for-dev` if no further questions remain)

Update variant note:
From: "inventory-flexible"
To: "inventory-flexible (clarifications integrated)"

## Implementation Notes for Story Authoring Agent

The updated story now contains:
- **Deterministic, auditable business rules** for all three previously open questions
- **Explicit formulas and algorithms** that can be directly translated to code
- **Comprehensive acceptance criteria** covering the new business rules
- **Complete data model** including storage location attributes needed for sorting and selection

All questions have been resolved with decisions that are:
- Simple to implement
- Predictable to operate
- Extensible for future enhancements
- Aligned with inventory domain authority

The story is now **ready for technical review** and can proceed to development once approved.

## Files Modified
1. `Durion-Processing.md` - Tracking document
2. `CLARIFICATION-RESOLUTION-SUMMARY.md` - This file
3. Issue #28 body - To be updated with integrated clarifications

## Next Steps
1. Story Authoring Agent should update issue #28 body with the integrated content
2. Remove `blocked:clarification` label from issue #28
3. Set `status:needs-review` label on issue #28
4. Close any associated clarification tracking issues
