Below is a **complete Accounting Domain Agent Guide** followed by a **Story Validation Checklist**.
Both are derived directly from the reviewed `domain-accounting.txt` corpus and are written as **contractual guidance** for AI agents and reviewers working in `domain:accounting`.

---

# **Accounting Domain — Agent Guide**

## 1. Domain Purpose

The **Accounting domain** is the **system of record for financial truth**.
It is responsible for transforming operational domain events into **balanced, auditable, period-controlled financial records**.

This domain owns:

* Chart of Accounts (CoA)
* Posting categories and mapping rules
* Journal Entry (JE) creation and posting
* Accounting periods and locks
* Sub-ledger integrity (Inventory, WIP, AP, AR, Cash)
* Auditability, explainability, and reconciliation

The Accounting domain **does not initiate business activity**.
It **reacts** to authoritative events from other domains and enforces accounting law and policy.

---

## 2. Domain Ownership & Boundaries

### Accounting **OWNS**

* Posting rules and rule versioning
* Journal Entry generation and posting
* Period open/close enforcement
* Reversals and corrections
* Financial reconciliation workflows
* Audit and explainability views

### Accounting **CONSUMES**

* Immutable events from:

  * Work Execution
  * Inventory
  * Billing
  * Payments

### Accounting **NEVER**

* Mutates upstream domain state
* Invents or redefines event semantics
* Performs operational workflow decisions
* Posts revenue or AR prematurely
* Allows unbalanced or silent postings

---

## 3. Core Invariants (Non-Negotiable)

1. **Immutability**

   * Posted ledger lines are immutable
   * Corrections occur via reversal entries only

2. **Balance**

   * Every Journal Entry must balance per currency
   * Unbalanced entries are rejected

3. **Traceability**

   * Every JE traces to:

     * Source event
     * Mapping rule version
     * Posting category
     * Business document (e.g., workorder)

4. **Idempotency**

   * One event → one accounting outcome
   * Replays must not duplicate financial impact

5. **Period Control**

   * No posting to closed periods
   * Period reopen requires audit and authorization

---

## 4. Business Rules (Mandatory)

### Event Processing

* Events must be schema-valid and versioned
* Required conditional attributes must be present
* No synchronous cross-domain lookups during posting

### Journal Entries

* Created as **Draft** before posting
* Posting is atomic
* Posting failures must not partially persist data

### Inventory & WIP

* Valuation method is configuration-driven
* Inventory and WIP movements are event-driven only
* Each adjustment must be explainable and auditable

### Payments (AP)

* Draft → Approved → Scheduled lifecycle
* Approval thresholds strictly enforced
* Payment execution blocked unless approved

### Periods

* Period close enforces hard locks
* Reopen requires reason codes and audit entries

---

## 5. Error Handling Policy

* **Validation failures** → reject deterministically
* **Mapping failures** → DLQ or suspense (explicit policy required)
* **Posting failures** → retry → DLQ → alert
* **Audit failure** → abort operation

Silent failure is prohibited.

---

## 6. Observability Requirements

* Structured logs with eventId, ruleVersion, reason
* Metrics for:

  * JE creation success/failure
  * Posting latency
  * Rule evaluation outcomes
* Alerts for:

  * DLQ growth
  * Posting failures
  * Period violations

---

## 7. Anti-Patterns (Auto-Reject)

* Silent correction
* Inline ledger mutation
* Best-effort posting
* Financial logic outside accounting
* Cross-domain writes

---