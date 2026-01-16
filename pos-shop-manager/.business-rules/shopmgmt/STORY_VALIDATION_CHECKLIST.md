# Story Validation Checklist for `shopmgmt` Domain

This checklist is intended for engineers and reviewers to validate story implementations within the `shopmgmt` domain. It covers key aspects from scope to documentation to ensure secure, reliable, and maintainable delivery.

---

## Scope / Ownership

- [ ] Verify the story aligns with the `shopmgmt` domain responsibilities and boundaries.
- [ ] Confirm all primary and secondary actors/stakeholders are accounted for in the implementation.
- [ ] Ensure story intent and functional behavior match the acceptance criteria and resolved decisions.
- [ ] Check that preconditions are enforced before story logic executes.
- [ ] Confirm that any integration points with external services (Work Execution, People/HR, Notification, Audit) are correctly identified and handled.

---

## Data Model & Validation

- [ ] Validate all input data models (e.g., `AppointmentRequest`, `AppointmentUpdate`) have required fields and correct types.
- [ ] Confirm referential integrity for immutable links (e.g., estimate/work order IDs) is enforced and unique constraints applied.
- [ ] Check validation rules for business logic (e.g., eligibility states, skill requirements, bay types) are implemented.
- [ ] Verify duration calculation precedence and override rules are correctly applied.
- [ ] Ensure conflict detection categorizes conflicts into hard vs soft correctly and applies override permissions.
- [ ] Confirm all enums (e.g., reschedule reasons) and flags (e.g., assignmentStatus) are validated and used consistently.
- [ ] Validate that data caches (e.g., skill read models) are refreshed or invalidated appropriately.

---

## API Contract

- [ ] Confirm API endpoints accept and return data conforming to the documented models.
- [ ] Verify that all required permissions (`CREATE_APPOINTMENT`, `RESCHEDULE_APPOINTMENT`, `OVERRIDE_SCHEDULING_CONFLICT`, etc.) are enforced at the API boundary.
- [ ] Check that error responses clearly distinguish between validation errors, permission errors, and system errors.
- [ ] Ensure idempotency is supported where applicable (e.g., event emission, appointment creation).
- [ ] Validate that API supports configurable assignment strategies per facility.
- [ ] Confirm that APIs expose necessary query parameters or filters for scoped data access (e.g., facility scoping, date ranges).

---

## Events & Idempotency

- [ ] Verify all domain events (`AppointmentCreatedFromEstimate`, `AppointmentRescheduled`, `AssignmentUpdated`, etc.) are emitted with required metadata (`eventId`, `appointmentId`, `version`).
- [ ] Confirm events are designed to be idempotent and consumers handle duplicates gracefully.
- [ ] Check that events include all necessary context for downstream consumers (e.g., Work Execution, Notification Service).
- [ ] Ensure audit events are emitted for critical actions (creation attempts, conflicts, overrides, notifications).
- [ ] Validate event schemas match documented data models.
- [ ] Confirm event emission occurs only after successful state changes.

---

## Security

- [ ] Confirm RBAC permissions are enforced consistently for all operations.
- [ ] Verify sensitive operations (e.g., conflict overrides, duration overrides, reschedules within minimum notice) require elevated permissions and audit logging.
- [ ] Ensure override reasons are mandatory and recorded for audit.
- [ ] Validate that user identity (`createdBy`, `rescheduledBy`) is captured and propagated.
- [ ] Check that data exposure respects facility and organizational scoping.
- [ ] Confirm no secrets or sensitive information are logged or exposed in APIs/events.
- [ ] Verify input validation prevents injection, enumeration, or privilege escalation attacks.

---

## Observability

- [ ] Confirm all key domain actions emit audit and observability events (e.g., `AppointmentCreationAttempted`, `ConflictDetected`, `NotificationSent`).
- [ ] Verify metrics are collected for success rates, conflict/override rates, assignment queue times, notification delivery, and duration estimation accuracy.
- [ ] Ensure logs include sufficient context for troubleshooting without leaking sensitive data.
- [ ] Check that notification failures emit `NotificationFailed` events and trigger manual follow-up alerts.
- [ ] Validate that assignment updates and GPS staleness warnings are observable.
- [ ] Confirm audit logs capture actor identity, action, reason, and timestamps immutably.

---

## Performance & Failure Modes

- [ ] Verify conflict detection and assignment logic perform efficiently under expected load.
- [ ] Confirm fallback mechanisms exist for external service unavailability (e.g., cached assignments, degraded UI).
- [ ] Ensure event emission and processing are resilient and retries are implemented where appropriate.
- [ ] Check that notification retries follow defined policies (SMS 1x, Email 2x).
- [ ] Validate that deferred assignments meet SLA requirements (e.g., assign by prior business day end or within 2 hours same-day).
- [ ] Confirm that system gracefully handles partial failures (e.g., mechanic unavailable on reschedule).
- [ ] Verify that polling and real-time update mechanisms meet latency targets (e.g., p95 <5s for assignment updates).

---

## Testing

- [ ] Confirm unit tests cover all business rules, validation logic, and permission checks.
- [ ] Verify integration tests cover interactions with external services and event emission.
- [ ] Ensure conflict scenarios (hard/soft) and override flows are tested.
- [ ] Validate edge cases for duration overrides, skill matching, and assignment preservation.
- [ ] Confirm tests cover notification sending, retry logic, and failure handling.
- [ ] Check tests for audit log creation and content correctness.
- [ ] Verify UI/UX tests for assignment visibility, real-time updates, and staleness warnings (if applicable).
- [ ] Ensure test data respects domain constraints and preconditions.

---

## Documentation

- [ ] Confirm story implementation is documented with intent, actors, preconditions, and functional behavior.
- [ ] Verify API documentation includes request/response schemas, permissions, error codes, and examples.
- [ ] Ensure event schemas and semantics are documented for consumers.
- [ ] Document configuration options (e.g., assignment strategies, override permissions).
- [ ] Provide operational runbooks or notes for monitoring key metrics and handling failures.
- [ ] Include audit and observability guidance for compliance and troubleshooting.
- [ ] Update README or domain-level docs with any new dependencies or integration points.
- [ ] Ensure no secrets or sensitive information are included in documentation.

---

*End of checklist.*
