# AGENT_GUIDE.md — Inventory Domain

---

## Purpose

The Inventory domain manages the authoritative data and business logic for inventory items, including product master references, stock quantities, costing, storage locations, and fulfillment-related inventory transactions. It ensures accurate, auditable, and consistent inventory state to support sales, fulfillment, accounting, and operational workflows within the POS ecosystem.

---

## Domain Boundaries

- **Owned Data:** Inventory items, stock quantities, costs (standard, last, average), storage locations, inventory ledger entries, cycle count plans, consumption and return transactions.
- **Authoritative Logic:** Cost calculations (Weighted Average Cost), stock level adjustments, inventory transactions (consume, return, transfer), storage location hierarchy.
- **Exclusions:** Product master data (owned by Product domain), accounting calculations (consume cost is authoritative input only), external distributor/manufacturer feed ingestion (integration service responsibility).
- **Integration:** Consumes product master references, purchase order events, work order data; publishes inventory events for consumption by Accounting, Work Execution, and other downstream systems.

---

## Key Entities / Concepts

- **InventoryItem:** Represents stock-keeping units (SKUs) with associated costs (`standardCost`, `lastCost`, `averageCost`) and quantities.
- **ItemCostAudit:** Immutable audit log capturing all cost changes with metadata (actor, reason, timestamps).
- **StorageLocation:** Physical locations within sites (Floor, Shelf, Bin, etc.) organized hierarchically; supports capacity and status management.
- **InventoryLedger:** Immutable transaction log recording all inventory movements and adjustments.
- **CycleCountPlan:** Scheduled plans for physical inventory counts by location and zone.
- **InventoryReturn:** Records of returned items from completed work orders, including reason codes.
- **WorkorderPartsConsumed Event:** Signals consumption of picked inventory items tied to a work order.
- **Cost Types:** 
  - `Standard Cost` (manual, reference only)
  - `Last Cost` (system-updated on purchase receipt)
  - `Average Cost` (Weighted Average Cost, authoritative for valuation)

---

## Invariants / Business Rules

- **Cost Management:**
  - `Standard Cost` is manually updated only by authorized users with audit and reason code.
  - `Last Cost` and `Average Cost` are system-managed, updated atomically on purchase order receipt events.
  - Costs are stored with minimum 4 decimal places precision.
  - Initial cost fields are `null` on new items; zero is disallowed to avoid ambiguity.
  - Weighted Average Cost formula is strictly enforced and authoritative.
  - Manual edits to `Last Cost` or `Average Cost` are prohibited.
- **Inventory Transactions:**
  - Consumption of picked items is atomic, immutable, and tied to valid work orders.
  - Returns to stock require reason codes and are only allowed for completed/closed work orders.
  - Storage location deactivation requires empty location or atomic stock transfer to a valid destination.
- **Storage Locations:**
  - Barcodes are unique per site.
  - Location hierarchy must be acyclic.
  - Locations have statuses (`Active`, `Inactive`) controlling usage.
- **Cycle Count Plans:**
  - Must be associated with one location and at least one zone.
  - Scheduled dates cannot be in the past.
  - Once started, plan scope is immutable.
- **Permissions:**
  - Manual `Standard Cost` updates require `inventory.cost.standard.update`.
  - Consumption and return operations require appropriate domain permissions.
  - Lifecycle state changes on products require product domain permissions.
- **Error Handling:**
  - Invalid cost values on purchase receipt reject cost updates.
  - Transactions involving cost updates and audit logs are atomic.
  - Unauthorized or invalid manual cost updates are rejected with validation errors.

---

## Events / Integrations

- **Consumed Events:**
  - `WorkorderPartsConsumed` emitted after successful consumption of picked items.
  - `Inventory.ItemReturnedToStock` emitted after successful return transactions.
- **Consumed Inputs:**
  - `Purchase Order Received` events trigger cost updates (`Last Cost`, `Average Cost`).
  - Product master references consumed from Product domain.
  - Workorder states and line items consumed for validation.
- **Audit Events:**
  - Cost changes generate `ItemCostAudit` entries.
  - Storage location changes and cycle count plan lifecycle changes generate audit logs.
- **Downstream Consumers:**
  - Accounting domain consumes authoritative cost data.
  - Work Execution consumes inventory events for job costing and fulfillment.
  - Reporting and auditing systems consume audit logs and events.

---

## API Expectations (High-Level)

- **Cost Management:**
  - Endpoints to manually update `Standard Cost` with permission checks and reason code validation.
  - Event-driven updates for `Last Cost` and `Average Cost` (no manual API).
- **Inventory Consumption:**
  - `POST /v1/inventory/consume` to consume picked items against a workorder.
  - Validation of workorder state, picked status, quantities, and permissions.
- **Inventory Returns:**
  - Endpoint to return unused items to stock with mandatory reason codes.
- **Cycle Count Plans:**
  - Endpoints to create, update, and query cycle count plans by location and zone.
- **Storage Locations:**
  - CRUD endpoints for storage locations with hierarchy management.
  - Deactivation endpoint requiring stock transfer if non-empty.
- **Availability Queries:**
  - Read-only endpoints to expose on-hand and available-to-promise quantities by product and location.
- **Product Master:**
  - Product creation, update, lifecycle state management handled by Product domain APIs (Inventory references productId).

> **Note:** Specific API paths and contracts are TBD and must align with domain conventions and frontend requirements.

---

## Security / Authorization Assumptions

- Authentication is enforced via OAuth2/JWT or equivalent.
- Role-based and permission-based access control:
  - `inventory.cost.standard.update` required for manual standard cost updates.
  - Permissions required for consumption, return, and storage location management.
- Unauthorized attempts result in `403 Forbidden`.
- Validation errors return `400 Bad Request` with descriptive messages.
- Audit logs capture actor identity for all changes.
- Sensitive operations are logged with sufficient context for forensic analysis.

---

## Observability (Logs / Metrics / Tracing)

- **Logging:**
  - Structured logs for all cost update transactions (success and failure).
  - Logs for inventory consumption and return transactions with correlation IDs.
  - Error logs with full stack traces and request context.
  - Audit trail logs for storage location and cycle count plan changes.
- **Metrics:**
  - Counters for cost updates by type and outcome (`inventory.cost.updates.count`).
  - Counters for manual adjustment rejections (`inventory.cost.manual_adjustments.rejected.count`).
  - Timers for cost calculation durations (`inventory.cost.calculation.duration.ms`).
  - Counters for audit log write failures (`inventory.cost.audit.write.failures.count`).
  - Metrics for consumption success/failure and latency.
  - Metrics for return transaction success/failure.
  - Metrics for cycle count plan creations and errors.
- **Tracing:**
  - Distributed tracing for API requests and event processing pipelines.
  - Correlation IDs propagated through event chains for end-to-end visibility.

---

## Testing Guidance

- **Unit Tests:**
  - Validate cost calculation logic, including WAC formula and edge cases (null initial values, zero cost rejection).
  - Permission enforcement for manual cost updates.
  - Validation of input data for consumption, returns, and storage location operations.
- **Integration Tests:**
  - End-to-end scenarios for purchase order receipt triggering cost updates and audit log creation.
  - Consumption of picked items with workorder state validation and ledger entry creation.
  - Return to stock workflows with reason code enforcement.
  - Storage location creation, update, deactivation with stock transfer.
  - Cycle count plan creation and validation of scheduling constraints.
- **Transactionality:**
  - Tests ensuring atomicity of cost updates and audit log writes.
  - Rollback scenarios on audit failure or validation errors.
- **Security Tests:**
  - Unauthorized access attempts rejected.
  - Permission boundary enforcement.
- **Performance Tests:**
  - Load testing for availability queries and batch cost update processing.
- **Error Handling:**
  - Invalid input scenarios produce appropriate error responses.
  - Handling of invalid purchase order costs and duplicate updates.

---

## Common Pitfalls

- **Manual edits to system-managed costs:** Attempting to update `Last Cost` or `Average Cost` manually must be prevented; these fields are event-driven only.
- **Ignoring audit requirements:** All cost changes must be auditable with required metadata; missing audit entries compromise compliance.
- **Non-atomic updates:** Updating costs and audit logs separately risks inconsistent state; always use transactions.
- **Incorrect cost precision:** Monetary values must maintain at least 4 decimal places to avoid rounding errors.
- **Misinterpreting cost ownership:** Inventory domain owns cost data and calculations; accounting domain must not recalculate or override.
- **Cycle count plan scope changes:** Modifying location or zones after plan start violates business rules.
- **Storage location hierarchy cycles:** Must be prevented to avoid infinite loops and data corruption.
- **Returning more items than consumed:** Validation must enforce return quantity limits.
- **Deactivating non-empty storage locations without transfer:** Must be rejected or handled atomically with stock transfer.
- **Incorrect ATP calculations:** ATP excludes expected replenishments in v1; including them leads to inaccurate availability.
- **Unmapped parts in feeds:** Must be tracked and surfaced for operations; ignoring leads to data gaps.
- **Insufficient logging or metrics:** Leads to poor observability and delayed incident response.

---

# End of AGENT_GUIDE.md
