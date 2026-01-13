## Story Intent
**As a** Service Advisor,
**I want** the system to automatically present actionable solutions like backorders, approved substitutions, or external availability when a requested part is out of stock,
**so that** I can make an immediate, informed decision to prevent work stoppage and keep the customer informed.

## Actors & Stakeholders
- **Service Advisor (User):** The primary actor who receives shortage information and makes a decision on how to proceed.
- **System (Inventory Service):** The automated actor responsible for detecting shortages, querying other domains for solutions, and presenting them.
- **Stakeholders:**
    - **Product Domain:** Provides the rules and data for part substitutions.
    - **Positivity Domain:** Provides data on external part availability.
    - **Work Execution Domain:** Consumes the outcome of the decision to update the work order and estimate.
    - **Auditing System:** Records the decision for traceability.

## Preconditions
1.  A Service Advisor is attempting to allocate a specific part (by SKU/Part Number) to a work order.
2.  The Inventory system has been configured with location-specific stock levels.
3.  The system has determined that the Available-to-Promise (ATP) quantity for the requested part at the primary location is less than the requested quantity.

## Functional Behavior
1.  **Trigger:** An allocation request is made for a part where `requested_quantity > atp_quantity`.
2.  The system flags an internal **Shortage Event** for the requested part and quantity.
3.  Upon detecting the shortage, the system orchestrates the following lookups in parallel:
    a. **Internal Backorder Option:** The system calculates its own internal backorder feasibility (this is the default fallback option).
    b. **Substitution Lookup:** The system sends a request to the **Product Domain** service with the original part SKU to retrieve a list of approved substitute parts.
    c. **External Availability Lookup:** The system sends a request to the **Positivity Domain** service with the original part SKU to check for availability from external suppliers.
4.  The system aggregates the responses from all lookups.
5.  The system returns a structured response to the Point of Sale (POS) client containing all available options, presented in the following **deterministic order**:
    - **Option 1: Substitute parts** (if available)
    - **Option 2: External availability** (if available)
    - **Option 3: Backorder** the original part (always available as fallback)
6.  The Service Advisor selects one of the presented options.
7.  The system captures the Service Advisor's decision, linking it to the original allocation request and work order.
8.  The system emits an event (e.g., `PartShortageResolvedEvent`) containing the original request, the decision made, and the resulting action (e.g., create backorder, update work order with new part SKU).

## Alternate / Error Flows
- **No Substitutes or External Availability:** If both the Product and Positivity services return no viable options, the system's response will only contain the "Backorder" option.
- **Integration Partner Timeout/Error:** 
  - If a request to the **Product Domain** exceeds **800 ms**, the system will proceed without substitute data.
  - If a request to the **Positivity Domain** exceeds **1200 ms**, the system will proceed without external availability data.
  - The system shall **not** fail the entire operation due to dependent service failures.
  - Timeouts and failures will be logged, and the user will be presented with the remaining valid options.
  - A banner message shall be displayed: _"Some availability options could not be retrieved at this time."_
- **User Cancels/Aborts:** If the Service Advisor cancels the operation without making a choice, the part allocation on the work order remains in a "pending allocation" or "shortage" state. No decision is recorded.

## Business Rules
- A part is considered in shortage if the quantity requested for immediate allocation exceeds the current Available-to-Promise (ATP) quantity.
- All substitute parts suggested MUST be from the authoritative list provided by the Product Domain. This system does not define its own substitution rules.
- The default action if no other option is available or chosen is to place the original part on backorder.
- **Decision Hierarchy (Option Presentation Order):**
  - Shortage resolution options are presented in this deterministic order:
    1. **Substitute parts** (preserves service continuity with minimal delay)
    2. **External availability** (maintains original spec but may add logistics cost)
    3. **Backorder** (least desirable operationally, always shown as fallback)
  - Within each option category, options are ranked by:
    1. **Availability / Lead Time ASC** (fastest first)
    2. **Total Cost Impact ASC** (price difference + handling)
    3. **Quality Tier DESC** (OEM > Equivalent > Aftermarket)
    4. **Brand Preference** (customer or shop preference, optional)
  - Configuration: `shortageDecisionOrder = [SUBSTITUTE, EXTERNAL, BACKORDER]` (default), with per-location override allowed.
- **Error Handling Policy:**
  - Product Domain timeout threshold: **800 ms**
  - Positivity Domain timeout threshold: **1200 ms**
  - No synchronous retries; background refresh allowed for UI updates.
  - Degradation: Omit failed option category, present remaining options with informational banner.
- **Backorder Lead Time Sourcing:**
  - Use tiered fallback model for `estimatedLeadTimeDays`:
    1. **Purchasing / Supplier domain** (if integrated) — authoritative
    2. **Inventory domain replenishment estimate** — preferred default
    3. **Product catalog static lead-time hint** — last resort
  - Lead time must always include `source` and `confidence` fields.
  - If no source exists, omit the backorder option rather than fabricating a lead time.

## Data Requirements

### API Response Structure
The API response for a shortage event must be a structured object containing a list of `ResolutionOption`.

```json
{
  "originalRequest": {
    "partSku": "OEM-12345",
    "requestedQuantity": 2,
    "workOrderId": "WO-9876"
  },
  "shortageDetails": {
    "shortfallQuantity": 2
  },
  "resolutionOptions": [
    {
      "type": "SUBSTITUTE",
      "partSku": "SUB-67890",
      "partName": "Premium Alternative Filter",
      "availableQuantity": 5,
      "substituteInfo": {
        "substituteProductId": "UUID-v7",
        "qualityTier": "OEM",
        "brand": "ACDelco",
        "fitmentConfidence": "HIGH",
        "priceDifference": {
          "amount": 5.00,
          "currency": "USD"
        },
        "notes": "Recommended OEM equivalent"
      }
    },
    {
      "type": "EXTERNAL_PURCHASE",
      "partSku": "OEM-12345",
      "externalInfo": {
        "sourceId": "supplier-partscorp-001",
        "sourceType": "SUPPLIER",
        "availableQuantity": 4,
        "estimatedLeadTimeDays": 1,
        "additionalCost": {
          "amount": 35.00,
          "currency": "USD"
        },
        "confidence": "HIGH"
      }
    },
    {
      "type": "BACKORDER",
      "partSku": "OEM-12345",
      "backorderInfo": {
        "estimatedLeadTimeDays": 5,
        "source": "PURCHASING",
        "confidence": "MEDIUM"
      }
    }
  ]
}
```

### Product Domain Integration (Substitutes)

**Endpoint:** `POST /product/v1/substitutes:resolve`

**Request Schema:**
```json
{
  "items": [
    {
      "productId": "UUIDv7",
      "quantity": 2,
      "context": {
        "vehicleAttributes": {
          "make": "Ford",
          "model": "F-150",
          "year": 2022
        },
        "locationId": "UUIDv7"
      }
    }
  ],
  "includePricing": true
}
```

**Response Schema:**
```json
{
  "results": [
    {
      "productId": "UUIDv7",
      "substitutes": [
        {
          "substituteProductId": "UUIDv7",
          "qualityTier": "OEM | EQUIVALENT | AFTERMARKET",
          "brand": "string",
          "fitmentConfidence": "HIGH | MEDIUM | LOW",
          "priceDifference": {
            "amount": 12.50,
            "currency": "USD"
          },
          "notes": "string"
        }
      ]
    }
  ]
}
```

**Guarantees:**
- Product domain does **not** guarantee availability.
- Fitment confidence must be explicit.
- Supports batch requests.

### Positivity Domain Integration (External Availability)

**Endpoint:** `POST /positivity/v1/availability/external`

**Request Schema:**
```json
{
  "items": [
    {
      "productId": "UUIDv7",
      "quantity": 2,
      "deliveryLocationId": "UUIDv7"
    }
  ]
}
```

**Response Schema:**
```json
{
  "results": [
    {
      "productId": "UUIDv7",
      "sources": [
        {
          "sourceId": "string",
          "sourceType": "SUPPLIER | PARTNER_SHOP",
          "availableQuantity": 4,
          "estimatedLeadTimeDays": 1,
          "additionalCost": {
            "amount": 35.00,
            "currency": "USD"
          },
          "confidence": "HIGH | MEDIUM | LOW"
        }
      ]
    }
  ]
}
```

**Definition of External Availability:**
An option is considered externally available if it includes:
- A third-party source identifier
- Non-zero available quantity
- A lead-time estimate

**Guarantees:**
- Supports batch requests.

## Acceptance Criteria
**Scenario 1: Shortage with both Substitute and External options available**
- **Given** a part with SKU "ABC-101" has an ATP of 0
- **And** a Service Advisor requests to allocate a quantity of 1 of "ABC-101"
- **And** the Product domain has an approved substitute "XYZ-202"
- **And** the Positivity domain indicates "ABC-101" is available from an external supplier
- **When** the system processes the allocation request
- **Then** it must return a response containing three options in this order:
    1. Substitution with "XYZ-202"
    2. External purchase of "ABC-101"
    3. Backorder for "ABC-101"

**Scenario 2: Shortage with only a Substitute option available**
- **Given** a part with SKU "DEF-303" has an ATP of 1
- **And** a Service Advisor requests to allocate a quantity of 2 of "DEF-303"
- **And** the Product domain has an approved substitute "UVW-404"
- **And** the Positivity domain indicates no external availability
- **When** the system processes the allocation request
- **Then** it must return a response containing two options in this order:
    1. Substitution with "UVW-404"
    2. Backorder for "DEF-303"

**Scenario 3: Shortage with no alternative options**
- **Given** a part with SKU "GHI-505" has an ATP of 0
- **And** a Service Advisor requests to allocate a quantity of 1 of "GHI-505"
- **And** the Product domain has no approved substitutes
- **And** the Positivity domain indicates no external availability
- **When** the system processes the allocation request
- **Then** it must return a response containing only the "Backorder for GHI-505" option.

**Scenario 4: Integration Partner Fails**
- **Given** a part with SKU "JKL-606" has an ATP of 0
- **And** a Service Advisor requests to allocate a quantity of 1 of "JKL-606"
- **And** the request to the Product domain service times out (exceeds 800ms)
- **And** the Positivity domain responds with external availability
- **When** the system processes the allocation request
- **Then** it must log the timeout error for the Product service
- **And** it must display the banner: "Some availability options could not be retrieved at this time."
- **And** it must return a response containing two options in this order:
    1. External purchase of "JKL-606"
    2. Backorder for "JKL-606"

**Scenario 5: Option Ranking within Category**
- **Given** a part with SKU "MNO-707" has an ATP of 0
- **And** the Product domain returns three substitute options with varying lead times and costs
- **When** the system processes the allocation request
- **Then** substitutes must be sorted by:
    1. Lead time (ascending)
    2. Total cost impact (ascending)
    3. Quality tier (descending: OEM > EQUIVALENT > AFTERMARKET)

**Scenario 6: Backorder with Lead Time Source**
- **Given** a part with SKU "PQR-808" has an ATP of 0
- **And** no substitutes or external options are available
- **When** the system presents the backorder option
- **Then** the response must include `estimatedLeadTimeDays`, `source`, and `confidence` fields
- **And** if no lead time source exists, the backorder option must be omitted (no fabricated lead time)

## Audit & Observability
- **Audit Log:** Every decision made by the Service Advisor (Backorder, Substitute, External Purchase) must be logged as an immutable audit event. The event must include:
    - `workOrderId`
    - `originalPartSku`
    - `decisionType` (e.g., `SUBSTITUTE`)
    - `resultingPartSku` (if different)
    - `userId` of the Service Advisor
    - `timestamp`
- **Metrics:** The system should expose metrics for:
    - Number of shortage events triggered.
    - Latency of dependent service calls (Product, Positivity).
    - Count of each resolution type chosen by users.
    - Timeout/failure rate for dependent services.
- **Logging:** Log errors and timeouts for all external API calls to dependent domains.

---
## Original Story (Unmodified – For Traceability)
# Issue #25 — [BACKEND] [STORY] Allocations: Handle Shortages with Backorder or Substitution Suggestion

## Current Labels
- backend
- story-implementation
- user

## Current Body
## Backend Implementation for Story

**Original Story**: [STORY] Allocations: Handle Shortages with Backorder or Substitution Suggestion

**Domain**: user

### Story Description

/kiro
# User Story
## Narrative
As a **Service Advisor**, I want shortage handling so that work proceeds with backorders or approved substitutes.

## Details
- If ATP insufficient: propose external availability or substitute options.
- Link to product substitution rules.

## Acceptance Criteria
- Shortage flagged.
- Suggested actions returned.
- Decision captured and auditable.

## Integrations
- Product provides substitution/pricing; Positivity provides external availability; workexec updates estimate/WO.

## Data / Entities
- ShortageFlag, SubstituteSuggestion, ExternalAvailabilityRef

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
