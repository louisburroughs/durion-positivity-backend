#!/bin/bash
# Script to apply clarification resolution for Issue #35
# This script updates GitHub issues #35 and #232 following Story Authoring Agent protocol

set -e

REPO="louisburroughs/durion-positivity-backend"
STORY_ISSUE="35"
CLARIFICATION_ISSUE="232"

echo "=========================================="
echo "Applying Clarification Resolution"
echo "Story Issue: #${STORY_ISSUE}"
echo "Clarification Issue: #${CLARIFICATION_ISSUE}"
echo "=========================================="
echo ""

# Check for GitHub CLI authentication
if ! gh auth status &> /dev/null; then
    echo "❌ ERROR: GitHub CLI is not authenticated"
    echo "Please run: gh auth login"
    exit 1
fi

echo "✅ GitHub CLI authenticated"
echo ""

# Step 1: Update Issue #35 Body
echo "Step 1: Updating Issue #${STORY_ISSUE} body..."
if gh issue edit ${STORY_ISSUE} \
    --body-file .story-work/issue-35-updated-body.md \
    --repo ${REPO}; then
    echo "✅ Issue #${STORY_ISSUE} body updated successfully"
else
    echo "❌ Failed to update issue #${STORY_ISSUE} body"
    exit 1
fi
echo ""

# Step 2: Update Labels on Issue #35
echo "Step 2: Updating labels on Issue #${STORY_ISSUE}..."

# Remove blocking labels
echo "  Removing: blocked:clarification, status:draft"
gh issue edit ${STORY_ISSUE} \
    --remove-label "blocked:clarification" \
    --remove-label "status:draft" \
    --repo ${REPO} || true

# Add new status label
echo "  Adding: status:needs-review"
gh issue edit ${STORY_ISSUE} \
    --add-label "status:needs-review" \
    --repo ${REPO}

echo "✅ Labels updated on Issue #${STORY_ISSUE}"
echo ""

# Step 3: Post Resolution Comment to Issue #232
echo "Step 3: Posting resolution comment to Issue #${CLARIFICATION_ISSUE}..."

RESOLUTION_COMMENT="## ✅ Clarification Resolved

This clarification issue has been fully resolved and all decisions have been incorporated into the updated story #${STORY_ISSUE}.

### Decisions Applied:

1. **Identifier Method:**
   - Supported: Manual text entry + Barcode scan
   - Entry method recorded as MANUAL or SCAN
   - Searchable list explicitly out of scope for this story

2. **Blind Receiving:**
   - **Status:** Blocked for this story
   - Requires valid PO or ASN identifier
   - System displays: \"Receiving requires a valid PO or ASN. Blind receiving is not supported.\"
   - Future enhancement possible with separate permission (\`ALLOW_BLIND_RECEIVING\`)

3. **Scope - Matching and Variances:**
   - **Confirmed:** Out of scope for story #${STORY_ISSUE}
   - This story covers session creation only
   - Counting, matching, and variance capture will be in a separate story

### Next Steps:

Story #${STORY_ISSUE} has been updated with:
- Complete clarifications section documenting all decisions
- Updated functional behavior reflecting the decisions
- Refined acceptance criteria
- Clear scope boundaries

**Story Status:** Ready for domain review (\`status:needs-review\`)

---
*Resolution applied by Story Authoring Agent on $(date -u +"%Y-%m-%dT%H:%M:%SZ")*"

if gh issue comment ${CLARIFICATION_ISSUE} \
    --body "${RESOLUTION_COMMENT}" \
    --repo ${REPO}; then
    echo "✅ Resolution comment posted to Issue #${CLARIFICATION_ISSUE}"
else
    echo "❌ Failed to post comment to Issue #${CLARIFICATION_ISSUE}"
    exit 1
fi
echo ""

# Step 4: Close Issue #232
echo "Step 4: Closing Issue #${CLARIFICATION_ISSUE}..."
if gh issue close ${CLARIFICATION_ISSUE} \
    --reason completed \
    --repo ${REPO}; then
    echo "✅ Issue #${CLARIFICATION_ISSUE} closed successfully"
else
    echo "❌ Failed to close Issue #${CLARIFICATION_ISSUE}"
    exit 1
fi
echo ""

echo "=========================================="
echo "✅ ALL STEPS COMPLETED SUCCESSFULLY"
echo "=========================================="
echo ""
echo "Verification:"
echo "  - Issue #${STORY_ISSUE}: https://github.com/${REPO}/issues/${STORY_ISSUE}"
echo "  - Issue #${CLARIFICATION_ISSUE}: https://github.com/${REPO}/issues/${CLARIFICATION_ISSUE}"
echo ""
echo "Next steps:"
echo "  1. Verify Issue #${STORY_ISSUE} has updated body and labels"
echo "  2. Verify Issue #${CLARIFICATION_ISSUE} is closed with resolution comment"
echo "  3. Story is ready for domain review (status:needs-review)"
