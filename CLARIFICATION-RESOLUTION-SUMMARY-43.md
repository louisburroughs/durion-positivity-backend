# Clarification Resolution Summary - Issue #43

## Clarification Issue
- **Clarification Issue Number:** #238
- **Origin Story:** Issue #43 - [BACKEND] [STORY] Rules: Enforce Location Restrictions and Service Rules for Products
- **Status:** RESOLVED - All questions answered
- **Date Resolved:** 2026-01-12

## Background

Issue #43 originally had conflicting domain guidance. The story requires a new business capability (Restriction Rules) whose ownership was unclear. The rules are defined based on product attributes (owned by `domain:inventory`), enforced during financial transactions like quotes (owned by `domain:pricing`), and used in service context (owned by `domain:workexec`).

A clarification issue (#238) was created with 5 blocking questions to resolve the domain conflict and specify the technical implementation details.

## Decisions Made

### 1. Domain Ownership (BLOCKER)

**Question:** Which domain (`inventory`, `pricing`, or `workexec`) is the definitive System of Record for creating and managing `RestrictionRule` entities?

**Decision:** **`domain:pricing`** is the System of Record for `RestrictionRule` entities.

**Rationale:**
- Restrictions are fundamentally **commercial policy** (what can be sold/discounted/quoted under what conditions)
- Inventory remains authoritative for **stock reality**, not sellability policy
- WorkExec consumes restrictions but should not own the rules
- Pricing owns CRUD, versioning, effective dating, audit, and publication of rules

**Impact:**
- All `RestrictionRule` entity management happens in the Pricing service
- Other domains consume rules through well-defined API contracts
- Clear separation of concerns: Inventory = stock, Pricing = policy, WorkExec = execution

### 2. Enforcement Contract (BLOCKER)

**Question:** What is the technical contract for enforcement? Is it a synchronous API call from the Pricing/Workexec service to the primary domain's service?

**Decision:** Two-part enforcement model:
1. **Synchronous evaluation API** (authoritative)
2. **Optional local cache** (non-authoritative acceleration)

#### Synchronous API (Authoritative)

Pricing service exposes an evaluation endpoint callable by Pricing itself and by WorkExec:

**Endpoint:** `POST /pricing/v1/restrictions:evaluate`

**Request:**
```json
{
  "tenantId": "UUIDv7",
  "locationId": "UUIDv7",
  "serviceTag": "WORKORDER",
  "customerAccountId": "UUIDv7",
  "items": [
    {
      "productId": "UUIDv7",
      "quantity": 2,
      "uom": "EA",
      "unitPrice": { "amount": 100.00, "currency": "USD" }
    }
  ],
  "context": {
    "vehicleType": "string",
    "workType": "string",
    "salesChannel": "POS"
  }
}
```

**Response:**
```json
{
  "decision": "ALLOW | BLOCK | ALLOW_WITH_OVERRIDE",
  "results": [
    {
      "productId": "UUIDv7",
      "decision": "ALLOW | BLOCK | ALLOW_WITH_OVERRIDE",
      "ruleIds": ["UUIDv7"],
      "reasonCodes": ["RESTRICTED_ITEM", "LOCATION_NOT_AUTHORIZED"],
      "override": {
        "allowed": true,
        "requiredPermission": "pricing:restriction:override",
        "requiresSecondApprover": false
      }
    }
  ],
  "evaluatedAt": "2026-01-12T17:10:00Z",
  "policyVersion": 42
}
```

#### Caching (Non-Authoritative Acceleration)

- WorkExec may cache **published rules** (event-driven) for UI speed
- Must still support the evaluation API as the source of truth for transactions
- If cached evaluation is used, response must include `confidence = CACHED` and `policyVersion`

### 3. Fail-Safe Behavior (BLOCKER)

**Question:** If the rule evaluation service is unavailable during a transaction, should the system 'fail open' (allow the transaction) or 'fail closed' (block the transaction)?

**Decision:** Context-dependent fail-safe strategy

#### Transactional Commit Paths (Fail Closed)
For checkout, invoice finalize, commit sale:
- **Behavior:** FAIL CLOSED (block the transaction)
- **Response:** Return `503` or `409` with message: "Restriction service unavailable; cannot complete transaction."
- **Timeout:** 800ms evaluation call timeout
- **Retries:** No synchronous retries; allow background refresh

#### Non-Commit Paths (Graceful Degrade)
For search, quote-building, browsing:
- **Behavior:** Allow adding to cart but mark as `RESTRICTION_UNKNOWN`
- **Response:** Block finalization until evaluated
- **Purpose:** Prevents unnecessary disruption to browsing while maintaining transaction safety

**Rationale:**
- Fail closed prevents unauthorized sales
- Graceful degradation for browsing avoids operational disruption
- Clear timeout and no retries prevents cascading failures

### 4. Tag Granularity

**Question:** What are the specific location and service tags we need to support for the initial implementation?

**Decision:** Start with a minimal, explicit enum set (centrally owned, not free-form strings)

#### Location Tags (Initial Set)
- `ALL_LOCATIONS` (global)
- `RETAIL_STORE`
- `WAREHOUSE`
- `MOBILE_SERVICE`
- `FRANCHISE`
- `TEST_LOCATION` (non-prod / training)

#### Service Tags (Initial Set)
- `POS_SALE` (counter sale)
- `WORKORDER` (service work execution)
- `ESTIMATE` (quote generation)
- `INVOICE` (finalization)
- `DELIVERY` (if applicable)

**Rationale:**
- Tags must be definitional and owned centrally (security or shared domain constants)
- Not free-form strings to prevent chaos
- Small initial set can be expanded as needed
- Clear semantics for each tag

### 5. Override UX Flow

**Question:** What is the expected user flow for an override? Understanding the flow impacts the API design.

**Decision:** Modal override flow in POS UI with explicit audit capture

#### UX Flow
1. User adds item / applies action
2. System evaluates restrictions
3. If `ALLOW_WITH_OVERRIDE` or `BLOCK` with override allowed:
   - Show **modal dialog** with:
     - Reason (non-sensitive)
     - Required permission
     - Optional second approver if needed
     - Required reason code + notes field
4. On approval, client calls override API

#### Override API (Pricing-Owned)

**Endpoint:** `POST /pricing/v1/restrictions:override`

**Request:**
```json
{
  "transactionId": "UUIDv7",
  "productId": "UUIDv7",
  "ruleIds": ["UUIDv7"],
  "overrideReasonCode": "MANAGER_APPROVAL",
  "notes": "string",
  "approvedBy": "UUIDv7",
  "secondApprover": "UUIDv7"
}
```

**Response:**
```json
{
  "overrideId": "UUIDv7",
  "status": "APPROVED",
  "policyVersion": 42
}
```

**Integration:**
- WorkExec/POS stores `overrideId` on the line item
- Include `overrideId` in downstream accounting/audit events
- Full audit trail maintained in Pricing service

## Changes Applied to Story #43

### Updated Sections
1. **Actors & Stakeholders:** Added Pricing as System of Record, clarified role of each stakeholder
2. **Preconditions:** Added API availability requirement with 800ms target
3. **Functional Behavior:** 
   - Added complete API contracts for evaluation and override
   - Added caching strategy details
   - Expanded rule management with versioning
4. **Alternate / Error Flows:** Added detailed fail-safe behaviors for commit vs. browse paths
5. **Business Rules:** Added versioning, tag ownership, and policyVersion requirements
6. **Data Requirements:** 
   - Added complete entity schemas with UUIDv7 identifiers
   - Added initial tag enum definitions
   - Added `policyVersion` fields for rule versioning
7. **Acceptance Criteria:** Expanded to 7 Gherkin scenarios covering:
   - Location-based blocking
   - Successful allow when no restriction applies
   - Authorized override with full audit
   - Unauthorized override denial
   - Service unavailable during commit (fail closed)
   - Service unavailable during quote building (graceful degrade)
   - Cached evaluation with confidence marking
8. **Audit & Observability:** Added `policyVersion` to all log events
9. **Open Questions:** All 5 questions resolved with strikethrough and resolution notes
10. **Clarification Resolution:** New section linking to issue #238 with key decisions summary

### Labels to Update
**Remove:**
- `blocked:clarification`
- `blocked:domain-conflict`
- `status:needs-review`

**Add:**
- `domain:pricing` (identifies the domain that owns this capability)
- `status:ready-for-dev` (indicates story is ready for implementation)

## Implementation Readiness

### Story Is Now Ready Because:
- ✅ Domain ownership is clearly defined (Pricing)
- ✅ Technical contracts are specified (2 APIs with full schemas)
- ✅ Fail-safe behaviors are explicit (fail closed for commits, degrade for browse)
- ✅ Tag taxonomy is defined and constrained (2 enum sets)
- ✅ Override flow is detailed (modal + API + audit)
- ✅ Acceptance criteria are testable (7 Gherkin scenarios)
- ✅ Data requirements are complete (2 entities with full schemas)
- ✅ Audit requirements are comprehensive (4 event types with payloads)
- ✅ Error handling is specified (timeouts, failures, cache inconsistencies)

### Developer Can Now:
1. Implement Pricing service's restriction management CRUD
2. Build the synchronous evaluation API with timeout handling
3. Build the override API with second approver support
4. Implement fail-safe logic for commit vs. browse paths
5. Create the initial tag enums as shared constants
6. Implement the audit logging for all 4 event types
7. Write tests based on the 7 Gherkin acceptance criteria
8. Build the modal UI for override flows
9. Integrate WorkExec to call the evaluation API
10. Optionally implement caching in WorkExec with event-driven refresh

### Tester Can Now:
1. Derive test cases directly from the 7 Gherkin scenarios
2. Test API contracts against the specified request/response schemas
3. Verify fail-safe behaviors (unavailable service during commit vs. browse)
4. Verify timeout handling (800ms limit)
5. Verify override permissions and audit trail
6. Verify tag enum constraints (reject free-form strings)
7. Verify policyVersion tracking across rule changes

## Next Steps

1. **Update Issue #43:**
   - Replace body with content from `issue-43-updated-body.md`
   - Remove blocking labels: `blocked:clarification`, `blocked:domain-conflict`, `status:needs-review`
   - Add domain label: `domain:pricing`
   - Add status label: `status:ready-for-dev`
   - Assign to `@github-copilot` for implementation

2. **Close Clarification Issue #238:**
   - Post resolution comment documenting what was applied
   - Close the issue

3. **Begin Implementation:**
   - Create implementation tasks or subtasks if needed
   - Start with foundational Pricing service APIs
   - Implement in order: CRUD → Evaluation API → Override API → Caching

## Files Prepared

1. **`issue-43-updated-body.md`** - Complete updated body for issue #43 with all clarifications integrated
2. **`CLARIFICATION-RESOLUTION-GUIDE-43.md`** - Step-by-step guide for applying changes manually
3. **`update-issue-43.sh`** - Automated script for GitHub CLI (requires GH_TOKEN)
4. **`CLARIFICATION-RESOLUTION-SUMMARY-43.md`** - This file - comprehensive summary of all decisions

## Summary

The clarification process successfully resolved a critical domain conflict by establishing that Pricing owns the restriction rules as commercial policy. The technical contracts provide clear integration points for consuming domains (WorkExec), and the fail-safe strategy balances safety with operational continuity.

All 5 blocking questions have been answered with comprehensive, decision-ready responses that enable immediate implementation without further guesswork. The story is now ready for development.

---

**Resolution Date:** 2026-01-12  
**Resolved By:** Story Authoring Agent  
**Clarification Issue:** #238  
**Origin Story:** #43
