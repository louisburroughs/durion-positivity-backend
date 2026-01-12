# Clarification Resolution - Execution Instructions

## ⚠️ Manual Action Required

This PR contains the complete resolution for clarification issue #231, but **requires manual execution** to apply the changes to GitHub issues.

## What's in This PR

This PR includes all the artifacts needed to resolve clarification issue #231 for story #33:

### Documentation Files
- **`issue-33-updated-body.txt`** - Complete updated body for issue #33
- **`issue-33-handoff-comment.txt`** - Handoff comment for issue #33
- **`issue-231-resolution-comment.txt`** - Resolution comment for issue #231
- **`issue-33-clarification-resolution.md`** - Detailed resolution documentation
- **`README-CLARIFICATION-33.md`** - Complete instructions and context

### Automation Script
- **`apply-clarification-resolution-33.sh`** - Executable script to apply all changes

## How to Apply These Changes

### Option 1: Automated Script (Recommended)

1. **Merge this PR** to the main branch
2. **Clone/pull the repository** to your local machine
3. **Authenticate GitHub CLI:**
   ```bash
   gh auth login
   ```
4. **Run the script:**
   ```bash
   cd .story-work
   ./apply-clarification-resolution-33.sh
   ```

The script will automatically:
- Update issue #33 body with resolved clarifications
- Update issue #33 labels
- Post handoff comment to issue #33
- Post resolution comment to issue #231
- Close clarification issue #231

### Option 2: Manual Application

If you prefer to review each change or don't have GitHub CLI:

1. **Update Issue #33 Body**
   - Open https://github.com/louisburroughs/durion-positivity-backend/issues/33
   - Click "Edit"
   - Replace the body with content from `.story-work/issue-33-updated-body.txt`
   - Click "Update comment"

2. **Update Issue #33 Labels**
   - On issue #33, remove labels: `blocked:clarification`, `status:draft`
   - Add label: `status:ready-for-dev`

3. **Post Handoff Comment to Issue #33**
   - Copy content from `.story-work/issue-33-handoff-comment.txt`
   - Post as a comment on issue #33

4. **Post Resolution Comment to Issue #231**
   - Open https://github.com/louisburroughs/durion-positivity-backend/issues/231
   - Copy content from `.story-work/issue-231-resolution-comment.txt`
   - Post as a comment

5. **Close Issue #231**
   - On issue #231, click "Close issue" with reason "Completed"

## What Gets Updated

### Issue #33 Changes

The story will be updated with the following resolved policies:

1. **Confirmation Policy**
   - Default: Manual confirmation with explicit prompt
   - Optional: Auto-issue under strict conditions
   - Audit: Record issueMode and confirmedBy

2. **Notification Contract**
   - Asynchronous event on topic `inventory.issue.completed.v1`
   - Non-blocking from POS perspective
   - Complete event schema defined

3. **Mismatched Part Handling**
   - Default: Strict block with error message
   - Override: Requires permission + reason code
   - Full audit trail

### Sections Updated in Issue #33

- Preconditions (added permissions)
- Functional Behavior (step 5 expanded, step 8 added)
- Alternate / Error Flows (mismatch override process)
- Business Rules (added #5, #6, #7)
- Data Requirements (fields, permissions, event schema)
- Acceptance Criteria (enhanced AC-1, added AC-3 and AC-5)
- Audit & Observability (new metrics)
- Open Questions → Resolved Questions

## Verification Checklist

After applying the changes:

- [ ] Issue #33 body contains all clarification decisions
- [ ] Issue #33 has label `status:ready-for-dev`
- [ ] Issue #33 does NOT have labels `blocked:clarification` or `status:draft`
- [ ] Issue #33 has the handoff comment
- [ ] Issue #231 has the resolution comment
- [ ] Issue #231 is closed with state "completed"

## Why Manual Action is Needed

GitHub Copilot agents cannot directly modify GitHub issues due to security restrictions. The agent has prepared all the content and scripts, but a human with appropriate permissions must apply the changes to the GitHub issues.

## Next Steps After Application

Once the clarification resolution is applied:

1. **Review** the updated story at issue #33
2. **Assign** the issue to the development team/sprint
3. **Implement** the story following the acceptance criteria

## Questions?

For detailed information about the clarification decisions and story changes, see:
- `.story-work/README-CLARIFICATION-33.md` - Complete guide
- `.story-work/issue-33-clarification-resolution.md` - Detailed resolution documentation

## Related Issues

- **Origin Story:** #33
- **Clarification Issue:** #231 (to be closed)
