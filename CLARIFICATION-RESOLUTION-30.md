# Clarification Resolution Summary for Issue #30

## Executive Summary

Clarification issue #228 has been fully answered by the business owner (@louisburroughs) with comprehensive, implementation-ready decisions. All resolution artifacts have been prepared and are ready to be applied to the origin story.

## Resolution Status

✅ **Complete** - All clarification questions answered
✅ **Artifacts Created** - Updated story body, comments, and scripts prepared
⏳ **Pending Application** - GitHub API updates require authentication

## Clarification Questions & Answers

### Question 1: Triggering Mechanism
**Asked:** What is the precise trigger for the replenishment check?

**Answer:** **Hybrid Model** — Event-Driven primary trigger + Batch safety net

**Details:**
- **Primary Trigger (Event-Driven):**
  - Listen for `InventoryDecremented` events
  - Trigger only when decrement is in Pick Face location AND quantity drops at/below minimum
  - Debounce per `(productId, pickFaceLocationId)` (e.g., once every 60 seconds)
  
- **Secondary Trigger (Batch Job):**
  - Scheduled scan every 5–15 minutes
  - Catches missed events, recovers from failures, reconciles bulk adjustments

- **Rationale:** Balances responsiveness (event-driven) with reliability (batch safety net). This is the standard pattern for inventory correctness.

### Question 2: Backstock Sourcing Logic
**Asked:** If an item exists in multiple backstock locations, what logic determines the source?

**Answer:** **Deterministic Hierarchy** (not "any location")

**Sourcing Order:**
1. **FEFO/FIFO Compliance** - Earliest expiry date (FEFO) or receipt date (FIFO) for controlled items
2. **Sufficient Quantity** - Prefer single location that can fulfill full replenishment need
3. **Location Proximity** - Lowest layout order (Zone → Aisle → Rack → Bin) if data exists
4. **Highest On-Hand Quantity** - Final deterministic tie-breaker

**Clarification:** "Any location with sufficient quantity" is acceptable ONLY as a last-resort tie-breaker, NOT as the primary rule. The deterministic hierarchy ensures FEFO/FIFO compliance, predictable behavior, and simplified audit trails.

### Additional Requirements (Volunteered)

**Partial Replenishment:**
- If no single location has sufficient quantity, create multiple `ReplenishmentTask` records
- Order tasks by same sourcing hierarchy

**Idempotency:**
- Tasks must be idempotent per `(productId, pickFaceLocationId, thresholdCrossing)`
- Prevent duplicate tasks for same trigger

**Audit Requirements:**
- Record `triggerType` (EVENT | BATCH)
- Record `sourceBackstockLocationId(s)`
- Record `decisionReason` (BELOW_MIN | SAFETY_SCAN)
- Record `occurredAt` timestamp

## Artifacts Created

All resolution artifacts are located in `.story-work/work/30/`:

1. **`after.md`** (16.6 KB)
   - Complete updated story body with all design decisions incorporated
   - Ready to replace current issue #30 body

2. **`handoff-comment.md`** (2.0 KB)
   - Summary comment to post on issue #30
   - Documents changes made and next steps

3. **`resolution-comment-228.md`** (1.8 KB)
   - Resolution comment to post on clarification issue #228
   - Thanks business owner and documents decisions

4. **`apply-resolution.sh`** (3.0 KB, executable)
   - Automated script to apply all changes via GitHub CLI
   - Requires `gh` CLI authentication

5. **`README.md`** (4.9 KB)
   - Comprehensive documentation of the resolution
   - Manual application instructions if script cannot be used

## Changes Made to Story

### Removed
- ❌ "STOP: Clarification required" warning banner

### Enhanced
- ✅ **Trigger Section** - Added hybrid model with event-driven + batch details
- ✅ **Backstock Sourcing Logic** - Added deterministic hierarchy with 4-level sourcing order
- ✅ **Partial Replenishment** - Added section explaining multiple task creation
- ✅ **Idempotency** - Added section explaining duplicate prevention

### Added
- ✅ **New Data Fields** in `ReplenishmentTask` entity:
  - `triggerType` (Enum: EVENT, BATCH)
  - `decisionReason` (Enum: BELOW_MIN, SAFETY_SCAN)
  
- ✅ **New Acceptance Criteria:**
  - Event-driven trigger scenario
  - Batch safety net scenario
  - FEFO sourcing for expiry-controlled items
  - Multiple tasks for partial replenishment
  - Event debouncing scenario
  
- ✅ **New Metrics:**
  - `replenishment_event.debounced.count`
  - `replenishment_batch.scan.count`
  - Tagged metrics by `triggerType`

### Replaced
- ✅ **"Open Questions"** → **"Design Decisions"** section
  - Documents resolved clarifications
  - Provides rationale for each decision
  - Links back to clarification issue #228

## Label Changes Required

### Issue #30 (Origin Story)
**Remove:**
- `blocked:clarification`
- `status:draft`

**Add:**
- `status:needs-review`

### Issue #228 (Clarification)
**Action:**
- Close with reason "completed"

## How to Apply

### Option 1: Automated (Recommended)
```bash
cd .story-work/work/30
./apply-resolution.sh
```

Requires:
- GitHub CLI (`gh`) installed
- Authenticated: `gh auth login`
- Write access to repository

### Option 2: Manual Application
See `.story-work/work/30/README.md` for step-by-step manual instructions.

### Option 3: CI/Automation
The artifacts can be consumed by CI/automation systems to apply changes programmatically.

## Verification Checklist

After application, verify:
- [ ] Issue #30 body contains "Design Decisions" section
- [ ] Issue #30 body contains hybrid trigger model details
- [ ] Issue #30 body contains deterministic sourcing hierarchy
- [ ] Issue #30 body contains new data fields (triggerType, decisionReason)
- [ ] Issue #30 body contains 6 enhanced acceptance criteria
- [ ] Issue #30 has `status:needs-review` label
- [ ] Issue #30 does NOT have `blocked:clarification` label
- [ ] Issue #30 does NOT have `status:draft` label
- [ ] Handoff comment posted to issue #30
- [ ] Resolution comment posted to issue #228
- [ ] Issue #228 is closed

## Next Steps

1. **Apply Changes** - Use one of the three application methods above
2. **Review Story** - Technical review of updated story
3. **Approve for Development** - If approved, update label to `status:ready-for-dev`
4. **Begin Implementation** - Development can proceed with clear requirements

## Impact Assessment

### Positive Impacts
✅ **Clarity** - All ambiguity removed, implementation path is clear
✅ **Reliability** - Hybrid trigger ensures no missed replenishments
✅ **Auditability** - Deterministic sourcing ensures predictable, traceable behavior
✅ **Compliance** - FEFO/FIFO support ensures regulatory compliance
✅ **Testability** - Clear acceptance criteria enable comprehensive testing

### Risks Mitigated
✅ **No System Overload** - Debouncing prevents event storms
✅ **No Missed Replenishments** - Batch safety net catches failures
✅ **No Non-Deterministic Behavior** - Sourcing hierarchy eliminates randomness
✅ **No Audit Gaps** - Required fields ensure complete audit trail

## Technical Debt

**None Identified** - All clarifications resolved with production-ready decisions.

## Quality Gate

**Status:** ✅ **PASSED**

- All questions answered explicitly
- All decisions include rationale
- All new requirements documented
- All acceptance criteria added
- All data fields specified
- All audit requirements defined

The story is now ready for technical review and subsequent development.

---

**Resolution Completed:** 2026-01-12
**Completed By:** @copilot (Principal Software Engineer Mode)
**Clarification Issue:** #228
**Origin Story:** #30
**Artifacts Location:** `.story-work/work/30/`
