# [CLARIFICATION] Domain Ownership for Inventory Item Cost Data

## Issue Type
Clarification Request - Domain Conflict Resolution

## Related Story
- **Original Story**: #196 - Cost: Maintain Standard/Last/Average Cost with Audit
- **Domain Conflict**: Inventory vs. Accounting ownership

## Background
The cost maintenance story (#196) requires implementing three cost types (Standard, Last, Average) for inventory items. This spans both Inventory and Accounting domains, creating a domain boundary conflict.

## Questions Requiring Clarification

### 1. Domain Ownership (CRITICAL - BLOCKER)
**Question**: Which domain is the system of record for inventory item cost data?

**Options**:
- **Option A**: **Inventory Domain** owns the cost data model
  - Cost fields (standardCost, lastCost, averageCost) are part of the InventoryItem/Product entity
  - Inventory service owns the database table with cost columns
  - Accounting domain calls Inventory APIs to read cost data
  - Rationale: Costs are intrinsic attributes of inventory items
  
- **Option B**: **Accounting Domain** owns the cost data model
  - Cost data is stored in a separate ItemCost table managed by Accounting
  - Accounting service is the authoritative source for cost information
  - Inventory domain queries Accounting APIs to get cost data
  - Rationale: Costs are financial/accounting concepts

- **Option C**: **Dual Ownership** with clear boundaries
  - Inventory owns the physical cost fields on the item entity
  - Accounting owns the calculation logic and validation rules
  - Integration via domain events
  - Rationale: Separation of concerns - data vs. business rules

**Impact if Unanswered**: Cannot determine which service implements the entities, repositories, and APIs. Implementation blocked.

### 2. Cost Calculation Logic Authority (CRITICAL - BLOCKER)
**Question**: Which domain agent is responsible for defining and implementing the cost calculation logic?

**Specific Areas**:
- Weighted average cost formula
- Rounding rules (4 decimal places specified)
- Event handling for Purchase Order Received
- Transaction atomicity requirements
- Error handling for invalid costs

**Impact if Unanswered**: Cannot implement the cost update logic. Business rules ownership unclear.

### 3. Primary Costing Method (HIGH PRIORITY)
**Question**: Which of the three cost methods (Standard, Last, Average) will be the default for downstream financial calculations?

**Context**:
- COGS (Cost of Goods Sold) calculations
- Inventory valuation on balance sheet
- Financial reporting requirements

**Impact if Unanswered**: Cannot design proper integration points. Financial reporting may be incorrect.

### 4. Permission Model (MEDIUM PRIORITY)
**Question**: What specific user role(s) are authorized to manually set or adjust the Standard Cost?

**Context**:
- Standard Cost is the only manually-editable cost field
- Last and Average costs are system-managed

**Options**:
- Inventory Manager only
- Accounting Manager only
- Both Inventory Manager and Accounting Manager
- Custom "Cost Controller" role

**Impact if Unanswered**: Cannot implement proper authorization checks. Security risk.

### 5. Initial Cost Values (MEDIUM PRIORITY)
**Question**: When a new inventory item is created, what should the initial values be?

**Options**:
- All zeros (0.00)
- NULL until first purchase
- Seeded from initial purchase order
- Copy from similar item template

**Impact if Unanswered**: Cannot implement item creation logic properly. Data integrity risk.

## Recommended Resolution Approach

Based on Domain-Driven Design principles and common POS architecture patterns, we recommend:

### Proposed Split: Two Separate Stories

#### Story A: Inventory Domain - Cost Data Model (Data Layer)
**Owner**: Inventory Domain Agent
**Responsibility**:
- Add cost fields to Product/InventoryItem entity
- Implement basic CRUD operations for cost data
- Publish domain events when costs change
- Store cost audit history (append-only log)

#### Story B: Accounting Domain - Cost Business Logic (Business Rules Layer)
**Owner**: Accounting Domain Agent  
**Responsibility**:
- Subscribe to Purchase Order Received events
- Implement cost calculation algorithms (Last, Average)
- Enforce business rules (non-negative costs, rounding)
- Validate cost updates
- Trigger inventory domain events to update cost fields
- Provide cost reporting APIs for financial systems

### Integration Pattern
1. Inventory domain owns the **data** (cost fields on item entity)
2. Accounting domain owns the **business rules** (calculation logic)
3. Communication via **domain events** (event-driven architecture)
4. Accounting service is a **consumer** of PO events and **producer** of cost update commands
5. Inventory service **stores** cost data and **publishes** cost change events for audit

This approach:
- ✅ Maintains clear domain boundaries
- ✅ Follows Single Responsibility Principle
- ✅ Enables independent deployment and scaling
- ✅ Respects data ownership (Inventory) and business logic ownership (Accounting)

## Decision Required
Please review the questions above and the recommended split approach. Provide explicit decisions for questions 1-5 so we can proceed with implementation.

## Next Steps After Clarification
Once decisions are made:
1. Create Story A (Inventory domain) with clear acceptance criteria
2. Create Story B (Accounting domain) with clear acceptance criteria
3. Define integration contract (events, APIs)
4. Mark both stories as ready-for-dev
5. Close this clarification issue

## Labels for This Issue
- `blocked:clarification`
- `domain:inventory`
- `domain:accounting`
- `priority:critical`
- `type:clarification`

---
**Created By**: Story Authoring Agent
**Date**: 2026-01-13
**References**: Issue #196
