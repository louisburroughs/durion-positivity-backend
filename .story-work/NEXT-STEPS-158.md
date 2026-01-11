# Next Steps - Clarification Resolution for Issue #158

## Status
✅ **Artifacts Complete** - Ready for GitHub Application

## Overview
This document provides detailed instructions for applying the clarification responses to GitHub issue #158 and closing clarification issue #303.

## Prerequisites

- GitHub CLI (`gh`) installed and authenticated
- Write access to louisburroughs/durion-positivity-backend repository
- Familiarity with Story Authoring Agent protocol

## Application Methods

Choose the method that best fits your workflow:

### Method 1: Automated Script (Recommended)
Best for: Users comfortable with shell scripts

### Method 2: Manual Application
Best for: Users who prefer step-by-step control

### Method 3: Partial Automation
Best for: Advanced users who want to mix approaches

---

## Method 1: Automated Script

### Step 1: Verify Prerequisites
```bash
# Check GitHub CLI is installed
gh --version

# Check authentication
gh auth status

# Navigate to repository root
cd /home/runner/work/durion-positivity-backend/durion-positivity-backend
```

### Step 2: Run Application Script
```bash
./.story-work/apply-clarification-resolution-158.sh
```

### Step 3: Follow Script Prompts
The script will:
1. Fetch current issue #158
2. Backup original body
3. Prompt for manual body update confirmation
4. Update labels on issue #158
5. Post resolution comment to issue #303
6. Close issue #303

### Step 4: Manual Update Required
The script will provide instructions for manually updating the issue body.

Follow these steps:

1. Open issue #158 in browser:
   https://github.com/louisburroughs/durion-positivity-backend/issues/158

2. Click "Edit" on the issue

3. Refer to `.story-work/issue-158-update-summary.md` for content to add

4. Add the following sections (see update summary for full content):
   - Business Rules (BR-ATOMIC-1 through BR-POLICY-1)
   - Data Requirements (PartUsageEvent, OutboxEvent, AccountingPolicyConfiguration)
   - Acceptance Criteria (AC-ATOMIC-1 through AC-POLICY-2)
   - Updated Audit & Observability section

5. Remove "Open Questions" section if present

6. Preserve all original story text

7. Save changes

### Step 5: Verify Application
Run the verification checklist below.

---

## Method 2: Manual Application

### Step 1: Review Artifacts
```bash
cd /home/runner/work/durion-positivity-backend/durion-positivity-backend/.story-work

# Read the complete update summary
cat issue-158-update-summary.md

# Review metadata
cat clarification-resolution-metadata-158.json
```

### Step 2: Update Issue #158 Body

1. Open issue #158:
   ```bash
   gh issue view 158 --repo louisburroughs/durion-positivity-backend --web
   ```

2. Click "Edit" button

3. Add Business Rules section:
   ```markdown
   ## Business Rules
   
   ### BR-ATOMIC-1: Atomic Transaction Requirement
   **Rule:** Part issue and consume operations MUST be executed within a single local database transaction that includes:
   - Work order part consumption state update
   - Inventory ledger entry creation
   - Outbox record creation for event publishing
   
   **Rationale:** Ensures data consistency by preventing partial updates. If any step fails, all changes are rolled back.
   
   **Authority:** Workexec domain
   
   ### BR-ASYNC-1: Asynchronous Event Publishing
   [... see issue-158-update-summary.md for full content ...]
   ```

4. Add Data Requirements section:
   ```markdown
   ## Data Requirements
   
   ### PartUsageEvent
   Represents a single part consumption event.
   
   **Fields:**
   - `partUsageEventId` (UUID, Primary Key): Unique identifier for this consumption event
   [... see issue-158-update-summary.md for full content ...]
   ```

5. Add Acceptance Criteria section:
   ```markdown
   ## Acceptance Criteria
   
   ### AC-ATOMIC-1: Single Transaction Guarantees Atomicity
   **Given** a request to issue and consume a part for a work order item
   **When** the part consumption is processed
   [... see issue-158-update-summary.md for full content ...]
   ```

6. Update Audit & Observability section:
   ```markdown
   ## Audit & Observability
   
   ### Event: PartConsumptionRequested
   **Logged When:** Part consumption operation is initiated
   [... see issue-158-update-summary.md for full content ...]
   ```

7. Remove "Open Questions" section

8. Save changes

### Step 3: Update Labels
```bash
# Remove blocked:clarification label
gh issue edit 158 --repo louisburroughs/durion-positivity-backend \
    --remove-label "blocked:clarification"

# Add status:needs-review label
gh issue edit 158 --repo louisburroughs/durion-positivity-backend \
    --add-label "status:needs-review"
```

### Step 4: Resolve Clarification Issue

1. Post resolution comment to issue #303:
   ```bash
   gh issue comment 303 --repo louisburroughs/durion-positivity-backend --body \
   "## ✅ Clarification Responses Applied (Origin #158 – Workexec)

   Issue #303 response summary:

   - **Q1 – Issued/Consumed Granularity**: Use single local DB transaction for state + ledger writes and outbox record; publish events asynchronously (no distributed transactions); ensure idempotency keys for retries.
   - **Q2 – Idempotency Key Format**: Apply standard best practices with explicit contracts, idempotency, audit trails, UTC timestamps, scoped RBAC, configurable defaults. Use format: \`{workorderId}-{workorderItemId}-{partUsageEventId}\`.
   - **Q3 – WIP vs COGS Rule Source**: Configurable via policy; Accounting consumes events with policy decision included in payload.

   Conflict review for Origin #158: Atomicity guarantees and idempotency keys are clearly specified. No unresolved conflicts detected.

   ---

   **Story Updated:** #158
   **Status:** Removed \`blocked:clarification\`, added \`status:needs-review\`
   **Next Steps:** Domain agent review (agent:workexec) and technical feasibility assessment"
   ```

2. Close issue #303:
   ```bash
   gh issue close 303 --repo louisburroughs/durion-positivity-backend --reason "completed"
   ```

### Step 5: Verify Application
Run the verification checklist below.

---

## Method 3: Partial Automation

Mix automated and manual steps as needed:

### Option A: Automate Labels, Manual Body Update
```bash
# Update labels automatically
gh issue edit 158 --repo louisburroughs/durion-positivity-backend \
    --remove-label "blocked:clarification" \
    --add-label "status:needs-review"

# Manually update issue body in web interface
gh issue view 158 --repo louisburroughs/durion-positivity-backend --web
```

### Option B: Manual Labels, Automate Clarification Closure
```bash
# Manually update issue #158 body in web interface

# Manually update labels

# Run script for clarification closure only
# (Edit script to skip label updates if needed)
./.story-work/apply-clarification-resolution-158.sh
```

---

## Verification Checklist

After applying updates, verify the following:

### Issue #158 Verification

- [ ] **Business Rules Section Added**
  - [ ] BR-ATOMIC-1: Atomic Transaction Requirement
  - [ ] BR-ASYNC-1: Asynchronous Event Publishing
  - [ ] BR-IDEMPOTENCY-1: Idempotency Key Standard
  - [ ] BR-AUDIT-1: Audit Trail Requirements
  - [ ] BR-POLICY-1: WIP vs COGS Policy Configuration

- [ ] **Data Requirements Section Updated**
  - [ ] PartUsageEvent entity documented with all fields
  - [ ] OutboxEvent entity documented with all fields
  - [ ] AccountingPolicyConfiguration entity documented with all fields

- [ ] **Acceptance Criteria Section Updated**
  - [ ] AC-ATOMIC-1: Single Transaction Guarantees Atomicity
  - [ ] AC-ASYNC-1: Events Published Asynchronously
  - [ ] AC-IDEMPOTENCY-1: Idempotency Keys Prevent Duplicate Processing
  - [ ] AC-AUDIT-1: Complete Audit Trail Captured
  - [ ] AC-POLICY-1: WIP vs COGS Determined by Configured Policy
  - [ ] AC-POLICY-2: Policy Decision Included in InventoryIssued Event

- [ ] **Audit & Observability Section Updated**
  - [ ] PartConsumptionRequested event documented
  - [ ] PartConsumptionTransactionCommitted event documented
  - [ ] PartConsumptionEventPublished event documented
  - [ ] PartConsumptionEventPublishFailed event documented
  - [ ] Metrics documented

- [ ] **Open Questions Section Removed**

- [ ] **Original Story Text Preserved**

- [ ] **Labels Updated**
  - [ ] `blocked:clarification` removed
  - [ ] `status:needs-review` added

### Issue #303 Verification

- [ ] **Resolution Comment Posted**
  - [ ] Summary of Q1 response
  - [ ] Summary of Q2 response
  - [ ] Summary of Q3 response
  - [ ] Link to updated issue #158
  - [ ] Next steps mentioned

- [ ] **Issue Closed**
  - [ ] Status is "completed"
  - [ ] Closed date is recorded

### Content Quality Verification

- [ ] All business rules are traceable to clarification responses
- [ ] Data requirements support the business rules
- [ ] Acceptance criteria are testable
- [ ] No new open questions introduced
- [ ] Audit requirements are comprehensive
- [ ] Domain boundaries respected (workexec authority)

---

## Post-Application Workflow

Once verification is complete, proceed with:

### 1. Domain Agent Review
Request review from agent:workexec to:
- Validate business rules accuracy
- Review data model completeness
- Confirm workflow logic correctness
- Identify any additional considerations

### 2. Technical Feasibility Assessment
Conduct technical review to:
- Verify acceptance criteria are testable
- Assess implementation complexity
- Identify technical dependencies
- Estimate effort and timeline

### 3. Status Promotion Decision

**If Approved:**
```bash
gh issue edit 158 --repo louisburroughs/durion-positivity-backend \
    --remove-label "status:needs-review" \
    --add-label "status:ready-for-dev"
```

**If Issues Found:**
- Document blockers in issue comments
- Add appropriate blocking labels
- Reopen clarification if needed
- Update story as necessary

### 4. Development Planning

Once `status:ready-for-dev`:
- Break story into implementation tasks
- Design transaction processing logic
- Implement transactional outbox pattern
- Build policy configuration system
- Create data model migrations
- Write unit and integration tests

---

## Troubleshooting

### Problem: GitHub CLI Authentication Failed
**Symptoms:** `gh auth status` fails or shows "not logged in"

**Solution:**
```bash
gh auth login
# Follow interactive prompts
# Choose HTTPS or SSH protocol
# Authenticate via browser or token
```

### Problem: Label Does Not Exist
**Symptoms:** Error when adding/removing labels

**Solution:**
1. Check if labels exist in repository:
   ```bash
   gh label list --repo louisburroughs/durion-positivity-backend
   ```

2. Create missing labels:
   ```bash
   gh label create "status:needs-review" --repo louisburroughs/durion-positivity-backend
   gh label create "blocked:clarification" --repo louisburroughs/durion-positivity-backend
   ```

### Problem: Cannot Fetch Issue
**Symptoms:** `gh issue view` fails

**Solution:**
1. Verify repository name:
   ```bash
   gh repo view louisburroughs/durion-positivity-backend
   ```

2. Verify issue number exists:
   ```bash
   gh issue list --repo louisburroughs/durion-positivity-backend | grep 158
   ```

3. Check access permissions

### Problem: Issue Body Too Large
**Symptoms:** Update fails due to size limit

**Solution:**
- Consider breaking the story into multiple smaller stories
- Move detailed specifications to linked documents
- Use issue comments for supplementary information

### Problem: Merge Conflict in Issue Body
**Symptoms:** Issue has been updated since clarification was requested

**Solution:**
1. Backup current issue body
2. Manually merge changes from `issue-158-update-summary.md`
3. Preserve all recent updates
4. Verify no information is lost

---

## Support and Escalation

### For Technical Issues
- Review `.story-work/README-158.md`
- Check `.story-work/COMPLETION-SUMMARY-158.md`
- Consult `.github/agents/story-authoring.agent.md`

### For Process Questions
- Review Story Authoring Agent protocol
- Check domain sub-contracts (Section 13.7 for workexec)
- Escalate to story authoring agent owner

### For Business Decisions
- Identify missing business rules
- Create new clarification issue if needed
- Escalate to business decision-makers

---

## Quick Reference Commands

### View Issue
```bash
gh issue view 158 --repo louisburroughs/durion-positivity-backend
```

### Edit Issue in Browser
```bash
gh issue view 158 --repo louisburroughs/durion-positivity-backend --web
```

### Update Labels
```bash
gh issue edit 158 --repo louisburroughs/durion-positivity-backend \
    --remove-label "blocked:clarification" \
    --add-label "status:needs-review"
```

### Post Comment
```bash
gh issue comment 303 --repo louisburroughs/durion-positivity-backend \
    --body "Your comment here"
```

### Close Issue
```bash
gh issue close 303 --repo louisburroughs/durion-positivity-backend \
    --reason "completed"
```

---

**Next:** After completing these steps, proceed to domain agent review and technical feasibility assessment.

**Status**: Ready for Application  
**Last Updated**: 2026-01-11T10:46:57Z
