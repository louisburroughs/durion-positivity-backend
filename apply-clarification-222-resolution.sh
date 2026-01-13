#!/bin/bash
# Script to apply clarification #222 resolution to issue #24
# This script requires GitHub CLI (gh) to be authenticated

set -e

REPO="louisburroughs/durion-positivity-backend"
STORY_ISSUE=24
CLARIFICATION_ISSUE=222

echo "=================================================="
echo "Clarification #222 Resolution Application Script"
echo "=================================================="
echo ""
echo "This script will:"
echo "  1. Update issue #24 body with clarification decisions"
echo "  2. Update issue #24 labels (remove blocked, add ready-for-dev)"
echo "  3. Assign issue #24 to @github-copilot"
echo "  4. Post handoff comment on issue #24"
echo "  5. Post completion comment on issue #222"
echo "  6. Close issue #222"
echo ""
read -p "Press Enter to continue or Ctrl+C to cancel..."

# Step 1: Update issue #24 body
echo ""
echo "Step 1: Updating issue #24 body..."
if [ -f "/tmp/issue-24-updated-body.md" ]; then
    gh issue edit $STORY_ISSUE \
        --repo $REPO \
        --body-file /tmp/issue-24-updated-body.md
    echo "✓ Issue #24 body updated"
else
    echo "✗ Error: /tmp/issue-24-updated-body.md not found"
    exit 1
fi

# Step 2: Update issue #24 labels
echo ""
echo "Step 2: Updating issue #24 labels..."
gh issue edit $STORY_ISSUE \
    --repo $REPO \
    --remove-label "blocked:clarification" \
    --remove-label "status:draft" \
    --add-label "status:ready-for-dev"
echo "✓ Issue #24 labels updated"

# Step 3: Assign issue #24
echo ""
echo "Step 3: Assigning issue #24 to @github-copilot..."
gh issue edit $STORY_ISSUE \
    --repo $REPO \
    --add-assignee "Copilot"
echo "✓ Issue #24 assigned"

# Step 4: Post handoff comment on issue #24
echo ""
echo "Step 4: Posting handoff comment on issue #24..."
if [ -f "/tmp/issue-24-handoff-comment.md" ]; then
    gh issue comment $STORY_ISSUE \
        --repo $REPO \
        --body-file /tmp/issue-24-handoff-comment.md
    echo "✓ Handoff comment posted on issue #24"
else
    echo "✗ Error: /tmp/issue-24-handoff-comment.md not found"
    exit 1
fi

# Step 5: Post completion comment on issue #222
echo ""
echo "Step 5: Posting completion comment on issue #222..."
if [ -f "/tmp/clarification-222-completion.md" ]; then
    gh issue comment $CLARIFICATION_ISSUE \
        --repo $REPO \
        --body-file /tmp/clarification-222-completion.md
    echo "✓ Completion comment posted on issue #222"
else
    echo "✗ Error: /tmp/clarification-222-completion.md not found"
    exit 1
fi

# Step 6: Close issue #222
echo ""
echo "Step 6: Closing issue #222..."
gh issue close $CLARIFICATION_ISSUE \
    --repo $REPO \
    --comment "Clarification resolved. All decisions incorporated into issue #24. Story is now ready for development."
echo "✓ Issue #222 closed"

echo ""
echo "=================================================="
echo "✓ All operations completed successfully!"
echo "=================================================="
echo ""
echo "Summary:"
echo "  • Issue #24 updated with clarification decisions"
echo "  • Issue #24 status: ready-for-dev"
echo "  • Issue #24 assigned to: @github-copilot"
echo "  • Issue #222 closed as resolved"
echo ""
echo "Next: Technical implementation can begin on issue #24"
