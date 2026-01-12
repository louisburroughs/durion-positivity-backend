#!/bin/bash
# Script to update GitHub issue #166 with the revised story body
# Usage: GH_TOKEN=<your_token> ./update-issue-166.sh

set -e

REPO="louisburroughs/durion-positivity-backend"
ISSUE_NUMBER=166
BODY_FILE="ISSUE-166-UPDATED-BODY.md"

# Check if GH_TOKEN is set
if [ -z "$GH_TOKEN" ]; then
    echo "Error: GH_TOKEN environment variable is not set"
    echo "Usage: GH_TOKEN=<your_token> ./update-issue-166.sh"
    exit 1
fi

# Check if body file exists
if [ ! -f "$BODY_FILE" ]; then
    echo "Error: $BODY_FILE not found"
    exit 1
fi

echo "Updating issue #$ISSUE_NUMBER in $REPO..."

# Update the issue body
gh issue edit $ISSUE_NUMBER \
    --repo $REPO \
    --body-file $BODY_FILE

echo "✓ Issue body updated successfully"

# Remove clarification:domain label if present
echo "Removing clarification:domain label if present..."
gh issue edit $ISSUE_NUMBER \
    --repo $REPO \
    --remove-label "clarification:domain" 2>/dev/null || echo "  (label not present)"

# Ensure status:ready-for-dev label is present
echo "Ensuring status:ready-for-dev label is present..."
gh issue edit $ISSUE_NUMBER \
    --repo $REPO \
    --add-label "status:ready-for-dev"

echo "✓ Labels updated successfully"

# Add a comment documenting the update
COMMENT="## Story Updated Based on Clarification Decisions

This story has been updated to incorporate all clarification decisions from the review comment dated 2026-01-12.

### Summary of Changes

All 8 clarification questions have been addressed:

1. **Estimate Versioning (Q1):** Using snapshot-only approach - removed \`EstimateVersion\` references
2. **Approval Tracking (Q2):** Using \`sourceApprovalEventId\` instead of \`ApprovalRecord\` entity
3. **Line Items (Q3):** Using \`WorkOrderService\` and \`WorkOrderPart\` instead of generic \`WorkorderItem\`
4. **Snapshots (Q4):** Using enhanced \`WorkOrderSnapshot\` with type \`PROMOTION\`
5. **Initial Status (Q5):** Using existing \`APPROVED\` status (hard-coded in v1)
6. **Idempotency (Q6):** Service-layer enforcement via \`findByEstimateId\`
7. **Audit Event (Q7):** Using \`WorkOrderSnapshot\` instead of separate audit event
8. **RBAC (Q8):** Explicitly marked as out of scope for this story

### Key Updates

- Added new acceptance criterion (AC-2) for line item mapping
- Added \"Out of Scope\" section for RBAC
- Added \"Implementation Notes\" section
- Completely revised Data Requirements with correct entity names
- Updated all acceptance criteria to reflect decisions
- Streamlined Functional Behavior steps

See \`ISSUE-166-UPDATE-SUMMARY.md\` in the repository for full details.

---
This story is now **ready for implementation** with no open questions or clarifications pending."

gh issue comment $ISSUE_NUMBER \
    --repo $REPO \
    --body "$COMMENT"

echo "✓ Comment added successfully"
echo ""
echo "Issue #$ISSUE_NUMBER has been fully updated!"
