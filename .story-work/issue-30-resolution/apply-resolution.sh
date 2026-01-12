#!/usr/bin/env bash
# Script to apply Story Authoring Agent updates to GitHub issues
# Clarification resolution for issue #30
set -euo pipefail

REPO="louisburroughs/durion-positivity-backend"
ORIGIN_ISSUE="30"
CLAR_ISSUE="228"

echo "==================================="
echo "Story Authoring Agent Update Script"
echo "==================================="
echo ""
echo "Task: Integrate clarification responses from issue #${CLAR_ISSUE} into issue #${ORIGIN_ISSUE}"
echo ""

# Check if gh CLI is authenticated
if ! gh auth status >/dev/null 2>&1; then
    echo "ERROR: gh CLI is not authenticated."
    echo "Please run: gh auth login"
    exit 1
fi

# Verify the updated issue body file exists
AFTER_FILE=".story-work/work/${ORIGIN_ISSUE}/after.md"
if [ ! -f "$AFTER_FILE" ]; then
    echo "ERROR: Updated issue body not found at: $AFTER_FILE"
    exit 1
fi

echo "Step 1: Updating issue #${ORIGIN_ISSUE} body..."
gh issue edit "$ORIGIN_ISSUE" -R "$REPO" --body-file "$AFTER_FILE"
echo "✓ Issue body updated"
echo ""

echo "Step 2: Updating labels on issue #${ORIGIN_ISSUE}..."
# Remove blocking and draft labels
gh issue edit "$ORIGIN_ISSUE" -R "$REPO" \
    --remove-label "blocked:clarification" \
    --remove-label "status:draft" || true

# Add needs-review label
gh issue edit "$ORIGIN_ISSUE" -R "$REPO" \
    --add-label "status:needs-review"
echo "✓ Labels updated"
echo ""

echo "Step 3: Adding handoff comment to issue #${ORIGIN_ISSUE}..."
HANDOFF_FILE=".story-work/work/${ORIGIN_ISSUE}/handoff-comment.md"
if [ -f "$HANDOFF_FILE" ]; then
    gh issue comment "$ORIGIN_ISSUE" -R "$REPO" --body-file "$HANDOFF_FILE"
    echo "✓ Handoff comment posted"
else
    echo "⚠ Handoff comment file not found, skipping"
fi
echo ""

echo "Step 4: Adding resolution comment to clarification issue #${CLAR_ISSUE}..."
RESOLUTION_FILE=".story-work/work/${ORIGIN_ISSUE}/resolution-comment-228.md"
if [ -f "$RESOLUTION_FILE" ]; then
    gh issue comment "$CLAR_ISSUE" -R "$REPO" --body-file "$RESOLUTION_FILE"
    echo "✓ Resolution comment posted"
else
    echo "⚠ Resolution comment file not found, skipping"
fi
echo ""

echo "Step 5: Closing clarification issue #${CLAR_ISSUE}..."
gh issue close "$CLAR_ISSUE" -R "$REPO" --reason "completed"
echo "✓ Clarification issue closed"
echo ""

echo "==================================="
echo "✅ All updates applied successfully!"
echo "==================================="
echo ""
echo "Summary:"
echo "  - Issue #${ORIGIN_ISSUE} body updated with clarification decisions"
echo "  - Issue #${ORIGIN_ISSUE} labels updated (removed blocked:clarification, status:draft; added status:needs-review)"
echo "  - Handoff comment posted to issue #${ORIGIN_ISSUE}"
echo "  - Resolution comment posted to issue #${CLAR_ISSUE}"
echo "  - Clarification issue #${CLAR_ISSUE} closed"
echo ""
echo "Next steps:"
echo "  1. Review the updated story in issue #${ORIGIN_ISSUE}"
echo "  2. If approved, update status to 'status:ready-for-dev'"
echo "  3. Proceed with implementation"
