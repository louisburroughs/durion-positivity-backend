## 🏷️ Labels (Proposed)
### Required
- type:story
- domain:positivity
- status:ready-for-dev

### Recommended
- agent:story-authoring
- agent:positivity
- agent:billing
- agent:workexec

### Blocking / Risk
- none

**Rewrite Variant:** decision-record-applied

## Story Intent
As a **Store Manager**, I need to cancel an order to correct mistakes or respond to customer requests, while ensuring the cancellation respects the current payment and work-in-progress status to prevent financial loss or operational disruption.

## Actors & Stakeholders
- **Primary Actor**: Store Manager (initiates cancellation).
- **Primary Orchestrator (System)**: POS / `domain:positivity` (owns `Order` aggregate and drives orchestration).
- **Billing (`domain:billing`)**: payment state authority; performs void/refund.
- **Work Execution (`domain:workexec`)**: work order state authority; enforces cancellability and performs work-order cancellation.
- **Finance**: requires traceability for void/refund.
- **Operations**: requires deterministic states and failure handling.

## Preconditions
- An `Order` exists with `orderId`.
- Initiating user is authenticated/authorized for cancel.
- Order may be linked to:
  - a payment identifier (`paymentId` / `paymentIntentId` / `chargeId` depending on Billing model)
  - a `workOrderId`
- POS has connectivity to Workexec + Billing synchronous APIs.

## Functional Behavior
### 1) Domain ownership and orchestration (Decision Record)
- **Primary orchestrator**: `domain:positivity` (POS) owns `Order` state and drives the cancellation saga.
- **Workexec** is the sole authority for whether a work order is cancellable and must enforce/reject cancellation.
- **Billing** is the sole authority for payment reversal and must enforce/reject reversal.

### 2) POS cancellation saga (required)
1. Store Manager requests cancel for `orderId` and provides a `cancellationReason`.
2. POS validates permission and creates/derives an **idempotency key** for the cancel attempt.
3. POS loads `Order` and determines whether `workOrderId` and `paymentId` are present.
4. POS sets order to a persisted in-flight state (e.g., `CANCEL_REQUESTED` / `CANCEL_PENDING`) and begins orchestration.

### 3) Workexec gating and cancellation (first, per Decision Record)
- If `workOrderId` exists, POS calls Workexec:
  - **Pre-check (advisory UX)**: `GET /api/v1/work-orders/{workOrderId}` to obtain `status`, `cancellable`, and optional `nonCancellableReason`.
  - **Command (authoritative)**: `POST /api/v1/work-orders/{workOrderId}/cancel` with:
    - `orderId`
    - `requestedBy`
    - `reasonCode`
    - `idempotencyKey`
- If Workexec rejects (e.g., `409 Conflict`), POS stops the saga and the order remains not-cancelled.

### 4) Billing reversal (second, per Decision Record)
- If a payment exists and Workexec cancellation (if applicable) has succeeded, POS calls Billing:
  - `POST /api/v1/payments/{paymentId}/reverse`
  - Request includes `orderId`, `reasonCode`, `currency`, and `idempotencyKey`
  - POS expresses intent `type: VOID|REFUND`, but Billing may decide final based on settlement state.

### 5) Finalization and states
- If all required downstream actions succeed, POS transitions Order to a terminal `CANCELLED` state.
- POS emits canonical events for downstream consumers (audit/reporting).

## Alternate / Error Flows
- **Workexec rejects cancellation (`409`)**: POS transitions to a failure state (e.g., `CANCEL_FAILED_WORKEXEC`) and returns a deterministic user-facing message based on Workexec `nonCancellableReason`.
- **Billing reversal fails**:
  - POS transitions to `CANCEL_FAILED_BILLING` (or `CANCEL_REQUIRES_MANUAL_REVIEW`).
  - POS retries reversal with bounded retry/backoff; provides an admin re-trigger mechanism.
- **Timeouts**:
  - POS treats timeouts as failure for this attempt, persists state, and supports retry (idempotent).
- **Duplicate requests**:
  - POS returns the current cancellation state and does not re-run side effects (idempotency key + persisted saga state).

## Business Rules
- **BR1 (ownership)**: Workexec is authoritative for cancellability and must reject invalid cancellation commands.
- **BR2 (ordering)**: POS cancels work order first, then reverses payment (minimizes revenue-loss risk).
- **BR3 (consistency model)**: No distributed transactions. POS uses an orchestrated saga with persisted state and retry/compensation paths.
- **BR4 (auditable)**: Every cancellation attempt (success/failure) is captured via events/logging with correlation and idempotency identifiers.

## Data Requirements
- **Order** (POS-owned):
  - `orderId`
  - `status` includes in-flight + terminal cancel states (Decision Record examples):
    - `CANCEL_REQUESTED`, `WORKORDER_CANCELLED`, `PAYMENT_REVERSED`, `CANCELLED`
    - `CANCEL_FAILED_WORKEXEC`, `CANCEL_FAILED_BILLING`, `CANCEL_REQUIRES_MANUAL_REVIEW`
  - `cancellationReason` (string)
  - `workOrderId` (nullable)
  - `paymentId` (nullable)
  - `correlationId` (trace)
  - `cancellationIdempotencyKey`

- **Contracts (Decision Record minimums)**
  - Workexec `GET work-order` response includes: `id`, `status`, `cancellable`, optional `nonCancellableReason`, `updatedAt/version`.
  - Workexec `POST cancel` includes: `orderId`, `requestedBy`, `reasonCode`, `idempotencyKey`.
  - Billing `POST reverse` includes: `type` (intent), `amount` (optional), `currency`, `reasonCode`, `orderId`, `idempotencyKey`.

## Acceptance Criteria
- Cancelling an order uses a **POS-orchestrated saga** with a persisted cancellation state machine and idempotency.
- If a `workOrderId` exists:
  - POS calls Workexec cancel first.
  - If Workexec rejects, POS does not attempt payment reversal and returns a deterministic denial message.
- If a payment exists and Workexec cancellation (if applicable) succeeds:
  - POS calls Billing reversal using an idempotency key.
  - POS records success/failure and transitions to the appropriate terminal state.
- Partial failure handling exists:
  - Workexec success + Billing failure results in a persisted failure state and retry/admin re-trigger.
  - Billing success + Workexec failure is prevented by ordering; if detected, POS enters `CANCEL_REQUIRES_MANUAL_REVIEW` and emits an ops-visible alert/event.

## Audit & Observability
- **Tracing**: POS propagates `correlationId` to Workexec and Billing.
- **Logs**: Structured logs include `orderId`, `workOrderId`, `paymentId`, `correlationId`, `idempotencyKey`, and failure category.
- **Metrics** (minimum):
  - `cancellations.success.count`
  - `cancellations.failure.count` tagged by `reason` (`workexec_denial`, `billing_error`, `timeout`, `manual_review`)
- **Events**: POS emits domain events for cancellation progress/final state; Workexec and Billing emit their own lifecycle events.

## Open Questions (if any)
- none (resolved by Decision Record comment)

## Original Story (Unmodified – For Traceability)
# Issue #19 — [BACKEND] [STORY] Order: Cancel Order with Controlled Void Logic

## Current Labels
- backend
- story-implementation
- payment

## Current Body
## Backend Implementation for Story

**Original Story**: [STORY] Order: Cancel Order with Controlled Void Logic

**Domain**: payment

### Story Description

/kiro
# User Story

## Narrative
As a **Store Manager**, I want to cancel an order so that mistaken orders can be voided safely.

## Details
- If payment authorized/captured, require appropriate void/refund flow.
- Prevent cancel when work already started unless controlled.

## Acceptance Criteria
- Cancellation enforces policy.
- Proper payment reversal initiated.
- Workexec link handled (cancel link or create adjustment).

## Integrations
- Payment service integration required; workexec notified if linked.

## Data / Entities
- CancellationRecord, PaymentReversalRef, WorkexecLink, AuditLog

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
