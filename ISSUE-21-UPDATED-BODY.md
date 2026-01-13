# Story Refinement Complete

## 🏷️ Labels (Proposed)

### Required
- type:story
- domain:workexec
- status:ready-for-dev

### Recommended
- agent:story-authoring
- agent:workexec

---
**Rewrite Variant:** workexec-structured
---

## Story Intent

As a POS Clerk, I need a robust mechanism to initiate a sales transaction by creating a persistent sales cart and populating it with specific products and services for a customer, so that I can accurately build an order for quoting and final sale.

## Actors & Stakeholders

- **POS Clerk (Primary Actor):** The user operating the POS terminal to serve a customer.
- **System (Primary System):** The Point of Sale system responsible for managing the sales order lifecycle.
- **Pricing Service (Secondary Domain):** An external service that provides authoritative pricing information for SKUs and service codes.
- **Inventory Service (Secondary Domain):** An external service that provides stock availability information for SKUs.
- **CRM Service (Secondary Domain):** A service providing customer and vehicle context.

## Preconditions

1. The POS Clerk is authenticated and has the necessary permissions to create sales orders.
2. The POS terminal is active and connected to the network.
3. A customer and/or vehicle context has been established in the session (or an anonymous session is explicitly permitted).

## Functional Behavior

1.  **Cart Initialization:**
    - The POS Clerk triggers the "Create New Order" action.
    - The System generates a new, unique `SalesOrder` entity with an initial state of `DRAFT`.
    - The `SalesOrder` is associated with the current customer/vehicle context (if provided) or allows anonymous cart creation.
    - The System presents the newly created empty cart to the Clerk.

2.  **Adding Items:**
    - The Clerk provides a product SKU or service code and a desired quantity.
    - The System sends a request to the **Pricing Service** to retrieve the current unit price for the given item.
    - The System (optionally, based on configuration) sends a request to the **Inventory Service** to check availability.
    - The System adds a new `SalesOrderLine` to the `SalesOrder` containing the SKU, quantity, and the retrieved unit price.
    - The System recalculates the `SalesOrder` subtotal (excluding taxes and discounts, which are handled by other processes).
    - The updated cart and its new total are displayed to the Clerk.

3.  **Linking Estimate/Workorder (Optional):**
    - The Clerk may link an existing estimate or workorder to the cart.
    - When linked, the System **merges** items from the source document into the current cart.
    - **Merge Rules:**
      - Items with the same SKU/service code and same unit price have their quantities merged.
      - Items with the same SKU/service code but different price or attributes are added as separate line items.
      - The System preserves source references: `sourceType` (ESTIMATE | WORKORDER), `sourceId`, and `sourceLineId`.
      - Re-linking the same source document is idempotent (no duplicate re-adds).

4.  **Updating & Removing Items:**
    - The Clerk can select an existing `SalesOrderLine` to change its quantity or remove it entirely.
    - The System updates or deletes the line item and recalculates the order subtotal accordingly.

## Alternate / Error Flows

- **Invalid SKU/Service Code:** If the Clerk enters an identifier that is not found by the Pricing Service, the System must reject the addition and display a "Product not found" error.
- **Pricing Service Unavailable:** 
  - **Primary Behavior:** If the Pricing Service does not respond, the System attempts to use a cached price if available within TTL (60 seconds).
  - **Cache Policy:** Cached prices are marked as `STALE` when served from cache. Cache key: `productId + customerAccountId + priceListId + currency`.
  - **Manual Fallback:** If no valid cached price exists, the System allows manual price entry for users with `ENTER_MANUAL_PRICE` permission. Manual prices must be flagged (`priceSource = MANUAL`), include a `reasonCode`, and are audited.
  - **Blocked Behavior:** Silent fallback to zero or last-known price without marking `STALE` is explicitly disallowed.
- **Inventory Insufficient:** 
  - **Default Behavior:** When Inventory reports insufficient stock, the System allows the item to be added to the cart.
  - The System sets `fulfillmentStatus = BACKORDER` on the line item.
  - The System displays a clear warning to the Clerk: "Insufficient stock — item will be backordered."
  - **Configuration:** The system supports a configuration policy `inventoryInsufficientPolicy = BLOCK | WARN_AND_BACKORDER` (default: `WARN_AND_BACKORDER`).
  - Per-item override is allowed (e.g., regulated items may be configured to `BLOCK`).

## Business Rules

- Each `SalesOrder` must have a globally unique identifier (`orderId`).
- The price on a `SalesOrderLine` is captured at the moment it is added (`price-at-time-of-add`). It is not dynamically updated unless the item is explicitly refreshed or re-added by the user.
- The `SalesOrder` subtotal must be the deterministic sum of (`line.quantity` * `line.unitPrice`) for all lines.
- The system must not allow a `SalesOrder` to be created without a valid context (e.g., Clerk ID, Terminal ID).
- **Anonymous Carts:** Anonymous carts (without `customerId`) are valid with the following restrictions:
  - **Allowed:** Add/remove items, inventory checks, pricing display (best-effort), backorder flags, link estimate/workorder (if accessible).
  - **Restricted until customer is set:** Promotions requiring customer eligibility, charge account/invoicing, tax finalization, credit/PO enforcement, order submission.
  - **Transition Rule:** Setting `customerId` later re-evaluates pricing, taxes, promotions, and policies.

## Data Requirements

**`SalesOrder` Entity (owned by `domain:workexec`)**
- `orderId`: string (PK, unique)
- `customerId`: string (FK, nullable - supports anonymous carts)
- `vehicleId`: string (FK, nullable)
- `clerkId`: string (FK)
- `terminalId`: string (FK)
- `status`: enum (`DRAFT`, `QUOTED`, `COMPLETED`, `VOIDED`)
- `subtotal`: decimal
- `createdAt`: timestamp
- `updatedAt`: timestamp

**`SalesOrderLine` Entity (owned by `domain:workexec`)**
- `orderLineId`: string (PK, unique)
- `orderId`: string (FK)
- `itemSku`: string
- `itemDescription`: string
- `quantity`: integer
- `unitPrice`: decimal (Price captured at time of add)
- `fulfillmentStatus`: enum (`AVAILABLE`, `BACKORDER`) - tracks inventory availability
- `priceSource`: enum (`PRICING_SERVICE`, `CACHE`, `MANUAL`) - tracks where price came from
- `reasonCode`: string (nullable, required when priceSource = MANUAL)
- `sourceType`: enum (`ESTIMATE`, `WORKORDER`, nullable) - for linked items
- `sourceId`: string (nullable) - reference to source document
- `sourceLineId`: string (nullable) - reference to source line item

## Acceptance Criteria

**Scenario 1: Create a new, empty sales cart**
- **Given** I am a logged-in POS Clerk
- **When** I initiate a "Create New Order" action
- **Then** the System creates a new `SalesOrder` with a unique `orderId` and a status of `DRAFT`
- **And** the order's subtotal is 0.00
- **And** the `customerId` may be null (anonymous cart).

**Scenario 2: Add a valid and available item to the cart**
- **Given** I have a `DRAFT` sales order
- **When** I add quantity `2` of a valid SKU `ABC-123` with a unit price of `10.50`
- **Then** the System adds a `SalesOrderLine` to the order for SKU `ABC-123` with quantity `2` and unit price `10.50`
- **And** the `SalesOrder` subtotal is recalculated to `21.00`.

**Scenario 3: Attempt to add an item with an invalid SKU**
- **Given** I have a `DRAFT` sales order
- **When** I attempt to add an item with an invalid SKU `INVALID-SKU`
- **Then** the System prevents the item from being added
- **And** displays an error message "Product not found"
- **And** the `SalesOrder` and its subtotal remain unchanged.

**Scenario 4: Update the quantity of an existing item**
- **Given** my cart contains a `SalesOrderLine` for SKU `ABC-123` with quantity `2` and a subtotal of `21.00`
- **When** I update the quantity for SKU `ABC-123` to `3`
- **Then** the corresponding `SalesOrderLine` quantity is updated to `3`
- **And** the `SalesOrder` subtotal is recalculated to `31.50`.

**Scenario 5: Add item with insufficient inventory (backorder)**
- **Given** I have a `DRAFT` sales order
- **When** I add an item for which Inventory Service reports insufficient stock
- **Then** the System adds the item to the cart
- **And** sets `fulfillmentStatus = BACKORDER` on the line item
- **And** displays a warning "Insufficient stock — item will be backordered"
- **And** the item is included in the subtotal calculation.

**Scenario 6: Merge items from estimate into cart**
- **Given** I have a `DRAFT` sales order with SKU `TIRE-001` quantity 2 at price $100
- **And** an estimate exists with SKU `TIRE-001` quantity 3 at price $100 and SKU `OIL-001` quantity 1 at price $25
- **When** I link the estimate to the cart
- **Then** the quantity for `TIRE-001` is merged to 5 (2 + 3)
- **And** `OIL-001` is added as a new line item
- **And** both lines have `sourceType = ESTIMATE`, `sourceId = <estimateId>`, and `sourceLineId` populated.

**Scenario 7: Price unavailable - use cached price**
- **Given** the Pricing Service is unavailable
- **And** a valid cached price exists for SKU `ABC-123` within the 60-second TTL
- **When** I add SKU `ABC-123` to the cart
- **Then** the System uses the cached price
- **And** marks the line item with `priceSource = CACHE`
- **And** displays a warning that the price is from cache (marked STALE).

**Scenario 8: Price unavailable - manual entry with permission**
- **Given** the Pricing Service is unavailable
- **And** no valid cached price exists for SKU `ABC-123`
- **And** I have the `ENTER_MANUAL_PRICE` permission
- **When** I manually enter a price for SKU `ABC-123` with a `reasonCode`
- **Then** the System adds the item with the manual price
- **And** marks the line item with `priceSource = MANUAL`
- **And** logs the manual price entry for audit.

**Scenario 9: Anonymous cart restrictions**
- **Given** I have created an anonymous cart (no `customerId`)
- **When** I attempt to apply a customer-specific promotion
- **Then** the System prevents the promotion from being applied
- **And** displays a message requiring customer assignment for promotions.

## Audit & Observability

- **Event:** `SalesOrderCreated`
  - **Payload:** `orderId`, `clerkId`, `terminalId`, `customerId`, `timestamp`
- **Event:** `SalesOrderLineAdded`
  - **Payload:** `orderId`, `orderLineId`, `itemSku`, `quantity`, `unitPrice`, `priceSource`, `fulfillmentStatus`, `timestamp`
- **Event:** `SalesOrderLineUpdated`
  - **Payload:** `orderId`, `orderLineId`, `newQuantity`, `oldQuantity`, `timestamp`
- **Event:** `SalesOrderLineRemoved`
  - **Payload:** `orderId`, `orderLineId`, `timestamp`
- **Event:** `EstimateLinked`
  - **Payload:** `orderId`, `sourceType`, `sourceId`, `itemsMerged`, `timestamp`
- **Event:** `ManualPriceEntered`
  - **Payload:** `orderId`, `orderLineId`, `itemSku`, `manualPrice`, `reasonCode`, `userId`, `timestamp`

## Resolved Business Decisions

The following decisions were made in response to clarification issue #221:

### 1. Inventory Policy (Insufficient Stock)
**Decision:** **(c) Allow the addition and flag the item for backorder**
- When Inventory reports insufficient stock, the item is added to the cart with `fulfillmentStatus = BACKORDER`.
- A clear warning is displayed to the Clerk.
- Downstream fulfillment steps are blocked until inventory is available.
- **Configuration:** `inventoryInsufficientPolicy = BLOCK | WARN_AND_BACKORDER` (default: `WARN_AND_BACKORDER`).
- Per-item overrides are supported (e.g., regulated items may use `BLOCK`).

### 2. Work Order Linking Behavior
**Decision:** **(b) Merge items into the current cart**
- Items from the source document (estimate/workorder) are added to the existing cart.
- **Duplicate Detection:** Same SKU/service + same unit price → merge quantities.
- **Different Price/Attributes:** Same SKU but different price → add as separate line item.
- **Source References:** Preserve `sourceType`, `sourceId`, and `sourceLineId` on merged lines.
- **Idempotency:** Re-linking the same source is idempotent (no duplicate re-adds).

### 3. Anonymous Cart Support
**Decision:** **Yes, anonymous carts are valid**
- **Allowed Actions:** Add/remove items, inventory checks, pricing display, backorder flags, link estimate/workorder.
- **Restricted Actions:** Customer-specific promotions, charge accounts, invoicing, tax finalization, credit/PO enforcement, order submission.
- **Transition Rule:** Setting `customerId` later triggers re-evaluation of pricing, taxes, promotions, and policies.

### 4. Pricing Service Dependency
**Decision:** **Soft dependency with bounded fallback**
- **Primary:** Use Pricing Service when available.
- **Fallback (TTL 60s):** Use cached price if available within TTL. Mark price as `STALE`.
- **Cache Key:** `productId + customerAccountId + priceListId + currency`.
- **Manual Entry:** If no cache available, allow manual price entry with `ENTER_MANUAL_PRICE` permission. Requires `reasonCode` and audit logging. Price flagged as `priceSource = MANUAL`.
- **Disallowed:** Silent fallback to zero or last-known price without marking `STALE`.

---

## Original Story (Unmodified – For Traceability)
# Issue #21 — [BACKEND] [STORY] Order: Create Sales Order Cart and Add Items

## Current Labels
- backend
- story-implementation
- user

## Current Body
## Backend Implementation for Story

**Original Story**: [STORY] Order: Create Sales Order Cart and Add Items

**Domain**: user

### Story Description

/kiro
# User Story

## Narrative
As a **POS Clerk**, I want to create a cart and add products/services so that I can quote and sell efficiently at the counter.

## Details
- Create cart for a customer/vehicle context.
- Add items by SKU/service code; set quantities.
- Support linking to an existing estimate/workorder as the source of items (optional).

## Acceptance Criteria
- Cart created with unique orderId.
- Items can be added/updated/removed.
- Totals recalc deterministically.
- Audit changes.

## Integrations
- Pull product pricing from product/pricing service; optionally check availability from inventory.

## Data / Entities
- PosOrder, PosOrderLine, PriceQuote, TaxQuote (hook), AuditLog

## Classification (confirm labels)
- Type: Story
- Layer: Experience
- domain :  Point of Sale

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
