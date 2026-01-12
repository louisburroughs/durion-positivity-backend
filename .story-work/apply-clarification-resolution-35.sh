#!/bin/bash

# apply-clarification-resolution-35.sh
# Applies clarification responses from issue #232 to origin issue #35
# Per Story Authoring Agent protocol

set -e

REPO="louisburroughs/durion-positivity-backend"
ORIGIN_ISSUE=35
CLARIFICATION_ISSUE=232

echo "=========================================="
echo "Clarification Resolution Application"
echo "=========================================="
echo "Repository: $REPO"
echo "Origin Issue: #$ORIGIN_ISSUE"
echo "Clarification Issue: #$CLARIFICATION_ISSUE"
echo "=========================================="
echo ""

# Check if gh CLI is available
if ! command -v gh &> /dev/null; then
    echo "❌ Error: GitHub CLI (gh) is not installed or not in PATH"
    echo "Install from: https://cli.github.com/"
    exit 1
fi

# Check if authenticated
if ! gh auth status &> /dev/null; then
    echo "❌ Error: Not authenticated with GitHub CLI"
    echo "Run: gh auth login"
    exit 1
fi

echo "✅ GitHub CLI is authenticated"
echo ""

# Step 1: Update origin issue #35 body
echo "=========================================="
echo "Step 1: Updating Issue #35 Body"
echo "=========================================="
echo ""

UPDATED_BODY_FILE=".story-work/issue-35-updated-body.md"

if [ ! -f "$UPDATED_BODY_FILE" ]; then
    echo "❌ Error: Updated body file not found: $UPDATED_BODY_FILE"
    exit 1
fi

echo "Reading updated story body from: $UPDATED_BODY_FILE"
echo ""

# Update the issue body
echo "Updating issue #$ORIGIN_ISSUE body..."
gh issue edit $ORIGIN_ISSUE -R $REPO --body-file "$UPDATED_BODY_FILE"

if [ $? -eq 0 ]; then
    echo "✅ Issue #$ORIGIN_ISSUE body updated successfully"
else
    echo "❌ Failed to update issue #$ORIGIN_ISSUE body"
    exit 1
fi

echo ""

# Step 2: Update labels on origin issue #35
echo "=========================================="
echo "Step 2: Updating Labels on Issue #35"
echo "=========================================="
echo ""

echo "Removing labels: blocked:clarification, status:draft"
gh issue edit $ORIGIN_ISSUE -R $REPO --remove-label "blocked:clarification" --remove-label "status:draft"

if [ $? -eq 0 ]; then
    echo "✅ Blocking labels removed"
else
    echo "⚠️  Warning: Failed to remove some labels (they may not exist)"
fi

echo ""

echo "Adding label: status:needs-review"
gh issue edit $ORIGIN_ISSUE -R $REPO --add-label "status:needs-review"

if [ $? -eq 0 ]; then
    echo "✅ status:needs-review label added"
else
    echo "❌ Failed to add status:needs-review label"
    exit 1
fi

echo ""

# Step 3: Close clarification issue #232 with resolution comment
echo "=========================================="
echo "Step 3: Closing Clarification Issue #232"
echo "=========================================="
echo ""

RESOLUTION_COMMENT="# ✅ Clarification Resolved

All questions have been answered by @louisburroughs on 2026-01-12.

## Decisions Applied

### 1. Identifier Method
✅ **Decision:** Manual text entry and barcode scan (same field). Searchable list is out of scope.

### 2. Blind Receiving
✅ **Decision:** Blocked for this story. Valid PO/ASN required.

### 3. Scope of Matching and Variances
✅ **Decision:** Confirmed out of scope. Session creation only.

## Story Updates

The origin story #35 has been updated with:
- ✅ All clarification decisions integrated
- ✅ Updated functional behavior
- ✅ New \`entryMethod\` field in data model
- ✅ New acceptance criteria (AC5 for blind receiving)
- ✅ New \"Clarifications (Resolved)\" section

## Next Steps

1. ✅ Origin story #35 updated
2. ✅ Labels updated on #35 (removed \`blocked:clarification\`, added \`status:needs-review\`)
3. ✅ This clarification issue closed

The story is now ready for technical review.

---

**See full resolution details:** 
- [Issue #35 Updated Story](https://github.com/$REPO/issues/$ORIGIN_ISSUE)
- [Resolution Summary](.story-work/issue-35-update-summary.md)
- [Resolution Metadata](.story-work/clarification-232-resolution-metadata.json)
"

echo "Adding resolution comment to issue #$CLARIFICATION_ISSUE..."
gh issue comment $CLARIFICATION_ISSUE -R $REPO --body "$RESOLUTION_COMMENT"

if [ $? -eq 0 ]; then
    echo "✅ Resolution comment added"
else
    echo "❌ Failed to add resolution comment"
    exit 1
fi

echo ""

echo "Closing issue #$CLARIFICATION_ISSUE..."
gh issue close $CLARIFICATION_ISSUE -R $REPO --reason completed

if [ $? -eq 0 ]; then
    echo "✅ Clarification issue #$CLARIFICATION_ISSUE closed"
else
    echo "❌ Failed to close clarification issue"
    exit 1
fi

echo ""

# Step 4: Summary
echo "=========================================="
echo "✅ Resolution Applied Successfully"
echo "=========================================="
echo ""
echo "Summary of changes:"
echo "  • Origin issue #$ORIGIN_ISSUE: Body updated with all clarification decisions"
echo "  • Origin issue #$ORIGIN_ISSUE: Labels updated (removed blocked:clarification, added status:needs-review)"
echo "  • Clarification issue #$CLARIFICATION_ISSUE: Closed with resolution comment"
echo ""
echo "Next step: Technical review of story #$ORIGIN_ISSUE"
echo ""
echo "View updated story: https://github.com/$REPO/issues/$ORIGIN_ISSUE"
echo "View closed clarification: https://github.com/$REPO/issues/$CLARIFICATION_ISSUE"
echo ""
echo "=========================================="
