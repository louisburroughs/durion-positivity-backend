---

# **Work Execution (`domain:workexec`) — Story Validation Checklist**

**Purpose**
This checklist is used to validate that a story:

* Belongs in `domain:workexec`
* Preserves execution invariants
* Is safe to implement without corrupting audit, inventory, labor, or downstream systems

A story **must pass all REQUIRED checks** to be considered valid.

---

## 1. Domain Fit & Ownership (REQUIRED)

* [ ] Story explicitly declares **`domain:workexec`** ownership
* [ ] Story involves **execution lifecycle**, not financial posting
* [ ] No AR/AP, revenue recognition, or accounting rules are implemented
* [ ] Story does not mutate CRM or HR system-of-record data

**FAIL if:** the story posts money, bypasses approval, or performs silent data correction.

---

## 2. Intent & Scope Clarity (REQUIRED)

* [ ] Story states the **actor** (human or system)
* [ ] Story states the **triggering condition**
* [ ] Story describes the **execution outcome**
* [ ] Story explicitly states what the story **does not change**

**FAIL if:** the outcome or scope is ambiguous.

---

## 3. State Machine Definition (REQUIRED)

* [ ] Current (from) state is explicitly defined
* [ ] Target (to) state is explicitly defined
* [ ] Invalid states are explicitly blocked
* [ ] Error behavior for invalid transitions is specified

**FAIL if:** the transition is implicit or relies on “current state”.

---

## 4. Snapshot & Versioning (REQUIRED)

* [ ] Story identifies the **snapshot** being used (estimate, approval, workorder)
* [ ] Snapshot version or hash is captured
* [ ] Snapshot is treated as **immutable**
* [ ] Post-approval changes require reopen or re-approval

**FAIL if:** snapshot mutation is allowed.

---

## 5. Business Rules (REQUIRED)

* [ ] All relevant business rules are explicitly listed
* [ ] Rules are enforced synchronously
* [ ] Rule violations produce deterministic errors
* [ ] No “best effort” or silent rule bypass exists

**FAIL if:** business rules are implicit or optional.

---

## 6. Idempotency & Replay Safety (REQUIRED)

* [ ] Idempotency key is defined
* [ ] Replay behavior is explicitly described
* [ ] Replays do not double:

  * Inventory consumption
  * Labor recording
  * Approvals
* [ ] Duplicate event emission is prevented

**FAIL if:** retry behavior is unspecified.

---

## 7. Audit & Traceability (REQUIRED)

* [ ] Audit event(s) are explicitly named
* [ ] Audit records include:

  * Actor
  * Timestamp
  * Snapshot/version reference
* [ ] Audit persistence failure aborts the business action

**FAIL if:** audit is optional or best-effort.

---

## 8. Domain Events (REQUIRED)

* [ ] Emitted domain events are explicitly listed
* [ ] Each event is:

  * Versioned
  * Idempotent
  * Immutable once published
* [ ] Events are clearly marked:

  * Posting vs non-posting (must be non-posting)

**FAIL if:** event behavior is implicit or financial.

---

## 9. Parts Execution (IF APPLICABLE)

* [ ] Parts issuance/consumption is event-based
* [ ] Authorized quantity limits are enforced
* [ ] Returns are handled explicitly
* [ ] Inventory effects are idempotent

**FAIL if:** inventory is directly mutated without events.

---

## 10. Labor Recording (IF APPLICABLE)

* [ ] Labor type (flat-rate / time-based) is specified
* [ ] Labor updates supersede prior entries
* [ ] Labor entries are auditable
* [ ] No AR or payroll calculation occurs

**FAIL if:** labor is silently edited or monetized.

---

## 11. Timekeeping (IF APPLICABLE)

* [ ] Submission and approval steps are defined
* [ ] Approved time is immutable
* [ ] On-behalf edits require reason codes
* [ ] HR/People integration event is defined

**FAIL if:** approved time can be changed.

---

## 12. Reopen & Correction Handling (IF APPLICABLE)

* [ ] Reopen requires authorization
* [ ] Reason code is mandatory
* [ ] Reopen invalidates invoice-ready snapshots
* [ ] Exactly one WorkorderReopened event is emitted

**FAIL if:** correction is done inline.

---

## 13. Integration Boundaries (REQUIRED)

* [ ] All outbound integrations are async
* [ ] No cross-domain writes occur
* [ ] Inbound data is treated as read-only
* [ ] Event contracts are versioned

**FAIL if:** another domain is mutated directly.

---

## 14. Observability (REQUIRED)

* [ ] Logging levels are defined (INFO/WARN/ERROR)
* [ ] Metrics are specified
* [ ] Alert conditions are defined for:

  * Audit failure
  * Event outbox failure
* [ ] Correlation IDs are propagated

**FAIL if:** failures are silent.

---

## 15. Negative Scenarios (REQUIRED)

* [ ] Invalid state transition
* [ ] Duplicate command replay
* [ ] Partial failure (audit or event outbox)
* [ ] Unauthorized actor attempt

**FAIL if:** only happy-path is defined.

---

## 16. Anti-Patterns Check (AUTO-FAIL)

* [ ] ❌ Silent mutation
* [ ] ❌ Implicit approval
* [ ] ❌ Financial posting
* [ ] ❌ Best-effort audit
* [ ] ❌ Cross-domain writes

**Any checked box here = automatic rejection.**

---

## 17. Final Validation Statement (REQUIRED)

Story includes an explicit statement:

> “This story complies with the Work Execution Agent Charter and preserves all execution invariants.”

**FAIL if:** missing.

---

### Usage Guidance for Agents

* Treat this checklist as **contractual**
* If any REQUIRED section fails:

  * Do not generate code
  * Flag the story as **invalid**
* Optional sections become mandatory when applicable

---

If you want, next I can:

* Turn this into a **machine-readable JSON schema**
* Embed it directly into a **Kiro preflight validator**
* Add **auto-generated acceptance criteria** per checklist item