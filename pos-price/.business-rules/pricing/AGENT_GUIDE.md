# AGENT_GUIDE.md — Pricing Domain

---

## Purpose

The Pricing domain is responsible for defining, maintaining, and serving all pricing-related data and logic within the POS system. This includes MSRP management, price book rules, location-specific overrides, promotions, eligibility rules, tax and total calculations, pricing snapshots, and enforcement of product restrictions. The domain ensures pricing is consistent, auditable, and compliant with business policies, enabling downstream systems to retrieve authoritative prices and apply promotions accurately.

---

## Domain Boundaries

- **Owned Entities & Logic:**
  - MSRP per product with effective dating.
  - Base company price book rules (markup, margin, fixed prices).
  - Location-specific store price overrides with guardrails and approval workflows.
  - Promotions: creation, activation, eligibility rules, and application.
  - Pricing calculations: taxes, fees, discounts, totals.
  - Immutable pricing snapshots for estimate/work order lines.
  - Restriction rules enforcing product sale/install constraints by location/service context.
  
- **Authoritative Data Ownership:**
  - Pricing domain owns all pricing rules, promotions, eligibility, overrides, and snapshots.
  - Inventory domain owns product definitions and cost data.
  - CRM domain owns customer account and tier data.
  - Product domain owns MSRP base data (queried by Pricing).
  
- **Integration Boundaries:**
  - Pricing depends on Inventory for product existence and cost.
  - Pricing depends on CRM for customer tiers and promotion eligibility.
  - Work Execution (workexec) consumes pricing results, snapshots, and promotions.
  - Inventory and CRM are authoritative for their respective data; Pricing validates against them via synchronous APIs or event-driven caches.
  
- **Excluded Responsibilities:**
  - Product lifecycle management (Inventory domain).
  - Customer account management (CRM domain).
  - Work order and estimate lifecycle (Workexec domain).

---

## Key Entities / Concepts

| Entity / Concept               | Description                                                                                     |
|-------------------------------|-------------------------------------------------------------------------------------------------|
| **ProductMSRP**               | MSRP records per product with effective start/end dates; temporal uniqueness enforced.          |
| **PriceBookRule**             | Base company pricing rules scoped by product/category/global, customer tier, location, and time.|
| **LocationPriceOverride**     | Location-specific price overrides subject to guardrails and approval workflows.                 |
| **PromotionOffer**            | Promotion definitions with codes, discount types, validity, usage limits, and lifecycle states.|
| **PromotionEligibilityRule** | Rules defining promotion eligibility based on account IDs, vehicle tags, and fleet size.       |
| **CalculationSnapshot**       | Immutable record capturing tax and total calculations for Estimates.                            |
| **PricingSnapshot**           | Immutable detailed snapshot of pricing components per estimate/work order line.                 |
| **RestrictionRule**           | Rules restricting product sale/install by location/service context, with override capability.   |
| **OverrideRecord**            | Audit record of restriction overrides with reason codes and approver info.                      |

---

## Invariants / Business Rules

- **MSRP Temporal Uniqueness:** No overlapping effective date ranges per product.
- **MSRP Product Validation:** ProductIDs must exist in Inventory; invalid product references rejected.
- **MSRP Historical Immutability:** MSRP records with past effectiveEndDate are immutable or require special permissions.
- **Price Book Rule Precedence:** Most specific rule wins (SKU > Category > Global), tie-breakers deterministic.
- **Missing Base Data:** Rules requiring missing cost/MSRP marked `NOT_APPLICABLE_MISSING_BASE`; fallback to MSRP if available.
- **Location Override Guardrails:** Enforce minimum margin and maximum discount; overrides exceeding thresholds require approval.
- **Promotion Code Uniqueness:** Promotion codes must be unique and immutable once created.
- **Promotion Availability:** Only `ACTIVE` promotions within valid date range are applicable.
- **Promotion Eligibility:** All configured eligibility rules must be satisfied (default AND logic).
- **Single Promotion per Estimate:** Only one promotion can be applied per estimate.
- **Calculation Determinism:** Pricing and tax calculations must be deterministic and reproducible.
- **Pricing Snapshot Immutability:** Pricing snapshots are immutable once created.
- **Restriction Enforcement:** Restrictions must be enforced synchronously; fail closed on evaluation service unavailability during commits.
- **Override Permissions:** Only authorized users with `pricing:restriction:override` can override restrictions.
- **Fail-Safe Principle:** On ambiguity or errors in eligibility or restriction evaluation, default to conservative (deny promotion or block product).

---

## Key Workflows

### MSRP Management

- Create/update MSRP records with validation against overlapping effective dates.
- Validate `ProductID` existence via Inventory domain.
- Retrieve active MSRP for a product on a given date.

### Price Book Rule Management

- Create/update base price book rules scoped by product/category/global, customer tier, location, and time.
- Validate conflicts and missing base data.
- Evaluate pricing by applying rules in precedence order.

### Location Price Override

- Store Manager submits override price.
- System validates against guardrails (min margin, max discount).
- Auto-approve or route for manual approval based on thresholds.
- Approval workflow with role-based approvers and escalation.

### Promotions

- Account Manager creates promotions with codes, discount types, validity, usage limits.
- Manage promotion lifecycle states: Draft, Active, Inactive, Expired.
- Define eligibility rules based on account IDs, vehicle tags, fleet size.
- Evaluate eligibility synchronously during pricing.
- Apply promotion during estimate pricing, returning pricing adjustments.

### Estimate Pricing & Totals Calculation

- Pricing Service calculates subtotal, taxes, fees, discounts, grand total for estimates.
- Generates immutable CalculationSnapshot for audit.
- Applies rounding policy (banker's rounding).
- Handles error flows for missing tax codes, unconfigured jurisdictions.

### Pricing Snapshot Creation

- On adding line items to estimates/work orders, Pricing Service creates immutable PricingSnapshot.
- Snapshot includes full pricing breakdown, applied rules, policy version.
- Workexec stores snapshot ID on line items.

### Restriction Rule Enforcement

- Evaluate product restrictions synchronously based on location and service context.
- Block or allow product addition accordingly.
- Allow authorized override with mandatory reason and audit logging.
- Support cached evaluations for UI responsiveness with confidence flags.

---

## Events / Integrations

- **MSRP Events:** `MSRP.Created`, `MSRP.Updated`
- **Price Book Rule Events:** `PriceBookRuleCreated`, `PriceBookRuleUpdated`, `PriceBookRuleDeactivated`
- **Price Override Events:** `PriceOverrideSubmittedForApproval`, `PriceOverrideActivated`, `PriceOverrideRejected`
- **Promotion Events:** Creation, activation, deactivation, eligibility rule changes, application events (e.g., `EstimateTotalsCalculated`)
- **Calculation Snapshot Events:** `CalculationSnapshotCreated`, `PricingSnapshotCreated`
- **Restriction Events:** `RestrictionRuleCreated`, `RestrictionEvaluated`, `RestrictionOverridden`, `RestrictionEvaluationFailed`
- **Integration Points:**
  - Inventory domain for product existence and cost.
  - CRM domain for customer tiers and promotion eligibility.
  - Workexec domain consumes pricing snapshots, promotions, and pricing results.
  - Product domain for MSRP base data.

---

## API Expectations (High-Level)

- MSRP CRUD with validation and retrieval of active MSRP by product and date.
- Price Book Rule management APIs with conflict detection.
- Location Price Override submission, approval, rejection endpoints.
- Promotion management APIs: create, activate, deactivate, query.
- Promotion eligibility evaluation API accepting account and vehicle context.
- Promotion application API during estimate pricing.
- Estimate totals calculation API producing CalculationSnapshot.
- Pricing snapshot creation and retrieval APIs.
- Restriction evaluation API with override submission.
- All APIs to follow RESTful conventions, use UUIDs, and support pagination/filtering where applicable.
- Specific API details: **TBD** (to be defined per implementation).

---

## Security / Authorization Assumptions

- All APIs require authentication and authorization.
- Role-based permissions:
  - MSRP management: `pricing:msrp:manage`
  - Price book rule management: `pricing:pricebook:manage`
  - Location override submission: `pricing:override:manage`
  - Override approval: `pricing:override:approve`
  - Promotion management: `pricing:promotion:manage`
  - Promotion eligibility configuration: `pricing:promotion:eligibility:manage`
  - Restriction override: `pricing:restriction:override`
- Sensitive operations (e.g., override approvals) require elevated permissions.
- Audit logs must capture user identity and action context.
- API input validation to prevent injection and ensure data integrity.

---

## Observability (Logs / Metrics / Tracing)

- **Logging:**
  - Structured logs for all API requests and responses (excluding secrets).
  - Log key business decisions (e.g., MSRP overlap validation failures, override approvals/rejections).
  - Log errors with stack traces and correlation IDs.
  - Log evaluation decisions with context (productId, promotionId, userId, timestamps).
  
- **Metrics:**
  - Counts of create/update/delete operations per entity type.
  - Success/failure counts for pricing calculations, overrides, promotions.
  - Latency histograms for critical APIs (e.g., price quote, eligibility evaluation).
  - Cache hit/miss rates for any caching layers.
  - Approval workflow metrics (time to approve, rejection rates).
  
- **Tracing:**
  - Distributed tracing for request flows involving multiple domains (e.g., Pricing → Inventory → CRM).
  - Correlation IDs propagated across service calls.
  
- **Audit Trail:**
  - Immutable audit logs for all critical changes (pricing rules, overrides, promotions, restrictions).
  - Override actions must include reason codes, approver IDs, timestamps.

---

## Testing Guidance

- **Unit Tests:**
  - Validate business rules (e.g., MSRP date overlap, price book precedence).
  - Validate eligibility rule evaluation logic with various input scenarios.
  - Validate override guardrail enforcement and approval workflows.
  - Validate rounding and tax calculation correctness.
  
- **Integration Tests:**
  - End-to-end MSRP lifecycle: create, update, retrieve active MSRP.
  - Price calculation with layered rules (base, override, tier).
  - Promotion lifecycle and eligibility evaluation.
  - Restriction evaluation and override flows.
  - Snapshot creation and retrieval consistency.
  
- **Performance Tests:**
  - Pricing quote API under load to meet SLA (P95 < 150ms).
  - Eligibility and restriction evaluation latency under concurrent requests.
  
- **Security Tests:**
  - Authorization enforcement per API.
  - Input validation and injection attack prevention.
  
- **Error Handling Tests:**
  - Invalid product IDs, invalid date ranges, overlapping MSRPs.
  - Missing base data scenarios.
  - Service unavailability and fail-safe behavior.
  
- **Audit & Logging Verification:**
  - Confirm audit events emitted with correct data.
  - Confirm logs contain necessary context for troubleshooting.

---

## Common Pitfalls

- **Domain Ownership Confusion:** Avoid mixing product ownership; always validate `ProductID` existence via Inventory domain, do not duplicate product data in Pricing.
- **MSRP Overlap Validation:** Failing to enforce temporal uniqueness leads to ambiguous active MSRP records.
- **Promotion Eligibility Logic:** Misinterpreting rule combination logic (default is AND) can cause incorrect eligibility decisions.
- **Ignoring Guardrails:** Allowing location price overrides without enforcing margin/discount limits or approval workflows risks financial loss.
- **Mutable Snapshots:** Pricing snapshots must be immutable; modifying historical snapshots breaks auditability.
- **Fail-Open on Restrictions:** Failing to fail closed on restriction evaluation during transaction commits risks compliance violations.
- **Inconsistent Rounding:** Applying inconsistent rounding policies leads to financial discrepancies.
- **Insufficient Logging:** Lack of structured logs and audit trails impedes troubleshooting and compliance.
- **Synchronous Cross-Domain Calls Without Timeouts:** Calls to Inventory or CRM must have strict timeouts and fallback strategies to avoid cascading failures.
- **Ignoring Timezones:** Dates are full dates (not timestamps); clarify timezone assumptions (UTC recommended) to avoid off-by-one-day errors.
- **Promotion Code Uniqueness Violations:** Duplicate promotion codes cause conflicts and user confusion.
- **Overloading Promotions:** Mixing multiple discount types in one promotion without clear rules complicates application logic.

---

*End of AGENT_GUIDE.md*
