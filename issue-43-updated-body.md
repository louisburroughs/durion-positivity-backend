## Story Intent

**As a** Shop Manager,
**I want** to configure and enforce rules that restrict the sale or installation of specific products based on location or service context,
**so that** we can prevent safety issues, regulatory non-compliance, and vehicle incompatibility, while maintaining a clear audit trail for any necessary exceptions.

## Actors & Stakeholders

-   **Shop Manager:** (User) Defines, manages, and overrides restriction rules.
-   **Service Advisor:** (User) Encounters rule enforcement (blocks) when creating quotes or work orders.
-   **System:** (Actor) The platform responsible for executing the rule checks and logging all related activities.
-   **Pricing Service:** (System - System of Record) Owns the creation, management, and enforcement evaluation of `RestrictionRule` entities.
-   **Work Execution Service:** (System Stakeholder) Provides the service context (e.g., service type) needed for rule evaluation and consumes restriction decisions.
-   **Inventory Service:** (System Stakeholder) Provides product information but does not own restriction rules.

## Preconditions

1.  A product catalog exists with uniquely identifiable products.
2.  Locations are defined and can be associated with metadata tags from the initial tag set.
3.  Services are defined and can be associated with metadata tags from the initial tag set.
4.  User roles and permissions (e.g., `Shop Manager`, `Service Advisor`) are established and enforceable.
5.  The Pricing service's restriction evaluation API is available and responsive (target: 800ms response time).

## Functional Behavior

### 1. Rule Management (Shop Manager)

-   The System SHALL provide an interface for a Shop Manager to create, view, update, and deactivate `RestrictionRule`s via the Pricing service.
-   A `RestrictionRule` MUST be defined by:
    -   A condition, which is composed of a type (from the initial location or service tag enums) and a corresponding value.
    -   A list of one or more products to which the restriction applies.
    -   An active/inactive status.
    -   Effective dating and versioning for audit and historical tracking.

### 2. Rule Enforcement (Service Advisor & System)

-   When a Service Advisor attempts to add a restricted product to a quote or work order, the consuming system (Pricing or WorkExec) MUST call the Pricing service's restriction evaluation API: `POST /pricing/v1/restrictions:evaluate`.
-   The evaluation request MUST include:
    -   `tenantId`, `locationId`, `serviceTag`, `customerAccountId`
    -   Array of items with `productId`, `quantity`, `uom`, `unitPrice`
    -   Context information (`vehicleType`, `workType`, `salesChannel`)
-   The Pricing service MUST evaluate the context against active `RestrictionRule`s for the requested products.
-   The response will include a decision (`ALLOW`, `BLOCK`, or `ALLOW_WITH_OVERRIDE`) for each product, along with applicable `ruleIds` and `reasonCodes`.
-   If the decision is `BLOCK` or `ALLOW_WITH_OVERRIDE`, the System MUST present a clear, user-facing message explaining why the product is restricted (e.g., "Product 'ABC-123' cannot be sold in this location - Restricted Item.").

### 3. Rule Override (Shop Manager)

-   When an action is blocked or requires override by a `RestrictionRule`, the System SHALL provide a modal dialog for an authorized user (Shop Manager) to initiate an override.
-   To perform an override, the Shop Manager MUST:
    -   Have the required permission (`pricing:restriction:override`)
    -   Provide a mandatory override reason code (e.g., `MANAGER_APPROVAL`)
    -   Provide free-text notes explaining the rationale (e.g., "Customer signed waiver for off-road use only.")
    -   Optionally provide a second approver ID if required by the rule
-   The override request is submitted to the Pricing service via: `POST /pricing/v1/restrictions:override`.
-   Upon a successful override (response status `APPROVED`), the System SHALL:
    -   Allow the originally blocked action to proceed
    -   Store the returned `overrideId` on the line item
    -   Include the `overrideId` in downstream accounting and audit events
-   The Pricing service MUST record the override event in an immutable audit log.

### 4. Caching Strategy (Optional Acceleration)

-   WorkExec or other consuming services MAY cache published restriction rules for UI responsiveness.
-   Cached evaluations MUST be marked as `confidence = CACHED` and include the `policyVersion`.
-   The authoritative evaluation API remains the source of truth for all transactional decisions.
-   Cached rules SHOULD be refreshed via event-driven updates when rules change.

## Alternate / Error Flows

### Error - Rule evaluation service unavailable

-   **For transactional commit paths** (checkout, invoice finalize, commit sale):
    -   The system MUST **fail closed** (block the transaction).
    -   Return HTTP 503 or 409 with message: "Restriction service unavailable; cannot complete transaction."
    -   Evaluation API timeout: 800ms (no synchronous retries).
-   **For non-commit paths** (search, quote-building, browsing):
    -   The system MAY **gracefully degrade**: allow adding items to cart but mark them as `RESTRICTION_UNKNOWN`.
    -   Block finalization until restrictions are successfully evaluated.
    -   This prevents unnecessary disruption to browsing while maintaining transaction safety.

### Error - Invalid product or context

-   If a product ID or context tag used in a rule becomes invalid or unavailable, the rule should be flagged for administrative review.
-   The system SHOULD continue to enforce other valid rules and log the error for investigation.

### Error - Cache inconsistency

-   If a cached evaluation conflicts with the authoritative API, the API result MUST take precedence.
-   The system SHOULD log cache inconsistencies for monitoring and alerting.

## Business Rules

-   A product can be subject to multiple restriction rules.
-   A rule is considered "matched" if the transaction context (e.g., location, service type) matches any active restriction rule for the given product.
-   All overrides must be captured in an immutable audit log with full traceability (user, timestamp, reason code, notes, approver IDs).
-   The permission to override restrictions is role-based and configurable (`pricing:restriction:override`).
-   Restriction rules support effective dating and versioning for historical audit and compliance.
-   Location and service tags MUST be from the defined initial enum sets (not free-form strings).

## Data Requirements

### `RestrictionRule` Entity (Pricing Service - System of Record)

-   `ruleId`: Unique Identifier (UUIDv7)
-   `name`: Human-readable name for the rule
-   `conditionType`: Enum (one of the initial location or service tag types)
-   `conditionValue`: String (must be from the initial tag enum set)
-   `restrictedProductIds`: List of UUIDv7
-   `isActive`: Boolean
-   `effectiveFrom`: DateTime
-   `effectiveTo`: DateTime (optional)
-   `policyVersion`: Integer (incremented on rule updates)
-   `createdAt`, `updatedAt`, `createdBy`

### `OverrideRecord` Entity (Pricing Service - System of Record)

-   `overrideId`: Unique Identifier (UUIDv7)
-   `ruleId`: FK to `RestrictionRule` (UUIDv7)
-   `transactionId`: ID of the quote, work order, or sale (UUIDv7)
-   `productId`: UUIDv7
-   `overrideReasonCode`: String (enum: `MANAGER_APPROVAL`, etc.)
-   `notes`: Text
-   `approvedBy`: User ID (UUIDv7)
-   `secondApprover`: User ID (UUIDv7, optional)
-   `policyVersion`: Integer (version of rule at time of override)
-   `timestamp`: DateTime
-   `status`: Enum (`APPROVED`, `DENIED`)

### Initial Tag Enums (Centrally Defined Constants)

**Location Tags (Initial Set):**
-   `ALL_LOCATIONS` (global)
-   `RETAIL_STORE`
-   `WAREHOUSE`
-   `MOBILE_SERVICE`
-   `FRANCHISE`
-   `TEST_LOCATION` (non-prod / training)

**Service Tags (Initial Set):**
-   `POS_SALE` (counter sale)
-   `WORKORDER` (service work execution)
-   `ESTIMATE` (quote generation)
-   `INVOICE` (finalization)
-   `DELIVERY` (if applicable)

## Acceptance Criteria

```gherkin
Scenario: Product is blocked due to a location-based restriction
  Given a "RETAIL_STORE" location tag is assigned to the current location
  And an active RestrictionRule exists that blocks "Part-X" for the "RETAIL_STORE" tag
  When a Service Advisor attempts to add "Part-X" to a quote at that location
  Then the Pricing service's evaluation API MUST return decision "BLOCK" for "Part-X"
  And the system MUST display a message indicating the product is restricted at that location
  And the message MUST include the relevant reason codes.

Scenario: Product is allowed when no restriction applies
  Given an active RestrictionRule exists that blocks "Part-X" for the "RETAIL_STORE" tag
  When a Service Advisor attempts to add "Part-X" to a quote at a "WAREHOUSE" location
  Then the Pricing service's evaluation API MUST return decision "ALLOW" for "Part-X"
  And the system MUST allow the action to complete successfully.

Scenario: Authorized user successfully overrides a restriction
  Given an action to add "Part-X" is blocked by an active RestrictionRule
  And the current user has the "Shop Manager" role with "pricing:restriction:override" permission
  When the Shop Manager initiates an override via the modal dialog
  And provides the override reason code "MANAGER_APPROVAL"
  And provides the notes "Customer approved special order"
  And calls the Pricing service's override API
  Then the Pricing service MUST return status "APPROVED" with an overrideId
  And the system MUST allow the action to complete
  And an OverrideRecord MUST be created in the Pricing service's audit log with the correct user, rule, reason code, notes, and timestamp
  And the overrideId MUST be stored on the transaction line item.

Scenario: Unauthorized user fails to override a restriction
  Given an action to add "Part-X" is blocked by an active RestrictionRule
  And the current user has the "Service Advisor" role without "pricing:restriction:override" permission
  When the Service Advisor attempts to initiate an override
  Then the system MUST deny the override request
  And the original action MUST remain blocked.

Scenario: Evaluation service unavailable during transaction commit
  Given a Service Advisor attempts to finalize an invoice containing "Part-Y"
  And the Pricing service's restriction evaluation API is unavailable (timeout or error)
  When the system attempts to evaluate restrictions for the invoice
  Then the system MUST fail closed (block the transaction)
  And return a 503 or 409 response with message "Restriction service unavailable; cannot complete transaction."

Scenario: Evaluation service unavailable during quote building
  Given a Service Advisor is building a quote and adds "Part-Z"
  And the Pricing service's restriction evaluation API is unavailable (timeout or error)
  When the system attempts to evaluate restrictions
  Then the system MAY allow adding the item to the quote
  But MUST mark the item as "RESTRICTION_UNKNOWN"
  And MUST block finalization of the quote until restrictions are successfully evaluated.

Scenario: Cached evaluation is used for UI responsiveness
  Given WorkExec has a cached version of restriction rules (policyVersion 42)
  When a Service Advisor adds "Part-A" to a quote
  Then WorkExec MAY evaluate restrictions using the cached rules
  And the response MUST include "confidence = CACHED" and "policyVersion = 42"
  And the final transactional decision MUST be validated against the authoritative Pricing service API.
```

## Audit & Observability

-   **Log Event:** `RestrictionRuleCreated`, `RestrictionRuleUpdated`, `RestrictionRuleDeactivated`
    -   **Payload:** `ruleId`, `adminUserId`, changes, `policyVersion`, timestamp
-   **Log Event:** `RestrictionEvaluated`
    -   **Payload:** `ruleId`, `productId`, `transactionId`, `decision`, `reasonCodes`, `context` (location/service tags), `policyVersion`, `evaluatedAt`
-   **Log Event:** `RestrictionOverridden`
    -   **Payload:** `overrideId`, `ruleId`, `transactionId`, `productId`, `userId`, `overrideReasonCode`, `notes`, `approvedBy`, `secondApprover`, `policyVersion`, `timestamp`, `status`
-   **Log Event:** `RestrictionEvaluationFailed`
    -   **Payload:** `transactionId`, `errorType`, `errorMessage`, `context`, `timestamp`

## Open Questions

~~1.  **[BLOCKER] Domain Ownership:** Which domain (`inventory`, `pricing`, or `workexec`) is the definitive System of Record for creating and managing `RestrictionRule` entities?~~

**RESOLVED:** `domain:pricing` is the System of Record for `RestrictionRule` entities. Restrictions are commercial policy (what can be sold/quoted under what conditions), and Pricing owns CRUD, versioning, effective dating, audit, and publication of rules.

~~2.  **[BLOCKER] Enforcement Contract:** What is the technical contract for enforcement? Is it a synchronous API call from the Pricing/Workexec service to the primary domain's service (e.g., `inventory.canSellItem(itemId, context)`)?~~

**RESOLVED:** Two-part enforcement model:
- **Synchronous evaluation API** (authoritative): `POST /pricing/v1/restrictions:evaluate` exposed by Pricing service, callable by Pricing itself and WorkExec.
- **Optional local cache** (non-authoritative acceleration): WorkExec may cache published rules for UI speed but must still use the evaluation API as the source of truth for transactions. Cached responses must include `confidence = CACHED` and `policyVersion`.

~~3.  **[BLOCKER] Fail-Safe Behavior:** If the rule evaluation service is unavailable during a transaction, should the system 'fail open' (allow the transaction) or 'fail closed' (block the transaction)? 'Fail closed' is safer but risks operational disruption.~~

**RESOLVED:** 
- **Transactional commit paths** (checkout, invoice finalize, commit sale): **Fail closed** - return 503 or 409 with message "Restriction service unavailable; cannot complete transaction."
- **Non-commit paths** (search, quote-building, browsing): **Gracefully degrade** - allow adding to cart but mark as `RESTRICTION_UNKNOWN` and block finalization until evaluated.
- **Timeouts:** Evaluation call timeout is 800ms with no synchronous retries.

~~4.  **Granularity:** What are the specific location and service tags we need to support for the initial implementation? Can we get a list?~~

**RESOLVED:** 
- **Location Tags (Initial):** `ALL_LOCATIONS`, `RETAIL_STORE`, `WAREHOUSE`, `MOBILE_SERVICE`, `FRANCHISE`, `TEST_LOCATION`
- **Service Tags (Initial):** `POS_SALE`, `WORKORDER`, `ESTIMATE`, `INVOICE`, `DELIVERY`
- These tags must be definitional and owned centrally (security or shared domain constants), not free-form strings.

~~5.  **UI/UX for Override:** While this is a backend story, what is the expected user flow for an override? Is it a modal dialog triggered in the POS UI? Understanding the flow impacts the API design.~~

**RESOLVED:** Modal override flow:
1. User adds item / applies action
2. System evaluates restrictions
3. If `ALLOW_WITH_OVERRIDE` or `BLOCK` with override allowed: show modal dialog with reason (non-sensitive), required permission, optional second approver if needed, required reason code + notes
4. On approval, client calls override API: `POST /pricing/v1/restrictions:override`
5. Response includes `overrideId` and `status` (`APPROVED`)
6. WorkExec/POS stores `overrideId` on line item and includes it in downstream accounting/audit events.

## Clarification Resolution

All open questions have been resolved via [Clarification Issue #238](https://github.com/louisburroughs/durion-positivity-backend/issues/238).

**Key Decisions:**
- **Domain ownership:** Pricing service is the System of Record
- **Enforcement pattern:** Synchronous evaluation API with optional caching
- **Fail-safe behavior:** Fail closed for commits, graceful degradation for browsing
- **Tag granularity:** Defined initial enum sets for location and service tags
- **Override UX:** Modal flow with pricing-owned override API

## Original Story (Unmodified – For Traceability)

# Issue #43 — [BACKEND] [STORY] Rules: Enforce Location Restrictions and Service Rules for Products

## Current Labels
- backend
- story-implementation
- user

## Current Body
## Backend Implementation for Story

**Original Story**: [STORY] Rules: Enforce Location Restrictions and Service Rules for Products

**Domain**: user

### Story Description

/kiro
# User Story
## Narrative
As a **Shop Manager**, I want restriction rules so that unsafe or incompatible items are not sold/installed.

## Details
- Block based on location tags or service type.
- Override requires permission + rationale.

## Acceptance Criteria
- Restrictions enforced in pricing/quote APIs.
- Override permission required.
- Decision recorded in trace.

## Integrations
- Workexec receives warnings/errors; shopmgr provides service context tags (optional).

## Data / Entities
- RestrictionRule, OverrideRecord, AuditLog

## Classification (confirm labels)
- Type: Story
- Layer: Domain
- Domain: Product / Parts Management


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
