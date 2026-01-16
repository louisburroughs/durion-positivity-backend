# STORY_VALIDATION_CHECKLIST.md for domain: accounting

This checklist is intended for engineers and reviewers to validate story implementations in the accounting domain. It covers key areas to ensure correctness, security, auditability, and operational robustness.

---

## Scope / Ownership
- [ ] Confirm the story implementation aligns with the defined domain ownership (`domain:accounting`).
- [ ] Verify that the story scope matches the intended business capability and actors.
- [ ] Ensure all domain entities and events referenced belong to or are owned by the accounting domain.
- [ ] Confirm no unauthorized cross-domain mutations occur without explicit contracts or APIs.

## Data Model & Validation
- [ ] Validate all required entity fields are present, correctly typed, and constrained (e.g., non-null, unique).
- [ ] Confirm mandatory tax basis fields (`taxCode`, `jurisdiction`, `pointOfSaleLocation`, `productType`) are validated with fail-fast behavior.
- [ ] Ensure immutability of entities and snapshots where specified (e.g., `CalculationSnapshot` after invoice issuance).
- [ ] Verify rounding rules are applied per-line with `RoundingMode.HALF_UP` and currency-scale precision.
- [ ] Check that variance records are created with correct reason codes and amounts.
- [ ] Confirm effective dating and status transitions respect business rules (e.g., GL accounts active/inactive).
- [ ] Validate that adjustments do not allow negative invoice totals; require credit memo process instead.
- [ ] Ensure audit fields (`createdAt`, `updatedAt`, `createdBy`, `updatedBy`) are properly set and immutable where required.

## API Contract
- [ ] Verify REST API endpoints conform to domain contracts and use appropriate HTTP status codes.
- [ ] Confirm idempotency keys are implemented for commands/events where required (e.g., `invoiceId + adjustmentId`).
- [ ] Validate error responses include actionable error codes and messages (e.g., `SCHEMA_VALIDATION_FAILED`, `INGESTION_DUPLICATE_CONFLICT`, `INVOICE_TOTAL_NEGATIVE_REQUIRES_CREDIT_MEMO`).
- [ ] Ensure authorization scopes and permissions are enforced (e.g., `invoice.adjust`, `invoice:issue`, `accounting:invoice:approve-variance`).
- [ ] Confirm APIs reject invalid state transitions with proper conflict or validation errors.
- [ ] Validate that event payloads conform to the canonical accounting event schema and versioning.

## Events & Idempotency
- [ ] Confirm all domain events include required fields: `eventId` (UUIDv7), `eventType`, `schemaVersion`, `sourceModule`, `sourceEntityRef`, `occurredAt`, `businessUnitId`, `currencyUomId`.
- [ ] Verify event payloads include full financial breakdown (lines, tax, fees, discounts) and traceability context.
- [ ] Ensure idempotency is enforced on event ingestion using `eventId` as the sole key.
- [ ] Validate conflict detection on duplicate event IDs with differing payloads triggers DLQ and alerts.
- [ ] Confirm replayed events with matching payloads are processed idempotently without side effects.
- [ ] Check that events emitted (e.g., `InvoiceAdjusted`, `InvoiceIssued`, `CreditMemoIssued`) include all required audit and versioning information.
- [ ] Verify event schema versioning follows SemVer and is published in the shared schema repository.

## Security
- [ ] Confirm all sensitive operations require appropriate authorization scopes and permissions.
- [ ] Validate that overrides (e.g., tax basis validation overrides) require explicit permission and are audited.
- [ ] Ensure no secrets or sensitive data are logged or exposed in error messages.
- [ ] Verify authentication and authorization are enforced on all API endpoints.
- [ ] Confirm that event producers are authenticated and `sourceModule` matches the authenticated principal.
- [ ] Check that audit logs include actor identity and source IP where applicable.

## Observability
- [ ] Verify audit trails exist for all critical state transitions and data mutations (e.g., invoice state changes, GL account updates).
- [ ] Confirm structured logging includes key identifiers (`invoiceId`, `eventId`, `adjustmentId`) and error details.
- [ ] Ensure metrics are emitted for success/failure counts, latencies, and key business events (e.g., `invoice_calculation_success_count`, `events.conflicts.count`).
- [ ] Validate alerts are configured for high rates of errors, conflicts, or rejections.
- [ ] Confirm that event consumers log processed event types and schema versions for traceability.

## Performance & Failure Modes
- [ ] Ensure graceful retry mechanisms exist for transient failures (e.g., tax service unavailability).
- [ ] Confirm that failures in tax or fee calculation transition invoices to `CalculationFailed` and block issuance.
- [ ] Validate that database transactions are atomic and consistent, especially for posting journal entries and invoice issuance.
- [ ] Check that concurrent modifications are detected and handled via optimistic locking or equivalent.
- [ ] Verify that large payloads (e.g., calculation snapshots) are stored efficiently and immutable.
- [ ] Confirm that event ingestion and processing scale with idempotency and conflict detection to prevent duplicates or data corruption.

## Testing
- [ ] Confirm unit tests cover all business rules, including edge cases (e.g., rounding, variance detection).
- [ ] Verify integration tests validate end-to-end flows, including event emission and consumption.
- [ ] Ensure negative test cases cover validation failures, unauthorized access, and error flows.
- [ ] Confirm idempotency and conflict scenarios are tested for event ingestion.
- [ ] Validate performance and load tests cover typical and peak usage patterns.
- [ ] Check that audit and logging behaviors are verified in tests.

## Documentation
- [ ] Ensure API documentation includes request/response schemas, error codes, and authorization requirements.
- [ ] Confirm event schema definitions are published and versioned in the shared repository.
- [ ] Verify business rules and domain invariants are documented and accessible to developers.
- [ ] Ensure operational runbooks include instructions for handling failures, conflicts, and alerts.
- [ ] Confirm that audit and observability requirements are documented for compliance and support teams.
- [ ] Validate that all story acceptance criteria are traceable to implementation and tests.

---

This checklist should be used as a guide to systematically verify the completeness, correctness, and robustness of any story implementation in the accounting domain.
