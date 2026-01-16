# AGENT_GUIDE.md — Location Domain

---

## Purpose

The Location domain manages the authoritative representation and lifecycle of business locations within the POS ecosystem. It provides CRUD operations for locations, including hierarchical relationships, operational status, and timezone data. This domain serves as the system of record for location metadata consumed by scheduling, staffing, inventory, and work execution services.

---

## Domain Boundaries

- **In-Scope:**  
  - Creation, retrieval, update, and soft-deactivation of Location entities.  
  - Management of location attributes: code, name, address, timezone, status, parent location hierarchy, operating hours, holiday closures, buffers, bays, mobile units, coverage rules, and travel buffer policies.  
  - Validation of location data integrity and uniqueness constraints.  
  - Emission of domain events reflecting location lifecycle changes.

- **Out-of-Scope:**  
  - Enforcement of business rules that depend on location status in consuming domains (e.g., preventing staffing assignments to inactive locations).  
  - Scheduling, staffing, or pricing logic that consumes location data.  
  - Authorization beyond location management permissions (delegated to security domain).  
  - Distance calculations or travel time computations (delegated to caller systems).

---

## Key Entities / Concepts

| Entity               | Description                                                                                     |
|----------------------|-------------------------------------------------------------------------------------------------|
| **Location**         | Represents a physical or logical business site with unique code, name, address, timezone, status, and optional parent location. |
| **Bay**              | Service bay within a location, with attributes like name, type, status, capacity, and constraints referencing services and skills. |
| **Mobile Unit**      | Mobile service resource linked to a base location, with capabilities, coverage rules, status, and travel buffer policies. |
| **Service Area**     | Geographic postal-code-based area used to define mobile unit coverage.                          |
| **Travel Buffer Policy** | Defines travel time buffers for mobile units, supporting fixed minutes or distance-tiered policies. |
| **SyncLog**          | Records synchronization runs when importing location data from external authoritative sources (e.g., durion-hr). |

---

## Invariants / Business Rules

- **Location**  
  - `code` is unique across all locations and immutable after creation.  
  - `name` is unique (case-insensitive, trimmed) across all locations.  
  - `timezone` must be a valid IANA time zone identifier.  
  - `parentLocationId`, if provided, must reference an existing Location.  
  - Status transitions allow soft-deactivation (`ACTIVE` → `INACTIVE`); no hard deletes.  
  - Operating hours must be valid local times with no duplicates or overnight ranges.  
  - Buffers (`checkInBufferMinutes`, `cleanupBufferMinutes`) are non-negative integers or null (fall back to global defaults).  
  - Bay names must be unique within their parent Location.  
  - Bay status can be `ACTIVE` or `OUT_OF_SERVICE`; out-of-service bays are excluded from availability queries.  
  - Mobile Units must have exactly one base location and be `ACTIVE` to be schedulable.  
  - Travel Buffer Policies must be valid per their type, with strictly increasing distance tiers and a catch-all tier for `DISTANCE_TIER` policies.  
  - Default staging and quarantine storage locations per site must be distinct and belong to the same site.

- **Cross-Domain Enforcement**  
  - Business rules such as "Inactive locations prevent new staffing assignments" are enforced by consuming domains (e.g., People Service), not within Location domain.

---

## Events / Integrations

- **Domain Events Emitted:**  
  - `pos.location.v1.LocationCreated`  
  - `pos.location.v1.LocationUpdated` (includes status changes)  
  - `pos.location.v1.BayCreated`, `BayUpdated`  
  - `pos.location.v1.MobileUnitCreated`, `MobileUnitUpdated`  
  - `pos.location.v1.TravelBufferPolicyCreated`, `TravelBufferPolicyUpdated`  
  - `pos.location.v1.SiteDefaultsUpdated` (for staging/quarantine location changes)  

- **Inbound Integrations:**  
  - Synchronization from authoritative external systems (e.g., `durion-hr`) to replicate and update location data.  
  - Validation of referenced entities (e.g., service capabilities, skills) against authoritative domains.

- **Outbound Integrations:**  
  - Downstream consumers (People, Work Execution, Pricing) rely on location data and status for operational decisions.  
  - Audit and security domains consume events for compliance and access control.

---

## API Expectations (High-Level)

- **Locations:**  
  - `POST /v1/locations` — Create a new location (status defaults to `ACTIVE`).  
  - `GET /v1/locations/{locationId}` — Retrieve location details.  
  - `GET /v1/locations?status=ACTIVE|INACTIVE|ALL` — List locations filtered by status.  
  - `PUT /v1/locations/{locationId}` — Full update (immutable `code`).  
  - `PATCH /v1/locations/{locationId}` — Partial update (preferred for status changes).  

- **Bays:**  
  - `POST /v1/locations/{locationId}/bays` — Create bay under location.  
  - `GET /v1/locations/{locationId}/bays` — List bays with optional status filter.  
  - `GET /v1/locations/{locationId}/bays/{bayId}` — Get bay details.  
  - `PATCH /v1/locations/{locationId}/bays/{bayId}` — Update bay attributes.  

- **Mobile Units:**  
  - `POST /v1/mobile-units` — Create mobile unit.  
  - `GET /v1/mobile-units:eligible?postalCode=...&countryCode=...&at=...` — Query eligible mobile units by coverage.  

- **Travel Buffer Policies:**  
  - `POST /v1/travel-buffer-policies` — Create policy.  
  - `GET /v1/travel-buffer-policies/{id}` — Retrieve policy.  

- **Site Default Locations:**  
  - `PUT /api/v1/sites/{siteId}/default-locations` — Configure default staging and quarantine locations.  

- **Note:** Detailed API contracts and payload schemas are **TBD**.

---

## Security / Authorization Assumptions

- All modifying operations require authenticated users with explicit permissions scoped to location management, e.g., `location:manage`, `location.mobile-unit.manage`.  
- Read operations may be scoped or public depending on consumer needs.  
- Authorization enforcement is external or via API gateway/security domain; Location domain assumes caller is authorized.  
- Sensitive data (e.g., internal IDs) is not exposed beyond necessary API responses.  
- Audit logs capture actor identity and changes for compliance.

---

## Observability (Logs / Metrics / Tracing)

- **Logging:**  
  - Structured logs at INFO level for all create/update/deactivate operations, including actor, resource IDs, and diffs.  
  - WARN/ERROR logs for validation failures, sync errors, and integration issues.  
  - Correlation IDs propagated for distributed tracing.

- **Metrics:**  
  - Counters for locations created, updated, deactivated.  
  - Bay and mobile unit counts by status.  
  - Sync job run counts, durations, and failure rates.  
  - API request latency and error rates.

- **Tracing:**  
  - Distributed tracing enabled for API calls and sync processes to diagnose latency and failures.  

---

## Testing Guidance

- **Unit Tests:**  
  - Validate business rules and invariants (e.g., uniqueness, timezone validation, status transitions).  
  - Mock external dependencies (e.g., service catalog, skill domain) for constraint validation.

- **Integration Tests:**  
  - End-to-end API tests covering happy and error paths for location, bay, mobile unit, and travel buffer policy management.  
  - Sync process tests simulating source data changes, missing records, and error scenarios.

- **Security Tests:**  
  - Verify authorization enforcement for all modifying endpoints.  
  - Test audit log generation and event emission.

- **Performance Tests:**  
  - Load test sync processes and API endpoints under realistic usage patterns.

- **Cross-Domain Contract Tests:**  
  - Validate emitted events conform to agreed schemas.  
  - Coordinate with consuming domains to verify integration points.

---

## Common Pitfalls

- **Domain Confusion:**  
  - Avoid implementing cross-domain enforcement rules (e.g., preventing staffing assignments to inactive locations) within Location domain; delegate to consuming services.

- **Immutable Fields:**  
  - Do not allow `code` changes after creation; enforce immutability strictly.

- **Timezone Validation:**  
  - Validate timezone strings against IANA database using reliable libraries (e.g., `ZoneId.of()` in Java).

- **Hierarchy Validation:**  
  - Ensure `parentLocationId` references existing locations; prevent cycles or excessive depth (business rules TBD).

- **Uniqueness Checks:**  
  - Normalize names (trim, lowercase) before uniqueness validation to avoid duplicates.

- **Soft Deletes:**  
  - Never hard delete locations; use status flags to deactivate and preserve referential integrity.

- **Concurrency:**  
  - Implement optimistic locking (e.g., version fields) to prevent lost updates.

- **Buffer Defaults:**  
  - Correctly apply global defaults when per-location buffer overrides are null.

- **Constraint References:**  
  - Validate referenced service and skill IDs exist in authoritative domains before persisting bays or mobile units.

- **Event Emission:**  
  - Ensure domain events include sufficient context (e.g., before/after states) and are emitted reliably.

- **Sync Idempotency:**  
  - Sync processes must be idempotent and handle missing-from-feed locations by marking them inactive without deletion.

---

*End of AGENT_GUIDE.md*
