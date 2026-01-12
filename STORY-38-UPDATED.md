# [BACKEND] [STORY] Configuration: Define Default Staging and Quarantine Storage Locations for a Site

## 🏷️ Labels (Applied)

### Required
- type:story
- status:ready-for-dev
- domain:location
- domain:inventory

### Recommended
- agent:story-authoring

---

## ✅ Clarification Resolutions Applied

**Clarification Issue**: #235
**Resolved Date**: 2026-01-12
**Resolved By**: @louisburroughs

### Resolution Summary

1. **Story Split Confirmed**: This story now focuses ONLY on configuration. A separate story for receiving workflow execution has been created (see Related Stories below).

2. **Uniqueness Rule Confirmed**: A `StorageLocation` cannot be designated as both default Staging and default Quarantine. This is enforced via validation.

3. **Permission Model Confirmed**: Permission definition and enforcement for quarantine moves is out of scope for this story and belongs to `domain:security` and `domain:inventory` execution stories.

---

## Story Intent

As an **Inventory Manager**, I want to configure default staging and quarantine storage locations for each site, so that all receiving workflows are standardized, consistent, and adhere to inventory handling policies.

## Actors & Stakeholders

- **Primary Actor:**
  - **Inventory Manager:** Responsible for configuring and maintaining the operational topology and policies of a warehouse or site.

- **Secondary Actors & Stakeholders:**
  - **System:** The POS/WMS system that must persist and expose the configured defaults.
  - **`domain:workexec`:** A key stakeholder and consumer of this configuration (consuming story: see Related Stories).
  - **`domain:audit`:** Requires events to be published when these critical site configurations are modified.

## Preconditions

1. The system has a concept of a `Site` or `Location` which represents a distinct physical or logical facility (e.g., a warehouse).
2. The system has a concept of a `StorageLocation` which represents a specific place within a `Site` where inventory can be held (e.g., a bin, a rack, or an area).
3. An authentication and authorization system is in place, capable of granting permissions to the `Inventory Manager` role to modify `Site` configurations.

## Functional Behavior

This story focuses **exclusively** on the **configuration** of the default locations. The consumption of these settings by receiving or other processes is handled in separate stories.

1. **Trigger:** An authorized `Inventory Manager` uses an API to update the default location settings for a specific `Site`.

2. **Behavior:**
   - The manager provides the unique identifier for a `Site`.
   - The manager provides the unique identifier of an existing `StorageLocation` to be designated as the **Default Staging Location**.
   - The manager provides the unique identifier of an existing `StorageLocation` to be designated as the **Default Quarantine Location**.

3. **Outcome:**
   - The system validates that both provided `StorageLocation` identifiers are:
     - Valid
     - Distinct (not the same location)
     - Belong to the specified `Site`
   - The system persists these two references against the `Site`'s configuration.
   - The system emits a `SiteDefaultsUpdated` event with the old and new values for auditing purposes.

## Alternate / Error Flows

1. **Unauthorized Access:** If the user does not have the required permissions to modify the `Site` configuration, the system rejects the request with a `403 Forbidden` error.

2. **Invalid `Site` ID:** If the specified `Site` does not exist, the system rejects the request with a `404 Not Found` error.

3. **Invalid `StorageLocation` ID:** If either the staging or quarantine `StorageLocation` ID does not exist or does not belong to the specified `Site`, the system rejects the request with a `400 Bad Request` or `422 Unprocessable Entity` validation error.

4. **Duplicate Location Assignment (Business Rule Violation):** If the same `StorageLocation` ID is provided for both staging and quarantine roles, the system rejects the request with a `400 Bad Request` validation error with error code `DEFAULT_LOCATION_ROLE_CONFLICT`.

## Business Rules

1. Each `Site` MUST be configurable with exactly one `Default Staging Location`.
2. Each `Site` MUST be configurable with exactly one `Default Quarantine Location`.
3. **The `Default Staging Location` and `Default Quarantine Location` for a single `Site` MUST be two distinct `StorageLocation`s.** (Enforced to prevent operational ambiguity and ensure physical/procedural separation.)
4. Inventory placed in a `Quarantine Location` is considered non-available stock. The permission model for moving items **out of** quarantine (e.g., `inventory.move.from_quarantine`) is defined and enforced by separate security and inventory execution stories. This story only marks the location as quarantine.

## Data Requirements

- The `Site` (or `Location`) entity/resource requires two new fields, both of which are references to a `StorageLocation`:
  - `defaultStagingLocationId: UUID`
  - `defaultQuarantineLocationId: UUID`

- An API endpoint must be created or updated to manage these settings:
  - **Example:** `PUT /api/v1/sites/{siteId}/default-locations`
  - **Example Payload:**
    ```json
    {
      "defaultStagingLocationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
      "defaultQuarantineLocationId": "6ec0bd7f-11c0-43da-975e-2a8ad9ebae0b"
    }
    ```

## Acceptance Criteria

### AC1: Configure Defaults Successfully
- **Given** I am an authenticated `Inventory Manager`
- **And** Site "WH-1" exists with Storage Locations "STAGING-A" and "QUARANTINE-B"
- **When** I submit a request to set "STAGING-A" as the default staging location and "QUARANTINE-B" as the default quarantine location for "WH-1"
- **Then** the system returns a `200 OK` success response
- **And** a subsequent GET request for the configuration of "WH-1" shows the correct IDs for the default locations.

### AC2: Reject Invalid Storage Location
- **Given** I am an authenticated `Inventory Manager`
- **And** Site "WH-1" exists
- **When** I submit a request to set a non-existent Storage Location "FAKE-ID" as the default staging location
- **Then** the system returns a client error response (e.g., `400 Bad Request` or `422 Unprocessable Entity`) with a descriptive error message.

### AC3: Reject Duplicate Role Assignment (Business Rule Enforcement)
- **Given** I am an authenticated `Inventory Manager`
- **And** Site "WH-1" exists with Storage Location "COMMON-AREA"
- **When** I submit a request to set "COMMON-AREA" as both the default staging and default quarantine location
- **Then** the system returns a `400 Bad Request` validation error with error code `DEFAULT_LOCATION_ROLE_CONFLICT`
- **And** the error message states that the locations must be distinct.

### AC4: Reject Unauthorized Request
- **Given** I am an authenticated user without `Inventory Manager` permissions
- **When** I attempt to update the default locations for Site "WH-1"
- **Then** the system returns a `403 Forbidden` error.

### AC5: Validate Storage Location Belongs to Site
- **Given** I am an authenticated `Inventory Manager`
- **And** Site "WH-1" exists
- **And** Storage Location "STAGING-X" belongs to Site "WH-2" (different site)
- **When** I submit a request to set "STAGING-X" as the default staging location for "WH-1"
- **Then** the system returns a `400 Bad Request` or `422 Unprocessable Entity` error
- **And** the error message indicates the storage location does not belong to the specified site.

## Audit & Observability

- **Event Emission:** A `SiteDefaultsUpdated` event MUST be emitted to a message bus (e.g., Kafka, RabbitMQ) upon any successful change.

- **Event Payload:** The event should include:
  - `siteId`
  - `updatedByUserId`
  - `timestamp`
  - Previous values: `previousDefaultStagingLocationId`, `previousDefaultQuarantineLocationId`
  - New values: `newDefaultStagingLocationId`, `newDefaultQuarantineLocationId`

- **Logging:** A structured log entry at the `INFO` level should be created, capturing the user, site, and changes made for traceability.

## Related Stories

### Dependency (This Story Must Be Completed First)
This story is a prerequisite for:

- **[NEW STORY]**: "[BACKEND] [STORY] Receiving: Use Site-Default Staging Location"
  - Domain: `domain:workexec`
  - Description: Receiving workflow consumes the site-default staging and quarantine locations configured in this story.

## Original Story (Unmodified – For Traceability)

# Issue #38 — [BACKEND] [STORY] Topology: Define Default Staging and Quarantine Locations for Receiving

## Current Labels
- backend
- story-implementation
- user

## Current Body
## Backend Implementation for Story

**Original Story**: [STORY] Topology: Define Default Staging and Quarantine Locations for Receiving

**Domain**: user

### Story Description

/kiro
# User Story
## Narrative
As an **Inventory Manager**, I want default receiving staging and quarantine locations so that receiving workflows are consistent.

## Details
- Each site can define staging and quarantine locations.
- Quarantine requires approval to move into available stock.

## Acceptance Criteria
- Staging/quarantine configured per location.
- Receiving uses staging by default.
- Quarantine moves require permission.

## Integrations
- Distributor receiving may land in staging; quality hold uses quarantine.

## Data / Entities
- ReceivingPolicy, StorageLocationRef, PermissionCheck

## Classification (confirm labels)
- Type: Story
- Layer: Domain
- Domain: Inventory Management


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
