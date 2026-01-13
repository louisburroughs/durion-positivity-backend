#!/bin/bash
# Script to update GitHub issue #3 with clarification resolutions
# Usage: GH_TOKEN=<your_token> ./update-issue-3.sh

set -e

REPO="louisburroughs/durion-positivity-backend"
ISSUE_NUMBER=3
BODY_FILE="ISSUE-3-UPDATED-BODY.md"

# Check if GH_TOKEN is set
if [ -z "$GH_TOKEN" ]; then
    echo "Error: GH_TOKEN environment variable is not set"
    echo "Usage: GH_TOKEN=<your_token> ./update-issue-3.sh"
    exit 1
fi

# Check if body file exists
if [ ! -f "$BODY_FILE" ]; then
    echo "Error: $BODY_FILE not found"
    echo "Please ensure the updated body file has been created."
    exit 1
fi

echo "Updating issue #$ISSUE_NUMBER in $REPO..."

# Update the issue body
gh issue edit $ISSUE_NUMBER \
    --repo $REPO \
    --body-file $BODY_FILE

echo "✓ Issue body updated successfully"

# Remove blocked:clarification and status:draft labels
echo "Removing blocking labels..."
gh issue edit $ISSUE_NUMBER \
    --repo $REPO \
    --remove-label "blocked:clarification" 2>/dev/null || echo "  (blocked:clarification label not present)"

gh issue edit $ISSUE_NUMBER \
    --repo $REPO \
    --remove-label "status:draft" 2>/dev/null || echo "  (status:draft label not present)"

# Add status:ready-for-dev and domain:billing labels
echo "Adding ready-for-dev and domain:billing labels..."
gh issue edit $ISSUE_NUMBER \
    --repo $REPO \
    --add-label "status:ready-for-dev"

gh issue edit $ISSUE_NUMBER \
    --repo $REPO \
    --add-label "domain:billing"

echo "✓ Labels updated successfully"

# Add a handoff comment
COMMENT="## Story Updated Based on Clarification Resolutions

This story has been updated to incorporate all clarification decisions provided on 2026-01-05.

### Summary of Changes

All 5 clarification questions have been addressed and integrated:

1. **Billing Rule Ownership (Q1):** Billing Management is the authoritative source; rules are versioned and timestamped
2. **PO Validation Rules (Q2):** Format defined (3-30 chars, alphanumeric with \`-\` and \`_\`); uniqueness configurable per customer
3. **Override Permissions (Q3):** Standalone permission with policy-based approval workflow (single vs two-person)
4. **Payment Terms Integration (Q4):** Independent from PO requirement; Billing Management owns credit limits
5. **Default Behavior (Q5):** Fail-safe for B2B accounts - require PO and block checkout if rules are misconfigured

### Key Updates

- Added **Billing Management** as authoritative domain for billing rules
- Added billing rule versioning requirements (\`billingRuleVersion\` captured with each order)
- Defined PO format validation rules (3-30 characters, alphanumeric with \`-\`, \`_\`)
- Added PO uniqueness policy options (configurable per customer)
- Defined override approval workflow (single vs two-person based on thresholds)
- Changed default behavior to fail-safe for B2B accounts (block checkout vs allow)
- Added new acceptance criteria for rule versioning, uniqueness validation, and two-person approval
- Added new audit events for uniqueness violations, two-person approvals, and credit limit checks
- Updated data requirements to include versioning fields and override policy tracking

### Clarification Decisions

See \"Resolved Questions & Decisions\" section in the updated story body for complete decision details.

---

This story is now **ready for implementation** with no open questions or clarifications pending.

**Next Steps:**
- Assign to \`@github-copilot\` and principal software engineer for implementation
- Begin technical design and API specification

See \`ISSUE-3-UPDATE-SUMMARY.md\` in the repository for full details."

gh issue comment $ISSUE_NUMBER \
    --repo $REPO \
    --body "$COMMENT"

echo "✓ Handoff comment added successfully"
echo ""
echo "Issue #$ISSUE_NUMBER has been fully updated and is ready for development!"
echo ""
echo "Optional: If there is a separate clarification issue, you may want to close it with:"
echo "  gh issue close <CLARIFICATION_ISSUE_NUMBER> --repo $REPO --comment 'Clarification resolved and integrated into issue #3'"
