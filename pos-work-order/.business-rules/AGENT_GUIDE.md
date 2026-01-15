Below is a **formal Agent Charter** for **`domain:workexec`**, written as a **contractual guidance document** for AI agents.
It is intentionally explicit, rule-driven, and suitable for direct reuse in Kiro / agent specifications.

---

# **Work Execution Domain (`domain:workexec`) — Agent Charter**

## 1. Domain Mission

The **Work Execution domain** is the **authoritative system of record for executable work** after customer intent has been approved.
It governs **what work is allowed to happen**, **what work actually happened**, and **when execution facts become immutable**.

The domain exists to:

* Convert **approved intent** into **controlled execution**
* Preserve **traceability, auditability, and non-repudiation**
* Emit **reliable, idempotent execution facts** for downstream systems

---

## 2. Domain Ownership & Boundaries

### 2.1 Owns

The Work Execution domain **owns**:

* Estimate → Workorder promotion
* Customer approval lifecycle (digital, in-person, partial, expiration)
* Workorder state transitions
* Execution facts:

  * Parts issued / consumed / returned
  * Labor recorded (flat-rate and time-based)
  * Timekeeping submission and approval (execution side)
* Reopen and correction workflows
* Audit trails for all execution transitions

### 2.2 Does *Not* Own

The Work Execution domain **does not own**:

* Accounts Receivable or revenue posting
* Accounting rules (COGS vs WIP decisions)
* CRM customer master data
* HR payroll calculations

It **emits facts**; other domains **interpret** them.

---

## 3. Core Business Invariants (Hard Rules)

These rules are **non-negotiable**.
Any story or code that violates them is **invalid**.

### BR-1: Snapshot Immutability

* All execution must reference a **versioned snapshot** (estimate, scope, approval).
* Once approved, snapshots **must never be mutated**.
* Changes require **re-approval or reopen** workflows.

### BR-2: Explicit State Transitions Only

* Every Workorder action must:

  * Validate the current state
  * Transition to an allowed next state
* Invalid transitions must **fail explicitly**.

### BR-3: Audit Is Mandatory

* Every meaningful business action must:

  * Persist an AuditEvent
  * Record actor, timestamp, and snapshot/version
* If audit persistence fails, the **business action fails**.

### BR-4: No Silent Corrections

* Post-approval or post-completion edits are forbidden.
* Corrections must use:

  * Reopen
  * Adjustment
  * Superseding records
* Silent mutation is a critical defect.

### BR-5: Idempotency Is Required

* All commands and emitted events must be idempotent.
* Replays must never:

  * Double-consume inventory
  * Duplicate labor
  * Re-emit approvals

### BR-6: Execution ≠ Financial Posting

* Workexec **never posts revenue**.
* All emitted accounting-related events are **non-posting signals**.
* Downstream accounting decides financial treatment.

---

## 4. Canonical Business Capabilities & Rules

### 4.1 Promotion (Estimate → Workorder)

**Rules**

* Promotion requires:

  * Approved estimate
  * Approval record
* Promotion is **atomic**.
* Promotion must record:

  * Snapshot version
  * Approval reference
  * Initiating actor

**Failure Handling**

* Partial promotion is forbidden.
* Audit failure causes rollback.

---

### 4.2 Customer Approvals

**Approval Types**

* Digital
* In-person
* Partial (line-item level)

**Rules**

* Approval allowed only in valid states.
* Approval payload integrity must be verifiable (hashing).
* Expired approvals must auto-invalidate.
* Retention rules:

  * B2B: through invoicing
  * B2C: archived with invoice

---

### 4.3 Parts Execution

**Rules**

* Parts usage must be recorded as **events**, not overwrites.
* Consumption cannot exceed authorized quantity without approval.
* Inventory effects must be idempotent.
* Replays must not double-reduce stock.

**Accounting Posture**

* Emits InventoryIssued / PartConsumed
* Non-posting by default (WIP or COGS decided elsewhere)

---

### 4.4 Labor Recording

**Rules**

* Supports flat-rate and time-based labor.
* Labor entries are auditable and versioned.
* Updates supersede prior entries.
* Labor events do **not** create AR.

---

### 4.5 Timekeeping (Execution Side)

**Rules**

* Submitted time is mutable until approved.
* Approved time is **locked**.
* On-behalf edits:

  * Allowed only pre-approval
  * Require reason codes
  * Must be fully auditable

**Integration**

* Approved time is emitted to HR/People as final facts.

---

### 4.6 Reopen & Corrections

**Rules**

* Reopen is an exception path.
* Requires explicit authorization and reason.
* Reopen invalidates invoice-ready snapshots.
* Emits exactly one WorkorderReopened event per version.

---

## 5. Integration Rules

### 5.1 Outbound

* Accounting: execution facts only (non-posting)
* HR/People: finalized time entries
* Events must be:

  * Versioned
  * Idempotent
  * Immutable once published

### 5.2 Inbound

* CRM rules (e.g., PO requirements) are **read-only inputs**.
* Workexec enforces rules but does not own them.

---

## 6. Observability Requirements (Mandatory)

Every Workexec story must define:

* **Audit Events**

  * What changed
  * Who initiated it
  * Snapshot/version used
* **Logs**

  * INFO for lifecycle start/end
  * WARN for business rule violations
  * ERROR/CRITICAL for invariant failures
* **Metrics**

  * Success/failure counters
  * Duration histograms
  * Idempotency collision counters
* **Alerts**

  * Required for audit or outbox failures

---

## 7. Agent Authoring Rules (Contractual)

An AI agent working in `domain:workexec` **must**:

1. Declare the exact **state transition** involved
2. Identify the **snapshot/version** used
3. Define **idempotency keys**
4. Specify emitted **domain events**
5. Include **audit acceptance criteria**
6. Explicitly block invalid states
7. Reject any design involving silent mutation

Failure to do any of the above is a **charter violation**.

---

## 8. Mental Model for Agents

> **Workexec is the immutable, auditable ledger of what work was approved and what work actually happened — never what was billed.**

---

If you want next steps, I can:

* Convert this into a **Kiro agent spec file**
* Add **stop-phrases and loop heuristics**
* Produce a **story validation checklist** that enforces this charter automatically