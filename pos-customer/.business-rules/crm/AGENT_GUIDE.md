# CRM Domain Agent Guide

## Purpose
The CRM domain is the authoritative system of record for customer relationship data within the modular POS system. It manages party (individuals and organizations), contact points, vehicle records, party relationships, communication preferences, promotions, and billing-related customer snapshots. The domain ensures data integrity, enforces business invariants, and exposes stable APIs and event streams for downstream consumers such as Workorder Execution and Billing systems.

## Domain Boundaries
- **Authoritative Ownership:** CRM owns master data for Parties (Persons and Organizations), Vehicles, Party Relationships, Contact Points, Communication Preferences, Promotions, and Billing Rules.
- **Read-Only Consumers:** Workorder Execution, Billing, and other downstream domains consume CRM data but do not modify it.
- **Event-Driven Integration:** CRM consumes domain events from Workorder Execution (e.g., VehicleUpdated, PromotionRedeemed) and emits domain events (e.g., PersonCreated, VehicleOwnerAssociated).
- **Data Scope:** CRM manages customer identity, contactability, vehicle assets, party relationships, communication consent, promotion redemptions, and billing configuration snapshots.

## Key Entities / Concepts
- **Party / Person:** Individual customer records with immutable `personId`, names, preferred contact method, and associated contact points.
- **ContactPoint:** Multiple labeled emails and phone numbers per party, with a single primary per kind (`EMAIL`, `PHONE`).
- **PartyRelationship:** Associations between parties (e.g., linking individuals to commercial accounts) with roles (`APPROVER`, `BILLING`), effective date ranges, and primary billing contact designation.
- **Vehicle:** Unique vehicle records identified by `vehicleId`, VIN, unit number, description, and optional license plate.
- **VehiclePartyAssociation:** Links vehicles to owning accounts and optionally primary drivers, with effective dating and atomic reassignment.
- **CommunicationPreference:** Per-customer communication channel preferences and consent flags (email marketing, SMS notifications).
- **PromotionRedemption:** Records of promotion usage linked to work orders and invoices, ensuring idempotent redemption tracking.
- **CustomerSnapshot:** Aggregated read model combining account, contacts, vehicles, and billing rules for efficient downstream consumption.
- **MergeAudit / PartyAlias:** Records supporting merging duplicate parties, preserving referential integrity and audit trails.

## Invariants / Business Rules
- **Party Creation:** `firstName` and `lastName` are required; `personId` is system-generated and immutable.
- **Contact Points:** Zero or more per party; only one primary per kind (`EMAIL`, `PHONE`); invalid formats rejected.
- **Party Relationships:** No overlapping active relationships for the same party pair and role; exactly one primary billing contact per commercial account at a time; deactivation sets `effectiveEndDate`.
- **Vehicle Ownership:** Exactly one active `OWNER` per vehicle; creating a new owner atomically ends the previous owner association.
- **Communication Preferences:** Consent flags default to opt-out if null; `updateSource` is mandatory for auditability.
- **Promotion Redemption:** Idempotent processing keyed by `promotionId` and `workOrderId`; atomic creation and counter increments.
- **Customer Snapshot:** Must include account metadata, active contacts (with primary), associated vehicles, and billing rules with defaults applied if missing.
- **Merge Parties:** Only two parties merged at a time; source party status set to `MERGED`; all child entities reassociated; aliases created for ID redirection.

## Events / Integrations
- **Inbound Events:**
  - `VehicleUpdated` (from Workorder Execution): Updates vehicle data with idempotency and conflict resolution.
  - `PromotionRedeemed` (from Workorder Execution): Records promotion usage, increments counters atomically.
- **Outbound Events:**
  - `PERSON_CREATED`: Emitted on successful person creation.
  - `VehicleOwnerAssociated`, `VehicleOwnerReassigned`, `VehiclePrimaryDriverAssigned`: Emitted on vehicle-party association changes.
  - Audit events for contact point changes, communication preference updates, party merges, and relationship changes.
- **APIs:**
  - Party creation and management endpoints (TBD).
  - Relationship management endpoints (TBD).
  - Vehicle creation, lookup, and association endpoints (TBD).
  - Communication preferences CRUD endpoints (TBD).
  - Promotion redemption event consumer (no direct API).
  - Customer snapshot read API (`GET /v1/crm-snapshot`).
  - Party merge API (TBD).
  - Vehicle search API (TBD).

## API Expectations (High-Level)
- RESTful endpoints secured with service-to-service authentication and authorization.
- Input validation with clear error responses (`400 Bad Request`, `404 Not Found`, `403 Forbidden`, `409 Conflict`).
- Idempotent operations where applicable (e.g., event consumption, merges).
- Stable, versioned snapshot API with pagination and filtering.
- Event-driven asynchronous processing for updates from external domains.
- Detailed error handling and rollback on persistence failures.

## Security / Authorization Assumptions
- All API calls require authentication and authorization.
- Service-to-service calls must be allowlisted and possess required scopes (e.g., `crm.snapshot.read`).
- User-level operations (e.g., CSR actions) require role-based access control.
- Sensitive data access is restricted; audit logs capture actor identity.
- No secrets or credentials are stored or exposed in logs or events.
- Authorization is enforced at API gateway and service layers.

## Observability (Logs / Metrics / Tracing)
- **Audit Logs:** Capture all create/update/delete operations on core entities with actor ID, timestamps, and before/after states.
- **Event Logs:** Log all inbound and outbound domain events with correlation IDs.
- **Metrics:**
  - Counters for creations (persons, relationships, vehicles), updates, merges, and promotion redemptions.
  - Error rates and validation failures.
  - Cache hit/miss rates for snapshot API.
  - Event processing latency and throughput.
- **Tracing:** Distributed tracing for API calls and event processing to enable end-to-end request visibility.
- **Alerts:** Trigger on repeated failures, DLQ entries, and unauthorized access attempts.

## Testing Guidance
- **Unit Tests:** Validate business rules, input validation, and domain logic for each entity.
- **Integration Tests:** Cover API endpoints with authentication, authorization, and error scenarios.
- **Event Processing Tests:** Verify idempotency, conflict resolution, and audit logging for inbound events.
- **Contract Tests:** Ensure snapshot API response schema stability and backward compatibility.
- **Performance Tests:** Benchmark snapshot API under load; validate cache effectiveness.
- **Security Tests:** Verify access control enforcement and absence of sensitive data leakage.
- **End-to-End Tests:** Simulate workflows involving multiple domains (e.g., creating a person, associating to account, vehicle assignment, snapshot retrieval).
- **Data Migration / Merge Tests:** Validate merging logic, alias resolution, and referential integrity.

## Common Pitfalls
- **Ignoring Idempotency:** Duplicate event processing can cause inconsistent state; always check for prior processing.
- **Violating Uniqueness Constraints:** Overlapping active relationships or multiple primary contacts violate invariants.
- **Partial Updates Without Validation:** Incomplete or invalid data can corrupt master records; enforce strict validation.
- **Race Conditions on Primary Flags:** Concurrent updates to primary contact or billing contact flags must be atomic.
- **Cache Staleness:** Not invalidating or refreshing snapshots timely leads to stale data exposure.
- **Insufficient Authorization Checks:** Exposing sensitive customer data without proper access control risks compliance violations.
- **Unclear Conflict Resolution:** Lack of defined policies for conflicting vehicle updates or merges causes data integrity issues.
- **Overloading Snapshot API:** Returning excessive data or unpaginated large result sets harms performance.
- **Ignoring Audit Requirements:** Missing audit trails impedes troubleshooting and compliance.
- **Hard Deletes Instead of Logical End-Dating:** Leads to loss of historical data and breaks referential integrity.

---

*This guide summarizes the CRM domain's key responsibilities, data ownership, workflows, and integration points to support secure, consistent, and observable operations within the POS ecosystem.*
