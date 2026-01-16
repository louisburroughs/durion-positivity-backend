# workexec AGENT_GUIDE

## Purpose
The `workexec` domain manages core workflows related to work order execution and customer approvals within the POS system. It handles receiving inventory into staging/quarantine locations, capturing various forms of customer approvals (digital, in-person, partial), managing estimate revisions, and facilitating parts picking by mechanics. The domain ensures authoritative state transitions, immutable audit trails, and integration with inventory, pricing, and audit subsystems.

---

## Domain Boundaries
- **In Scope:**
  - Receiving workflow execution using site-configured default staging and quarantine locations.
  - Capturing customer approvals for work orders and estimates (digital, in-person, partial).
  - Managing approval expiration and alerting.
  - Adding parts and labor to estimates, including price overrides and non-catalog entries.
  - Revising estimates prior to approval.
  - Generating customer-facing estimate summaries.
  - Mechanic picking and confirmation of parts for work orders.

- **Out of Scope:**
  - Configuration of default locations (handled by `domain:location`).
  - Permission enforcement for moving items out of quarantine (handled by `domain:inventory` and `domain:security`).
  - Notification delivery for approval expiration alerts (external notification systems).
  - Change order workflows post-approval or conversion to work orders.

---

## Key Entities / Concepts

| Entity / Concept          | Description                                                                                     |
|--------------------------|-------------------------------------------------------------------------------------------------|
| **Receiving Session**    | Context for receiving inventory, linked to POs or ASNs.                                         |
| **Inventory Movement**   | Records movement of inventory to staging or quarantine locations with quantities and status.    |
| **Approval**             | Immutable record of customer consent for work orders or estimates, including digital signatures.|
| **Work Order**           | Represents authorized work to be performed, with approval states and line items.                |
| **Estimate**             | Draft or pending quote containing parts and labor line items, subject to revision and approval. |
| **Estimate Version**     | Immutable snapshot of an estimate at a point in time, supporting revision history.               |
| **Picking List**         | List of parts allocated for a work order, tracked through scanning and confirmation by mechanics.|
| **ApprovalExpirationJob**| Scheduled job that transitions pending approvals to expired/cancelled states after timeout.     |
| **ApprovalAlertJob**     | Scheduled job that publishes alerts for approvals nearing expiration.                           |

---

## Invariants / Business Rules

- **Receiving:**
  - Inventory must be received to the site's default staging location unless explicitly overridden.
  - Quarantine receiving requires explicit user action and uses the site's default quarantine location.
  - Default locations must be configured and available; otherwise, manual selection is required.
  - Inventory movement records must be created atomically with ledger updates.
  
- **Approvals:**
  - Approvals can only be captured when the target entity is in a valid state (e.g., `Pending Customer Approval`).
  - Digital approvals require tamper-evident payload hashes and immutable storage.
  - In-person approvals record the approving user, timestamp, and method.
  - Partial approvals allow line-item level approval/decline with recalculated totals.
  - Approval expiration transitions pending approvals to terminal expired/cancelled states.
  - Approval methods are configurable per store and customer, with customer settings taking precedence.
  
- **Estimates:**
  - Parts and labor can only be added or modified while the estimate is in `Draft` state.
  - Price overrides require explicit permission and may require a reason code.
  - Non-catalog parts and custom labor items are allowed only if enabled by policy.
  - Revisions create new immutable versions, invalidating prior approvals if applicable.
  - Estimate summaries must exclude internal-only fields and include legal terms.
  
- **Picking:**
  - Picking lists can be partially picked and saved.
  - Scanned items must match the picking list; over-picking is disallowed.
  - Confirmation requires all required items to be picked.
  - Items flagged as `NOT_FOUND` notify inventory management.
  - Inventory status updates must be transactional and auditable.

---

## Events / Integrations

| Event Name                  | Description / Payload Highlights                                            | Consumers / Integration Points               |
|-----------------------------|-----------------------------------------------------------------------------|----------------------------------------------|
| `InventoryReceived`          | Emitted on successful receiving to staging location. Includes movement details.| `domain:audit`, inventory ledger, reporting |
| `InventoryQuarantined`       | Emitted on receiving to quarantine location with quarantine status.          | `domain:audit`, inventory ledger             |
| `workexec.EntityApproved`    | Emitted on successful digital or in-person approval. Contains entity and approval IDs, payload hash.| Billing, audit, downstream workflows         |
| `WorkOrderApprovalRecorded`  | Emitted on partial approval recording with line item statuses and approval method.| Audit, scheduling, technician notifications  |
| `APPROVAL_EXPIRING_SOON`     | Published by alert job for approvals nearing expiration, includes recipients and timing.| External notification system                   |
| `PICKING_LIST_CONFIRMED`     | Emitted upon successful picking confirmation with picked items and quantities.| Inventory, billing, audit                      |
| `PICKING_LIST_PARTIAL`       | Emitted when a partial pick is saved.                                        | Inventory, audit                              |

---

## API Expectations (High-Level)

- **Receiving Workflow:**
  - Endpoint(s) to confirm receipt of items in a receiving session.
  - Calls Site Configuration API to retrieve default staging/quarantine locations.
  - Creates inventory movement records and emits related events.
  - TBD: Exact API paths and request/response schemas.

- **Approval Capture:**
  - POST endpoints for capturing approvals on Estimates and Work Orders:
    - `/api/v1/estimates/{estimateId}/approvals`
    - `/api/v1/work-orders/{workOrderId}/approvals`
  - Accepts signature data, approval payload, and metadata.
  - Validates entity state and payload integrity.
  - Returns `201 Created` with approval ID on success.

- **In-Person Approval:**
  - Endpoint to capture in-person approval with minimal input (confirmation).
  - Updates Work Order status and records approval metadata.

- **Partial Approval:**
  - Endpoint to record line-item approval statuses with approval method.
  - Enforces state and permission checks.

- **Estimate Management:**
  - Endpoints to add parts and labor to draft estimates.
  - Support for price overrides with permission checks.
  - Endpoint to revise estimates creating new versions.
  - Endpoint to generate customer-facing estimate summaries.

- **Picking:**
  - Endpoints to scan items, save partial picks, and confirm picking lists.
  - Validation of scanned items against picking lists.

- **Approval Expiration & Alerts:**
  - Internal scheduled jobs; no direct external API.

---

## Security / Authorization Assumptions

- All user-initiated actions require authentication and authorization.
- Permissions are role-based and fine-grained:
  - Receiving associates authorized for receiving operations.
  - Service Advisors authorized for estimate and approval management.
  - Mechanics authorized for picking operations.
  - Price override requires explicit permission.
- Approval capture endpoints enforce entity state and user permissions.
- Audit events include user identity for traceability.
- Sensitive data (e.g., signature images) handled securely and stored immutably.
- API calls to external services (e.g., Site Configuration API) use secure channels and credentials managed outside this domain.

---

## Observability (Logs / Metrics / Tracing)

- **Logging:**
  - INFO logs for successful operations (receiving, approvals, picking).
  - WARN logs for configuration issues (missing default locations).
  - ERROR logs for failures (invalid states, permission denials, system errors).
  - Structured logs with correlation IDs for tracing user actions.

- **Metrics:**
  - Counters for:
    - Receiving operations completed.
    - Approvals captured (by type and success/failure).
    - Price overrides performed.
    - Picking lists completed and partial saves.
    - Approval expirations processed.
    - Alerts published.
  - Histograms for operation latencies (e.g., approval capture duration).

- **Tracing:**
  - Distributed tracing for API calls, including external dependencies (Site Configuration API, Pricing Service).
  - Trace context propagated through asynchronous event publishing.

- **Audit:**
  - Immutable audit events for all state transitions and approvals.
  - Audit logs include user ID, timestamps, and relevant entity identifiers.

---

## Testing Guidance

- **Unit Tests:**
  - Validate business rules and state transitions for receiving, approvals, estimate revisions, and picking.
  - Mock external dependencies (Site Configuration API, Pricing Service).
  - Validate permission checks and error handling.

- **Integration Tests:**
  - End-to-end workflows for receiving inventory with default locations.
  - Approval capture flows (digital, in-person, partial).
  - Estimate part and labor additions with price overrides.
  - Revision workflows creating new versions.
  - Picking list scanning and confirmation.

- **Contract Tests:**
  - Verify API contracts with frontend and external services.
  - Validate event payload schemas and publishing.

- **Performance Tests:**
  - Load testing for approval capture and receiving workflows.
  - Stress tests for scheduled expiration and alert jobs.

- **Security Tests:**
  - Authorization enforcement tests.
  - Input validation and tamper detection for digital approvals.

---

## Common Pitfalls

- **Configuration Dependencies:**
  - Failure to handle missing or unavailable default staging/quarantine locations gracefully.
  - Not caching site configuration leading to excessive API calls or stale data usage.

- **State Management:**
  - Allowing approvals or receiving operations in invalid entity states.
  - Not invalidating approvals upon estimate revision.

- **Data Integrity:**
  - Missing or incorrect payload hash computation for digital approvals.
  - Partial updates without transactional guarantees causing inconsistent totals or statuses.

- **Permission Checks:**
  - Overlooking price override permission enforcement.
  - Allowing quarantine receiving without explicit user action.

- **Event Emission:**
  - Failing to emit required domain events or emitting incomplete payloads.
  - Not handling event publishing failures or retries.

- **Observability Gaps:**
  - Insufficient logging for error conditions.
  - Missing audit events for critical state changes.

- **User Experience:**
  - Poor error messages when default locations are missing or unavailable.
  - Lack of feedback on partial picking progress or approval capture failures.

---

# End of workexec AGENT_GUIDE.md
