## 🏷️ Labels (Proposed)

### Required
- type:story
- domain:crm
- domain:billing
- status:ready-for-dev

### Recommended
- agent:crm
- agent:billing
- agent:story-authoring

---

## Story Intent

As a **POS Clerk**, I need to enforce customer-specific billing rules (particularly PO requirements) during checkout so that commercial accounts comply with their contractual billing terms and internal approval processes, preventing order finalization until required documentation is captured.

---

## Actors & Stakeholders

- **POS Clerk / Service Advisor** (primary actor) — processes checkout and captures required PO references when prompted by the system
- **Commercial Customer** — subject to PO requirements and specific payment terms based on their account configuration
- **Finance / Accounts Receivable** — defines customer billing rules, payment terms, and credit limits via Billing Management system
- **Billing Management System** — authoritative source for customer billing rules, versioning, and credit limits
- **CRM System** — surfaces and caches customer billing configuration for UX and workflow (not authoritative)
- **Accounting System** — receives orders with PO references and applies payment terms for invoicing; enforces posting against credit limits
- **System** — enforces billing rules at checkout and blocks finalization if requirements are not met

---

## Preconditions

- The customer account exists with billing configuration defined in **Billing Management** (authoritative source)
- The customer's billing rules include a versioned "PO Required" flag (true/false) with effective dates
- Payment terms and charge account eligibility are configured for the customer in Billing Management
- The POS Clerk is authenticated and authorized to process checkout
- Override permissions and approval policies are defined for bypassing PO requirements (if permitted)
- Billing rule versions are immutably stored with `effectiveStartAt`, `effectiveEndAt`, `changedBy`, and `changeReason`

---

## Functional Behavior

### Enforce PO Requirement at Checkout
**When** a POS Clerk attempts to finalize an order for a commercial customer,
**Then** the system SHALL:
1. Query the customer's billing rules from **Billing Management** (authoritative source)
2. Retrieve the active billing rule version based on the current timestamp
3. If PO is required and no PO reference has been captured, block order finalization with a clear message (e.g., "PO number required for this customer")
4. Prompt the POS Clerk to enter the PO reference (alphanumeric, 3-30 characters, letters/numbers/`-`/`_` allowed, case-insensitive)
5. Validate the PO reference format:
   - Length: 3-30 characters
   - Pattern: alphanumeric with `-` and `_` allowed
   - No prefix requirement by default (customer-specific prefix configurable)
6. If customer has PO uniqueness policy configured, validate uniqueness:
   - `PER_ORDER` (default): no uniqueness check
   - `PER_ACCOUNT_OPEN_ORDERS`: check against open orders for this account
   - `PER_ACCOUNT_ALL_TIME`: check against all orders for this account
7. Link the PO reference to the order along with the `billingRuleVersion` used at checkout
8. Allow order finalization once the PO is captured and validated

### Apply Payment Terms (Optional Flow)
**When** a commercial customer has specific payment terms configured (e.g., "Net 30", "Net 60"),
**Then** the system SHALL:
1. Retrieve the payment terms from **Billing Management**
2. Apply the terms to the order/invoice (defer payment due date)
3. Set the billing method flag: `billingMethod = CHARGE_ACCOUNT | IMMEDIATE_PAYMENT`
4. Query credit limits and charge account eligibility from **Billing Management**
5. Record the applied payment terms for accounting integration
6. Pass credit limit information to Accounting for enforcement during posting

### Override PO Requirement (With Permission and Approval)
**When** a POS Clerk with override permission attempts to bypass the PO requirement,
**Then** the system SHALL:
1. Validate that the clerk has the `OVERRIDE_PO_REQUIREMENT` permission
2. Evaluate override policy rules:
   - Check order total against thresholds (e.g., > $10,000 requires escalated approval)
   - Check customer risk tier
   - Check account credit status
3. If order is under threshold and account is standard risk:
   - Allow **single approver** override (the clerk with permission)
4. If order exceeds threshold or account is high-risk:
   - Require **two-person approval** (second approver with override permission)
   - Prompt for second approver authentication
5. Prompt for an override reason code (mandatory, from predefined list or free text)
6. Capture override approval details:
   - `approverId(s)` (one or two approvers depending on policy)
   - `policyVersion` (version of override policy applied)
   - `overrideReasonCode` (mandatory)
   - `overrideTimestamp`
7. Allow order finalization without a PO reference
8. Log the override event with complete audit trail
9. Flag the order as "PO Overridden" for finance review

---

## Alternate / Error Flows

### Error: PO Requirement Not Met
**When** a POS Clerk attempts to finalize an order for a customer with PO requirement but no PO captured,
**Then** the system SHALL:
1. Reject the finalization request
2. Display a clear error message: "PO number required for customer [Customer Name]. Please enter PO or contact finance for override approval."
3. Log the blocked attempt (clerk, customer, timestamp, billingRuleVersion)

### Error: Invalid PO Format
**When** a PO reference is entered but does not match the validation pattern (3-30 characters, alphanumeric with `-` and `_`),
**Then** the system SHALL:
1. Reject the PO
2. Display an error message describing the expected format: "PO must be 3-30 characters, letters, numbers, dashes, and underscores only."
3. Prompt for correction

### Error: PO Uniqueness Violation
**When** a PO reference violates the customer's configured uniqueness policy,
**Then** the system SHALL:
1. Reject the PO
2. Display an error message: "PO number [PO] is already in use for another order for this customer. Please use a unique PO or contact the customer."
3. Log the uniqueness violation (clerk, customer, PO number, conflicting order reference)

### Error: Unauthorized Override Attempt
**When** a POS Clerk without override permission attempts to bypass the PO requirement,
**Then** the system SHALL:
1. Reject the override request
2. Display an error message: "Insufficient permissions to override PO requirement. Contact a manager."
3. Log the denied override attempt (clerk, customer, timestamp)

### Error: Missing Customer Billing Configuration (Fail-Safe for B2B)
**When** a customer account identified as B2B lacks billing rule configuration (PO required flag is undefined or invalid),
**Then** the system SHALL:
1. **Default to REQUIRING PO** (fail-safe for commercial accounts)
2. Block checkout with a clear error message: "Billing rules not configured for this B2B customer. Please contact an administrator."
3. Log a critical warning for Billing Management administrators to review and configure billing rules
4. **Do not allow checkout to proceed** until rules are configured or an authorized override is applied

### Error: Insufficient Credit Limit
**When** an order with charge account billing method would exceed the customer's credit limit,
**Then** the system SHALL:
1. Query credit limit from **Billing Management**
2. Block order finalization if credit limit would be exceeded
3. Display an error message: "Order total exceeds available credit limit. Please select a different payment method or contact finance."
4. Log the credit limit violation

### Error: Two-Person Approval Required but Not Provided
**When** an override requires two-person approval but only one approver is authenticated,
**Then** the system SHALL:
1. Reject the override request
2. Display an error message: "This override requires approval from a second authorized user. Order total exceeds single-approval threshold."
3. Prompt for second approver authentication
4. Log the incomplete approval attempt

---

## Business Rules

1. **Billing Rule Authority:** **Billing Management** is the authoritative source for customer billing rules. CRM may cache and display rules for UX, but all queries for enforcement must go to Billing Management.

2. **Rule Versioning (Mandatory):** Billing rules are versioned and timestamped with:
   - `ruleId` (unique identifier)
   - `version` (immutable version number)
   - `effectiveStartAt` (timestamp when rule becomes active)
   - `effectiveEndAt` (timestamp when rule expires, nullable for current rules)
   - `changedBy` (user identity who created the rule version)
   - `changeReason` (mandatory audit note)

3. **Order-Rule Linkage:** Orders must capture and store the `billingRuleVersion` used at time of checkout for historical reconciliation. No retroactive rule changes may affect completed orders.

4. **PO Format (Default):** PO numbers must be:
   - Alphanumeric (letters, numbers, `-`, `_` allowed)
   - Case-insensitive
   - Length: 3-30 characters
   - No prefix requirement by default (customer-specific prefix configurable)

5. **PO Uniqueness (Configurable):** PO uniqueness is configurable per customer:
   - `PER_ORDER` (default): Same PO may be used across multiple orders (blanket PO pattern)
   - `PER_ACCOUNT_OPEN_ORDERS`: PO must be unique among open orders for the customer
   - `PER_ACCOUNT_ALL_TIME`: PO must be globally unique for the customer across all time

6. **Override Permission:** `OVERRIDE_PO_REQUIREMENT` is a standalone permission. Roles (e.g., Manager, Finance Manager) may bundle this permission, but it is not role-specific.

7. **Override Approval Workflow:** Overrides are subject to policy rules:
   - **Single approver** sufficient for orders under threshold and standard-risk accounts
   - **Two-person approval** required for:
     - Orders exceeding dollar threshold (e.g., > $10,000)
     - High-risk customer accounts
     - Accounts with credit holds or past-due balances

8. **Mandatory Override Audit:** All overrides must capture:
   - `approverId(s)` (one or two approvers)
   - `policyVersion` (version of override policy applied)
   - `overrideReasonCode` (mandatory, from predefined list or free text)
   - `overrideTimestamp`

9. **Immutable PO Reference:** Once a PO reference is captured and linked to an order, it MUST NOT be editable (only correction via adjustment order or cancellation/re-entry).

10. **Payment Terms Independence:** Payment terms (e.g., Net 30) and PO requirements are independent but commonly paired for commercial accounts. One does not automatically trigger the other, but policies may recommend specific combinations.

11. **Charge Account Billing Method:** Charge account is a billing method flag on the order (`billingMethod = CHARGE_ACCOUNT | IMMEDIATE_PAYMENT`), not a separate checkout path. Orders with `CHARGE_ACCOUNT` billing method defer payment and apply terms.

12. **Credit Limit Authority:** **Billing Management** owns customer credit limits and charge account eligibility. Accounting enforces posting against limits; CRM displays status for UX.

13. **Fail-Safe Default for B2B Accounts:** If billing rules are undefined or invalid for a customer identified as B2B:
    - **Default to REQUIRING PO** (fail-safe)
    - **Block checkout** with clear error message
    - **Require billing rule configuration** or authorized override before proceeding
    - Global fallback policy: `ALL_B2B_REQUIRE_PO = true` (recommended default)

14. **No External PO Validation (Default):** PO numbers are not validated against external customer procurement systems by default. Any value matching the format is acceptable unless customer-specific integration is configured.

---

## Data Requirements

**Customer Billing Configuration (Billing Management - Authoritative):**
- `customer_id` (unique identifier)
- `billing_rule_id` (unique rule identifier)
- `billing_rule_version` (immutable version number)
- `po_required` (boolean flag)
- `payment_terms` (e.g., "Net 30", "Net 60", "COD", nullable)
- `charge_account_eligible` (boolean flag)
- `credit_limit` (decimal, nullable)
- `risk_tier` (e.g., "STANDARD", "HIGH_RISK", "CREDIT_HOLD")
- `po_uniqueness_policy` (enum: `PER_ORDER`, `PER_ACCOUNT_OPEN_ORDERS`, `PER_ACCOUNT_ALL_TIME`)
- `po_format_prefix` (optional customer-specific prefix requirement)
- `effective_start_at` (timestamp when rule becomes active)
- `effective_end_at` (timestamp when rule expires, nullable for current rules)
- `changed_by` (user identity who created the rule version)
- `change_reason` (mandatory audit note for rule changes)

**PO Reference (Order Data):**
- `order_id` (foreign key)
- `po_number` (alphanumeric, 3-30 characters)
- `po_captured_at` (timestamp)
- `po_captured_by` (clerk identity)
- `billing_rule_version` (foreign key to specific billing rule version used at checkout)
- `po_overridden` (boolean flag, default false)
- `override_reason_code` (mandatory if overridden, from predefined list or free text)
- `override_approver_ids` (array of user identities, one or two approvers)
- `override_policy_version` (version of override policy applied)
- `override_at` (timestamp, nullable)

**Override Policy Configuration:**
- `policy_id` (unique identifier)
- `policy_version` (immutable version number)
- `single_approval_threshold` (decimal dollar amount, e.g., 10000.00)
- `two_person_approval_required_above_threshold` (boolean)
- `high_risk_requires_two_person` (boolean)
- `credit_hold_requires_two_person` (boolean)
- `effective_start_at` (timestamp)
- `effective_end_at` (timestamp, nullable)

**Audit Log (Billing Rule Enforcement Events):**
- `audit_id` (unique identifier)
- `event_type` (e.g., `PORequired`, `POCaptured`, `POOverridden`, `POBlockedCheckout`, `InvalidPOFormat`, `POUniquenessViolation`, `UnauthorizedPOOverride`, `TwoPersonApprovalRequired`, `CreditLimitExceeded`)
- `customer_id` (foreign key)
- `order_id` (foreign key, nullable if checkout blocked)
- `billing_rule_version` (foreign key to billing rule version queried)
- `actor_id` (clerk or approver performing action)
- `approver_ids` (array of approver identities for overrides)
- `override_policy_version` (foreign key if override occurred)
- `timestamp` (immutable)
- `details` (JSON or structured log, includes PO number, reason codes, policy evaluation results)

---

## Acceptance Criteria

### AC1: PO Requirement Enforced (Billing Management Authority)
- **Given** a commercial customer with "PO Required" enabled in **Billing Management**,
- **When** a POS Clerk attempts to finalize an order without entering a PO reference,
- **Then** the system queries Billing Management for the active billing rule version, blocks finalization, displays a clear error message, and logs the blocked attempt with `billingRuleVersion`.

### AC2: PO Captured with Rule Version Linkage
- **Given** a commercial customer with "PO Required" enabled,
- **When** a POS Clerk enters a valid PO reference (e.g., "PO-12345") matching the format (3-30 characters, alphanumeric with `-` and `_`),
- **Then** the system validates format, links the PO to the order, captures the `billingRuleVersion` used at checkout, allows finalization, and logs the `POCaptured` event.

### AC3: PO Format Validation
- **Given** a POS Clerk enters a PO reference,
- **When** the PO does not match the format requirements (e.g., 2 characters, special characters),
- **Then** the system rejects the PO, displays a clear format error message, and prompts for correction.

### AC4: PO Uniqueness Policy Enforced
- **Given** a customer with PO uniqueness policy set to `PER_ACCOUNT_OPEN_ORDERS`,
- **When** a POS Clerk enters a PO number already used for another open order for this customer,
- **Then** the system rejects the PO, displays a uniqueness error message with the conflicting order reference, and logs the violation.

### AC5: Single Approver Override (Under Threshold)
- **Given** a POS Clerk with `OVERRIDE_PO_REQUIREMENT` permission,
- **And** an order total under the single-approval threshold (e.g., $8,000),
- **And** the customer is standard-risk,
- **When** the clerk overrides the PO requirement and provides a reason code (e.g., "Customer verbal approval obtained"),
- **Then** the system allows finalization with single approver, flags the order as "PO Overridden", and logs the override event with clerk identity, reason code, and `policyVersion`.

### AC6: Two-Person Approval Required (Above Threshold)
- **Given** a POS Clerk with `OVERRIDE_PO_REQUIREMENT` permission,
- **And** an order total exceeding the single-approval threshold (e.g., $12,000),
- **When** the clerk attempts to override the PO requirement,
- **Then** the system requires a second approver with override permission to authenticate, captures both `approverId`s, and logs the two-person approval event.

### AC7: Unauthorized Override Denied
- **Given** a POS Clerk without `OVERRIDE_PO_REQUIREMENT` permission,
- **When** the clerk attempts to bypass the PO requirement,
- **Then** the system rejects the override, displays an error message, and logs the denied attempt.

### AC8: Fail-Safe Default for Misconfigured B2B Account
- **Given** a customer identified as B2B with undefined or invalid billing rule configuration,
- **When** a POS Clerk attempts to finalize an order,
- **Then** the system defaults to REQUIRING PO (fail-safe), blocks checkout with a clear error message ("Billing rules not configured"), and logs a critical warning for administrators.

### AC9: Audit Trail Includes Rule Version and Approvers
- **Given** a PO requirement override has occurred with two-person approval,
- **When** finance reviews the audit trail,
- **Then** the `POOverridden` event is present with both approver identities, override reason code, `billingRuleVersion`, `policyVersion`, customer account, and timestamp.

### AC10: Payment Terms Applied with Credit Limit Check
- **Given** a commercial customer with "Net 30" payment terms and a credit limit of $50,000,
- **And** the customer has $45,000 in outstanding invoices,
- **When** an order for $6,000 is finalized with PO captured and `billingMethod = CHARGE_ACCOUNT`,
- **Then** the system blocks finalization due to credit limit exceeded, displays a clear error message, and logs the `CreditLimitExceeded` event.

### AC11: Historical Orders Reference Billing Rule Version
- **Given** a completed order with a PO reference,
- **When** the customer's billing rules are updated (new version created),
- **Then** the completed order continues to reference the original `billingRuleVersion` used at checkout, and no retroactive rule changes affect the historical order.

---

## Audit & Observability

**Audit Events to Log:**
- `PORequired` — fired when the system detects a PO requirement for a customer at checkout (include customer, clerk, timestamp, billingRuleVersion)
- `POCaptured` — fired when a PO reference is successfully entered and validated (include customer, order, PO number, clerk, timestamp, billingRuleVersion)
- `POOverridden` — fired when a clerk overrides the PO requirement (include customer, order, clerk(s), reason code, timestamp, policyVersion, billingRuleVersion, number of approvers)
- `POBlockedCheckout` — fired when finalization is blocked due to missing PO (include customer, order, clerk, timestamp, billingRuleVersion)
- `InvalidPOFormat` — fired when a PO reference fails format validation (include customer, order, PO number, clerk, timestamp)
- `POUniquenessViolation` — fired when a PO reference violates uniqueness policy (include customer, order, PO number, clerk, timestamp, conflicting order reference, uniqueness policy)
- `UnauthorizedPOOverride` — fired when a clerk without permission attempts an override (include customer, order, clerk, timestamp)
- `TwoPersonApprovalRequired` — fired when an override requires two-person approval (include customer, order, clerk, timestamp, policyVersion, order total, threshold)
- `TwoPersonApprovalCompleted` — fired when two-person approval is successfully completed (include customer, order, both approver identities, timestamp)
- `CreditLimitExceeded` — fired when an order would exceed customer credit limit (include customer, order, credit limit, current balance, order total, timestamp)
- `MissingBillingConfiguration` — fired when a B2B customer lacks billing rule configuration (include customer, clerk, timestamp)

**Observability Metrics:**
- Count of PO-required orders per day/week/month (to understand commercial account volume)
- Count of PO overrides per clerk (to detect pattern abuse or training needs)
- Count of single-approver vs. two-person overrides (to validate policy effectiveness)
- Count of blocked checkouts due to missing PO (to measure friction and potential process improvements)
- Count of blocked checkouts due to misconfigured billing rules (to identify configuration gaps)
- Average time from order start to PO capture (to measure clerk efficiency)
- Count of invalid PO format errors (to identify validation rule issues)
- Count of PO uniqueness violations (to identify customer PO management issues)
- Count of credit limit exceeded events (to monitor credit risk exposure)
- Billing rule version distribution (to track adoption of rule updates)

---

## Resolved Questions & Decisions

The following clarifications were provided by business stakeholders and integrated into this story:

### Decision 1: Billing Rule Ownership and Authority
**Question:** Who is the authoritative source for customer billing rules?

**Decision:**
- **Billing Management (Billing domain)** is the system of record for customer billing rules
- CRM may surface and cache selected flags (e.g., "PO Required") for UX and workflow, but is not authoritative
- Accounting consumes rules for posting and reconciliation; it does not own them
- Changes are performed via Billing Management APIs by users with `MANAGE_BILLING_RULES` permission
- Billing rules are versioned and timestamped: `ruleId`, `version`, `effectiveStartAt`, `effectiveEndAt`, `changedBy`, `changeReason`
- Orders reference the `billingRuleVersion` used at time of checkout
- No retroactive mutation of rules for historical orders

**Why this works:** Single authority, immutable history, and deterministic reconciliation.

---

### Decision 2: PO Validation Rules and Format
**Question:** What are the PO format and validation requirements?

**Decision:**
- **Format:** Alphanumeric, case-insensitive
- **Length:** 3-30 characters
- **Allowed characters:** Letters, numbers, `-`, `_`
- **No prefix requirement** by default (configurable per customer if needed)
- **No external validation** against customer procurement systems by default
- **Uniqueness scope** (configurable per customer):
  - `PER_ORDER` (default): Same PO allowed across orders (blanket PO pattern)
  - `PER_ACCOUNT_OPEN_ORDERS`: PO unique among open orders
  - `PER_ACCOUNT_ALL_TIME`: PO globally unique for customer

**Why this works:** Flexible format supports common blanket PO patterns while allowing customer-specific constraints.

---

### Decision 3: Override Permission Scope and Approval Workflow
**Question:** What are the override permission and approval requirements?

**Decision:**
- `OVERRIDE_PO_REQUIREMENT` is a standalone permission, not implicitly tied to a role
- Roles (e.g., Manager, Finance Manager) bundle this permission
- Overrides are subject to policy rules:
  - Order total thresholds (e.g., > $10,000)
  - Customer risk tier
  - Account credit status
- **Approval workflow:**
  - **Single approver** with permission for orders under threshold
  - **Two-person rule** required above threshold or for high-risk accounts
- **Approval captures:**
  - `approverId(s)`
  - `policyVersion`
  - `overrideReasonCode`

**Why this works:** Balances speed with control and audit strength. Prevents override sprawl while maintaining operational flexibility.

---

### Decision 4: Payment Terms and Charge Account Integration
**Question:** How do payment terms and PO requirements interact?

**Decision:**
- **Independent but commonly paired:** PO Required does not automatically imply Net terms, and vice versa
- Policies may recommend combinations (e.g., PO + Net 30) but do not hard-couple
- **Charge account is a billing mode flag** on the order: `billingMethod = CHARGE_ACCOUNT | IMMEDIATE_PAYMENT`
- **Billing Management owns:**
  - Credit limits
  - Charge account eligibility
  - Risk holds
- Accounting enforces posting against limits; CRM displays status

**Why this works:** Keeps credit risk centralized and consistent while maintaining flexibility in payment workflows.

---

### Decision 5: Default Behavior for Missing or Misconfigured Rules
**Question:** What should the system do if billing rules are undefined?

**Decision:**
- **Fail-safe for commercial (B2B) accounts**
- If `PO Required` is undefined for a B2B account: **Require PO**
- If rule configuration is missing or invalid: **Block checkout** with clear error: "Billing rules not configured. Please contact an administrator."
- Optional global fallback: `ALL_B2B_REQUIRE_PO = true` (recommended default)
- Authorized users may override with proper approval and audit

**Why this works:** Prevents silent non-compliance while allowing controlled exceptions. Protects against configuration drift and ensures deliberate rule management.

---

## Original Story (Unmodified – For Traceability)

# Issue #3 — [BACKEND] [STORY] Customer: Enforce PO Requirement and Billing Rules During Checkout

## Current Labels
- backend
- story-implementation
- payment

## Current Body
## Backend Implementation for Story

**Original Story**: [STORY] Customer: Enforce PO Requirement and Billing Rules During Checkout

**Domain**: payment

### Story Description

/kiro
# User Story

## Narrative
As a **POS Clerk**, I want billing rules enforced so that compliance is maintained for commercial accounts.

## Details
- If PO required, block finalization until captured.
- Apply terms/charge account flow optional.
- Override requires permission.

## Acceptance Criteria
- Rule enforced consistently.
- Override requires permission.
- Audit includes who/why.

## Integrations
- CRM billing rules; accounting terms may apply.

## Data / Entities
- BillingRuleCheck, PoReference, AuditLog

## Classification (confirm labels)
- Type: Story
- Layer: Experience
- domain :  Point of Sale

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
