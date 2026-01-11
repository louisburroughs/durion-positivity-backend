#!/bin/bash

# apply-clarification-resolution-155.sh
# Applies clarification responses from issue #302 to origin issue #155
# Per Story Authoring Agent protocol

set -e

REPO="louisburroughs/durion-positivity-backend"
ORIGIN_ISSUE=155
CLARIFICATION_ISSUE=302

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

# Step 1: Fetch current issue body
echo "Step 1: Fetching current issue #$ORIGIN_ISSUE body..."
gh issue view $ORIGIN_ISSUE -R $REPO --json body -q .body > /tmp/issue-155-current.md
echo "✅ Current issue body saved to /tmp/issue-155-current.md"
echo ""

# Step 2: Prepare updated issue body
echo "Step 2: Preparing updated issue body..."
echo ""
echo "⚠️  MANUAL STEP REQUIRED:"
echo "The script has fetched the current issue body to /tmp/issue-155-current.md"
echo "You need to manually apply the updates from issue-155-update-summary.md"
echo ""
echo "Updates to apply:"
echo "  - Remove 'Open Questions' section"
echo "  - Add 6 new Business Rules (BR-POLICY-1, BR-POLICY-2, BR-RBAC-1, BR-RBAC-2, BR-API-1, BR-API-2)"
echo "  - Add 3 new Data Requirements (VisibilityPolicy, VisibilityPolicyCache, VisibilityAuditEvent)"
echo "  - Add 8 new Acceptance Criteria (AC-POLICY-1 through AC-AUDIT-1)"
echo "  - Update Functional Behavior section"
echo "  - Update Audit & Observability section"
echo ""
echo "Save the updated content to /tmp/issue-155-updated.md"
echo ""
read -p "Press Enter when you have prepared /tmp/issue-155-updated.md (or Ctrl+C to cancel)..."
echo ""

# Verify the updated file exists
if [ ! -f /tmp/issue-155-updated.md ]; then
    echo "❌ Error: /tmp/issue-155-updated.md not found"
    echo "Please create the updated issue body and save it to /tmp/issue-155-updated.md"
    exit 1
fi

# Step 3: Update issue body
echo "Step 3: Updating issue #$ORIGIN_ISSUE body..."
gh issue edit $ORIGIN_ISSUE -R $REPO --body-file /tmp/issue-155-updated.md
echo "✅ Issue body updated"
echo ""

# Step 4: Update labels
echo "Step 4: Updating labels..."
gh issue edit $ORIGIN_ISSUE -R $REPO --remove-label "blocked:clarification"
echo "✅ Removed label: blocked:clarification"
gh issue edit $ORIGIN_ISSUE -R $REPO --add-label "status:needs-review"
echo "✅ Added label: status:needs-review"
echo ""

# Step 5: Add resolution comment to origin issue
echo "Step 5: Adding resolution comment to issue #$ORIGIN_ISSUE..."
COMMENT="## ✅ Clarification Resolution Complete

All clarification questions from issue #$CLARIFICATION_ISSUE have been reviewed and integrated into this story.

### Decisions Integrated:

**Q1 – Policy Source of Truth**: Security/Policy service is authoritative for permissions and visibility rules; domain services enforce server-side and cache with short TTL + invalidation events.

**Q2 – Field Granularity**: Configurable; use explicit RBAC scopes (domain:resource:action) decoupled from role names.

**Q3 – API Strategy**: Use standard best practices with explicit contracts, idempotency, audit trails, UTC timestamps, scoped RBAC, configurable defaults.

### Impact:
- **6 new Business Rules** added (BR-POLICY-1, BR-POLICY-2, BR-RBAC-1, BR-RBAC-2, BR-API-1, BR-API-2)
- **3 new Data Requirements** (VisibilityPolicy, VisibilityPolicyCache, VisibilityAuditEvent)
- **8 new Acceptance Criteria** (AC-POLICY-1 through AC-AUDIT-1)
- Updated **Functional Behavior** and **Audit & Observability** sections

### Next Steps:
This story is now ready for:
- Technical design phase
- Implementation planning
- Security service API contract definition
- Test case development

See clarification resolution artifacts in \`.story-work/\`:
- \`issue-155-update-summary.md\` - Complete integration guidance
- \`clarification-resolution-metadata-155.json\` - Machine-readable metadata
- \`README-155.md\` - Workflow documentation
- \`NEXT-STEPS-155.md\` - Application guidance
- \`COMPLETION-SUMMARY-155.md\` - Executive summary"

gh issue comment $ORIGIN_ISSUE -R $REPO --body "$COMMENT"
echo "✅ Resolution comment added"
echo ""

# Step 6: Close clarification issue
echo "Step 6: Closing clarification issue #$CLARIFICATION_ISSUE..."
CLOSE_COMMENT="✅ Clarification responses have been successfully integrated into origin issue #$ORIGIN_ISSUE.

All questions have been answered and decisions documented:
1. **Policy Source of Truth**: Security/Policy service is authoritative; domain services cache and enforce
2. **Field Granularity**: Configurable with explicit RBAC scopes (domain:resource:action)
3. **API Strategy**: Single endpoint with dynamic filtering, audit trails, explicit contracts

The origin story now includes:
- 6 new Business Rules
- 3 new Data Requirements
- 8 new Acceptance Criteria

Origin issue #$ORIGIN_ISSUE is ready for technical design and implementation."

gh issue close $CLARIFICATION_ISSUE -R $REPO --comment "$CLOSE_COMMENT"
echo "✅ Clarification issue #$CLARIFICATION_ISSUE closed"
echo ""

echo "=========================================="
echo "✅ Clarification Resolution Complete!"
echo "=========================================="
echo ""
echo "Summary:"
echo "  - Origin issue #$ORIGIN_ISSUE updated with all clarification decisions"
echo "  - Labels updated (removed blocked:clarification, added status:needs-review)"
echo "  - Resolution comment added to #$ORIGIN_ISSUE"
echo "  - Clarification issue #$CLARIFICATION_ISSUE closed"
echo ""
echo "Next steps:"
echo "  1. Review updated issue #$ORIGIN_ISSUE"
echo "  2. Begin technical design for implementation"
echo "  3. Define Security service API contract"
echo "  4. Develop test cases based on new acceptance criteria"
echo ""
echo "Documentation artifacts available in .story-work/:"
echo "  - issue-155-update-summary.md"
echo "  - clarification-resolution-metadata-155.json"
echo "  - README-155.md"
echo "  - NEXT-STEPS-155.md"
echo "  - COMPLETION-SUMMARY-155.md"
echo ""
