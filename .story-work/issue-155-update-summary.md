# Issue #155 Update Summary

## Task
Update GitHub issue #155 with clarification responses from issue #302, per Story Authoring Agent protocol.

## Clarification Responses (from Issue #302)

### Question 1: Policy Source of Truth
**Question:** What is the authoritative source for `VisibilityPolicy` data? Is it configured in a database table within the `Workexec` domain, a static configuration file, or managed by a separate `Security` / `People` domain service via an API? This defines the critical integration contract.

**Decision:** Security/Policy service is authoritative for permissions and visibility rules; domain services enforce server-side and cache with short TTL + invalidation events.

**Interpretation:**
- The **Security/Policy service** is the authoritative source for all visibility policies and permissions
- The **Workexec domain service** is responsible for enforcement but NOT policy authorship
- Domain services SHALL cache visibility policies with:
  - Short TTL (Time-To-Live) to ensure freshness
  - Cache invalidation on policy change events from Security service
- Server-side enforcement is REQUIRED; client-side filtering is supplementary only
- API contract SHALL be defined between Security service and domain services

### Question 2: Field Granularity
**Question:** The story mentions specific fields (`unitPrice`, `cost`, etc.). Is this list exhaustive? Is the policy configurable per-field, or is it a single "Can View Financials" flag per role? The Acceptance Criteria assume per-field control is desired.

**Decision:** Configurable; use explicit RBAC scopes (domain:resource:action) decoupled from role names.

**Interpretation:**
- Field-level visibility SHALL be configurable and NOT hardcoded
- RBAC scopes SHALL follow the pattern: `domain:resource:action`
  - Example: `workexec:workorder:view-pricing`
  - Example: `workexec:workorder:view-cost`
- Role names SHALL be decoupled from permissions (role → permissions mapping, NOT role-based checks in code)
- Policy SHALL support granular field-level control (not just coarse-grained "financial" flags)
- The Security service SHALL manage the mapping of roles to permission scopes
- Domain services SHALL check permission scopes, NOT role names

### Question 3: API Strategy
**Question:** Should this be implemented using a single endpoint that returns a differently shaped DTO based on role, or should we use separate, role-specific endpoints (e.g., `/api/workorders/{id}/mechanic-view` vs `/api/workorders/{id}/full-view`)? The functional behavior assumes a single endpoint with a filtered DTO, which is the preferred approach unless otherwise specified.

**Decision:** Use standard best practices with explicit contracts, idempotency, audit trails, UTC timestamps, scoped RBAC, configurable defaults.

**Interpretation:**
- Use a **single endpoint** pattern with dynamic DTO filtering based on permissions
- API contract SHALL be explicit and well-documented (OpenAPI/Swagger)
- Idempotency SHALL be implemented for state-changing operations
- Audit trails SHALL capture:
  - Who requested the data
  - What permissions were applied
  - Which fields were filtered/visible
  - Timestamp in UTC
- RBAC scopes SHALL be checked on every request (no session-level caching of permissions)
- Configurable defaults SHALL be provided for common visibility scenarios
- The endpoint SHALL return HTTP 403 Forbidden if the user lacks the minimum required permission scope
- The endpoint SHALL return a filtered DTO (omitting fields) if the user has partial permissions

## Actions Required

### 1. Update Issue #155 Body

Key changes to be made:
- **Remove** "Open Questions" section (as all questions are now resolved)
- **Update Business Rules** to include:
  - BR-POLICY-1: Security Service as Authoritative Source
  - BR-POLICY-2: Domain Service Enforcement with Caching
  - BR-RBAC-1: Explicit RBAC Scope Pattern
  - BR-RBAC-2: Role-to-Permission Decoupling
  - BR-API-1: Single Endpoint with Dynamic Filtering
  - BR-API-2: Audit Trail Requirements
- **Update Functional Behavior** to specify:
  - Policy retrieval and caching from Security service
  - Permission scope validation per request
  - Dynamic DTO field filtering based on permissions
  - Error handling for insufficient permissions
- **Update Data Requirements** to include:
  - VisibilityPolicy entity (owned by Security service)
  - VisibilityPolicyCache entity (in domain service for caching)
  - Permission scope definitions (domain:resource:action)
  - Audit event structure for visibility filtering
- **Update Acceptance Criteria** to add:
  - AC-POLICY-1: Security service provides visibility policies
  - AC-POLICY-2: Domain service caches policies with short TTL
  - AC-POLICY-3: Cache invalidated on policy change events
  - AC-RBAC-1: Permission scopes follow domain:resource:action pattern
  - AC-RBAC-2: Code checks permission scopes, NOT role names
  - AC-API-1: Single endpoint with dynamic DTO filtering
  - AC-API-2: HTTP 403 when minimum permissions not met
  - AC-API-3: Partial DTO returned with accessible fields only
  - AC-AUDIT-1: Audit trail captures request, permissions, filtered fields, UTC timestamp
- **Update Audit & Observability** to capture:
  - Policy cache hits/misses
  - Policy cache invalidation events
  - Permission check results (allowed/denied)
  - Field filtering decisions
  - Failed authorization attempts

### 2. Update Issue #155 Labels

**Remove:**
- `blocked:clarification`

**Add:**
- `status:needs-review`

### 3. Close Issue #302
Mark as resolved since clarification has been provided and integrated.

### 4. Add Comment to Issue #155
Add a comment linking to issue #302 and noting that clarifications have been resolved and integrated.

## Business Rules to Add

### BR-POLICY-1: Security Service as Authoritative Source
The Security/Policy service is the single source of truth for all visibility policies and permission scopes. No other service may define or modify visibility policies. Domain services enforce policies but do not author them.

**Rationale:** Centralized policy management ensures consistency, auditability, and simplifies compliance.

### BR-POLICY-2: Domain Service Enforcement with Caching
Domain services (e.g., Workexec) SHALL:
- Retrieve visibility policies from the Security service via API
- Cache policies locally with a short TTL (recommended: 5-15 minutes)
- Subscribe to policy change events from Security service for immediate cache invalidation
- Perform server-side enforcement on every API request
- NEVER rely solely on client-side filtering for security

**Rationale:** Caching improves performance while short TTL and event-based invalidation ensure policy freshness.

### BR-RBAC-1: Explicit RBAC Scope Pattern
All permission scopes SHALL follow the pattern: `domain:resource:action`

Examples:
- `workexec:workorder:view` - Can view work order basic data
- `workexec:workorder:view-pricing` - Can view pricing fields
- `workexec:workorder:view-cost` - Can view cost fields
- `workexec:workorder:view-labor` - Can view labor details
- `workexec:workorder:edit` - Can edit work order

**Rationale:** Explicit, structured scopes provide fine-grained control and are self-documenting.

### BR-RBAC-2: Role-to-Permission Decoupling
Application code SHALL:
- Check permission scopes (e.g., `hasPermission('workexec:workorder:view-pricing')`)
- NEVER check role names directly (e.g., AVOID `if (role == 'MANAGER')`)

The Security service maintains the mapping: Role → List of Permission Scopes.

**Rationale:** Decoupling allows permission changes without code changes and supports flexible role definitions.

### BR-API-1: Single Endpoint with Dynamic Filtering
The Workexec API SHALL use a single endpoint pattern (e.g., `GET /api/workorders/{id}`) that:
- Accepts a standard request format
- Checks the caller's permission scopes
- Returns a DTO with fields filtered based on those scopes
- Omits fields the caller is not authorized to view

**Rationale:** Single endpoint simplifies client code, API versioning, and reduces maintenance burden.

### BR-API-2: Audit Trail Requirements
Every API request that involves visibility filtering SHALL generate an audit event containing:
- **requestId**: Unique identifier for the request
- **userId**: ID of the user making the request
- **timestamp**: UTC timestamp of the request
- **endpoint**: API endpoint accessed
- **resourceId**: ID of the resource accessed (e.g., work order ID)
- **permissionScopes**: List of permission scopes checked
- **fieldsVisible**: List of fields included in the response
- **fieldsFiltered**: List of fields excluded due to permissions
- **result**: SUCCESS or DENIED

**Rationale:** Comprehensive audit trails support compliance, troubleshooting, and security monitoring.

## Data Model Changes

### VisibilityPolicy (Owned by Security Service)
This entity is defined and managed by the Security service, NOT the Workexec domain.

```json
{
  "policyId": "uuid",
  "domain": "workexec",
  "resource": "workorder",
  "action": "view-pricing",
  "scope": "workexec:workorder:view-pricing",
  "description": "Permission to view pricing fields on work orders",
  "fieldMapping": {
    "unitPrice": true,
    "lineTotal": true,
    "tax": true,
    "discount": true
  },
  "effectiveDate": "2026-01-01T00:00:00Z",
  "expirationDate": null,
  "isActive": true,
  "createdAt": "2025-12-01T00:00:00Z",
  "updatedAt": "2025-12-15T00:00:00Z"
}
```

### VisibilityPolicyCache (In Workexec Domain for Caching)
```json
{
  "cacheId": "uuid",
  "policyId": "uuid (from Security service)",
  "scope": "workexec:workorder:view-pricing",
  "fieldMapping": {
    "unitPrice": true,
    "lineTotal": true,
    "tax": true,
    "discount": true
  },
  "cachedAt": "2026-01-11T10:00:00Z",
  "expiresAt": "2026-01-11T10:15:00Z",
  "version": "v1.2.3"
}
```

### VisibilityAuditEvent (In Workexec Domain for Audit Logging)
```json
{
  "eventId": "uuid",
  "requestId": "uuid",
  "userId": "user-123",
  "timestamp": "2026-01-11T10:30:00Z",
  "endpoint": "/api/workorders/wo-456",
  "resourceId": "wo-456",
  "resourceType": "workorder",
  "permissionScopes": [
    "workexec:workorder:view",
    "workexec:workorder:view-labor"
  ],
  "fieldsVisible": [
    "id", "status", "customerName", "vehicleVIN", 
    "laborItems", "laborHours", "mechanicName"
  ],
  "fieldsFiltered": [
    "unitPrice", "cost", "margin", "discount"
  ],
  "result": "SUCCESS"
}
```

## Acceptance Criteria to Add

### AC-POLICY-1: Security Service Provides Visibility Policies
**Given** a domain service needs to enforce visibility rules  
**When** the service requests visibility policies from the Security service  
**Then** the Security service returns a list of applicable visibility policies for the domain and resource  
**And** each policy includes the permission scope, field mappings, and metadata

### AC-POLICY-2: Domain Service Caches Policies with Short TTL
**Given** the domain service has retrieved visibility policies from the Security service  
**When** the policies are cached locally  
**Then** the cache TTL is set to a configurable short duration (default: 10 minutes)  
**And** subsequent requests within the TTL use the cached policies  
**And** requests after TTL expiry trigger a refresh from the Security service

### AC-POLICY-3: Cache Invalidated on Policy Change Events
**Given** the domain service is subscribed to policy change events from the Security service  
**When** a policy change event is received  
**Then** the relevant cached policies are immediately invalidated  
**And** the next API request fetches fresh policies from the Security service  
**And** the cache invalidation event is logged for audit

### AC-RBAC-1: Permission Scopes Follow domain:resource:action Pattern
**Given** visibility policies are defined in the Security service  
**When** permission scopes are created or validated  
**Then** each scope follows the format `domain:resource:action`  
**And** invalid scope formats are rejected with a validation error  
**And** scope definitions are documented in the API contract

### AC-RBAC-2: Code Checks Permission Scopes, NOT Role Names
**Given** an API request requires authorization  
**When** the authorization check is performed  
**Then** the code verifies the user has the required permission scope(s)  
**And** the code does NOT check role names directly  
**And** the permission-to-role mapping is maintained in the Security service

### AC-API-1: Single Endpoint with Dynamic DTO Filtering
**Given** a user requests a work order via `GET /api/workorders/{id}`  
**When** the user has some but not all permission scopes  
**Then** the API returns HTTP 200 with a DTO containing only authorized fields  
**And** unauthorized fields are omitted from the response (not null, not present)  
**And** the response structure is consistent regardless of permissions

### AC-API-2: HTTP 403 When Minimum Permissions Not Met
**Given** a user requests a work order via `GET /api/workorders/{id}`  
**When** the user lacks the minimum required permission scope (`workexec:workorder:view`)  
**Then** the API returns HTTP 403 Forbidden  
**And** the response includes a message: "Insufficient permissions to view this resource"  
**And** the failed authorization attempt is logged in the audit trail

### AC-API-3: Partial DTO Returned with Accessible Fields Only
**Given** a user with permission `workexec:workorder:view` but NOT `workexec:workorder:view-pricing`  
**When** the user requests a work order  
**Then** the API returns a DTO with:
  - Basic work order fields (id, status, dates)
  - Customer and vehicle information
  - Labor and parts descriptions
  - BUT pricing fields (unitPrice, cost, margin) are OMITTED  
**And** the response is a valid, well-formed DTO

### AC-AUDIT-1: Audit Trail Captures Request, Permissions, Filtered Fields, UTC Timestamp
**Given** any API request that applies visibility filtering  
**When** the request is processed  
**Then** an audit event is created with:
  - Request ID and user ID
  - UTC timestamp
  - Endpoint and resource ID
  - Permission scopes checked
  - List of visible fields
  - List of filtered fields
  - Result (SUCCESS or DENIED)  
**And** the audit event is persisted to the audit log store  
**And** the audit event can be queried for compliance reporting

## How to Apply Updates

### Option 1: Using apply-clarification-resolution-155.sh script
```bash
cd /home/runner/work/durion-positivity-backend/durion-positivity-backend
./.story-work/apply-clarification-resolution-155.sh
```

### Option 2: Using gh CLI directly
```bash
# First, fetch the current issue body
gh issue view 155 -R louisburroughs/durion-positivity-backend --json body -q .body > /tmp/issue-155-current.md

# Manual edit required: Apply the changes described above to /tmp/issue-155-current.md
# Then update the issue:

gh issue edit 155 -R louisburroughs/durion-positivity-backend \
  --body-file /tmp/issue-155-updated.md

# Update labels
gh issue edit 155 -R louisburroughs/durion-positivity-backend \
  --remove-label "blocked:clarification" \
  --add-label "status:needs-review"

# Add comment
gh issue comment 155 -R louisburroughs/durion-positivity-backend \
  --body "## Clarification Resolution Complete

All clarification questions from issue #302 have been reviewed and integrated into this story.

### Decisions Integrated:

**Policy Source of Truth**: Security/Policy service is authoritative for permissions and visibility rules; domain services enforce server-side and cache with short TTL + invalidation events.

**Field Granularity**: Configurable; use explicit RBAC scopes (domain:resource:action) decoupled from role names.

**API Strategy**: Use standard best practices with explicit contracts, idempotency, audit trails, UTC timestamps, scoped RBAC, configurable defaults.

### Impact:
- 6 new Business Rules added (BR-POLICY-1, BR-POLICY-2, BR-RBAC-1, BR-RBAC-2, BR-API-1, BR-API-2)
- 3 new Data Requirements (VisibilityPolicy, VisibilityPolicyCache, VisibilityAuditEvent)
- 8 new Acceptance Criteria (AC-POLICY-1 through AC-AUDIT-1)
- Updated Functional Behavior and Audit & Observability sections

Story is now ready for implementation planning and technical design."

# Close clarification issue
gh issue close 302 -R louisburroughs/durion-positivity-backend \
  --comment "Clarification responses have been integrated into origin issue #155. All questions resolved."
```

## Verification Checklist

After applying updates, verify:
- [ ] Issue #155 body includes all 6 new Business Rules
- [ ] Issue #155 body includes all 3 Data Requirements
- [ ] Issue #155 body includes all 8 new Acceptance Criteria
- [ ] "Open Questions" section has been removed from issue #155
- [ ] `blocked:clarification` label removed from issue #155
- [ ] `status:needs-review` label added to issue #155
- [ ] Comment added to issue #155 linking to issue #302
- [ ] Issue #302 is closed with resolution comment
- [ ] All decisions are clearly documented and traceable
