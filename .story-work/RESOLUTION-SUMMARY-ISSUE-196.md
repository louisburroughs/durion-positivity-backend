# Domain Conflict Resolution Summary - Issue #196

## Executive Summary
The original story (#196) "Cost: Maintain Standard/Last/Average Cost with Audit" has been analyzed and identified as spanning two domain boundaries: **Inventory** and **Accounting**. This document outlines the recommended resolution through domain separation.

## Problem Analysis

### The Domain Conflict
The story requires implementing three cost types (Standard, Last, Average) for inventory items. This creates a natural tension:

- **Inventory Domain Perspective**: Cost is an attribute of an inventory item (product data)
- **Accounting Domain Perspective**: Cost calculation and valuation are core accounting principles

### Why This Matters
Without clear domain boundaries, we risk:
- Duplicated or conflicting business logic
- Unclear ownership for maintenance
- Integration coupling between services
- Difficulty in testing and deployment
- Violation of Single Responsibility Principle

## Recommended Resolution: Domain-Driven Split

### Proposed Architecture Pattern
We recommend **Dual Ownership with Clear Boundaries**:

1. **Inventory Domain** owns the **data** (cost fields on item entity)
2. **Accounting Domain** owns the **business rules** (calculation logic)
3. Communication via **domain events** (event-driven architecture)

### Benefits of This Approach
✅ Clear separation of concerns
✅ Each domain can evolve independently
✅ Testability: Logic and data can be tested separately
✅ Scalability: Services can scale based on their specific load
✅ Maintainability: Changes to calculation logic don't require Inventory service changes
✅ Follows Domain-Driven Design principles

---

## Split Stories

### Story A: [INVENTORY] Item Cost Data Model
**File**: `.story-work/STORY-INVENTORY-COST-DATA-MODEL.md`

**Responsibility**: Data persistence layer for cost information

**Scope**:
- Add cost fields to Product/InventoryItem entity (standardCost, lastCost, averageCost)
- Database schema migration
- Basic CRUD APIs for cost data
- Domain events for cost changes (for audit purposes)
- Authorization for Standard Cost manual updates
- Validation (non-negative costs, 4 decimal precision)

**Does NOT Include**:
- Cost calculation logic (belongs to Accounting)
- Purchase Order event handling (belongs to Accounting)
- Weighted average formula implementation (belongs to Accounting)

**Key APIs**:
- `GET /api/inventory/items/{itemId}/costs` - Retrieve cost data
- `PUT /api/inventory/items/{itemId}/costs/standard` - Manually update Standard Cost (authorized only)
- `PUT /api/inventory/items/{itemId}/costs/system-update` - System updates Last/Average (called by Accounting)

**Acceptance Criteria**: 7 scenarios covering CRUD operations, validation, authorization

---

### Story B: [ACCOUNTING] Cost Business Logic and Calculation
**File**: `.story-work/STORY-ACCOUNTING-COST-LOGIC.md`

**Responsibility**: Business rules and calculation logic for cost maintenance

**Scope**:
- Subscribe to Purchase Order Received events
- Implement Last Cost calculation (direct assignment)
- Implement Average Cost calculation (weighted average formula)
- Validation logic (positive costs, valid quantities)
- Update Inventory service with new costs (via API or event)
- Create audit log entries for all cost changes
- Transaction management and rollback logic
- Error handling and retry logic

**Does NOT Include**:
- Cost data storage (belongs to Inventory)
- Inventory item CRUD operations (belongs to Inventory)
- Standard Cost manual update logic (belongs to Inventory)

**Key Components**:
- Event consumer for `PurchaseOrderReceived`
- Cost calculation service (with weighted average formula)
- `ItemCostAudit` entity and repository
- Integration client for Inventory service API

**Acceptance Criteria**: 6 scenarios covering calculations, error handling, audit trail

---

## Integration Contract

### Events Published by Inventory Service
```json
{
  "eventType": "ItemCostChanged",
  "itemId": "uuid",
  "costType": "STANDARD" | "LAST" | "AVERAGE",
  "oldValue": 10.0000,
  "newValue": 12.5000,
  "changedBy": "user:john.doe" | "system",
  "timestamp": "2026-01-13T00:00:00Z"
}
```

### Events Consumed by Accounting Service
```json
{
  "eventType": "PurchaseOrderReceived",
  "purchaseOrderId": "PO-12345",
  "itemId": "uuid",
  "receivedQuantity": 50,
  "receivedUnitCost": 6.0000,
  "timestamp": "2026-01-13T00:00:00Z"
}
```

### API Calls from Accounting to Inventory
```http
PUT /api/inventory/items/{itemId}/costs/system-update
Content-Type: application/json

{
  "lastCost": 12.5000,
  "averageCost": 11.2567,
  "sourceEvent": "PurchaseOrderReceived",
  "sourceId": "PO-12345"
}
```

---

## Sequence Diagram: Purchase Order Receipt Flow

```
┌─────────┐         ┌────────────┐         ┌──────────┐         ┌───────────┐
│   PO    │         │ Accounting │         │ Inventory│         │ Audit Log │
│ System  │         │  Service   │         │ Service  │         │           │
└────┬────┘         └─────┬──────┘         └────┬─────┘         └─────┬─────┘
     │                    │                     │                      │
     │ PurchaseOrderReceived                    │                      │
     │─────Event─────────>│                     │                      │
     │                    │                     │                      │
     │                    │ GET /items/{id}     │                      │
     │                    │────(current costs)──>│                      │
     │                    │                     │                      │
     │                    │<────response────────│                      │
     │                    │                     │                      │
     │                    │                     │                      │
     │            ┌───────┴──────────┐          │                      │
     │            │ Calculate:       │          │                      │
     │            │ - Last Cost      │          │                      │
     │            │ - Average Cost   │          │                      │
     │            └───────┬──────────┘          │                      │
     │                    │                     │                      │
     │                    │ PUT /costs/system-update                   │
     │                    │─────(new costs)────>│                      │
     │                    │                     │                      │
     │                    │                     │ ItemCostChanged      │
     │                    │                     │──────Event──────────>│
     │                    │                     │                      │
     │                    │ Save Audit Entry    │                      │
     │                    │────────────────────────────────────────────>│
     │                    │                     │                      │
     │                    │<────success─────────│                      │
     │                    │                     │                      │
     │<────complete───────│                     │                      │
     │                    │                     │                      │
```

---

## Clarification Requirements

### Created Clarification Issue
**File**: `.story-work/CLARIFICATION-ISSUE-196-DOMAIN-OWNERSHIP.md`

This clarification issue documents **5 blocking questions** that must be answered:

1. **Domain Ownership** (CRITICAL): Which domain is system of record for cost data?
2. **Logic Authority** (CRITICAL): Which domain implements calculation logic?
3. **Primary Costing Method** (HIGH): Which cost type is default for COGS?
4. **Permission Model** (MEDIUM): Which role can update Standard Cost?
5. **Initial Cost Values** (MEDIUM): What are default values for new items?

### Labels for Clarification Issue
- `type:clarification`
- `blocked:clarification`
- `domain:inventory`
- `domain:accounting`
- `priority:critical`

---

## Implementation Sequence

### Recommended Order
1. **FIRST**: Resolve clarification questions (human decision required)
2. **SECOND**: Implement Inventory story (data model foundation)
3. **THIRD**: Implement Accounting story (business logic on top of data model)

### Why This Order?
- Inventory story creates the data foundation that Accounting depends on
- Accounting story can be tested independently once Inventory APIs exist
- This order minimizes integration risk and allows incremental deployment

---

## Open Questions for Human Decision

The following questions are **blocking** and require explicit human decisions before implementation can begin:

### Question 1: Domain Ownership (CRITICAL)
**Recommend**: Inventory owns data, Accounting owns logic (Dual Ownership model)

**Alternatives**:
- Pure Inventory ownership (Accounting only reads)
- Pure Accounting ownership (Inventory delegates all cost operations)

**Impact**: Determines service responsibilities and API design

### Question 2: Integration Pattern
**Recommend**: Accounting calls Inventory REST API for cost updates

**Alternatives**:
- Accounting publishes command events, Inventory consumes
- Direct database access (NOT recommended - violates domain boundaries)

**Impact**: Determines event schema and service coupling

### Question 3: Primary Costing Method
**Recommend**: Average Cost for COGS (standard retail practice)

**Alternatives**:
- Standard Cost (for variance analysis)
- Last Cost (simpler, but less accurate for COGS)

**Impact**: Determines financial reporting accuracy and method

### Question 4: Authorization Role
**Recommend**: "INVENTORY_MANAGER" role for Standard Cost updates

**Alternatives**:
- "ACCOUNTING_MANAGER"
- Both roles (role-based)
- Custom "COST_CONTROLLER" role

**Impact**: Security configuration and role definitions

### Question 5: Initial Cost Values
**Recommend**: 0.0000 for all costs on item creation

**Alternatives**:
- NULL until first purchase
- Copy from similar item template

**Impact**: Data integrity and reporting on new items

---

## Success Criteria for Domain Split

The domain split is successful when:

✅ Each story has clear, non-overlapping scope
✅ Integration contract is well-defined (events and APIs)
✅ Each story can be implemented independently
✅ Each story can be tested independently
✅ Each story can be deployed independently
✅ No business logic duplication between domains
✅ Clear ownership for future maintenance

---

## Next Steps

### For Story Authoring Agent
1. ✅ Create this resolution summary
2. ✅ Create clarification issue document
3. ✅ Create two domain-specific stories
4. ⏳ Wait for human decisions on clarification questions
5. ⏳ Update stories based on clarification responses
6. ⏳ Mark stories as ready-for-dev
7. ⏳ Assign to technical implementation team

### For Business Stakeholders
1. ⏳ Review clarification questions
2. ⏳ Provide explicit answers to Questions 1-5
3. ⏳ Approve the domain split approach
4. ⏳ Prioritize the implementation sequence

### For Technical Team (After Clarification)
1. ⏳ Review and refine story acceptance criteria
2. ⏳ Estimate implementation effort for each story
3. ⏳ Set up event bus infrastructure (if not present)
4. ⏳ Implement Inventory story first
5. ⏳ Implement Accounting story second
6. ⏳ Create integration tests

---

## Related Documents
- **Original Story**: Issue #196
- **Clarification Issue**: `.story-work/CLARIFICATION-ISSUE-196-DOMAIN-OWNERSHIP.md`
- **Inventory Story**: `.story-work/STORY-INVENTORY-COST-DATA-MODEL.md`
- **Accounting Story**: `.story-work/STORY-ACCOUNTING-COST-LOGIC.md`
- **This Summary**: `.story-work/RESOLUTION-SUMMARY-ISSUE-196.md`

---

**Resolution Status**: ⏳ AWAITING CLARIFICATION

**Blocking**: Yes - Requires human decision on domain ownership

**Ready for Development**: No - Clarification questions must be answered first

---
*Created by Story Authoring Agent*
*Date: 2026-01-13*
*Agent Status: Paused pending clarification*
