# Safe Default Assumptions Appendix (POS Domains)
**Purpose:** Provide domain-specific defaults that an agent/copilot may assume to enrich stories **without requiring clarification**.  
**Rule:** If an assumption crosses into **policy / money / security / legal / regulatory** territory, it MUST become an Open Question and trigger clarification.

---

## Global Safe Defaults (Apply Unless Contradicted)

### Roles & Actors (safe)
- Common actors exist: **Service Advisor**, **Mechanic/Technician**, **Manager**, **Accounting Clerk** (if applicable), **System**
- Actions require authentication; roles determine access (RBAC concept only)

### Record Identity & Traceability (safe)
- Core records have stable identifiers (UUID preferred)
- Records include created/updated timestamps
- Stories should preserve traceability between upstream/downstream artifacts (e.g., estimate → workorder → invoice)

### Error Handling (safe)
- Validation errors are user-correctable
- System errors are logged and surfaced as a generic failure with correlation id
- Idempotency is desirable for external calls, but details require clarification

### Audit (safe)
- Important lifecycle transitions emit an auditable event (schema may be TBD)
- Audit includes actor, timestamp, action, affected entity id, and before/after state when feasible

---

## 1) Accounting (domain:accounting)
**Allowed Safe Defaults (Conceptual only)**
- AR/AP/GL integrations exist as conceptual downstream posting targets
- Invoices and payments produce accounting-relevant events (no posting logic assumed)

**NOT Safe (Always Clarify)**
- Debit/credit entries, posting accounts, journal structure
- Tax calculation logic and jurisdiction
- Revenue recognition timing
- Rounding, currency conversion, settlement timing
- Refund/chargeback accounting treatment

**Safe Enrichment Examples**
- “When an invoice is finalized, the system emits an `InvoiceFinalized` event for downstream accounting.”
- “When a payment is applied, the system emits a `PaymentApplied` event with invoice linkage.”

---

## 2) Inventory Control (domain:inventory)
**Allowed Safe Defaults**
- Inventory items exist in locations (warehouse, service bay, truck)
- Inventory lifecycle includes: **Receive → Putaway → Pick/Issue → Consume/Return → Adjust**
- Stock status is tracked (e.g., Available, Reserved, Damaged, Returned)

**NOT Safe (Always Clarify)**
- Costing method (FIFO/LIFO/AVG)
- Reservation/allocation priority rules
- Ownership transfer rules (consignment / customer-owned / vendor-managed)
- Serialized handling requirements if not stated
- Backorder/substitution policies

**Safe Enrichment Examples**
- Add explicit states: Available vs Reserved
- Add error flows: insufficient quantity, location mismatch
- Add acceptance criteria for audit trail on adjustments

---

## 3) Product & Catalog (domain:product)
**Allowed Safe Defaults**
- Products can be goods (parts/tires) or services (labor lines)
- Products have SKU/identifier, name, description, unit of measure
- Catalog visibility can depend on location and active/inactive status

**NOT Safe (Always Clarify)**
- Fitment rules (vehicle compatibility)
- Product configuration rules (options/variants) beyond simple attributes
- Bundle/kit composition policies
- Manufacturer restrictions or warranty constraints if implied

**Safe Enrichment Examples**
- Require fields: SKU, description, UOM, active flag
- Describe lifecycle: Draft/Active/Inactive (if needed)

---

## 4) Pricing & Fees (domain:pricing)
**Allowed Safe Defaults**
- Prices are applied to line items at time of quote/invoice
- Discounts may exist (percentage or fixed amount) conceptually
- Fees may be separate line items (shop fee, disposal fee) conceptually

**NOT Safe (Always Clarify)**
- Discount precedence and stacking
- Price override permissions
- Promotions and eligibility rules
- Rounding rules
- Any tax calculations

**Safe Enrichment Examples**
- “System stores both list price and applied price per line item.”
- “Applied discounts are recorded with reason codes (if provided).”

---

## 5) CRM (domain:crm)
**Allowed Safe Defaults**
- Customer has: name, contact methods, addresses
- Fleet accounts may have multiple vehicles and contacts
- Customer records may be active/inactive

**NOT Safe (Always Clarify)**
- Customer dedup/merge rules
- Credit terms, credit limits, invoicing policies
- Complex hierarchy behavior (parent/child billing) unless stated

**Safe Enrichment Examples**
- Add acceptance criteria for searching by name/phone/email
- Include audit on customer updates

---

## 6) Shop Management (domain:shopmgmt)
**Allowed Safe Defaults**
- Locations have operating hours and bays/resources
- Appointments and workorders are scheduled to a location
- Work can be assigned to a technician

**NOT Safe (Always Clarify)**
- Scheduling optimization rules
- Dispatch priority policies
- Capacity constraints (exact formulas)
- Overtime/timekeeping policy

**Safe Enrichment Examples**
- Add error flow: cannot schedule outside operating hours (if hours exist)
- Add state: Scheduled → In Progress → Completed

---

## 7) Workorder Execution (domain:workexec)
**Allowed Safe Defaults**
- Workorder lifecycle includes: Created → Approved → In Progress → Completed
- Work includes labor lines and parts consumption
- Completion captures who did the work and when

**NOT Safe (Always Clarify)**
- Reopen rules and constraints
- Partial completion behavior
- Authorization for adjustments to completed work
- Warranty-specific behavior if implied

**Safe Enrichment Examples**
- Add acceptance criteria that completion requires at least one performed action
- Add audit events for state transitions

---

## 8) Billing (Invoicing & Payments) (domain:billing)
**Allowed Safe Defaults**
- Invoices are generated from completed workorders
- Payments can be recorded against invoices
- Payment failures produce a recoverable state

**NOT Safe (Always Clarify)**
- Partial payments and allocation order
- Refund and reversal policy
- Chargeback handling
- Settlement timing and reconciliation ownership

**Safe Enrichment Examples**
- Add acceptance criteria for invoice immutability after finalization (unless adjustment story exists)
- Add audit events: InvoiceFinalized, PaymentRecorded

---

## 9) People & Roles (domain:people)
**Allowed Safe Defaults**
- Users have roles
- Roles gate access to actions
- People records may be separate from users (profile vs login)

**NOT Safe (Always Clarify)**
- Role inheritance and delegation rules
- Termination/leave impacts on access
- Timekeeping/payroll policies

**Safe Enrichment Examples**
- Add acceptance criteria: role required to approve or finalize
- Add audit: changes to roles are logged

---

## 10) Location Management (domain:location)
**Allowed Safe Defaults**
- A location is a physical shop/site with address and timezone
- Location has identifiers and active status
- Workorders and inventory may reference a location

**NOT Safe (Always Clarify)**
- Cross-location transfer policies
- Ownership/accounting segmentation by location
- Regional compliance differences

**Safe Enrichment Examples**
- Require location timezone for timestamp consistency
- Add acceptance criteria: deactivated locations cannot accept new workorders

---

## 11) Positivity Integrations (domain:positivity)
**Allowed Safe Defaults**
- External interactions are event-driven or request/response
- Failures are retried or queued conceptually (details TBD)
- External references are stored for traceability

**NOT Safe (Always Clarify)**
- API schemas, endpoints, authentication methods
- Idempotency keys and retry backoff policy
- System of record for shared data

**Safe Enrichment Examples**
- Add requirement to capture external correlation ids
- Add acceptance criteria that integration failures do not lose the core transaction

---

## 12) Security & Authorization (domain:security)
**Allowed Safe Defaults (high-level only)**
- RBAC exists
- Sensitive actions require explicit permission
- Audit logging applies to security-relevant actions

**NOT Safe (Always Clarify)**
- Authentication mechanism (SSO/OAuth/etc.)
- Data encryption/masking requirements
- Tenant isolation rules
- Regulatory compliance specifics

**Safe Enrichment Examples**
- Add acceptance criteria: unauthorized users receive access denied
- Add audit requirement for permission changes

---

## 13) Audit & Observability (domain:audit)
**Allowed Safe Defaults**
- Key business events are auditable
- Logs include correlation ids
- Operational metrics exist (success/failure counts)

**NOT Safe (Always Clarify)**
- Retention periods
- Schema contracts (unless provided elsewhere)
- Regulatory audit formats

**Safe Enrichment Examples**
- Require audit fields: actor, timestamp, action, entity id, before/after state
- Require correlation id returned on failure

---

## Decision Rule (Summary)
**Assume**: workflow/state patterns, traceability, generic audit needs, basic RBAC concept  
**Clarify**: money/tax/accounting posting, legal/regulatory, detailed security posture, external contract schemas, precedence rules

---
