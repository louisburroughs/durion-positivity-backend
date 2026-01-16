# CRM Story Validation Checklist

This checklist is intended for engineers and reviewers to validate story implementations within the CRM domain. It covers key areas to ensure correctness, security, observability, and maintainability.

---

## Scope / Ownership
- [ ] Verify the story aligns with CRM domain ownership and responsibilities.
- [ ] Confirm the story’s actors and stakeholders are correctly identified.
- [ ] Ensure preconditions (authentication, authorization, existence of referenced entities) are clearly defined and enforced.
- [ ] Confirm that the story does not overlap or conflict with other domain responsibilities.
- [ ] Validate that downstream consumers and integrations are considered and respected.

---

## Data Model & Validation
- [ ] Confirm all required fields are present and correctly typed according to the story’s data requirements.
- [ ] Verify that unique identifiers (e.g., `personId`, `vehicleId`, `partyRelationshipId`) are system-generated, immutable, and globally unique (UUID).
- [ ] Validate enum fields use the defined controlled vocabularies and reject invalid values.
- [ ] Ensure format validations are implemented (e.g., email format, VIN length and characters).
- [ ] Check that optional fields are handled correctly and nullable where appropriate.
- [ ] Confirm business rules on data uniqueness, relationships, and constraints are enforced (e.g., single primary contact per kind, no overlapping active relationships).
- [ ] Verify that transactional integrity is maintained for multi-entity operations (e.g., creating Person with ContactPoints).
- [ ] Confirm that duplicate detection or merging behavior is implemented as per story requirements (e.g., duplicates allowed or merge disabled).

---

## API Contract
- [ ] Validate that API endpoints follow RESTful conventions and use appropriate HTTP methods.
- [ ] Confirm request and response schemas match the story’s data requirements and acceptance criteria.
- [ ] Verify correct HTTP status codes are returned for success and error cases (e.g., 201 Created, 400 Bad Request, 404 Not Found, 409 Conflict, 500 Internal Server Error).
- [ ] Ensure error responses include meaningful messages for client troubleshooting.
- [ ] Confirm idempotency where required (e.g., create/update operations).
- [ ] Check that API contracts are versioned and backward compatible if applicable.
- [ ] Validate that query parameters and filters behave as specified (e.g., filtering active relationships).

---

## Events & Idempotency
- [ ] Confirm domain events are emitted on key state changes (e.g., `PERSON_CREATED`, `VehicleOwnerAssociated`).
- [ ] Verify event payloads include all required identifiers and metadata (actorId, timestamps).
- [ ] Ensure event emission is transactional with state changes to avoid inconsistencies.
- [ ] Validate idempotency mechanisms for event consumption and processing (e.g., deduplication by eventId).
- [ ] Confirm that event consumers can rely on event ordering and uniqueness guarantees.
- [ ] Check that audit events are distinct and capture meaningful changes (e.g., primary billing contact changes).

---

## Security
- [ ] Verify that all endpoints enforce authentication and authorization according to the story’s preconditions.
- [ ] Confirm that sensitive data is not exposed in API responses or logs.
- [ ] Ensure role-based access control is implemented and tested.
- [ ] Validate input sanitization to prevent injection attacks.
- [ ] Confirm that audit logs capture the identity of the actor performing changes.
- [ ] Check that service-to-service calls require appropriate scopes and allowlists.
- [ ] Ensure error messages do not leak sensitive information.

---

## Observability
- [ ] Confirm audit logging is implemented for all create, update, delete, and merge operations.
- [ ] Verify that audit logs include before/after states or sufficient change details.
- [ ] Ensure metrics are emitted for key operations (e.g., `person_creation_total`, `party_merges_total`).
- [ ] Validate that error and warning logs include context (entity IDs, user IDs, correlation IDs).
- [ ] Confirm that event emission and processing failures are logged and monitored.
- [ ] Check that API request/response metrics and latencies are tracked.
- [ ] Ensure alerts are configured for critical failures or unusual patterns (e.g., high duplicate event rates).

---

## Performance & Failure Modes
- [ ] Verify that database queries are optimized and indexed on key fields (e.g., UUIDs, foreign keys).
- [ ] Confirm that bulk operations handle large payloads efficiently (e.g., multiple ContactPoints).
- [ ] Ensure transactions are scoped to minimize locking and contention.
- [ ] Validate graceful handling of downstream system unavailability (e.g., retry, circuit breaker).
- [ ] Confirm that cache strategies (if applicable) are defined and tested.
- [ ] Check that error flows return appropriate status codes and do not leak internal errors.
- [ ] Verify rollback behavior on persistence failures to maintain data consistency.

---

## Testing
- [ ] Unit tests cover all business rules, validation logic, and error flows.
- [ ] Integration tests verify end-to-end behavior including database persistence and event emission.
- [ ] Security tests validate authorization enforcement and input sanitization.
- [ ] Idempotency tests confirm duplicate event handling and no side effects on repeated requests.
- [ ] Performance tests ensure acceptable response times under expected load.
- [ ] Negative tests cover invalid inputs, missing entities, and unauthorized access.
- [ ] Acceptance criteria from the story are fully automated as test cases.
- [ ] Mock external dependencies and verify interactions (e.g., event bus, downstream APIs).

---

## Documentation
- [ ] API documentation is complete, including request/response schemas, status codes, and error messages.
- [ ] Data model diagrams or descriptions are updated to reflect new or changed entities.
- [ ] Event schemas and contracts are documented with versioning and payload details.
- [ ] Security requirements and access control policies are documented.
- [ ] Operational runbooks include instructions for monitoring, troubleshooting, and recovery.
- [ ] Release notes highlight new features, breaking changes, and migration steps if any.
- [ ] Open questions and decisions are recorded and linked to the story.
- [ ] README or onboarding docs include usage examples and integration points.

---

# End of Checklist
