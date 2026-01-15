# **Accounting Domain — Story Validation Checklist**

This checklist is a **hard gate**.
Any failure blocks story approval and code generation.

---

## 1. Domain Ownership (REQUIRED)

* [ ] Story is labeled `domain:accounting`
* [ ] Financial system of record responsibility is explicit
* [ ] No operational workflow ownership

---

## 2. Intent & Scope (REQUIRED)

* [ ] Actor is clearly defined (system or role)
* [ ] Triggering event or command is specified
* [ ] Financial outcome is explicit
* [ ] Explicit non-goals stated

---

## 3. Event Consumption (REQUIRED)

* [ ] Event type and version declared
* [ ] Idempotency key defined
* [ ] Required attributes listed
* [ ] Replay behavior defined

---

## 4. Posting Rules (REQUIRED)

* [ ] Mapping rules are versioned
* [ ] Rule selection criteria explicit
* [ ] Rule publication validation defined
* [ ] Unmappable event behavior defined

---

## 5. Journal Entry Construction (REQUIRED)

* [ ] Draft vs Posted state defined
* [ ] Debit/Credit balancing enforced
* [ ] Dimensions and accounts specified
* [ ] Multi-currency behavior defined (if applicable)

---

## 6. Period Controls (REQUIRED)

* [ ] Period validation performed before posting
* [ ] Closed-period behavior defined
* [ ] Reopen policy defined (if applicable)

---

## 7. Immutability & Reversal (REQUIRED)

* [ ] Posted entries are immutable
* [ ] Correction via reversal only
* [ ] Reversal linkage defined

---

## 8. Audit & Explainability (REQUIRED)

* [ ] Audit events defined
* [ ] Rule and mapping versions persisted
* [ ] Full trace path defined:
  `Event → Rule → JE → Ledger`

---

## 9. Failure Handling (REQUIRED)

* [ ] Validation failure behavior defined
* [ ] Posting failure behavior defined
* [ ] DLQ and alert behavior defined
* [ ] No partial persistence allowed

---

## 10. Integration Boundaries (REQUIRED)

* [ ] No upstream domain writes
* [ ] Async event consumption only
* [ ] External systems treated as read-only

---

## 11. Observability (REQUIRED)

* [ ] Logging levels defined
* [ ] Metrics defined
* [ ] Alerts defined
* [ ] Correlation IDs propagated

---

## 12. Negative Scenarios (REQUIRED)

* [ ] Duplicate event
* [ ] Invalid mapping
* [ ] Closed period
* [ ] Unauthorized action

---

## 13. Anti-Pattern Check (AUTO-FAIL)

* [ ] ❌ Unbalanced JE
* [ ] ❌ Silent correction
* [ ] ❌ Cross-domain mutation
* [ ] ❌ Best-effort posting

Any checked item fails the story.

---

## 14. Final Compliance Statement (REQUIRED)

Story explicitly states:

> “This story complies with the Accounting Domain Agent Guide and preserves all accounting invariants.”

---

### Enforcement Guidance

* Agents **must not** generate code if checklist fails
* Missing policy decisions → `blocked:clarification`
* Accounting domain prioritizes **correctness over throughput**

---