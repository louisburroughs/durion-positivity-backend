#!/bin/bash
#
# Apply Clarification Resolution for Issue #21
# This script updates GitHub issues based on clarification resolution
#

set -e

REPO="louisburroughs/durion-positivity-backend"
ORIGIN_ISSUE="21"
CLARIFICATION_ISSUE="221"

echo "🔧 Applying clarification resolution for issue #${ORIGIN_ISSUE}"
echo "Repository: ${REPO}"
echo ""

# Check if gh CLI is available
if ! command -v gh &> /dev/null; then
    echo "❌ Error: GitHub CLI (gh) is not installed or not in PATH"
    echo "Please install gh CLI or execute the actions manually"
    exit 1
fi

# Check if authenticated
if ! gh auth status &> /dev/null; then
    echo "❌ Error: Not authenticated with GitHub CLI"
    echo "Please run: gh auth login"
    exit 1
fi

echo "✅ GitHub CLI is authenticated"
echo ""

# Step 1: Update issue #21 body
echo "📝 Step 1/6: Updating issue #${ORIGIN_ISSUE} body..."
gh issue edit "${ORIGIN_ISSUE}" --repo "${REPO}" \
  --body-file ISSUE-21-UPDATED-BODY.md
echo "✅ Issue body updated"
echo ""

# Step 2: Update issue #21 labels
echo "🏷️  Step 2/6: Updating issue #${ORIGIN_ISSUE} labels..."
gh issue edit "${ORIGIN_ISSUE}" --repo "${REPO}" \
  --remove-label "blocked:clarification" \
  --remove-label "status:draft" \
  --add-label "status:ready-for-dev" 2>/dev/null || echo "⚠️  Some labels may not exist"
echo "✅ Labels updated"
echo ""

# Step 3: Post handoff comment on issue #21
echo "💬 Step 3/6: Posting handoff comment on issue #${ORIGIN_ISSUE}..."
gh issue comment "${ORIGIN_ISSUE}" --repo "${REPO}" \
  --body-file ISSUE-21-HANDOFF-COMMENT.md
echo "✅ Handoff comment posted"
echo ""

# Step 4: Assign issue #21
echo "👤 Step 4/6: Assigning issue #${ORIGIN_ISSUE}..."
gh issue edit "${ORIGIN_ISSUE}" --repo "${REPO}" \
  --add-assignee "Copilot" 2>/dev/null || echo "⚠️  Could not assign to Copilot (may not be a valid assignee)"
echo "✅ Issue assigned"
echo ""

# Step 5: Post closing comment on issue #221
echo "💬 Step 5/6: Posting closing comment on issue #${CLARIFICATION_ISSUE}..."
gh issue comment "${CLARIFICATION_ISSUE}" --repo "${REPO}" \
  --body-file ISSUE-221-CLOSE-COMMENT.md
echo "✅ Closing comment posted"
echo ""

# Step 6: Close issue #221
echo "🔒 Step 6/6: Closing issue #${CLARIFICATION_ISSUE}..."
gh issue close "${CLARIFICATION_ISSUE}" --repo "${REPO}" \
  --reason "completed"
echo "✅ Clarification issue closed"
echo ""

echo "🎉 All actions completed successfully!"
echo ""
echo "Summary:"
echo "  - Issue #${ORIGIN_ISSUE}: Updated body, labels, and posted handoff comment"
echo "  - Issue #${CLARIFICATION_ISSUE}: Posted closing comment and closed"
echo ""
echo "Please verify the changes at:"
echo "  https://github.com/${REPO}/issues/${ORIGIN_ISSUE}"
echo "  https://github.com/${REPO}/issues/${CLARIFICATION_ISSUE}"
