# [BACKEND] [STORY] Receiving: Use Site-Default Staging Location

## 🏷️ Labels (Proposed)

### Required
- type:story
- status:draft
- domain:workexec
- depends-on:issue-38

### Recommended
- agent:story-authoring

---

## Story Intent

As a **Receiving Associate**, I want the receiving workflow to automatically use the site's configured default staging location when I receive inventory, so that the process is standardized and I don't have to manually select a location each time.

## Actors & Stakeholders

- **Primary Actor:**
  - **Receiving Associate:** The warehouse worker performing receiving operations.

- **Secondary Actors & Stakeholders:**
  - **Inventory Manager:** Configured the default locations (in Issue #38).
  - **System:** The POS/WMS receiving workflow that consumes the configuration.
  - **`domain:location` / `domain:inventory`:** Provides the configured default locations.
  - **`domain:audit`:** Requires events to be published when inventory is moved to staging or quarantine.

## Preconditions

1. **Issue #38 must be completed**: The system can retrieve default staging and quarantine locations for a site.
2. A receiving session has been created (e.g., from a PO or ASN).
3. The receiving associate is authenticated and authorized to perform receiving operations.
4. The site has configured default staging and quarantine locations.

## Functional Behavior

### 1. Receiving to Default Staging Location

1. **Trigger:** A Receiving Associate confirms receipt of items in a receiving session.

2. **Behavior:**
   - The system retrieves the site's `defaultStagingLocationId` from the site configuration (configured in Issue #38).
   - The system creates an inventory movement record:
     - **Source**: `RECEIVING` (or null, depending on inventory model)
     - **Destination**: The site's default staging location
     - **Quantity**: As confirmed by the receiving associate
     - **Product/SKU**: From the receiving session
   - The system updates the inventory ledger with the movement.

3. **Outcome:**
   - The received inventory is recorded at the default staging location.
   - An `InventoryReceived` event is emitted with the location details.

### 2. Receiving to Default Quarantine Location (Optional Flow)

1. **Trigger:** A Receiving Associate marks items for quarantine during receiving (e.g., due to damage or quality concerns).

2. **Behavior:**
   - The system retrieves the site's `defaultQuarantineLocationId` from the site configuration.
   - The system creates an inventory movement record to the default quarantine location.
   - The system marks the inventory with a `QUARANTINE` status.

3. **Outcome:**
   - The received inventory is recorded at the default quarantine location with quarantine status.
   - An `InventoryQuarantined` event is emitted.

## Alternate / Error Flows

1. **Default Location Not Configured:** If the site does not have a configured default staging location, the system:
   - Prompts the Receiving Associate to manually select a staging location.
   - Logs a warning that the site configuration is incomplete.

2. **Default Location Not Available:** If the configured default staging location is:
   - Disabled, deleted, or marked as unavailable
   - The system prompts the associate to select an alternative location
   - Logs an error for the Inventory Manager to review the site configuration.

3. **Configuration Retrieval Failure:** If the system cannot retrieve the site configuration (e.g., due to a service outage):
   - The system should fail gracefully with a clear error message.
   - The receiving operation is blocked until configuration can be retrieved.

## Business Rules

1. The receiving workflow SHALL use the site's default staging location unless the associate explicitly overrides it.

2. If the associate selects the quarantine option during receiving, the system SHALL use the site's default quarantine location.

3. The system SHALL NOT allow inventory to be received to a quarantine location without explicit user action (e.g., selecting "Quarantine" option).

4. Permission enforcement for moving items **out of** quarantine is handled by separate inventory movement stories (out of scope for this story).

## Data Requirements

- **API Dependency:** The receiving workflow must call the Site Configuration API to retrieve default locations:
  - **Example:** `GET /api/v1/sites/{siteId}/default-locations`
  - **Expected Response:**
    ```json
    {
      "siteId": "site-123",
      "defaultStagingLocationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
      "defaultQuarantineLocationId": "6ec0bd7f-11c0-43da-975e-2a8ad9ebae0b"
    }
    ```

- **Inventory Movement Record:**
  - `movementId`: UUID
  - `siteId`: UUID
  - `productId`: UUID
  - `fromLocationId`: UUID or null (RECEIVING)
  - `toLocationId`: UUID (default staging or quarantine location)
  - `quantity`: Decimal
  - `unitOfMeasure`: String
  - `movedAt`: Timestamp
  - `movedByUserId`: UUID
  - `movementType`: Enum (`RECEIVING_TO_STAGING`, `RECEIVING_TO_QUARANTINE`)

## Acceptance Criteria

### AC1: Receive Items to Default Staging Location
- **Given** Site "WH-1" has a configured default staging location "STAGING-A"
- **And** I am a Receiving Associate at Site "WH-1"
- **And** I have an active receiving session for PO "PO-123"
- **When** I confirm receipt of 10 units of product "P-ABC"
- **Then** the system creates an inventory movement to location "STAGING-A"
- **And** the inventory ledger shows 10 units of "P-ABC" at "STAGING-A"
- **And** an `InventoryReceived` event is emitted with the location details.

### AC2: Receive Items to Default Quarantine Location
- **Given** Site "WH-1" has a configured default quarantine location "QUARANTINE-B"
- **And** I am a Receiving Associate at Site "WH-1"
- **And** I have an active receiving session
- **When** I mark 5 units of product "P-XYZ" for quarantine (e.g., due to damage)
- **Then** the system creates an inventory movement to location "QUARANTINE-B"
- **And** the inventory status is marked as `QUARANTINE`
- **And** an `InventoryQuarantined` event is emitted.

### AC3: Fallback When Default Location Not Configured
- **Given** Site "WH-2" does NOT have a configured default staging location
- **And** I am a Receiving Associate at Site "WH-2"
- **And** I have an active receiving session
- **When** I attempt to confirm receipt of items
- **Then** the system prompts me to manually select a staging location
- **And** a warning is logged indicating the site configuration is incomplete.

### AC4: Handle Default Location Unavailable
- **Given** Site "WH-1" has a configured default staging location "STAGING-A"
- **And** "STAGING-A" has been disabled or deleted
- **When** I attempt to confirm receipt of items at Site "WH-1"
- **Then** the system displays an error message indicating the default location is unavailable
- **And** the system prompts me to select an alternative staging location
- **And** an error is logged for the Inventory Manager to review.

## Audit & Observability

- **Event Emission:**
  - `InventoryReceived`: Emitted when items are received to the default staging location.
    - Payload: `movementId`, `siteId`, `productId`, `locationId`, `quantity`, `receivedByUserId`, `timestamp`
  - `InventoryQuarantined`: Emitted when items are received to the default quarantine location.
    - Payload: `movementId`, `siteId`, `productId`, `locationId`, `quantity`, `reason`, `quarantinedByUserId`, `timestamp`

- **Logging:**
  - `INFO`: Log each successful receiving operation with location details.
  - `WARN`: Log when a site's default location is not configured.
  - `ERROR`: Log when a configured default location is unavailable.

## Dependencies

### Blocking Dependency
- **Issue #38**: "[BACKEND] [STORY] Configuration: Define Default Staging and Quarantine Storage Locations for a Site"
  - This story MUST be completed first.
  - The receiving workflow depends on the ability to retrieve configured default locations.

## Related Stories

- **Future Story**: "Inventory: Move Items Out of Quarantine with Permission Check"
  - Will handle the permission enforcement for moving items from quarantine to available stock.
  - Domain: `domain:inventory`, `domain:security`

## Technical Notes

- The receiving workflow should cache the site configuration for a reasonable TTL (e.g., 5 minutes) to reduce API calls.
- If the Site Configuration API is unavailable, the system should fail gracefully rather than using a potentially stale default.
- Consider implementing a circuit breaker pattern for the configuration API call.

---

## Original Context (From Issue #38 Split)

This story represents the **execution** portion of the original Issue #38, which combined both configuration and execution concerns. The original story has been split to maintain clean domain boundaries:

- **Configuration** (Issue #38): Defining and managing default locations.
- **Execution** (This story): Using those configured defaults in the receiving workflow.
