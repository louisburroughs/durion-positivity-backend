# AGENT_GUIDE.md — Domain: `positivity`

## Purpose
`positivity` is the POS-facing orchestration domain. It owns the POS **Order** aggregate and provides:
- **Order lifecycle orchestration** across downstream domains (notably `workexec` and `billing`) using a **persisted saga** (no distributed transactions).
- **Read aggregation** for browse/quote experiences by composing authoritative data from Catalog/Pricing/Inventory into a single response with explicit degradation metadata.

## Domain Boundaries
### What `positivity` owns (authoritative)
- **Order aggregate state** and transitions, including cancellation state machine and persisted saga progress.
- **Cancellation attempt metadata**: `cancellationReason`, `correlationId`, `cancellationIdempotencyKey`, and failure categorization.
- **API composition** for product detail views (response shaping, caching policy, and status metadata).

### What `positivity` does *not* own (delegated authority)
- **Payment state and reversals**: owned by `domain:billing` (void/refund decision and execution).
- **Work order state and cancellability**: owned by `domain:workexec` (authoritative gating + cancellation).
- **Product master data**: owned by Product Catalog service.
- **Pricing**: owned by Pricing service.
- **Availability/ATP and dynamic lead time**: owned by Inventory/Supply Chain service.

## Key Entities / Concepts
### Order (POS-owned aggregate)
Minimum fields implied by stories:
- `orderId`
- `status` (includes in-flight + terminal cancellation states)
- `workOrderId` (nullable)
- `paymentId` (nullable)
- `cancellationReason` (string)
- `correlationId` (trace/correlation)
- `cancellationIdempotencyKey`

Cancellation-related statuses (examples from story; exact enum TBD):
- In-flight: `CANCEL_REQUESTED` / `CANCEL_PENDING`, `WORKORDER_CANCELLED`, `PAYMENT_REVERSED`
- Terminal success: `CANCELLED`
- Terminal failure: `CANCEL_FAILED_WORKEXEC`, `CANCEL_FAILED_BILLING`, `CANCEL_REQUIRES_MANUAL_REVIEW`

### Cancellation Saga (orchestrated, persisted)
A deterministic workflow driven by `positivity`:
1. Persist “cancel requested” state + idempotency key
2. Cancel work order (if present) via `workexec` (authoritative)
3. Reverse payment (if present) via `billing` (authoritative)
4. Finalize order state and emit events/logs/metrics

### ProductDetailView (read model / API response DTO)
An aggregated view composed from:
- Catalog (master details + static lead-time hints + substitutions)
- Pricing (MSRP + location-scoped store price + currency)
- Inventory (on-hand, ATP, best-effort dynamic lead time)

Includes explicit per-component status metadata:
- `pricing.status: OK | UNAVAILABLE | STALE` (exact enum TBD)
- `availability.status: OK | UNAVAILABLE | STALE`
- `generatedAt`, and component `asOf` timestamps

## Invariants / Business Rules
### Cancellation (Order)
- **Authority invariant**: `workexec` is the sole authority for work-order cancellability; `billing` is the sole authority for payment reversal.
- **Ordering invariant**: cancel **work order first**, then reverse payment (minimizes revenue-loss risk).
- **No distributed transactions**: use a persisted saga with retries and explicit failure states.
- **Idempotency**: duplicate cancel requests must not re-run side effects; return current cancellation state.
- **Auditability**: every attempt (success/failure) must be traceable via correlation + idempotency identifiers.

### Product detail aggregation (browse/quote read)
- **Fail-fast only for missing product**: if Catalog says product not found → return `404` and do not synthesize.
- **Graceful degradation**: Pricing/Inventory failures still return `200` with explicit status metadata and safe defaults:
  - Pricing unavailable → price fields `null` + `pricing.status=UNAVAILABLE`
  - Inventory unavailable → availability represented as **UNKNOWN** (not “in stock”) + `availability.status=UNAVAILABLE`
- **Lead time precedence**: dynamic lead time from Inventory overrides Catalog static hint when available.
- **Caching**: short TTLs with staleness metadata (aggregated default 15s; component TTLs per story).

## Events / Integrations
### Synchronous integrations (explicitly referenced)
- `workexec`
  - Advisory pre-check: `GET /api/v1/work-orders/{workOrderId}` → includes `status`, `cancellable`, optional `nonCancellableReason`, `updatedAt/version`
  - Authoritative command: `POST /api/v1/work-orders/{workOrderId}/cancel` with `orderId`, `requestedBy`, `reasonCode`, `idempotencyKey`
- `billing`
  - `POST /api/v1/payments/{paymentId}/reverse` with `orderId`, `reasonCode`, `currency`, `idempotencyKey`, and intent `type: VOID|REFUND` (billing decides final)
- Catalog / Pricing / Inventory services
  - Endpoints are not specified here beyond the composed POS endpoint; treat downstream contracts as **TBD** except for the required fields described in Issue #16.

### Domain events (asynchronous)
`positivity` emits “canonical events for cancellation progress/final state” for audit/reporting/ops visibility. Event names, schemas, and transport are **TBD**, but must include:
- `orderId`, `workOrderId` (if any), `paymentId` (if any)
- `correlationId`, `idempotencyKey`
- state transition + failure category (if any)
- timestamps

## API Expectations (high-level)
### Product details (read aggregation)
- `GET /api/v1/products/{productId}?location_id={locationId}`
  - `200 OK` with aggregated `ProductDetailView` and per-component status metadata
  - `404 Not Found` if product missing in Catalog
  - `400 Bad Request` for invalid `location_id`
  - Must include `generatedAt` and component `asOf` timestamps when applicable
  - Must not silently null-out pricing/availability without a corresponding status

### Order cancellation (command)
- Cancel endpoint path/shape is **TBD** (not provided in export).
- Behavior must match the cancellation saga described in Issue #19:
  - Requires `orderId` and `cancellationReason` (and maps to downstream `reasonCode`)
  - Persists in-flight state before calling downstream services
  - Uses idempotency key and returns deterministic state on duplicates
  - Stops if `workexec` rejects (e.g., `409 Conflict`) and does not attempt billing reversal

## Security / Authorization assumptions
- Requests are authenticated; authorization is enforced per action:
  - Product detail read requires permission to read product + location-scoped data.
  - Order cancel requires a role/permission consistent with “Store Manager can cancel orders”.
- Propagate identity context to downstream services where required:
  - `requestedBy` passed to `workexec` cancel command.
- Do not log sensitive payment details; only log identifiers (`paymentId`) and correlation metadata.

## Observability (logs / metrics / tracing)
### Tracing
- Generate/propagate `correlationId` across:
  - inbound request → `positivity` → `workexec` / `billing` / Catalog / Pricing / Inventory
- Ensure correlation is present in logs and outbound headers (exact header keys TBD; use standard trace propagation where available).

### Logs (structured)
For cancellation:
- Include: `orderId`, `workOrderId`, `paymentId`, `correlationId`, `idempotencyKey`, current `orderStatus`, failure category, downstream HTTP status (if applicable).
For product detail:
- Include: `productId`, `locationId`, `generatedAt`, component statuses, and dependency latency summaries.

### Metrics (minimum implied)
Cancellation:
- `cancellations.success.count`
- `cancellations.failure.count` tagged by `reason` (`workexec_denial`, `billing_error`, `timeout`, `manual_review`)
Product detail:
- Per-dependency latency metrics (Catalog/Pricing/Inventory)
- Counters for degraded responses (pricing unavailable, inventory unavailable)

## Testing guidance
### Unit tests
- Cancellation state machine:
  - Workexec rejection (`409`) → no billing call; terminal failure state set.
  - Workexec success + billing failure → `CANCEL_FAILED_BILLING` (or manual review) and retry eligibility.
  - Duplicate cancel request → no repeated side effects; returns persisted state.
- Product detail aggregation:
  - Catalog not found → `404`
  - Pricing unavailable → `200` with `pricing.status=UNAVAILABLE` and null prices
  - Inventory unavailable → `200` with `availability.status=UNAVAILABLE` and UNKNOWN semantics
  - Lead time precedence: inventory dynamic overrides catalog static

### Integration tests (contract + resilience)
- Stub downstream services to validate:
  - Idempotency key propagation to `workexec` and `billing`
  - Correct ordering: `workexec` cancel before `billing` reverse
  - Timeout handling results in persisted failure state and safe retry behavior
- For product detail:
  - Partial outage scenarios return `200` with explicit status metadata (not silent nulls)

### Data / persistence tests
- Verify cancellation saga progress is persisted before side effects (crash/restart safety).
- Verify state transitions are monotonic and deterministic (no “backwards” transitions).

## Common pitfalls
- **Reversing payment before work cancellation**: violates ordering invariant and can create revenue-loss/manual-reconciliation scenarios.
- **Missing idempotency**: retries or duplicate user actions can double-cancel work orders or double-reverse payments.
- **Silent degradation** in product detail: returning `null` pricing/availability without `status` leads to unsafe downstream actions.
- **Treating inventory-unavailable as “0”**: must be represented as UNKNOWN, not out-of-stock or in-stock.
- **Not persisting in-flight state** before calling downstream services: makes recovery and retries non-deterministic.
- **Insufficient correlation**: lack of `correlationId`/`idempotencyKey` in logs/events breaks auditability and support workflows.

--- 

**TBDs to resolve in implementation**
- Exact Order cancel REST endpoint(s) and response schema
- Event names/schemas/transport for cancellation progress/finalization
- Standard headers for correlation/trace propagation (if not already standardized across the platform)
- Exact enum values for component `status` (`OK|UNAVAILABLE|STALE`) and cancellation statuses (beyond examples)
