# Story: Cancel Order with Controlled Void Logic

## Story Intent

Enable Service Advisors and Managers to cancel orders in the POS system with proper validation, downstream coordination, and comprehensive audit trails. The POS Order domain orchestrates cancellation policy while respecting the authority of Payment and Work Execution domains for their respective operational boundaries.

## Actors & Stakeholders

### Primary Actors
- **Service Advisor**: Initiates order cancellation for customer-facing orders
- **Manager**: Initiates or approves cancellation for special cases
- **System Administrator**: Resolves orders stuck in `CANCELLATION_FAILED` state

### Stakeholders
- **Customer**: Receives confirmation and financial settlement
- **Payment System**: Authoritative on void vs. refund capability
- **Work Execution System**: Authoritative on work-started status
- **Accounting System**: Receives cancellation events for GL impact (out of scope for this story)

## Preconditions

- Order exists in POS system with a valid `orderId`
- User has permission: `ORDER_CANCEL`
- Order is in a cancellable state (see Business Rules below)

## Functional Behavior

### Main Flow: Successful Cancellation

1. User invokes **Cancel Order** operation with:
   - `orderId` (required)
   - `reason` (required) - structured reason code
   - `comments` (optional) - free text justification

2. POS Order Service validates:
   - Order exists and is active
   - User has `ORDER_CANCEL` permission
   - Order is not in a terminal state (`COMPLETED`, `CLOSED`, `CANCELLED`)

3. POS queries **Work Execution System** to check work status:
   - If work status is in the **blocking list** (see Business Rule BR-2), reject cancellation
   - Return: `400 Bad Request` with message: `"Cannot cancel order: work has already started (status: {workStatus})"`

4. POS queries **Payment System** to determine payment state:
   - If payment is **Authorized but not Captured**: Payment system supports void
   - If payment is **Captured but not Settled**: Payment system attempts void, falls back to refund if void fails
   - If payment is **Captured and Settled**: Payment void is not possible (see Alternate Flow A)

5. POS transitions order to `CANCELLING` (intermediate state)

6. POS orchestrates downstream cancellation:
   - **Payment System**: Void authorization (if applicable)
   - **Work Execution System**: Rollback work reservation (if applicable)

7. If all downstream operations succeed:
   - POS transitions order to `CANCELLED`
   - POS emits `OrderCancelled` event with:
     - `orderId`
     - `cancellationReason`
     - `cancelledBy` (userId)
     - `cancelledAt` (timestamp)
     - `paymentVoidStatus` (VOIDED | NOT_APPLICABLE)
     - `workRollbackStatus` (ROLLED_BACK | NOT_APPLICABLE)
   - Return: `200 OK` with confirmation

8. System records audit trail with:
   - User ID, timestamp, reason, comments
   - Downstream operation results
   - Correlation IDs for Payment and Work Execution requests

### Alternate Flow A: Payment Captured and Settled

**Trigger:** Payment has been captured and settled (e.g., after end-of-day batch processing)

**Behavior:**
- POS cancellation is **NOT blocked**
- Payment void is **not possible**
- POS transitions order to `CANCELLED_REQUIRES_REFUND` (distinct state)
- POS emits `OrderCancelledRequiresRefund` event with payment details
- Manual refund/adjustment process is triggered outside this flow
- Return: `200 OK` with message: `"Order cancelled. Payment has been settled and requires manual refund processing."`

**Key Decision (OQ-3):**
> Cancellation is a **logical order state**, not equivalent to a financial reversal. Blocking cancellation due to settlement would create operational deadlocks.

### Alternate Flow B: Downstream System Failure

**Trigger:** POS successfully transitions order to `CANCELLING`, but one or more downstream compensations fail

**Behavior:**
1. POS records:
   - Cancellation intent
   - Partial completion status
   - Failure reason(s) from downstream systems
   - Correlation IDs

2. POS transitions order to `CANCELLATION_FAILED` (terminal operational state)

3. POS emits `OrderCancellationFailed` event with:
   - `orderId`
   - `failedSubsystems` (e.g., `["PAYMENT_VOID", "WORK_ROLLBACK"]`)
   - `correlationIds`
   - `retryEligible` (boolean)

4. Return: `500 Internal Server Error` with message: `"Order cancellation failed. Manual intervention required."`

5. Order appears in **Operator Dashboard** for manual resolution

**Resolution Procedure (OQ-4):**
- Operator dashboard lists:
  - Failed subsystem(s)
  - Retry eligibility
  - Recommended action
- Operators may:
  - Retry downstream action (if eligible)
  - Mark as **Accepted Financial Adjustment Required**
  - Escalate to Accounting or Operations
- **Guarantees:**
  - No silent retries beyond configured limits
  - No automatic reversion to ACTIVE
  - Every failed cancellation must end in one of:
    - Fully cancelled + reconciled
    - Manually accepted with documented exception

## Business Rules

### BR-1: Domain Authority (OQ-1 Decision)

**Primary Authority:** POS Order domain is the orchestrator and authority for cancellation policy.

**Downstream Authority:**
- **Work Execution System**: Authoritative on whether work has started or is reversible
- **Payment System**: Authoritative on whether funds can be voided vs. must be refunded
- **Accounting System**: Authoritative on GL reversal timing (out of scope for this story)

**Primary Domain Label:** `domain:order`

### BR-2: Work Status Blocking Rules (OQ-2 Decision)

**Rule:** Cancellation is blocked once **irreversible operational work** has begun.

**Exhaustive Blocking Statuses (Work Execution):**
The following are considered **"work already started"** and **block cancellation**:
- `IN_PROGRESS`
- `LABOR_STARTED`
- `PARTS_ISSUED`
- `MATERIALS_CONSUMED`
- `COMPLETED`
- `CLOSED`

**Non-Blocking Statuses (Cancellation Allowed):**
- `CREATED`
- `SCHEDULED`
- `ASSIGNED`
- `PARTS_RESERVED` (but not issued)
- `AWAITING_START`

**Important Nuance:**
- `MATERIALS_ORDERED` **alone does not block** cancellation if:
  - Parts are cancelable with the supplier, OR
  - The business accepts restocking handling separately
- This nuance should be **configurable**, but defaults to **non-blocking**

### BR-3: Payment Settlement Rules (OQ-3 Decision)

**Payment States and Cancellation Behavior:**

| Payment State | Cancellation Blocked? | POS Behavior |
|--------------|----------------------|--------------|
| Authorized Only (Not Captured) | No | Payment system voids authorization |
| Captured but NOT Settled | No | Payment system attempts void; fallback to refund path if void fails |
| Captured AND Settled | **No** | POS transitions to `CANCELLED_REQUIRES_REFUND`; refund/adjustment triggered outside this flow |

**Key Principle:**
> Cancellation is a **logical order state**, not equivalent to a financial reversal.

### BR-4: Failure Handling (OQ-4 Decision)

**State Model:**
- `CANCELLATION_FAILED` is the correct terminal operational state when:
  - POS cancellation succeeds locally, **but**
  - One or more required downstream compensations fail (Payment void, Work Exec rollback)

**Operational Resolution Procedure:**
- Orders in `CANCELLATION_FAILED` require **explicit intervention**
- Operator dashboard lists failed subsystem(s), retry eligibility, recommended action
- Operators may retry, accept as financial adjustment, or escalate
- **No silent retries** beyond configured limits
- **No automatic reversion** to ACTIVE

### BR-5: Audit Requirements

All cancellation operations must record:
- User ID (who initiated)
- Timestamp (when)
- Reason code and comments (why)
- Order state before/after (what)
- Downstream operation results
- Correlation IDs for external system calls

## Data Requirements

### Entities

**Order (POS)**
- `orderId` (PK)
- `status` (enum: includes `ACTIVE`, `CANCELLING`, `CANCELLED`, `CANCELLED_REQUIRES_REFUND`, `CANCELLATION_FAILED`)
- `customerId`
- `totalAmount`

**CancellationRecord (POS)**
- `cancellationId` (PK)
- `orderId` (FK)
- `reason` (enum: structured reason codes)
- `comments` (text, optional)
- `cancelledBy` (userId)
- `cancelledAt` (timestamp)
- `paymentVoidStatus` (enum: `VOIDED`, `VOID_FAILED`, `REFUND_REQUIRED`, `NOT_APPLICABLE`)
- `workRollbackStatus` (enum: `ROLLED_BACK`, `ROLLBACK_FAILED`, `NOT_APPLICABLE`)
- `correlationIds` (JSON or related table)

**WorkOrder (Work Execution System - External)**
- `orderId` (FK to Order)
- `workStatus` (enum - see BR-2)

**PaymentTransaction (Payment System - External)**
- `orderId` (FK to Order)
- `paymentStatus` (enum: `AUTHORIZED`, `CAPTURED`, `SETTLED`, `VOIDED`, `REFUNDED`)

### Fields

**CancelOrderRequest (API Input)**
```json
{
  "orderId": "string (required)",
  "reason": "enum (required) - CUSTOMER_REQUEST | INVENTORY_UNAVAILABLE | PRICING_ERROR | DUPLICATE_ORDER | OTHER",
  "comments": "string (optional, max 2000 chars)"
}
```

**CancelOrderResponse (API Output)**
```json
{
  "orderId": "string",
  "status": "string (CANCELLED | CANCELLED_REQUIRES_REFUND | CANCELLATION_FAILED)",
  "message": "string",
  "cancellationId": "string",
  "paymentVoidStatus": "string",
  "workRollbackStatus": "string"
}
```

## Acceptance Criteria

- **AC1: Validation**
  - [ ] Returns `400 Bad Request` if order does not exist
  - [ ] Returns `403 Forbidden` if user lacks `ORDER_CANCEL` permission
  - [ ] Returns `400 Bad Request` if order is in a terminal state (`COMPLETED`, `CLOSED`, `CANCELLED`)

- **AC2: Work Status Blocking**
  - [ ] Returns `400 Bad Request` with descriptive message if Work Execution status is in blocking list (BR-2)
  - [ ] Includes the specific work status in the error message (e.g., `"Cannot cancel: work status is IN_PROGRESS"`)

- **AC3: Payment Void (Authorized/Captured but Not Settled)**
  - [ ] Successfully voids payment authorization when payment is `AUTHORIZED` or `CAPTURED` but not settled
  - [ ] Transitions order to `CANCELLED` on success
  - [ ] Returns `200 OK` with confirmation

- **AC4: Payment Settled (Alternate Flow A)**
  - [ ] Does **not** block cancellation when payment is `SETTLED`
  - [ ] Transitions order to `CANCELLED_REQUIRES_REFUND`
  - [ ] Emits `OrderCancelledRequiresRefund` event
  - [ ] Returns `200 OK` with message indicating manual refund required

- **AC5: Downstream Failure (Alternate Flow B)**
  - [ ] Transitions order to `CANCELLATION_FAILED` if Payment void fails
  - [ ] Transitions order to `CANCELLATION_FAILED` if Work Exec rollback fails
  - [ ] Emits `OrderCancellationFailed` event with correlation IDs
  - [ ] Returns `500 Internal Server Error`

- **AC6: Audit Trail**
  - [ ] Records complete audit trail for all cancellation attempts (success or failure)
  - [ ] Includes: userId, timestamp, reason, comments, downstream results, correlation IDs
  - [ ] Audit record is immutable and queryable

- **AC7: Events**
  - [ ] Emits `OrderCancelled` event on successful cancellation (with payment void status)
  - [ ] Emits `OrderCancelledRequiresRefund` event when payment is settled
  - [ ] Emits `OrderCancellationFailed` event on downstream failure
  - [ ] All events include correlation IDs for downstream traceability

- **AC8: Idempotency**
  - [ ] Returns `200 OK` with current state if order is already `CANCELLED`
  - [ ] Returns `409 Conflict` if order is currently `CANCELLING` (retry in progress)

## Audit & Observability

### Events to Emit
- `OrderCancelled` (on success)
- `OrderCancelledRequiresRefund` (when payment settled)
- `OrderCancellationFailed` (on downstream failure)

### Metrics to Track
- Cancellation request count (by reason)
- Cancellation success rate
- Payment void success rate
- Work rollback success rate
- Orders in `CANCELLATION_FAILED` state (alert threshold)
- Time to resolve `CANCELLATION_FAILED` orders

### Logs to Record
- All cancellation attempts (with correlation IDs)
- Downstream system call results
- State transitions
- Permission checks

## Notes for Implementers

### Integration Points
- **Work Execution System**: Query endpoint for work status (`GET /api/work-orders/{orderId}/status`)
- **Payment System**: Void endpoint (`POST /api/payments/{paymentId}/void`)
- **Payment System**: Query endpoint for settlement status (`GET /api/payments/{paymentId}/status`)

### Configuration
- Retry limits for downstream operations (default: 3)
- Timeout for downstream calls (default: 5000ms)
- Correlation ID format (UUID v4 recommended)
- Work status blocking list (configurable, defaults per BR-2)

### Testing Considerations
- Mock Work Execution System with configurable statuses
- Mock Payment System with configurable void success/failure
- Test all combinations of work status and payment status
- Test idempotency with duplicate requests
- Test concurrent cancellation attempts
- Load test with high cancellation volume

## Classification

- **Type:** Story
- **Layer:** Backend / Domain Logic
- **Primary Domain:** `domain:order` (POS)
- **Related Domains:** `domain:payment`, `domain:work-execution`

## Resolution History

### Clarification Decisions (2026-01-13)

This story was refined based on clarification issue responses from @louisburroughs:

**OQ-1 (Domain Ownership):**
- **Decision:** POS Order domain is the primary authority and orchestrator
- **Rationale:** Cancellation is a customer/order lifecycle decision, not purely financial or operational
- **Label:** `domain:order` (POS)

**OQ-2 (Work Status Blocking Rules):**
- **Blocking Statuses:** `IN_PROGRESS`, `LABOR_STARTED`, `PARTS_ISSUED`, `MATERIALS_CONSUMED`, `COMPLETED`, `CLOSED`
- **Non-Blocking Statuses:** `CREATED`, `SCHEDULED`, `ASSIGNED`, `PARTS_RESERVED`, `AWAITING_START`
- **Nuance:** `MATERIALS_ORDERED` does not block by default (configurable)

**OQ-3 (Payment Settlement Policy):**
- **Decision:** Cancellation is NOT blocked when payment is settled
- **Behavior:** Transition to `CANCELLED_REQUIRES_REFUND` state; trigger manual refund process
- **Principle:** Cancellation is a logical order state, not equivalent to financial reversal

**OQ-4 (Failure Handling):**
- **State:** `CANCELLATION_FAILED` is the correct terminal state for downstream failures
- **Resolution:** Explicit operator intervention via dashboard
- **Guarantees:** No silent retries, no automatic reversion to ACTIVE

---

## Original Story (For Traceability)

*(This section would contain the original story text as submitted before Story Authoring Agent refinement. Since we don't have the original in this context, this serves as a placeholder.)*

**Original Title:** [BACKEND] [STORY] Order: Cancel Order with Controlled Void Logic

**Original Description:** *(Original unmodified text would be preserved here)*
