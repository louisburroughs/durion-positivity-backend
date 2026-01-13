#!/bin/bash
# Script to update GitHub issue #17 with clarification resolution
# Usage: GH_TOKEN=<your_token> ./update-issue-17.sh

set -e

REPO="louisburroughs/durion-positivity-backend"
ISSUE_NUMBER=17
CLARIFICATION_ISSUE=219
BODY_FILE="ISSUE-17-UPDATED-BODY.md"
HANDOFF_COMMENT_FILE="ISSUE-17-HANDOFF-COMMENT.md"
CLOSE_COMMENT_FILE="ISSUE-219-CLOSE-COMMENT.md"

# Check if GH_TOKEN is set
if [ -z "$GH_TOKEN" ]; then
    echo "Error: GH_TOKEN environment variable is not set"
    echo "Usage: GH_TOKEN=<your_token> ./update-issue-17.sh"
    exit 1
fi

# Check if required files exist
if [ ! -f "$BODY_FILE" ]; then
    echo "Error: $BODY_FILE not found"
    exit 1
fi

if [ ! -f "$HANDOFF_COMMENT_FILE" ]; then
    echo "Error: $HANDOFF_COMMENT_FILE not found"
    exit 1
fi

if [ ! -f "$CLOSE_COMMENT_FILE" ]; then
    echo "Error: $CLOSE_COMMENT_FILE not found"
    exit 1
fi

echo "==================================================================="
echo "Story Authoring Agent: Clarification Resolution for Issue #17"
echo "==================================================================="
echo ""

# Update the origin story issue body
echo "Step 1: Updating issue #$ISSUE_NUMBER body..."
gh issue edit $ISSUE_NUMBER \
    --repo $REPO \
    --body-file $BODY_FILE

echo "✓ Issue body updated successfully"
echo ""

# Remove blocked:clarification label
echo "Step 2: Removing blocked:clarification label..."
gh issue edit $ISSUE_NUMBER \
    --repo $REPO \
    --remove-label "blocked:clarification" 2>/dev/null || echo "  (label not present)"

echo "✓ Label removed successfully"
echo ""

# Remove status:draft label
echo "Step 3: Removing status:draft label..."
gh issue edit $ISSUE_NUMBER \
    --repo $REPO \
    --remove-label "status:draft" 2>/dev/null || echo "  (label not present)"

echo "✓ Label removed successfully"
echo ""

# Add status:ready-for-dev label
echo "Step 4: Adding status:ready-for-dev label..."
gh issue edit $ISSUE_NUMBER \
    --repo $REPO \
    --add-label "status:ready-for-dev"

echo "✓ Label added successfully"
echo ""

# Add handoff comment to origin story
echo "Step 5: Adding handoff comment to issue #$ISSUE_NUMBER..."
gh issue comment $ISSUE_NUMBER \
    --repo $REPO \
    --body-file $HANDOFF_COMMENT_FILE

echo "✓ Handoff comment added successfully"
echo ""

# Add close comment to clarification issue
echo "Step 6: Adding close comment to clarification issue #$CLARIFICATION_ISSUE..."
gh issue comment $CLARIFICATION_ISSUE \
    --repo $REPO \
    --body-file $CLOSE_COMMENT_FILE

echo "✓ Close comment added successfully"
echo ""

# Close the clarification issue
echo "Step 7: Closing clarification issue #$CLARIFICATION_ISSUE..."
gh issue close $CLARIFICATION_ISSUE \
    --repo $REPO \
    --reason completed

echo "✓ Clarification issue closed successfully"
echo ""

echo "==================================================================="
echo "✓ Clarification Resolution Complete!"
echo "==================================================================="
echo ""
echo "Summary:"
echo "  - Issue #$ISSUE_NUMBER body updated with clarification answers"
echo "  - Labels updated (removed: blocked:clarification, status:draft; added: status:ready-for-dev)"
echo "  - Handoff comment posted to issue #$ISSUE_NUMBER"
echo "  - Clarification issue #$CLARIFICATION_ISSUE closed with completion note"
echo ""
echo "Issue #$ISSUE_NUMBER is now ready for development!"
echo ""
