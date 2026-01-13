## 🏷️ Labels (Proposed)
### Required
- type:story
- domain:inventory
- status:needs-review

### Recommended
- agent:inventory
- agent:story-authoring

---
**Rewrite Variant:** inventory-flexible (clarifications integrated)
---

## Story Intent
**As a** Dispatcher,
**I want** the system to automatically generate an optimized pick list when parts for a work order are confirmed,
**so that** Mechanics have a clear, efficient, and ordered set of tasks for gathering the correct parts, which minimizes errors and accelerates vehicle service preparation.

## Actors & Stakeholders
- **Dispatcher (Primary Actor):** The user who oversees the allocation of work and parts, and relies on this process to be efficient.
- **Mechanic (End User):** The user who will consume the generated pick list to physically retrieve parts from storage.
- **System (Inventory Domain):** The system of record for parts, locations, and the pick list itself. This is the domain responsible for implementing this story.
- **System (Work Execution Domain):** The upstream system that manages work orders and provides the "parts reservation confirmed" trigger event.

## Preconditions
1. A Work Order exists in the Work Execution system.
2. The specific products and quantities required for the Work Order have been successfully reserved in the Inventory system.
3. Products within the inventory system have assigned storage locations (e.g., aisle, rack, bin).
4. Storage locations have layout ordering attributes (zoneOrder, aisleOrder, rackOrder, binOrder) defined.

## Functional Behavior
### Trigger
The process is initiated when the Inventory system receives a `WorkOrderPartsReservationConfirmed` event from the Work Execution domain. This event must contain the `workOrderId` and a list of reserved `productId`s and `quantity`s.

### Happy Path (Success Flow)
1. Upon receiving the trigger event, the system validates its contents.
2. The system creates a new `PickList` entity, associating it with the `workOrderId`.
3. For each unique item in the confirmed reservation list, the system:
    - Creates a corresponding `PickTask` entity.
    - Populates the `PickTask` with the `productId`, required `quantity`, and a suggested storage location using the location selection hierarchy (see Business Rules below).
    - Assigns a priority using the priority calculation formula (base work order priority + inventory modifiers, capped at MAX_PRIORITY).
    - Assigns a due time calculated as `workOrder.scheduledStartAt − pickLeadTimeBuffer` (default buffer: 30 minutes).
4. All newly created `PickTask`s are associated with the parent `PickList`.
5. The system sorts the collection of `PickTask`s within the `PickList` based on the deterministic warehouse layout sorting algorithm (zoneOrder → aisleOrder → rackOrder → binOrder → locationCode).
6. The `PickList` is finalized and its status is set to `ReadyToPick`.
7. A `PickListCreated` event is published, containing the `pickListId` and `workOrderId`, for downstream consumers (e.g., mobile apps, printing services).

## Alternate / Error Flows
- **Item with No Storage Location:** If a reserved product does not have a defined storage location in the inventory system, the corresponding `PickTask` is still created but its status is set to `NeedsReview`. The parent `PickList` may be held in a `Draft` state until all tasks are actionable. A system alert is raised for manual intervention.
- **Invalid Trigger Event:** If the incoming `WorkOrderPartsReservationConfirmed` event is malformed or missing essential data (e.g., `workOrderId`), the event is rejected and moved to a dead-letter queue (DLQ) for investigation. An error is logged.
- **Partial Fulfillment Required:** If no single location can fulfill the required quantity, the system suggests the best primary location using the location selection hierarchy, and generates additional pick tasks for the remaining quantity using the same selection rules.

## Business Rules

### Pick List Generation
- A `PickList` must be generated for every `WorkOrderPartsReservationConfirmed` event.
- A `PickTask` represents a single action: retrieving a specific quantity of one product from one location.
- All quantities and products on the `PickList` must exactly match the confirmed reservation.

### Priority Determination
- **Authority and Inheritance:** Base priority and due time are inherited from the Work Order SLA.
- **Inventory-Specific Modifiers:** Inventory applies bounded adjustments (only if applicable):
  - **Stock risk** (low on-hand for the item): +1 priority
  - **Backorder resolution** (this pick unblocks waiting work): +1 priority
  - **Critical part type** (safety/immobilizing component): +1 priority
- **Formula:** `EffectivePriority = min(workOrderPriority + inventoryModifiers, MAX_PRIORITY)`
- **Rationale:** WorkExec defines urgency; Inventory refines execution sequencing.

### Due Time Determination
- Default: `pickDueAt = workOrder.scheduledStartAt − pickLeadTimeBuffer`
- `pickLeadTimeBuffer` default: **30 minutes**
- If no scheduled start: inherit `workOrder.dueAt`
- Inventory **must not** delay beyond the work order's SLA.

### Location Selection Hierarchy (Deterministic)
When multiple storage locations exist for a product, apply this strict decision hierarchy:
1. **Dedicated Pick Zone:** If any location is flagged `isPickZone = true` and has sufficient quantity, select it.
2. **FEFO / FIFO Compliance:** If lot/expiry controlled, select location with earliest expiry or receipt date.
3. **Sufficient Quantity:** Prefer a single location that can fulfill the entire required quantity.
4. **Proximity in Layout:** Lowest `(zoneOrder, aisleOrder, rackOrder, binOrder)`
5. **Highest On-Hand Quantity:** Final tie-breaker.

### Location Selection Rules
- Do not split picks unnecessarily.
- Do not pick from reserve/bulk locations unless no pick-zone stock exists.
- For partial fulfillment: suggest the best primary location, generate additional pick tasks for remaining quantity.

### Sorting Algorithm (Deterministic, Layout-Aware)
The system uses a deterministic, layout-aware sort based on pre-defined warehouse layout:
- **Required model:** Zone → Aisle → Rack → Bin
- **Location attributes:** Each location stores `zoneOrder`, `aisleOrder`, `rackOrder`, `binOrder`
- **Sorting algorithm (stable, in order):**
  1. `zoneOrder ASC`
  2. `aisleOrder ASC`
  3. `rackOrder ASC`
  4. `binOrder ASC`
  5. `locationCode ASC` (final tie-breaker)

### Explicit Non-Goals (v1)
- No shortest-path optimization
- No picker-specific routing
- No dynamic reordering mid-pick

**Rationale:** Deterministic ordering is auditable, explainable, and sufficient at launch. Route optimization can be added later without breaking contracts.

## Data Requirements

### `PickList` Entity
| Field | Type | Description | Notes |
|---|---|---|---|
| `pickListId` | UUID | Primary key for the pick list. | |
| `workOrderId` | UUID | Foreign key to the Work Order. | |
| `status` | Enum | The current state of the list (e.g., `Draft`, `ReadyToPick`, `InProgress`). | |
| `createdAt` | Timestamp | Timestamp of when the list was created. | |

### `PickTask` Entity
| Field | Type | Description | Notes |
|---|---|---|---|
| `pickTaskId` | UUID | Primary key for the pick task. | |
| `pickListId` | UUID | Foreign key to the parent `PickList`. | |
| `productId` | UUID | Foreign key to the Product being picked. | |
| `quantityRequired` | Integer | The number of units to pick. | |
| `suggestedLocationId` | UUID | Foreign key to the suggested storage location. | Determined by location selection hierarchy |
| `sortOrder` | Integer | The position of this task in the picking sequence. | Determined by layout-aware sorting algorithm |
| `priority` | Integer | The priority of the task. | Calculated: base + modifiers, capped at MAX_PRIORITY |
| `dueTime` | Timestamp | The time by which the task should be completed. | `workOrder.scheduledStartAt − pickLeadTimeBuffer` |
| `status` | Enum | The current state of the task (e.g., `Pending`, `NeedsReview`, `Picked`). | |

### `StorageLocation` Entity (Required Attributes)
| Field | Type | Description | Notes |
|---|---|---|---|
| `locationId` | UUID | Primary key for the storage location. | |
| `locationCode` | String | Human-readable location identifier. | |
| `zoneOrder` | Integer | Sort order for zone. | Used in sorting algorithm |
| `aisleOrder` | Integer/String | Sort order for aisle (normalized). | Used in sorting algorithm |
| `rackOrder` | Integer | Sort order for rack. | Used in sorting algorithm |
| `binOrder` | Integer | Sort order for bin. | Used in sorting algorithm |
| `isPickZone` | Boolean | Flag indicating if this is a dedicated pick zone. | Used in location selection hierarchy |
| `onHandQuantity` | Integer | Current quantity at this location. | Used in location selection |

## Acceptance Criteria
**Scenario 1: Successful Pick List Generation**
- **Given** a `WorkOrderPartsReservationConfirmed` event is received for a work order with three reserved products.
- **And** all three products have defined storage locations.
- **When** the system processes the event.
- **Then** a new `PickList` is created with the status `ReadyToPick`.
- **And** the `PickList` contains exactly three `PickTask`s, one for each product.
- **And** each `PickTask` contains the correct product ID, quantity, and a suggested location selected using the location selection hierarchy.
- **And** each `PickTask` has a priority calculated using the priority formula and a due time calculated from the work order's scheduled start time.
- **And** the `PickTask`s are sorted according to the deterministic layout-aware sorting algorithm (zoneOrder → aisleOrder → rackOrder → binOrder → locationCode).
- **And** a `PickListCreated` event is published.

**Scenario 2: Reserved Item is Missing a Storage Location**
- **Given** a `WorkOrderPartsReservationConfirmed` event is received.
- **And** one of the reserved products does not have an assigned storage location.
- **When** the system processes the event.
- **Then** a new `PickList` is created.
- **And** the `PickTask` for the item without a location is created with a status of `NeedsReview`.
- **And** a system alert is logged or sent, indicating manual action is required.

**Scenario 3: Invalid Event Received**
- **Given** the system receives a `WorkOrderPartsReservationConfirmed` event with a null `workOrderId`.
- **When** the event is processed.
- **Then** the event is rejected.
- **And** an error is logged detailing the validation failure.
- **And** the invalid event is sent to a dead-letter queue.

**Scenario 4: Priority Calculation with Modifiers**
- **Given** a `WorkOrderPartsReservationConfirmed` event is received.
- **And** the work order has a base priority of 5.
- **And** one product has low stock (stock risk modifier applies).
- **When** the system creates the pick task for that product.
- **Then** the pick task priority is calculated as `min(5 + 1, MAX_PRIORITY)` = 6 (assuming MAX_PRIORITY is not exceeded).

**Scenario 5: Location Selection from Pick Zone**
- **Given** a product exists in multiple storage locations.
- **And** one location has `isPickZone = true` with sufficient quantity.
- **And** another location has higher quantity but `isPickZone = false`.
- **When** the system selects the suggested location.
- **Then** the pick zone location is selected (following the location selection hierarchy).

**Scenario 6: Pick Task Sorting by Layout**
- **Given** a pick list with three tasks for locations: Zone 2 Aisle A, Zone 1 Aisle B, Zone 1 Aisle A.
- **When** the system sorts the pick tasks.
- **Then** the tasks are ordered: Zone 1 Aisle A, Zone 1 Aisle B, Zone 2 Aisle A (sorted by zoneOrder then aisleOrder).

## Audit & Observability
- **Audit Trail:** Every `PickList` creation and state change must be logged with user/system attribution, `workOrderId`, and timestamp for full traceability.
- **Metrics:**
    - `picklists_created_total`: Counter for the number of pick lists generated.
    - `pick_tasks_per_list`: Histogram of tasks per pick list.
    - `picklist_generation_duration_seconds`: Histogram measuring the time from event receipt to `PickListCreated` publication.
    - `pick_task_priority_modifiers_applied`: Counter for each type of priority modifier applied (stock risk, backorder, critical part).
    - `location_selection_by_rule`: Counter for which rule in the location selection hierarchy was used.
- **Events:** The system must emit a `PickListCreated` event on a dedicated message topic upon successful generation.

## Original Story (Unmodified – For Traceability)
# Issue #28 — [BACKEND] [STORY] Fulfillment: Create Pick List / Pick Tasks for Workorder

## Current Labels
- backend
- story-implementation
- user

## Current Body
## Backend Implementation for Story

**Original Story**: [STORY] Fulfillment: Create Pick List / Pick Tasks for Workorder

**Domain**: user

### Story Description

/kiro
# User Story
## Narrative
As a **Dispatcher**, I want a pick list so that mechanics know what to pull for a workorder.

## Details
- Pick tasks include product, qty, suggested storage locations, priority, and due time.

## Acceptance Criteria
- Pick tasks generated when reservation confirmed.
- Sorted by route/location.
- Printable or mobile view.

## Integrations
- Workexec provides workorder context; shopmgr may surface to mechanics.

## Data / Entities
- PickTask, PickList, RouteHint

## Classification (confirm labels)
- Type: Story
- Layer: Domain
- Domain: Inventory Management


### Backend Requirements

- Implement Spring Boot microservices
- Create REST API endpoints
- Implement business logic and data access
- Ensure proper security and validation

### Technical Stack

- Spring Boot 3.2.6
- Java 21
- Spring Data JPA
- PostgreSQL/MySQL

---
*This issue was automatically created by the Durion Workspace Agent*
