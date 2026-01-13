# Manual Steps for Issue #17 Clarification Resolution

This document provides manual steps to complete the clarification resolution for issue #17 if you prefer not to use the automation script.

## Overview

- **Origin Story:** Issue #17 - [BACKEND] [STORY] Catalog: Search Catalog by Keyword/SKU and Filter
- **Clarification Issue:** Issue #219
- **Status:** All clarification questions answered
- **Action Required:** Update issue #17 and close issue #219

## Automated Option

If you have a GitHub token, you can use the automation script:

```bash
GH_TOKEN=<your_token> ./update-issue-17.sh
```

## Manual Steps

### Step 1: Update Issue #17 Body

1. Navigate to https://github.com/louisburroughs/durion-positivity-backend/issues/17
2. Click "Edit" on the issue
3. Replace the entire body with the content from `ISSUE-17-UPDATED-BODY.md`
4. Click "Update comment" to save

### Step 2: Update Labels on Issue #17

1. On issue #17, locate the "Labels" section in the right sidebar
2. **Remove** the following labels:
   - `blocked:clarification`
   - `status:draft`
3. **Add** the following label:
   - `status:ready-for-dev`

### Step 3: Post Handoff Comment on Issue #17

1. On issue #17, scroll to the bottom comment section
2. Copy the entire content from `ISSUE-17-HANDOFF-COMMENT.md`
3. Paste it into the comment box
4. Click "Comment" to post

### Step 4: Close Clarification Issue #219

1. Navigate to https://github.com/louisburroughs/durion-positivity-backend/issues/219
2. Scroll to the bottom comment section
3. Copy the entire content from `ISSUE-219-CLOSE-COMMENT.md`
4. Paste it into the comment box
5. Click "Comment" to post
6. Click "Close issue" and select "Completed" as the reason

## Verification

After completing the steps above, verify:

- [ ] Issue #17 body has been updated with all clarification answers
- [ ] Issue #17 no longer has `blocked:clarification` or `status:draft` labels
- [ ] Issue #17 has the `status:ready-for-dev` label
- [ ] Issue #17 has a handoff comment documenting the updates
- [ ] Issue #219 has a close comment explaining the resolution
- [ ] Issue #219 is closed with reason "Completed"

## Files Reference

- `ISSUE-17-UPDATED-BODY.md` - New body for issue #17 (includes all clarification answers)
- `ISSUE-17-HANDOFF-COMMENT.md` - Comment to post on issue #17 (summarizes updates)
- `ISSUE-219-CLOSE-COMMENT.md` - Comment to post on issue #219 (confirms resolution)
- `ISSUE-17-UPDATE-SUMMARY.md` - Detailed summary of changes (for reference)
- `update-issue-17.sh` - Automation script (if you prefer automated approach)

## What Changed in Issue #17

The updated story now includes:

1. **Business Rules Section - Enhanced**
   - Complete keyword matching specification with 6 searchable fields
   - Explicit exclusions (Long Description, notes)
   - Matching behavior (normalization, ranking, weighting)
   - Pagination policy (default 25, max 100, cursor-based)
   - Performance requirements (P95/P99/P50 targets)

2. **Data Requirements - Updated**
   - Cursor-based pagination parameters (`cursor`, `page_size`, `next_cursor`)

3. **Acceptance Criteria - Expanded**
   - Added 5 new testable criteria covering pagination, performance, field matching

4. **Removed "Open Questions"**
   - All questions answered and incorporated

5. **Added "Clarification History"**
   - Documents the clarification process and resolution

## Next Steps After Completion

Once the manual steps are complete:

1. Issue #17 is **ready for development**
2. Development team can be assigned
3. Implementation can begin using the specifications in the story
4. Performance monitoring should be set up per the targets

## Questions?

If you have questions about this process, refer to:
- `ISSUE-17-UPDATE-SUMMARY.md` for detailed change summary
- The Story Authoring Agent instructions in `.github/instructions/`
