#!/bin/bash
# Apply Clarification Resolution for Issue #46
# This script automates the handoff process for the Story Authoring Agent

set -e

REPO="louisburroughs/durion-positivity-backend"
ORIGIN_ISSUE=46
CLARIFICATION_ISSUE=239
STORY_FILE=".story-work/issue-46-updated-story.md"

echo "=========================================="
echo "Clarification Resolution Handoff"
echo "Origin Story: #${ORIGIN_ISSUE}"
echo "Clarification Issue: #${CLARIFICATION_ISSUE}"
echo "=========================================="
echo ""

# Check if GitHub CLI is available
if ! command -v gh &> /dev/null; then
    echo "❌ GitHub CLI (gh) is not available"
    echo ""
    echo "Please install GitHub CLI: https://cli.github.com/"
    echo "Or perform the following actions manually:"
    echo ""
    echo "1. Update issue #${ORIGIN_ISSUE} body with content from ${STORY_FILE}"
    echo "2. Remove label 'blocked:clarification' from issue #${ORIGIN_ISSUE}"
    echo "3. Add label 'status:ready-for-dev' to issue #${ORIGIN_ISSUE}"
    echo "4. Add handoff comment to issue #${ORIGIN_ISSUE}"
    echo "5. Add completion comment to issue #${CLARIFICATION_ISSUE}"
    echo "6. Close issue #${CLARIFICATION_ISSUE}"
    echo "7. Assign issue #${ORIGIN_ISSUE} to @github-copilot"
    echo ""
    exit 1
fi

# Check if story file exists
if [ ! -f "${STORY_FILE}" ]; then
    echo "❌ Story file not found: ${STORY_FILE}"
    exit 1
fi

echo "✅ GitHub CLI available"
echo "✅ Story file found: ${STORY_FILE}"
echo ""

# Step 1: Update origin story body
echo "Step 1/7: Updating origin story #${ORIGIN_ISSUE} body..."
gh issue edit ${ORIGIN_ISSUE} \
  --repo ${REPO} \
  --body-file ${STORY_FILE}
echo "✅ Story body updated"
echo ""

# Step 2: Remove blocked:clarification label
echo "Step 2/7: Removing 'blocked:clarification' label from issue #${ORIGIN_ISSUE}..."
gh issue edit ${ORIGIN_ISSUE} \
  --repo ${REPO} \
  --remove-label "blocked:clarification"
echo "✅ Label removed"
echo ""

# Step 3: Add status:ready-for-dev label
echo "Step 3/7: Adding 'status:ready-for-dev' label to issue #${ORIGIN_ISSUE}..."
gh issue edit ${ORIGIN_ISSUE} \
  --repo ${REPO} \
  --add-label "status:ready-for-dev"
echo "✅ Label added"
echo ""

# Step 4: Post handoff comment on origin story
echo "Step 4/7: Posting handoff comment on issue #${ORIGIN_ISSUE}..."
cat << 'EOF' | gh issue comment ${ORIGIN_ISSUE} --repo ${REPO} --body-file -
## Clarification Resolution Complete ✅

All questions from clarification issue #239 have been answered and integrated into this story.

### Resolved Questions:
1. **Mapping Authority**: pos-product is SoR, accessed via API (precondition)
2. **Feed Specification**: Single standardized JSON format with versioned schema
3. **Minimum Order Rules**: Optional field in feed, store if present (enforcement out of scope)

### Story Updates:
- Business Rules expanded with 5 decision-driven rules
- Data Requirements include complete JSON schema and database definitions
- Functional Behavior details API integration patterns
- Acceptance Criteria are testable and comprehensive
- All error flows documented

### Next Actions:
This story is now **ready for development**. 

**Assigned to**: @github-copilot for implementation support

See clarification issue #239 for full decision context.
EOF
echo "✅ Handoff comment posted"
echo ""

# Step 5: Post completion comment on clarification issue
echo "Step 5/7: Posting completion comment on clarification issue #${CLARIFICATION_ISSUE}..."
cat << 'EOF' | gh issue comment ${CLARIFICATION_ISSUE} --repo ${REPO} --body-file -
## Clarification Resolved ✅

All questions answered by @louisburroughs.

**Actions Completed**:
- Story #46 updated with all clarification decisions
- Business rules integrated
- Data schemas defined
- Acceptance criteria expanded
- Labels updated (removed blocked:clarification, added status:ready-for-dev)

Story #46 is now ready for development.

**Updated story**: See issue #46 for complete integrated story.
EOF
echo "✅ Completion comment posted"
echo ""

# Step 6: Close clarification issue
echo "Step 6/7: Closing clarification issue #${CLARIFICATION_ISSUE}..."
gh issue close ${CLARIFICATION_ISSUE} \
  --repo ${REPO} \
  --reason completed
echo "✅ Clarification issue closed"
echo ""

# Step 7: Assign origin story to github-copilot
echo "Step 7/7: Assigning issue #${ORIGIN_ISSUE} to @github-copilot..."
gh issue edit ${ORIGIN_ISSUE} \
  --repo ${REPO} \
  --add-assignee "github-copilot"
echo "✅ Issue assigned"
echo ""

echo "=========================================="
echo "✅ All handoff actions completed successfully!"
echo "=========================================="
echo ""
echo "Story #${ORIGIN_ISSUE} is now ready for development."
echo "Clarification issue #${CLARIFICATION_ISSUE} is closed."
echo ""
echo "Next step: Development team can begin implementation."
