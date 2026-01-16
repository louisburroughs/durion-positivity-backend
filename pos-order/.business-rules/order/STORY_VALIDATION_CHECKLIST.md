# STORY_VALIDATION_CHECKLIST.md  
**Domain:** Order (POS)

---

## Scope / Ownership
- [ ] Verify the story implementation aligns with the POS Order domain as the primary orchestrator of cancellation policy.
- [ ] Confirm downstream systems (Payment, Work Execution) are treated as authoritative only within their operational boundaries.
- [ ] Ensure no accounting or GL reversal logic is implemented (out of scope).
- [ ] Validate that cancellation logic respects user roles and permissions (`ORDER_CANCEL`).

---

## Data Model & Validation
- [ ] Confirm `orderId` exists and references a valid, active order.
- [ ] Validate order status is not terminal (`COMPLETED`, `CLOSED`, `CANCELLED`) before cancellation.
- [ ] Verify `CancelOrderRequest` fields:
  - `orderId`: present and valid format.
  - `reason`: valid enum value.
  - `comments`: optional, max 2000 characters.
- [ ] Check audit trail data model records:
  - User ID, timestamp, reason, comments.
  - Order state before and after cancellation.
  - Downstream operation results.
  - Correlation IDs.
- [ ] Confirm `CancellationRecord` entity is created/updated correctly with all required fields.
- [ ] Validate correlation IDs follow UUID v4 format.

---

## API Contract
- [ ] Confirm API returns `400 Bad Request` if:
  - Order does not exist.
  - Order is in terminal state.
  - Work Execution status blocks cancellation.
- [ ] Confirm API returns `403 Forbidden` if user lacks `ORDER_CANCEL` permission.
- [ ] Confirm API returns `409 Conflict` if order is currently in `CANCELLING` state.
- [ ] Confirm API returns `200 OK` with appropriate status and message on success:
  - `CANCELLED` when payment void succeeds.
  - `CANCELLED_REQUIRES_REFUND` when payment is settled.
  - Idempotent response if order already cancelled.
- [ ] Confirm API returns `500 Internal Server Error` with descriptive message on downstream failure.
- [ ] Validate response payload includes:
  - `orderId`
  - `status`
  - `message`
  - `cancellationId`
  - `paymentVoidStatus`
  - `workRollbackStatus`

---

## Events & Idempotency
- [ ] Verify emission of `OrderCancelled` event on successful cancellation with all required fields.
- [ ] Verify emission of `OrderCancelledRequiresRefund` event when payment is settled.
- [ ] Verify emission of `OrderCancellationFailed` event on downstream failure, including failed subsystems and correlation IDs.
- [ ] Confirm all events include correlation IDs for traceability.
- [ ] Validate idempotency:
  - Duplicate cancellation requests for already cancelled orders return `200 OK` with current state.
  - Requests during `CANCELLING` state return `409 Conflict`.
- [ ] Confirm no silent retries beyond configured retry limits.
- [ ] Confirm no automatic reversion from `CANCELLATION_FAILED` to active states.

---

## Security
- [ ] Confirm user permission `ORDER_CANCEL` is checked before processing cancellation.
- [ ] Validate audit trail records user identity and action timestamp immutably.
- [ ] Ensure no sensitive payment or personal data is logged or exposed in error messages.
- [ ] Confirm correlation IDs are generated securely and are unique per request.
- [ ] Verify downstream system calls are authenticated and authorized as per integration standards.

---

## Observability
- [ ] Confirm logging of all cancellation attempts with correlation IDs.
- [ ] Verify logs include:
  - Permission checks.
  - State transitions.
  - Downstream system call results and errors.
- [ ] Validate metrics are emitted/tracked for:
  - Cancellation request count by reason.
  - Cancellation success rate.
  - Payment void success rate.
  - Work rollback success rate.
  - Number of orders in `CANCELLATION_FAILED` state.
  - Time to resolve `CANCELLATION_FAILED` orders.
- [ ] Confirm alerts are configured for thresholds on `CANCELLATION_FAILED` orders.

---

## Performance & Failure Modes
- [ ] Validate downstream calls to Payment and Work Execution systems have configured timeouts (default 5000ms).
- [ ] Confirm retry limits for downstream operations are enforced (default max 3 retries).
- [ ] Verify proper handling of downstream failures:
  - Transition to `CANCELLATION_FAILED`.
  - Emit failure event with detailed failure reasons.
  - No silent retries beyond limits.
- [ ] Confirm system does not deadlock or block cancellation when payment is settled.
- [ ] Validate concurrent cancellation attempts are handled gracefully with correct status codes.

---

## Testing
- [ ] Test validation failures:
  - Non-existent order.
  - Terminal order states.
  - User without permission.
  - Blocking work statuses.
- [ ] Test successful cancellation flows:
  - Payment authorized only (void succeeds).
  - Payment captured but not settled (void with fallback).
  - Payment captured and settled (alternate flow).
- [ ] Test downstream failure scenarios:
  - Payment void failure.
  - Work rollback failure.
  - Partial downstream failures.
- [ ] Test idempotency:
  - Repeat cancellation requests on cancelled orders.
  - Requests during `CANCELLING` state.
- [ ] Test concurrency:
  - Simultaneous cancellation requests for same order.
- [ ] Test audit trail completeness and immutability.
- [ ] Test event emission correctness and completeness.
- [ ] Test observability:
  - Metrics increment.
  - Logs contain required details.
- [ ] Load test cancellation endpoint under high volume.

---

## Documentation
- [ ] Confirm API documentation includes:
  - Request and response schemas.
  - Possible status codes and error messages.
  - Description of cancellation states and flows.
- [ ] Document domain ownership and orchestration responsibilities.
- [ ] Document business rules BR-1 through BR-5 clearly.
- [ ] Document failure handling and operator resolution procedures.
- [ ] Document integration points with Payment and Work Execution systems.
- [ ] Document configuration options (timeouts, retry limits, blocking statuses).
- [ ] Include examples of events emitted and their payloads.
- [ ] Provide guidance on audit trail querying and compliance requirements.
- [ ] Document security considerations and permission requirements.

---

# End of Checklist
