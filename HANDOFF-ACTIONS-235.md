# Handoff Actions for Clarification Issue #235 Resolution

## Overview
This document outlines the manual actions required to complete the resolution of Clarification Issue #235 for Origin Story #38.

**Important**: Due to system limitations, these actions cannot be performed automatically by the agent. They require manual execution by a user with appropriate GitHub permissions.

---

## Action 1: Update Issue #38 (Origin Story - Configuration)

### Update Issue Title
**Current**: `[BACKEND] [STORY] Topology: Define Default Staging and Quarantine Locations for Receiving`

**New**: `[BACKEND] [STORY] Configuration: Define Default Staging and Quarantine Storage Locations for a Site`

### Update Issue Body
Replace the entire body with the content from:
- File: `STORY-38-UPDATED.md`

### Update Labels

**Remove**:
- `blocked:clarification`
- `blocked:domain-conflict`
- `status:needs-review`

**Add**:
- `status:ready-for-dev`
- `domain:location`
- `domain:inventory`

**Keep**:
- `type:story`
- `backend`
- `story-implementation`
- `user`

### GitHub CLI Commands
```bash
# Update title
gh issue edit 38 --title "[BACKEND] [STORY] Configuration: Define Default Staging and Quarantine Storage Locations for a Site"

# Update body (using prepared file)
gh issue edit 38 --body-file STORY-38-UPDATED.md

# Remove blocking labels
gh issue edit 38 --remove-label "blocked:clarification" --remove-label "blocked:domain-conflict" --remove-label "status:needs-review"

# Add new labels
gh issue edit 38 --add-label "status:ready-for-dev" --add-label "domain:location" --add-label "domain:inventory"
```

### Post Handoff Comment on Issue #38
```markdown
## ✅ Clarification Resolved - Story Ready for Development

**Clarification Issue**: #235
**Resolved Date**: 2026-01-12

All clarification questions have been answered and incorporated into this story.

### Decisions Applied

1. **Story Split**: This story now focuses ONLY on **configuration** of default staging and quarantine locations. A separate story for receiving workflow execution will be created.

2. **Uniqueness Rule**: Confirmed that a `StorageLocation` cannot be designated as both default Staging and default Quarantine. Validation enforces this rule with error code `DEFAULT_LOCATION_ROLE_CONFLICT`.

3. **Permission Model**: Permission enforcement for quarantine moves is out of scope for this story. It will be handled by separate `domain:security` and `domain:inventory` execution stories.

### Related Stories

**Depends On** (must be completed first):
- None

**Enables** (this story is a prerequisite for):
- [NEW STORY] "[BACKEND] [STORY] Receiving: Use Site-Default Staging Location" (domain:workexec)

### Next Steps

This story is now **ready for development**:
- All acceptance criteria are testable
- Domain boundaries are clear
- No open questions remain

---
**Resolved by**: @louisburroughs
**Full resolution details**: See #235
```

---

## Action 2: Create New Issue (Story B - Execution)

### Create New Issue
**Title**: `[BACKEND] [STORY] Receiving: Use Site-Default Staging Location`

**Body**: Use content from file `STORY-B-EXECUTION-NEW.md`

**Labels**:
- `type:story`
- `status:draft`
- `domain:workexec`
- `depends-on:issue-38`
- `backend`
- `story-implementation`

### GitHub CLI Command
```bash
# Create new issue
gh issue create \
  --title "[BACKEND] [STORY] Receiving: Use Site-Default Staging Location" \
  --body-file STORY-B-EXECUTION-NEW.md \
  --label "type:story" \
  --label "status:draft" \
  --label "domain:workexec" \
  --label "backend" \
  --label "story-implementation"
```

### Link to Issue #38
After creating the new issue (let's say it's #XXX), add a comment to Issue #38:
```markdown
## Related Story Created

This configuration story enables the following execution story:
- #XXX: [BACKEND] [STORY] Receiving: Use Site-Default Staging Location (domain:workexec)

The execution story depends on this configuration story being completed first.
```

---

## Action 3: Close Clarification Issue #235

### Post Final Comment on Issue #235
```markdown
## ✅ Clarification Resolved

All questions have been answered and decisions have been incorporated into the origin story.

### Resolution Summary

1. **Story Split Confirmed**: Issue #38 has been split into two stories:
   - **Story A (Configuration)**: #38 - Updated to focus only on configuring default locations
   - **Story B (Execution)**: #XXX - New story for receiving workflow execution

2. **Uniqueness Rule Confirmed**: A `StorageLocation` cannot be both default Staging and default Quarantine. Validation enforces this with error code `DEFAULT_LOCATION_ROLE_CONFLICT`.

3. **Permission Model Confirmed**: Permission enforcement for quarantine moves is out of scope for the configuration story. It belongs to `domain:security` and `domain:inventory` execution stories.

### Updated Stories

- **Origin Story Updated**: #38 - Now ready for development
- **New Execution Story**: #XXX - Created as a separate story
- **Full Resolution Details**: See `CLARIFICATION-RESOLUTION-235.md` in repository

### Actions Completed

- ✅ Origin story #38 updated with clarifications
- ✅ Blocking labels removed from #38
- ✅ `status:ready-for-dev` label added to #38
- ✅ New execution story created
- ✅ Domain labels applied correctly
- ✅ Clarification issue closed

---
**Resolved by**: @louisburroughs
**Resolution documentation**: Available in repository at `CLARIFICATION-RESOLUTION-235.md`
```

### Close the Issue
```bash
# Post final comment
gh issue comment 235 --body-file CLARIFICATION-CLOSE-COMMENT-235.md

# Close the issue as completed
gh issue close 235 --reason completed
```

---

## Action 4: Verification Checklist

After completing all actions, verify:

- [ ] Issue #38 title updated to reflect configuration focus
- [ ] Issue #38 body updated with clarifications
- [ ] Issue #38 has labels: `status:ready-for-dev`, `domain:location`, `domain:inventory`
- [ ] Issue #38 does NOT have labels: `blocked:clarification`, `blocked:domain-conflict`, `status:needs-review`
- [ ] New execution story created with correct title and body
- [ ] New execution story has labels: `type:story`, `status:draft`, `domain:workexec`
- [ ] Link between #38 and new story is established
- [ ] Issue #235 has final resolution comment
- [ ] Issue #235 is closed with reason "completed"

---

## Notes

- Replace `#XXX` with the actual issue number of the newly created execution story
- Ensure all file references are correct before using --body-file flags
- Test commands in dry-run mode if available before executing
- All actions require GH_TOKEN environment variable to be set

---

## Alternative: Manual GitHub UI Steps

If GitHub CLI is not available, these actions can be performed through the GitHub web interface:

1. Navigate to Issue #38
   - Click "Edit" on title and update
   - Click "Edit" on body and paste content from STORY-38-UPDATED.md
   - Update labels using the labels sidebar
   - Add handoff comment

2. Click "New Issue" in repository
   - Paste title and body from STORY-B-EXECUTION-NEW.md
   - Add labels
   - Submit

3. Navigate to Issue #235
   - Post final comment
   - Click "Close issue" and select "Close as completed"

---

## Emergency Contact

If issues arise during handoff, contact:
- Repository Owner: @louisburroughs
- Agent that created clarification: Story Authoring Agent
