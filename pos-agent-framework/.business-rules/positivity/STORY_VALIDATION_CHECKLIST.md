```markdown
# STORY_VALIDATION_CHECKLIST.md (domain: positivity)

Checklist for engineers/reviewers to validate story implementations in the **positivity** domain (POS backend). Items are intended to be **actionable and verifiable**.

---

## Scope/Ownership

- [ ] The change is correctly scoped to **positivity** responsibilities (POS orchestration, aggregation, Order aggregate ownership).
- [ ] Cross-domain authority boundaries are respected:
  - [ ] **workexec** is authoritative for work-order cancellability and cancellation.
  - [ ] **billing** is authoritative for payment reversal (void/refund decision).
  - [ ] **catalog/pricing/inventory** are authoritative for their respective read models (for product detail aggregation).
- [ ] Orchestration behavior matches the story’s decision record (e.g., cancel saga ordering: **workexec first, billing second**).
- [ ] Any new state machine/status values are owned by positivity and documented (no “shadow” states duplicated from other domains).
- [ ] Integration points (sync APIs, events) are explicitly listed in the PR description and match the story contract.

---

## Data Model & Validation

- [ ] Persistence changes (tables/columns/indexes) are present, migrated, and backward compatible where required.
- [ ] `Order` (or relevant aggregate) includes required fields for the story and they are persisted:
  - [ ] `status` includes in-flight + terminal cancellation states as needed (e.g., `CANCEL_REQUESTED`, `CANCEL_FAILED_*`, `CANCELLED`).
  - [ ] `cancellationReason` (or `reasonCode`) stored and validated (length, allowed values if enumerated).
  - [ ] `workOrderId` and `paymentId` nullable and validated as UUIDs when present.
  - [ ] `correlationId` stored/propagated (or derivable) for traceability.
  - [ ] `cancellationIdempotencyKey` stored for idempotent retries.
- [ ] Input validation is enforced at the API boundary:
  - [ ] UUID format validation for IDs (`orderId`, `productId`, `locationId`, etc.).
  - [ ] Required fields enforced (`cancellationReason`, `location_id`, etc.).
  - [ ] Rejects invalid combinations (e.g., missing `location_id` for product detail view).
- [ ] Domain invariants are enforced in the aggregate/service layer (not only controller-level validation).
- [ ] State transitions are validated (e.g., cannot move from `CANCELLED` back to active; cannot re-run side effects once terminal).

---

## API Contract

- [ ] Endpoints match the story specification (paths, methods, query params):
  - [ ] Cancel flow endpoint(s) accept `orderId` and `cancellationReason` and return deterministic state.
  - [ ] Product detail endpoint: `GET /api/v1/products/{productId}?location_id={locationId}`.
- [ ] Response codes match the story’s rules:
  - [ ] `404` when product not found in Catalog (do not synthesize).
  - [ ] `400` for invalid `location_id`.
  - [ ] Cancel flow returns a deterministic response for duplicates (idempotent behavior).
- [ ] Response schemas include required metadata:
  - [ ] Product detail includes `generatedAt` and per-component `status` + `asOf`.
  - [ ] Degraded responses never silently return `null` without a corresponding `status=UNAVAILABLE|STALE`.
- [ ] Error responses are consistent and actionable (stable error codes/messages; no leaking internals).
- [ ] API changes are versioned or backward compatible (no breaking changes without a version bump and migration plan).

---

## Events & Idempotency

- [ ] Cancellation uses a **persisted saga/state machine** (not purely in-memory orchestration).
- [ ] An **idempotency key** is created/derived and persisted for cancel attempts.
- [ ] Duplicate cancel requests:
  - [ ] Do not re-trigger downstream side effects if already executed.
  - [ ] Return current cancellation state (or equivalent) deterministically.
- [ ] Downstream calls include idempotency identifiers:
  - [ ] Workexec cancel includes `idempotencyKey`.
  - [ ] Billing reverse includes `idempotencyKey`.
- [ ] Ordering guarantees are implemented and tested:
  - [ ] Workexec cancel attempted before Billing reversal when `workOrderId` exists.
  - [ ] Billing reversal is not attempted if Workexec rejects with `409`.
- [ ] Canonical POS/positivity events are emitted for cancellation progress/final state (success and failure).
- [ ] Events include correlation identifiers (`correlationId`, `orderId`, and idempotency key where appropriate).
- [ ] Event emission is resilient (e.g., outbox pattern or documented at-least-once behavior) and does not block the main request path without justification.

---

## Security

- [ ] Authentication is required for all endpoints in scope.
- [ ] Authorization is enforced for:
  - [ ] Order cancellation (role/permission check for Store Manager or equivalent).
  - [ ] Product/location reads (location-scoped access where applicable).
- [ ] Sensitive identifiers are handled safely:
  - [ ] No payment PAN/PCI data is stored or logged.
  - [ ] Logs do not include secrets/tokens/credentials.
- [ ] Input is sanitized/validated to prevent injection (SQL/JPQL, log injection).
- [ ] Cross-service calls use approved service-to-service auth mechanisms (no hardcoded credentials).
- [ ] Rate limiting / abuse controls are considered for high-traffic read endpoints (product detail) and state-changing endpoints (cancel).

---

## Observability

- [ ] Structured logs include key identifiers:
  - [ ] `orderId`, `workOrderId`, `paymentId` (when present), `productId`, `locationId` (as applicable).
  - [ ] `correlationId` and `idempotencyKey` for cancellation flows.
- [ ] Distributed tracing propagates `correlationId` to Workexec and Billing calls.
- [ ] Metrics are added/updated and tagged appropriately:
  - [ ] Cancellation counters: success/failure with reason tags (`workexec_denial`, `billing_error`, `timeout`, `manual_review`).
  - [ ] Dependency latency metrics for Catalog/Pricing/Inventory and Workexec/Billing.
  - [ ] Counters for degraded product detail responses (pricing unavailable, inventory unavailable).
- [ ] Alerts/dashboards are feasible based on emitted metrics (at minimum: failure rate, timeout rate, manual review count).
- [ ] Failures are categorized consistently (e.g., `CANCEL_FAILED_WORKEXEC`, `CANCEL_FAILED_BILLING`) and visible in logs/metrics.

---

## Performance & Failure Modes

- [ ] Timeouts are configured for all downstream calls (Workexec, Billing, Catalog, Pricing, Inventory) and are not infinite.
- [ ] Retry behavior is bounded and safe:
  - [ ] Cancellation reversal retries use backoff and a max attempt limit.
  - [ ] Retries are idempotent and do not duplicate side effects.
- [ ] Partial failure behavior matches story intent:
  - [ ] Cancel flow: fail/stop on Workexec rejection; persist failure state on Billing failure; support retry/admin re-trigger.
  - [ ] Product detail: graceful degradation for Pricing/Inventory failures with explicit status metadata.
- [ ] Caching behavior (for product detail) is implemented as specified:
  - [ ] Aggregated TTL defaults to ~15s (or documented alternative).
  - [ ] Component staleness is represented via `asOf` and `status`.
  - [ ] Cache keys include all required context (e.g., `locationId`; future pricing context if applicable).
- [ ] Concurrency is handled:
  - [ ] Concurrent cancel attempts do not corrupt state (optimistic locking/versioning or equivalent).
  - [ ] State transitions are atomic at the aggregate level.
- [ ] Manual review path exists and is reachable when invariants are violated (e.g., billing reversed without workexec cancel due to unexpected ordering breach).

---

## Testing

- [ ] Unit tests cover:
  - [ ] State transitions and invariants for cancellation saga.
  - [ ] Idempotency behavior (duplicate requests return same state; no duplicate downstream calls).
  - [ ] Product detail aggregation mapping and status metadata behavior.
- [ ] Integration tests cover:
  - [ ] Workexec `409` rejection stops saga and prevents billing reversal.
  - [ ] Billing failure persists `CANCEL_FAILED_BILLING` (or `CANCEL_REQUIRES_MANUAL_REVIEW`) and supports retry.
  - [ ] Timeouts treated as failures with persisted state and safe retry.
  - [ ] Product not found returns `404` and does not synthesize.
  - [ ] Pricing unavailable and inventory unavailable each produce `200` with correct `status` and null/omitted fields.
- [ ] Contract tests (or schema validation) exist for:
  - [ ] Workexec GET/POST cancel request/response shapes used.
  - [ ] Billing reverse request/response shapes used.
- [ ] Tests assert observability requirements:
  - [ ] Correlation/idempotency propagation (headers/fields) is present.
  - [ ] Metrics/log fields are emitted (where test harness supports it).
- [ ] Negative/security tests exist for unauthorized/forbidden access and invalid inputs.

---

## Documentation

- [ ] API documentation updated (OpenAPI/Swagger) with:
  - [ ] Request/response schemas including status metadata fields.
  - [ ] Error codes and examples for key failure modes.
- [ ] State machine/status definitions are documented (including terminal vs in-flight states).
- [ ] Runbook notes added for operations:
  - [ ] How to identify `CANCEL_FAILED_BILLING` / `CANCEL_REQUIRES_MANUAL_REVIEW`.
  - [ ] How to re-trigger cancellation reversal/admin action safely (idempotent).
- [ ] Dependency assumptions documented (required downstream availability for cancel; graceful degradation for product detail).
- [ ] Any new configuration (timeouts, TTLs, retry limits) is documented with defaults and rationale.
```
