# AGENT_GUIDE.md — Order Domain

---

## Purpose

The Order domain manages lifecycle and state transitions of POS orders, with a focus on orchestrating order cancellation. It enforces cancellation policies, coordinates with Payment and Work Execution domains, and ensures auditability and operational correctness in cancellation workflows.

---

## Domain Boundaries

- **Primary Authority:** POS Order domain owns order cancellation policy and state transitions.
- **Downstream Dependencies:**
  - **Payment System:** Authoritative on payment state and void/refund capabilities.
  - **Work Execution System:** Authoritative on work status and reversibility.
- **Out of Scope:** Accounting domain handles GL impacts and refunds outside this flow.

---

## Key Entities / Concepts

| Entity                 | Description                                                                                  |
|------------------------|----------------------------------------------------------------------------------------------|
| **Order**              | Core POS order with status (`ACTIVE`, `CANCELLING`, `CANCELLED`, `CANCELLED_REQUIRES_REFUND`, `CANCELLATION_FAILED`), customer, and amount. |
| **CancellationRecord** | Immutable audit record capturing cancellation metadata, user info, downstream results, and correlation IDs. |
| **WorkOrder**          | External entity representing work status for the order (e.g., `IN_PROGRESS`, `CREATED`).    |
| **PaymentTransaction** | External entity representing payment status (`AUTHORIZED`, `CAPTURED`, `SETTLED`, etc.).     |
| **CancelOrderRequest** | API input containing `orderId`, cancellation `reason`, and optional `comments`.             |
| **CancelOrderResponse**| API output indicating cancellation result, status, and downstream operation statuses.        |

---

## Invariants / Business Rules

- **BR-1:** POS Order domain is the orchestrator and authoritative owner of cancellation policy.
- **BR-2:** Cancellation is blocked if work status is in blocking list:  
  `IN_PROGRESS`, `LABOR_STARTED`, `PARTS_ISSUED`, `MATERIALS_CONSUMED`, `COMPLETED`, `CLOSED`.
- **BR-3:** Cancellation allowed regardless of payment settlement state;  
  - If payment is authorized or captured but not settled, void payment authorization.  
  - If payment is settled, transition to `CANCELLED_REQUIRES_REFUND` and trigger manual refund.
- **BR-4:** On downstream failure (payment void or work rollback), transition to `CANCELLATION_FAILED` and require manual intervention.
- **BR-5:** All cancellation attempts must be fully audited with user, timestamp, reason, downstream results, and correlation IDs.

---

## Events / Integrations

| Event Name                   | Emitted When                              | Payload Highlights                                  |
|-----------------------------|------------------------------------------|----------------------------------------------------|
| `OrderCancelled`            | Successful cancellation with void/refund | `orderId`, `cancellationReason`, `cancelledBy`, `paymentVoidStatus`, `workRollbackStatus`, correlation IDs |
| `OrderCancelledRequiresRefund` | Cancellation when payment settled         | `orderId`, payment details, cancellation reason, correlation IDs |
| `OrderCancellationFailed`   | Downstream failure during cancellation    | `orderId`, failed subsystems, correlation IDs, retry eligibility |

**Integration Points:**

- **Work Execution System:**  
  - Query work status: `GET /api/work-orders/{orderId}/status`
  - Rollback work reservation (endpoint TBD)
- **Payment System:**  
  - Query payment status: `GET /api/payments/{paymentId}/status`
  - Void payment authorization: `POST /api/payments/{paymentId}/void`

---

## API Expectations (High-Level)

- **Cancel Order Operation**  
  - Input: `orderId` (required), `reason` (required enum), `comments` (optional)  
  - Validations: Order existence, user permission `ORDER_CANCEL`, cancellable order state, work status blocking  
  - Behavior: Orchestrate cancellation with downstream systems, handle alternate flows and failures  
  - Responses:  
    - `200 OK` with cancellation confirmation or refund-required message  
    - `400 Bad Request` for validation failures or blocking work status  
    - `403 Forbidden` if permission missing  
    - `409 Conflict` if cancellation already in progress (`CANCELLING`)  
    - `500 Internal Server Error` on downstream failure requiring manual intervention  
  - Idempotency: Return current state if order already cancelled

**Note:** Detailed API contract and endpoints are TBD.

---

## Security / Authorization Assumptions

- Users must have `ORDER_CANCEL` permission to initiate cancellation.
- Authorization checks occur before any state changes or downstream calls.
- Audit trails record user identity and actions for accountability.
- Downstream system credentials and communication secured per platform standards (out of scope here).

---

## Observability (Logs / Metrics / Tracing)

- **Logs:**  
  - All cancellation attempts with user ID, order ID, timestamps, and correlation IDs  
  - Downstream call results and errors  
  - State transitions and permission checks

- **Metrics:**  
  - Cancellation request counts (by reason code)  
  - Success and failure rates of cancellations  
  - Payment void and work rollback success rates  
  - Count and resolution time of orders in `CANCELLATION_FAILED` state (alerting threshold)

- **Tracing:**  
  - Correlation IDs propagated through downstream calls and events for end-to-end traceability

---

## Testing Guidance

- Mock Work Execution System with configurable work statuses (blocking and non-blocking).
- Mock Payment System with configurable payment states and void/refund success or failure.
- Validate all business rules, including blocking work statuses and payment settlement scenarios.
- Test idempotency by repeating cancellation requests on same order.
- Test concurrent cancellation attempts to verify conflict handling.
- Simulate downstream failures to verify transition to `CANCELLATION_FAILED` and event emission.
- Load test cancellation workflows under high volume.

---

## Common Pitfalls

- **Ignoring work status blocking rules:** Cancelling orders with irreversible work started leads to inconsistent states.
- **Treating cancellation as financial reversal:** Cancellation is a logical state; settled payments require manual refund.
- **Silent retries on downstream failures:** Must avoid automatic retries beyond configured limits to prevent operational deadlocks.
- **Insufficient audit logging:** Missing user or correlation data impedes troubleshooting and compliance.
- **Not handling idempotency:** Duplicate cancellation requests must return current state or conflict, not cause errors or duplicate side effects.
- **Not propagating correlation IDs:** Leads to poor traceability across distributed systems.
- **Hardcoding blocking statuses:** Should be configurable to accommodate business nuances (e.g., `MATERIALS_ORDERED`).

---

*End of AGENT_GUIDE.md*
