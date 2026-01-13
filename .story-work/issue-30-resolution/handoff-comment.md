## Clarification Resolution Complete ✅

All clarification questions from issue #228 have been answered by the business owner and incorporated into this story.

### Changes Made

1. **Removed** "STOP: Clarification required" warning
2. **Updated** "Open Questions" section → "Design Decisions" with resolved answers
3. **Enhanced** Trigger mechanism section with hybrid model details (Event-Driven + Batch)
4. **Enhanced** Backstock sourcing logic with deterministic hierarchy
5. **Added** new data fields for audit trail:
   - `triggerType` (EVENT | BATCH)
   - `decisionReason` (BELOW_MIN | SAFETY_SCAN)
6. **Added** new acceptance criteria covering event-driven triggers, batch scans, FEFO sourcing, and debouncing
7. **Enhanced** Audit & Observability section with new metrics

### Design Decisions Summary

#### 1. Triggering Mechanism
**Decision:** Hybrid Model — Event-Driven primary trigger + Batch safety net
- **Primary:** Event on `InventoryDecremented` when pick face drops at/below min (debounced per product-location)
- **Secondary:** Scheduled batch scan every 5–15 minutes
- **Rationale:** Balances responsiveness with reliability

#### 2. Backstock Sourcing Logic
**Decision:** Deterministic Hierarchy (not "any location")
1. FEFO/FIFO compliance (earliest expiry/receipt)
2. Sufficient quantity (prefer single location)
3. Location proximity (optional)
4. Highest on-hand quantity (tie-breaker)

#### 3. Additional Behaviors
- **Partial replenishment:** Multiple tasks if no single location has enough
- **Idempotency:** Prevent duplicate tasks per (product, pickFace, threshold)
- **Audit:** Record triggerType, sourceLocation(s), decisionReason, occurredAt

---

**Status Update:**
- Removed labels: `blocked:clarification`, `status:draft`
- Added labels: `status:needs-review`

**Next Steps:**
- Story is now ready for technical review
- After review approval, can move to `status:ready-for-dev`

---
*Clarification resolution completed by Copilot Principal Software Engineer*
*Source clarification: #228*
*Resolved: 2026-01-12*
