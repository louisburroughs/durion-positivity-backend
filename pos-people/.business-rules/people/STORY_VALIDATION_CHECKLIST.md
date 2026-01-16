# Story Validation Checklist for Domain: People

This checklist is intended for engineers and reviewers to validate story implementations within the **people** domain. It ensures consistency, correctness, security, and observability aligned with domain requirements and best practices.

---

## Scope / Ownership
- [ ] Verify the story aligns with the **people** domain boundaries and responsibilities.
- [ ] Confirm the story’s actors and stakeholders are correctly identified and addressed.
- [ ] Ensure the story respects domain ownership of entities such as User, Person, EmployeeProfile, RoleAssignment, PersonLocationAssignment, TimeEntry, and related aggregates.
- [ ] Confirm no cross-domain responsibilities are improperly handled (e.g., Work Execution or Security domain logic).

---

## Data Model & Validation
- [ ] Validate all required fields and enums are present and correctly typed as per story data requirements.
- [ ] Confirm uniqueness constraints (e.g., `employeeNumber`, `primaryEmail`, `(tenantId, sessionId)` for idempotency) are enforced at the database and application level.
- [ ] Check that status lifecycle enums and transitions conform to the authoritative definitions (`ACTIVE`, `DISABLED`, `TERMINATED`, etc.).
- [ ] Ensure effective dating fields (`effectiveStartAt`, `effectiveEndAt`, `statusEffectiveAt`) are validated for logical consistency (start ≤ end).
- [ ] Verify that scope constraints (e.g., RoleAssignment `allowedScopes`, Location required for LOCATION scope) are enforced.
- [ ] Confirm duplicate detection logic is implemented according to policy (hard-block vs. soft warning).
- [ ] Validate that no physical deletes occur for soft-deleted or logically disabled entities.
- [ ] Check that all mandatory fields for creation/update are validated and missing or malformed data results in clear errors.

---

## API Contract
- [ ] Confirm REST API endpoints exist and follow RESTful conventions for create, update, query, and action operations.
- [ ] Validate request and response payloads conform to the specified schema and include all required fields.
- [ ] Ensure HTTP status codes are used appropriately (e.g., `201 Created` on creation, `400 Bad Request` for validation errors, `403 Forbidden` for authorization failures, `404 Not Found` for missing resources, `409 Conflict` for duplicates or overlapping assignments).
- [ ] Verify idempotency keys and deduplication mechanisms are exposed and respected in APIs where applicable.
- [ ] Confirm APIs enforce input validation including enum values, date formats, and referential integrity.
- [ ] Check that APIs support filtering, paging, and scoping parameters as specified (e.g., locationId, technicianIds).
- [ ] Ensure APIs return informative error messages to aid client troubleshooting.

---

## Events & Idempotency
- [ ] Verify domain events are published for all state changes with correct event types, payloads, and versioned schemas.
- [ ] Confirm events include necessary metadata: eventId, timestamp, actor, subject, version, and reason codes where applicable.
- [ ] Check that event ordering and monotonic versioning are enforced to prevent stale or out-of-order updates.
- [ ] Ensure idempotency keys are used to deduplicate incoming events and API requests.
- [ ] Validate retry and dead letter queue (DLQ) mechanisms exist for downstream command failures, with exponential backoff and alerting.
- [ ] Confirm manual intervention workflows are supported for DLQ items, including re-run, risk acceptance, and export capabilities.

---

## Security
- [ ] Confirm all actions require appropriate authentication and authorization checks (e.g., `user.disable`, `people.employee.create`, `timekeeping:approve`).
- [ ] Verify permission enforcement prevents unauthorized access or modifications.
- [ ] Ensure sensitive data is not exposed in logs, events, or API responses.
- [ ] Check that disabled or terminated users cannot authenticate or be assigned work.
- [ ] Validate that elevated permissions are required for optional or exceptional flows (e.g., keeping assignments active on disable).
- [ ] Confirm audit trails include actor identity and are immutable.

---

## Observability
- [ ] Ensure audit events are emitted for all create, update, disable, approve/reject, and assignment changes with sufficient detail.
- [ ] Confirm structured logging at appropriate levels (INFO, WARN, ERROR, CRITICAL) for key operations and failures.
- [ ] Verify metrics are collected for success/failure counts, retry queue depth, DLQ counts, and latency.
- [ ] Check alerting rules are defined and triggered for critical conditions (e.g., DLQ messages, retry queue thresholds).
- [ ] Confirm correlation IDs or request tracing is supported for troubleshooting.

---

## Performance & Failure Modes
- [ ] Validate that atomic transactions are used where required (e.g., status updates, primary assignment demotion).
- [ ] Confirm downstream commands are issued asynchronously with retry and DLQ handling.
- [ ] Ensure that primary guarantees (e.g., immediate authentication block on disable) are met even if downstream services lag.
- [ ] Check that bulk queries and batch processing are used for report generation and reconciliation to optimize performance.
- [ ] Verify that error handling paths do not cause data corruption or inconsistent states.
- [ ] Confirm fallback and retry strategies are implemented for transient failures.

---

## Testing
- [ ] Unit tests cover all business rules, validation logic, and edge cases.
- [ ] Integration tests verify API contracts, event publishing, and downstream interactions.
- [ ] Security tests validate permission enforcement and authentication blocking.
- [ ] Idempotency tests ensure duplicate requests/events do not cause side effects.
- [ ] Failure injection tests simulate downstream failures and verify retry/DLQ behavior.
- [ ] Performance tests validate response times and resource usage under expected load.
- [ ] Regression tests cover status lifecycle transitions and data integrity.

---

## Documentation
- [ ] API documentation is complete, accurate, and includes request/response examples.
- [ ] Event schemas and contracts are documented with versioning and payload details.
- [ ] Data model diagrams or ER diagrams are updated to reflect entity changes.
- [ ] Operational runbooks include instructions for manual intervention on DLQ items.
- [ ] Security and compliance documentation covers audit logging and data retention policies.
- [ ] User-facing documentation or UI help text reflects new workflows and error messages.
- [ ] Known limitations, open questions, and assumptions are clearly documented.

---

# End of Checklist
