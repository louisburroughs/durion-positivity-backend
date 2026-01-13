# Manual GitHub Actions Required

## Overview

The Story Authoring Agent has completed the clarification resolution for Issue #18 (Order Cancellation). Since automated GitHub issue updates require authentication tokens not available in this environment, the following manual actions are required to complete the handoff.

## Files Created

1. **ISSUE-18-UPDATED-BODY.md** - Complete updated story body for Issue #18
2. **CLARIFICATION-CLOSURE-NOTE.md** - Closure note for the clarification issue
3. **This file** - Manual action instructions

## Required Actions

### Action 1: Update Origin Story (Issue #18)

**URL:** https://github.com/louisburroughs/durion-positivity-backend/issues/18

**Steps:**
1. Navigate to Issue #18
2. Click "Edit" on the issue description
3. Replace the entire body with the content from `ISSUE-18-UPDATED-BODY.md`
4. Save the changes

**Alternative (using gh CLI):**
```bash
# From repository root
gh issue edit 18 --body-file ./ISSUE-18-UPDATED-BODY.md
```

### Action 2: Update Labels on Issue #18

**Remove these labels (if present):**
- `blocked:clarification`
- `status:draft`
- `risk:missing-requirements`

**Add these labels:**
- `domain:order`
- `status:ready-for-dev`

**Using gh CLI:**
```bash
# Remove blocking labels
gh issue edit 18 --remove-label "blocked:clarification"
gh issue edit 18 --remove-label "status:draft"

# Add ready labels
gh issue edit 18 --add-label "domain:order"
gh issue edit 18 --add-label "status:ready-for-dev"
```

**Using GitHub UI:**
1. Navigate to Issue #18
2. Click on the gear icon next to "Labels" in the right sidebar
3. Uncheck: `blocked:clarification`, `status:draft`
4. Check: `domain:order`, `status:ready-for-dev`

### Action 3: Post Handoff Comment on Issue #18

**Comment text:**

```markdown
## ✅ Story Ready for Development

This story has been refined based on clarification responses and is now ready for implementation.

### Clarification Resolved

All open questions (OQ-1 through OQ-4) have been answered by @louisburroughs. See the clarification issue for details.

### Key Decisions

- **Domain:** POS Order domain is the orchestrator (`domain:order`)
- **Work blocking:** Defined exhaustive list of statuses that block cancellation
  - Blocking: `IN_PROGRESS`, `LABOR_STARTED`, `PARTS_ISSUED`, `MATERIALS_CONSUMED`, `COMPLETED`, `CLOSED`
  - Non-blocking: `CREATED`, `SCHEDULED`, `ASSIGNED`, `PARTS_RESERVED`, `AWAITING_START`
- **Payment settlement:** Cancellation allowed even when payment is settled; triggers manual refund path via `CANCELLED_REQUIRES_REFUND` state
- **Failure handling:** `CANCELLATION_FAILED` state with explicit operator intervention workflow

### Implementation Ready

✅ All acceptance criteria are testable
✅ Business rules are clearly defined and enforceable
✅ Data requirements are specified
✅ Integration points are documented
✅ State machine is complete with all edge cases
✅ Audit and observability requirements specified

### Next Steps

Ready for technical execution and implementation by the development team.

---

**Refined by:** Story Authoring Agent
**Date:** 2026-01-13
**Status:** Ready for Development
```

**Using gh CLI:**
```bash
gh issue comment 18 --body "$(cat << 'EOF'
## ✅ Story Ready for Development

This story has been refined based on clarification responses and is now ready for implementation.

### Clarification Resolved

All open questions (OQ-1 through OQ-4) have been answered by @louisburroughs. See the clarification issue for details.

### Key Decisions

- **Domain:** POS Order domain is the orchestrator (domain:order)
- **Work blocking:** Defined exhaustive list of statuses that block cancellation
- **Payment settlement:** Cancellation allowed even when payment is settled; triggers manual refund path
- **Failure handling:** CANCELLATION_FAILED state with explicit operator intervention workflow

### Implementation Ready

✅ All acceptance criteria are testable
✅ Business rules are clearly defined
✅ Integration points are documented

Ready for technical execution and implementation.
EOF
)"
```

### Action 4: Close the Clarification Issue

**Issue:** The clarification issue referenced in the problem statement (titled "[CLARIFICATION] Origin #18: [BACKEND] [STORY] Order: Cancel Order with Controlled Void Logic")

**Steps:**

1. **Post closure comment with the content from `CLARIFICATION-CLOSURE-NOTE.md`**

**Using gh CLI:**
```bash
# Find the clarification issue number first (might be #220 or #30)
gh issue list --label "type:clarification" --search "Origin #18" --json number,title

# Then close it (replace XXX with the actual issue number)
gh issue comment XXX --body-file ./CLARIFICATION-CLOSURE-NOTE.md
gh issue close XXX --reason completed
```

**Using GitHub UI:**
1. Navigate to the clarification issue
2. Paste the content from `CLARIFICATION-CLOSURE-NOTE.md` as a comment
3. Click "Close issue" and select "Close as completed"

### Action 5: (Optional) Assign Issue #18

**Note:** This step may require organizational authorization policy confirmation.

**Assignees:**
- `@github-copilot` (if supported in your repository)
- Principal Software Engineer Agent (or actual engineer username)

**Using gh CLI:**
```bash
# Assign to specific users
gh issue edit 18 --add-assignee "github-copilot"
# Or assign to a real user
gh issue edit 18 --add-assignee "your-engineer-username"
```

**Using GitHub UI:**
1. Navigate to Issue #18
2. Click on the gear icon next to "Assignees" in the right sidebar
3. Select the appropriate assignees

## Verification Checklist

After completing the manual actions, verify:

- [ ] Issue #18 body has been updated with the new story structure
- [ ] Issue #18 has label `domain:order`
- [ ] Issue #18 has label `status:ready-for-dev`
- [ ] Issue #18 does NOT have label `blocked:clarification`
- [ ] Handoff comment has been posted on Issue #18
- [ ] Clarification issue has been closed with resolution note
- [ ] (Optional) Issue #18 has been assigned to development team

## Quick Verification Commands

```bash
# View Issue #18 current state
gh issue view 18

# View Issue #18 labels
gh issue view 18 --json labels --jq '.labels[].name'

# List open clarification issues
gh issue list --label "type:clarification" --state open
```

## Support

If you encounter any issues with these manual actions, please:

1. Check GitHub permissions for issue editing
2. Verify label names exist in the repository
3. Confirm assignee usernames are valid
4. Contact repository administrators if needed

---

**Created by:** Story Authoring Agent (Copilot)
**Date:** 2026-01-13
**Purpose:** Complete clarification resolution handoff for Issue #18
