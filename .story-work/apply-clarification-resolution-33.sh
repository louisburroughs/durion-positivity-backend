#!/bin/bash
# Apply clarification resolution for Issue #33
# Resolves clarification issue #231 and updates story issue #33

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_OWNER="louisburroughs"
REPO_NAME="durion-positivity-backend"

echo "=========================================="
echo "Issue #33 Clarification Resolution Script"
echo "=========================================="
echo ""
echo "This script will:"
echo "  1. Update issue #33 body with resolved clarifications"
echo "  2. Update issue #33 labels (remove blocked:clarification, status:draft; add status:ready-for-dev)"
echo "  3. Post handoff comment to issue #33"
echo "  4. Post resolution comment to issue #231"
echo "  5. Close clarification issue #231"
echo ""

# Check if gh CLI is available and authenticated
if ! command -v gh &> /dev/null; then
    echo "Error: GitHub CLI (gh) is not installed"
    echo "Install it from: https://cli.github.com/"
    exit 1
fi

if ! gh auth status &> /dev/null; then
    echo "Error: GitHub CLI is not authenticated"
    echo "Run: gh auth login"
    exit 1
fi

echo "GitHub CLI is ready"
echo ""

# Step 1: Update issue #33 body
echo "Step 1: Updating issue #33 body..."
if gh issue edit 33 \
    --repo "$REPO_OWNER/$REPO_NAME" \
    --body-file "$SCRIPT_DIR/issue-33-updated-body.txt"; then
    echo "✓ Issue #33 body updated"
else
    echo "✗ Failed to update issue #33 body"
    exit 1
fi
echo ""

# Step 2: Update issue #33 labels
echo "Step 2: Updating issue #33 labels..."

# Remove labels
if gh issue edit 33 \
    --repo "$REPO_OWNER/$REPO_NAME" \
    --remove-label "blocked:clarification" \
    --remove-label "status:draft" \
    --add-label "status:ready-for-dev"; then
    echo "✓ Issue #33 labels updated"
else
    echo "✗ Failed to update issue #33 labels"
    exit 1
fi
echo ""

# Step 3: Post handoff comment to issue #33
echo "Step 3: Posting handoff comment to issue #33..."
if gh issue comment 33 \
    --repo "$REPO_OWNER/$REPO_NAME" \
    --body-file "$SCRIPT_DIR/issue-33-handoff-comment.txt"; then
    echo "✓ Handoff comment posted to issue #33"
else
    echo "✗ Failed to post comment to issue #33"
    exit 1
fi
echo ""

# Step 4: Post resolution comment to issue #231
echo "Step 4: Posting resolution comment to issue #231..."
if gh issue comment 231 \
    --repo "$REPO_OWNER/$REPO_NAME" \
    --body-file "$SCRIPT_DIR/issue-231-resolution-comment.txt"; then
    echo "✓ Resolution comment posted to issue #231"
else
    echo "✗ Failed to post comment to issue #231"
    exit 1
fi
echo ""

# Step 5: Close issue #231
echo "Step 5: Closing clarification issue #231..."
if gh issue close 231 \
    --repo "$REPO_OWNER/$REPO_NAME" \
    --reason "completed"; then
    echo "✓ Issue #231 closed"
else
    echo "✗ Failed to close issue #231"
    exit 1
fi
echo ""

echo "=========================================="
echo "✓ Clarification Resolution Complete!"
echo "=========================================="
echo ""
echo "Summary:"
echo "  - Issue #33 has been updated with clarification decisions"
echo "  - Issue #33 is now labeled as 'status:ready-for-dev'"
echo "  - Clarification issue #231 has been resolved and closed"
echo ""
echo "Next steps:"
echo "  - Review the updated story at: https://github.com/$REPO_OWNER/$REPO_NAME/issues/33"
echo "  - Story is ready for development implementation"
echo ""
