# Issue #164 Domain Conflict Resolution - Implementation Guide

## Summary
This document provides the finalized issue body for issue #164 after resolving the domain conflict. The user (@louisburroughs) has confirmed that `domain:workexec` is the correct authoritative domain.

## Changes Made

### 1. Removed STOP Condition
**Before:**
```
STOP: Conflicting domain guidance detected
```

**After:**
- Completely removed from the issue body

### 2. Removed Domain Conflict Warning Section
**Removed Section:**
```markdown
## ⚠️ Domain Conflict Summary
- **Candidate Primary Domains:** `domain:workexec`, `domain:user`
- **Why conflict was detected:** The original issue's frontmatter specifies `Domain: user`, but the "Classification" section within the body correctly identifies the domain as `domain:workexec`. The promotion of an estimate to a work order is a core business process within the Work Execution lifecycle, not a generic user management function.
- **What must be decided:** Confirm that `domain:workexec` is the authoritative domain for this story, superseding the incorrect `domain:user` label. This rewrite proceeds under the assumption that `workexec` is correct.
- **Recommended split:** No split is needed. This is a classification correction, not a true multi-domain responsibility conflict. The story belongs entirely within `domain:workexec`.
```

### 3. Updated Labels Section
**Before:**
- Current Labels included `user`

**After:**
- Updated to properly reflect `domain:workexec`
- Removed references to `domain:user`

### 4. Preserved All Story Content
All story sections remain intact:
- Story Intent
- Actors & Stakeholders
- Preconditions
- Functional Behavior (3 scenarios)
- Alternate / Error Flows
- Business Rules
- Data Requirements
- Acceptance Criteria (4 criteria)
- Audit & Observability
- Original Story (for traceability)

## How to Apply Changes

### Option 1: Using GitHub Web Interface
1. Navigate to: https://github.com/louisburroughs/durion-positivity-backend/issues/164
2. Click "Edit" on the issue
3. Replace the entire issue body with the content from `ISSUE-164-CLEANED-BODY.md`
4. Update labels:
   - Remove: `user`
   - Add: `domain:workexec`, `agent:workexec`, `agent:story-authoring`
5. Save changes

### Option 2: Using GitHub CLI (if GH_TOKEN is available)
```bash
# Update issue body
gh issue edit 164 --body-file ISSUE-164-CLEANED-BODY.md

# Update labels
gh issue edit 164 --remove-label "user" --add-label "domain:workexec,agent:workexec,agent:story-authoring"

# Post a resolution comment
gh issue comment 164 --body "✅ Domain conflict resolved. Primary domain confirmed as **domain:workexec**. STOP condition removed. Story is now ready for technical review."
```

### Option 3: Using GitHub API
```bash
# Requires GITHUB_TOKEN environment variable
curl -X PATCH \
  -H "Authorization: token $GITHUB_TOKEN" \
  -H "Accept: application/vnd.github.v3+json" \
  https://api.github.com/repos/louisburroughs/durion-positivity-backend/issues/164 \
  -d @- <<EOF
{
  "body": "$(cat ISSUE-164-CLEANED-BODY.md | jq -Rs .)",
  "labels": ["type:story", "domain:workexec", "status:draft", "agent:workexec", "agent:story-authoring", "backend", "story-implementation"]
}
EOF
```

## Next Steps After Applying Changes

According to the Story Authoring Agent contract (Section 10), once the story is finalized:

1. **Remove blocking labels:**
   - `blocked:clarification` (if present)
   - `risk:missing-requirements` (if present)

2. **Update status:**
   - Remove: `status:draft`
   - Add: `status:ready-for-dev`

3. **Assign for implementation:**
   - Assignees: `@github-copilot` and `@principal-software-engineer-agent`

4. **Post handoff comment:**
   ```markdown
   ✅ **Story Finalized and Ready for Implementation**
   
   **Domain Conflict Resolution:**
   - Primary domain confirmed: `domain:workexec`
   - Rationale: Promotion of estimates to work orders is a core Work Execution lifecycle operation
   
   **Story Status:**
   - All clarifications resolved
   - Acceptance criteria are testable
   - Business rules are explicit
   - Data requirements are defined
   
   **Assigned To:**
   - @github-copilot for code generation support
   - @principal-software-engineer-agent for technical execution
   
   The story is now implementation-ready.
   ```

## Verification Checklist

After applying changes, verify:
- [ ] STOP condition is completely removed from issue body
- [ ] Domain conflict warning section is removed
- [ ] Labels reflect `domain:workexec` (not `domain:user`)
- [ ] All story content sections are intact and properly formatted
- [ ] Original story section is preserved for traceability
- [ ] Issue status is updated to `status:ready-for-dev` (once finalized)

## Decision Record

**Decision Authority:** @louisburroughs (Product Owner)

**Decision Date:** 2026-01-12

**Decision:**
- **Primary Domain:** `domain:workexec`
- **Rationale:** Promoting an estimate to a work order is a core Work Execution lifecycle operation (state transition, orchestration, downstream effects). It is not a user management concern.
- **Action:** Classification correction only; no story split required.

**References:**
- Issue: #164
- Agent Contract: Story Authoring Agent (Section 10, 13.7, 14)
- Domain Sub-Contract: Workorder Execution Domain (Section 13.7)
