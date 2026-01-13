# Clarification Resolution for Issue #30

## Overview

This directory contains the resolution artifacts for clarification issue #228, which was blocking story issue #30 "[BACKEND] [STORY] Putaway: Replenish Pick Faces from Backstock (Optional)".

## What Happened

1. **Clarification Issue Created:** Issue #228 was created by the Story Authoring Agent on 2026-01-05 to resolve two missing business decisions
2. **Business Owner Response:** @louisburroughs provided comprehensive answers to both questions on 2026-01-12
3. **Story Updated:** This work incorporates those decisions into the story and closes the clarification issue

## Files in This Resolution

- **`after.md`** - Complete updated body for issue #30 with all clarification decisions incorporated
- **`handoff-comment.md`** - Comment to post on issue #30 summarizing the changes
- **`resolution-comment-228.md`** - Comment to post on issue #228 documenting the resolution
- **`apply-resolution.sh`** - Automated script to apply all changes
- **`README.md`** - This file

## Quick Start

To apply the clarification resolution:

```bash
cd /home/runner/work/durion-positivity-backend/durion-positivity-backend/.story-work/work/30
./apply-resolution.sh
```

The script will:
1. Update issue #30 body with resolved clarifications
2. Update issue #30 labels (remove `blocked:clarification`, `status:draft`; add `status:needs-review`)
3. Post handoff comment to issue #30
4. Post resolution comment to issue #228
5. Close clarification issue #228

## Prerequisites

- GitHub CLI (`gh`) must be installed
- GitHub CLI must be authenticated (`gh auth login`)
- User must have write access to the repository

## Clarification Decisions Summary

### Decision 1: Triggering Mechanism
**Question:** What is the precise trigger for the replenishment check?

**Answer:** Hybrid Model — Event-Driven primary trigger + Batch safety net
- Primary: Event on `InventoryDecremented` when pick face drops at/below min (debounced)
- Secondary: Batch scan every 5–15 minutes
- Rationale: Balances responsiveness with reliability

### Decision 2: Backstock Sourcing Logic
**Question:** If an item exists in multiple backstock locations, what logic determines the source?

**Answer:** Deterministic Hierarchy
1. FEFO/FIFO compliance (earliest expiry or receipt date)
2. Sufficient quantity (prefer single location)
3. Location proximity (optional)
4. Highest on-hand quantity (tie-breaker)

**Clarification on "Any Location":** Acceptable only as last-resort tie-breaker, not primary rule.

### Decision 3: Additional Behaviors
- Partial replenishment: Multiple tasks if no single location has enough
- Idempotency: Prevent duplicate tasks per (product, pickFace, threshold)
- Audit: Record triggerType, sourceLocation(s), decisionReason, occurredAt

## Story Changes

The following sections in issue #30 were updated:

1. **Removed** "STOP: Clarification required" warning
2. **Enhanced** "Trigger" section with hybrid model details
3. **Enhanced** "Backstock Sourcing Logic" with deterministic hierarchy
4. **Added** new data fields in `ReplenishmentTask` entity:
   - `triggerType` (EVENT | BATCH)
   - `decisionReason` (BELOW_MIN | SAFETY_SCAN)
5. **Added** new acceptance criteria:
   - Event-driven trigger scenario
   - Batch safety net scenario
   - FEFO sourcing scenario
   - Partial replenishment scenario
   - Debouncing scenario
6. **Enhanced** "Audit & Observability" section with new metrics
7. **Replaced** "Open Questions" with "Design Decisions" section documenting resolutions

## Manual Application (Alternative)

If you prefer to apply the changes manually:

### 1. Update Issue #30 Body
```bash
gh issue edit 30 -R louisburroughs/durion-positivity-backend --body-file after.md
```

### 2. Update Issue #30 Labels
```bash
gh issue edit 30 -R louisburroughs/durion-positivity-backend \
  --remove-label "blocked:clarification" \
  --remove-label "status:draft" \
  --add-label "status:needs-review"
```

### 3. Post Handoff Comment to Issue #30
```bash
gh issue comment 30 -R louisburroughs/durion-positivity-backend --body-file handoff-comment.md
```

### 4. Post Resolution Comment to Issue #228
```bash
gh issue comment 228 -R louisburroughs/durion-positivity-backend --body-file resolution-comment-228.md
```

### 5. Close Issue #228
```bash
gh issue close 228 -R louisburroughs/durion-positivity-backend --reason "completed"
```

## Verification

After applying changes, verify:
- [ ] Issue #30 body contains all design decisions
- [ ] Issue #30 has `status:needs-review` label
- [ ] Issue #30 does NOT have `blocked:clarification` or `status:draft` labels
- [ ] Handoff comment is visible on issue #30
- [ ] Resolution comment is visible on issue #228
- [ ] Issue #228 is closed with reason "completed"

---

**Resolution Date:** 2026-01-12
**Resolved By:** @copilot (Principal Software Engineer)
**Clarification Issue:** #228
**Origin Story:** #30
