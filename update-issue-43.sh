#!/bin/bash
set -e

REPO="louisburroughs/durion-positivity-backend"
ISSUE_NUMBER=43
CLARIFICATION_ISSUE=238

echo "=========================================="
echo "Updating Issue #$ISSUE_NUMBER"
echo "=========================================="

# Update the issue body
echo "Updating issue body..."
gh issue edit $ISSUE_NUMBER \
  --repo "$REPO" \
  --body-file issue-43-updated-body.md

echo "✓ Issue body updated"

# Remove blocking labels
echo "Removing blocking labels..."
gh issue edit $ISSUE_NUMBER \
  --repo "$REPO" \
  --remove-label "blocked:clarification" \
  --remove-label "blocked:domain-conflict" \
  --remove-label "status:needs-review"

echo "✓ Blocking labels removed"

# Add domain label and ready-for-dev status
echo "Adding domain:pricing label..."
gh issue edit $ISSUE_NUMBER \
  --repo "$REPO" \
  --add-label "domain:pricing"

echo "✓ Domain label added"

echo "Adding status:ready-for-dev label..."
gh issue edit $ISSUE_NUMBER \
  --repo "$REPO" \
  --add-label "status:ready-for-dev"

echo "✓ Status label added"

# Post handoff comment
echo "Posting handoff comment..."
HANDOFF_COMMENT="## ✅ Story Ready for Development

All clarification questions have been resolved via [Clarification Issue #238](https://github.com/$REPO/issues/$CLARIFICATION_ISSUE).

### Key Decisions Applied

1. **Domain Ownership:** \`domain:pricing\` is the System of Record for \`RestrictionRule\` entities
2. **Enforcement Contract:** Synchronous evaluation API (\`POST /pricing/v1/restrictions:evaluate\`) with optional caching
3. **Fail-Safe Behavior:** Fail closed for transactional commits; graceful degradation for browsing
4. **Tag Granularity:** Defined initial enum sets for location and service tags
5. **Override UX:** Modal flow with pricing-owned override API

### Story Status

- ✅ All blocking questions resolved
- ✅ Acceptance criteria are testable
- ✅ Domain ownership clarified
- ✅ Technical contracts defined
- ✅ Ready for implementation

### Next Steps

This story is now assigned to \`@github-copilot\` for technical implementation. The story includes:
- Complete API contracts for evaluation and override endpoints
- Comprehensive acceptance criteria with Gherkin scenarios
- Audit and observability requirements
- Clear fail-safe behaviors

---

**Labels updated:**
- Removed: \`blocked:clarification\`, \`blocked:domain-conflict\`, \`status:needs-review\`
- Added: \`domain:pricing\`, \`status:ready-for-dev\`"

gh issue comment $ISSUE_NUMBER \
  --repo "$REPO" \
  --body "$HANDOFF_COMMENT"

echo "✓ Handoff comment posted"

# Close the clarification issue
echo ""
echo "=========================================="
echo "Closing Clarification Issue #$CLARIFICATION_ISSUE"
echo "=========================================="

RESOLUTION_COMMENT="## ✅ Clarification Resolved

All questions in this clarification issue have been answered and incorporated into the origin story.

### Decisions Applied to Story #$ISSUE_NUMBER

1. **Domain Ownership (BLOCKER):** Resolved - \`domain:pricing\` is the System of Record
2. **Enforcement Contract (BLOCKER):** Resolved - Synchronous API with optional caching
3. **Fail-Safe Behavior (BLOCKER):** Resolved - Fail closed for commits, graceful degrade for browse
4. **Tag Granularity:** Resolved - Defined initial enum sets for location and service tags
5. **Override UX:** Resolved - Modal flow with dedicated override API

### Actions Taken

- ✅ Updated story #$ISSUE_NUMBER with all clarification responses
- ✅ Integrated decisions into appropriate story sections
- ✅ Updated business rules and acceptance criteria
- ✅ Added API contract specifications
- ✅ Removed blocking labels from story
- ✅ Added \`domain:pricing\` and \`status:ready-for-dev\` labels to story
- ✅ Posted handoff comment on story

Story #$ISSUE_NUMBER is now ready for development.

---

Closing this clarification issue as resolved."

gh issue comment $CLARIFICATION_ISSUE \
  --repo "$REPO" \
  --body "$RESOLUTION_COMMENT"

echo "✓ Resolution comment posted"

gh issue close $CLARIFICATION_ISSUE \
  --repo "$REPO" \
  --comment "Clarification resolved and applied to story #$ISSUE_NUMBER."

echo "✓ Clarification issue closed"

echo ""
echo "=========================================="
echo "✅ All updates complete!"
echo "=========================================="
echo "Story #$ISSUE_NUMBER is ready for development"
echo "Clarification #$CLARIFICATION_ISSUE has been closed"
