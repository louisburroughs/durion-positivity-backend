# Manual GitHub Actions Required

## Summary

The clarification resolution for issue #30 is **complete** but requires manual GitHub API operations to apply the changes. All artifacts have been prepared and are ready for application.

## Why Manual Actions Are Needed

GitHub Copilot in this environment does not have authentication credentials for the GitHub API. Therefore, the following operations must be performed manually or via CI/CD:

1. Update issue #30 body with resolved story
2. Update issue #30 labels
3. Post comments to issues #30 and #228
4. Close issue #228

## What Has Been Completed

✅ **All resolution artifacts prepared:**
- Complete updated story body incorporating clarification decisions
- Handoff comment for issue #30
- Resolution comment for issue #228
- Automated application script
- Comprehensive documentation

✅ **All artifacts committed to repository:**
- Location: `.story-work/issue-30-resolution/`
- Executive summary: `CLARIFICATION-RESOLUTION-30.md`

## How to Apply the Resolution

### Option 1: Automated Script (Recommended)

```bash
cd .story-work/issue-30-resolution
./apply-resolution.sh
```

**Requirements:**
- GitHub CLI (`gh`) installed
- Authenticated: `gh auth login`
- Write access to repository

The script will automatically:
1. Update issue #30 body from `after.md`
2. Remove labels: `blocked:clarification`, `status:draft`
3. Add label: `status:needs-review`
4. Post handoff comment to issue #30
5. Post resolution comment to issue #228
6. Close issue #228 as completed

### Option 2: Manual Application

See detailed step-by-step instructions in:
`.story-work/issue-30-resolution/README.md`

### Option 3: CI/CD Integration

The artifacts can be consumed by GitHub Actions or other CI/CD systems to apply changes programmatically.

Example GitHub Actions workflow:

```yaml
name: Apply Clarification Resolution
on:
  workflow_dispatch:
    inputs:
      issue_number:
        description: 'Origin issue number'
        required: true
        default: '30'

jobs:
  apply-resolution:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Apply resolution
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          cd .story-work/issue-30-resolution
          ./apply-resolution.sh
```

## What Will Change

### Issue #30 Changes
- **Body:** Complete rewrite with clarification decisions incorporated
- **Labels Removed:** `blocked:clarification`, `status:draft`
- **Labels Added:** `status:needs-review`
- **Comment Added:** Handoff comment summarizing changes

### Issue #228 Changes
- **Comment Added:** Resolution comment thanking business owner
- **Status:** Closed as completed

## Clarification Decisions Summary

### 1. Triggering Mechanism
**Decision:** Hybrid Model (Event-Driven + Batch)
- Primary: Event on `InventoryDecremented` (debounced)
- Secondary: Batch scan every 5-15 minutes

### 2. Backstock Sourcing Logic
**Decision:** Deterministic Hierarchy
1. FEFO/FIFO compliance
2. Sufficient quantity
3. Location proximity
4. Highest on-hand quantity

### 3. Additional Requirements
- Partial replenishment support
- Idempotency per (product, pickFace, threshold)
- Audit trail with triggerType, decisionReason, occurredAt

## Verification Checklist

After applying changes, verify:
- [ ] Issue #30 body contains "Design Decisions" section
- [ ] Issue #30 has new data fields (triggerType, decisionReason)
- [ ] Issue #30 has 6 enhanced acceptance criteria
- [ ] Issue #30 has `status:needs-review` label
- [ ] Issue #30 does NOT have `blocked:clarification` label
- [ ] Issue #30 does NOT have `status:draft` label
- [ ] Handoff comment is visible on issue #30
- [ ] Resolution comment is visible on issue #228
- [ ] Issue #228 is closed with reason "completed"

## Files Reference

| File | Purpose | Size |
|------|---------|------|
| `.story-work/issue-30-resolution/after.md` | Updated story body for issue #30 | 16.6 KB |
| `.story-work/issue-30-resolution/handoff-comment.md` | Comment for issue #30 | 2.0 KB |
| `.story-work/issue-30-resolution/resolution-comment-228.md` | Comment for issue #228 | 1.8 KB |
| `.story-work/issue-30-resolution/apply-resolution.sh` | Automated application script | 3.0 KB |
| `.story-work/issue-30-resolution/README.md` | Comprehensive documentation | 4.9 KB |
| `CLARIFICATION-RESOLUTION-30.md` | Executive summary | 8.7 KB |

## Next Steps

1. **Apply Changes** - Use one of the three methods above
2. **Verify** - Check all items in verification checklist
3. **Review Story** - Technical review of updated story
4. **Approve** - Update label to `status:ready-for-dev` if approved
5. **Implement** - Begin development with clear requirements

---

**Prepared By:** @copilot (Principal Software Engineer Mode)
**Date:** 2026-01-12
**Branch:** `copilot/clarify-putaway-replenish`
**Clarification Issue:** #228
**Origin Story:** #30
