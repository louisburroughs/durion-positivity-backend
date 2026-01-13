# [STORY] [INVENTORY] Item Cost Data Model - Add Cost Fields to Inventory Items

## Story Type
User Story - Domain: Inventory

## Parent/Related Stories
- **Split From**: #196 - Cost: Maintain Standard/Last/Average Cost with Audit
- **Related Story**: [ACCOUNTING] Cost Business Logic and Calculation (to be created)
- **Clarification**: CLARIFICATION-ISSUE-196-DOMAIN-OWNERSHIP.md

## Labels (Proposed)
- `type:story`
- `domain:inventory`
- `status:draft`
- `blocked:clarification`
- `priority:high`
- `layer:data`

---

## Story Intent
**As an** Inventory service,
**I want** to store and maintain three cost types (Standard, Last, and Average) for each inventory item in the data model,
**so that** downstream accounting and financial systems can access accurate, traceable cost data for each item.

## Actors & Stakeholders
- **Primary Actor**: Inventory Service (the system itself)
- **Consumer**: Accounting Service (will read and update cost data via APIs or events)
- **Consumer**: Financial Reporting Systems (read-only access for COGS and valuation)
- **Stakeholder**: Inventory Manager (may view cost data in UI)
- **Stakeholder**: Finance Team (relies on accurate cost data)

## Preconditions
- Inventory Item or Product entity exists and can be uniquely identified
- Database schema migration tooling is in place (e.g., Liquibase, Flyway)
- Repository pattern is established in the codebase

## Functional Behavior

### 1. Data Model Extension
- Extend the existing `Product` or `InventoryItem` entity to include three new cost fields:
  - `standardCost` (Decimal/Money)
  - `lastCost` (Decimal/Money)
  - `averageCost` (Decimal/Money)

### 2. Field Specifications
- **Data Type**: All cost fields must be `DECIMAL` with precision sufficient for 4 decimal places
  - Recommended: `DECIMAL(19,4)` or equivalent for the database column
  - Java: Use `BigDecimal` for all cost fields
- **Nullable**: All three fields should allow NULL until first populated
  - Alternative: Default to `0.0000` if business rules require non-null
- **Indexed**: Consider adding database index on `averageCost` for frequent queries

### 3. Basic CRUD Operations
- **Create**: When a new inventory item is created, initialize cost fields (see Open Questions for default values)
- **Read**: Provide repository methods to retrieve item with cost data
- **Update**: Support updating cost fields via service layer (with authorization checks)
- **Audit**: Every cost change must trigger a domain event for audit purposes

### 4. API Endpoints (REST)
Expose endpoints for cost data access:

- `GET /api/inventory/items/{itemId}/costs`
  - Returns current cost values for an item
  - Response:
    ```json
    {
      "itemId": "uuid",
      "standardCost": 10.5000,
      "lastCost": 12.0000,
      "averageCost": 11.2500,
      "lastUpdated": "2026-01-13T00:00:00Z"
    }
    ```

- `PUT /api/inventory/items/{itemId}/costs/standard`
  - Manually update Standard Cost (requires authorization)
  - Request body: `{ "standardCost": 15.0000, "reason": "Annual cost review" }`
  - Only authorized roles can call this endpoint

- `GET /api/inventory/items/costs`
  - Bulk retrieval for multiple items (query param: `itemIds`)
  - Supports pagination

### 5. Domain Events
Publish events when cost values change:

**Event**: `ItemCostChanged`
```json
{
  "eventId": "uuid",
  "timestamp": "2026-01-13T00:00:00Z",
  "itemId": "uuid",
  "costType": "STANDARD" | "LAST" | "AVERAGE",
  "oldValue": 10.0000,
  "newValue": 12.5000,
  "changedBy": "user:john.doe" | "system",
  "reason": "Manual adjustment" | "Purchase order received"
}
```

## Alternate / Error Flows

### Error - Invalid Cost Value
- **Trigger**: Attempt to set a cost to a negative value or non-numeric value
- **Response**: Return HTTP 400 Bad Request with error message
- **Action**: Do not update the cost field

### Error - Unauthorized Update
- **Trigger**: User without proper role attempts to update Standard Cost
- **Response**: Return HTTP 403 Forbidden
- **Action**: Log the unauthorized attempt

### Error - Item Not Found
- **Trigger**: API call for non-existent item ID
- **Response**: Return HTTP 404 Not Found
- **Action**: No state change

## Business Rules
1. All cost values must be stored with **4 decimal places** precision
2. Cost values **cannot be negative** (validation required)
3. Only **Standard Cost** can be manually updated by authorized users
4. **Last Cost** and **Average Cost** are reserved for system updates only (future story will implement logic)
5. Cost field updates must be **auditable** via domain events
6. Initial cost values must follow the pattern defined in clarification (pending decision)

## Data Requirements

### Database Schema Changes

**Table**: `product` (or `inventory_item` - depends on existing schema)

Add columns:
```sql
ALTER TABLE product
ADD COLUMN standard_cost DECIMAL(19, 4) DEFAULT 0.0000,
ADD COLUMN last_cost DECIMAL(19, 4) DEFAULT 0.0000,
ADD COLUMN average_cost DECIMAL(19, 4) DEFAULT 0.0000,
ADD COLUMN cost_last_updated TIMESTAMP;

-- Optional: Add index for query performance
CREATE INDEX idx_product_average_cost ON product(average_cost);
```

### Entity Class Changes

**Java Entity**: `ProductEntity` (or `InventoryItem`)

```java
@Entity
@Table(name = "product")
public class ProductEntity {
    // ... existing fields ...
    
    @Column(name = "standard_cost", precision = 19, scale = 4)
    private BigDecimal standardCost;
    
    @Column(name = "last_cost", precision = 19, scale = 4)
    private BigDecimal lastCost;
    
    @Column(name = "average_cost", precision = 19, scale = 4)
    private BigDecimal averageCost;
    
    @Column(name = "cost_last_updated")
    private LocalDateTime costLastUpdated;
    
    // Getters and Setters
}
```

### Repository Interface

```java
public interface ProductRepository extends JpaRepository<ProductEntity, Long> {
    // Existing methods...
    
    @Query("SELECT p FROM ProductEntity p WHERE p.id IN :ids")
    List<ProductEntity> findByIdIn(@Param("ids") List<Long> ids);
}
```

## Acceptance Criteria

### Scenario 1: Database Schema Includes Cost Fields
- **Given** the database migration scripts have been applied
- **When** I query the `product` table schema
- **Then** I should see columns `standard_cost`, `last_cost`, `average_cost` with DECIMAL(19,4) type
- **And** I should see column `cost_last_updated` with TIMESTAMP type

### Scenario 2: Create New Item with Default Costs
- **Given** the inventory service is running
- **When** I create a new inventory item via POST `/api/inventory/items`
- **Then** the new item should have cost fields initialized to default values (0.0000 or NULL, per clarification)
- **And** the response should include the cost fields in the JSON

### Scenario 3: Retrieve Item Costs
- **Given** an inventory item exists with ID "12345"
- **And** the item has `standardCost` = 10.5000, `lastCost` = 12.0000, `averageCost` = 11.2500
- **When** I call GET `/api/inventory/items/12345/costs`
- **Then** I receive HTTP 200 OK
- **And** the response body contains the cost values with 4 decimal places

### Scenario 4: Manually Update Standard Cost (Authorized)
- **Given** an inventory item with `standardCost` = 10.0000
- **And** I am authenticated as an authorized Inventory Manager
- **When** I call PUT `/api/inventory/items/{itemId}/costs/standard` with body `{"standardCost": 15.0000}`
- **Then** I receive HTTP 200 OK
- **And** the item's `standardCost` is updated to 15.0000
- **And** an `ItemCostChanged` event is published with `costType` = "STANDARD"

### Scenario 5: Reject Negative Cost Value
- **Given** an inventory item with `standardCost` = 10.0000
- **When** I attempt to update `standardCost` to -5.0000
- **Then** I receive HTTP 400 Bad Request
- **And** the error message indicates "Cost value cannot be negative"
- **And** the `standardCost` remains 10.0000

### Scenario 6: Reject Unauthorized Update
- **Given** an inventory item with `standardCost` = 10.0000
- **And** I am authenticated as a user without Inventory Manager role
- **When** I attempt to call PUT `/api/inventory/items/{itemId}/costs/standard`
- **Then** I receive HTTP 403 Forbidden
- **And** the `standardCost` remains unchanged

### Scenario 7: Bulk Retrieve Costs for Multiple Items
- **Given** three inventory items with IDs "A", "B", "C" exist
- **When** I call GET `/api/inventory/items/costs?itemIds=A,B,C`
- **Then** I receive HTTP 200 OK
- **And** the response contains cost data for all three items

## Audit & Observability

### Domain Events
- Publish `ItemCostChanged` event to the event bus for every cost modification
- Event payload must include old value, new value, cost type, and actor

### Logging
- Log all cost update attempts (success and failure) at INFO level
- Log validation errors at WARN level
- Include item ID, user ID, and timestamp in all log entries

### Metrics
- Counter: `inventory.cost.updates.total` (labels: `cost_type`, `status`)
- Histogram: `inventory.cost.update.duration` (time to process update)
- Gauge: `inventory.items.with_costs.total` (count of items with non-zero costs)

## Open Questions
1. **Initial Cost Values**: Should new items default to 0.0000 or NULL? (Blocked - awaiting clarification)
2. **Authorization Role**: What is the exact role name for Standard Cost updates? (Blocked - awaiting clarification)
3. **Event Bus**: Which event bus technology? (Kafka, RabbitMQ, internal Spring Events?)
4. **Existing Schema**: Does `ProductEntity` or `InventoryItem` entity exist? What's the current structure?

## Implementation Notes
- **Do NOT implement cost calculation logic** - that belongs to the Accounting domain story
- **Do NOT implement Purchase Order event handling** - that belongs to the Accounting domain story
- Focus solely on **data persistence and basic CRUD** operations
- The Accounting service will call Inventory APIs or produce events to update Last/Average costs

## Testing Requirements
- Unit tests for entity validation
- Integration tests for repository operations
- API tests for all endpoints (success and error cases)
- Event publishing tests (verify event payload)
- Authorization tests (verify role-based access control)

## Dependencies
- Requires database migration tooling
- Requires event bus infrastructure (if not already present)
- May require updates to security configuration for new endpoints

---

## Original Story Reference
This story is split from #196: "Cost: Maintain Standard/Last/Average Cost with Audit"

**Scope of this story**: Data model and storage layer only (Inventory domain responsibility)

**Out of scope**: Cost calculation logic, Purchase Order event handling (Accounting domain responsibility)
