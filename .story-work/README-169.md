# Clarification Resolution Workflow for Issue #169

## Overview
This directory contains artifacts for resolving clarification issue #305 and updating origin story issue #169.

## Problem Statement
- **Origin Issue**: #169 - [BACKEND] [STORY] Estimate: Present Estimate Summary for Review
- **Clarification Issue**: #305 - Missing Terms & Conditions Policy clarification
- **Domain**: workexec
- **Status**: Clarification response received, ready for integration

## Clarification Response Summary
**Question**: If required legal terms and disclaimers are not configured in the system, should the summary generation fail with an error, or should it proceed with a warning/default text?

**Decision**: Use immutable snapshots; if legal terms are missing, handle gracefully per policy (fail or use defaults) with clear documentation.

## Files in This Directory

### 1. `issue-169-update-summary.md`
Human-readable summary of all changes to be applied to issue #169, including:
- Clarification decision and interpretation
- Specific changes to each story section
- Business rules to be added
- Data model changes
- Acceptance criteria additions
- Instructions for manual or automated application

### 2. `apply-clarification-resolution-169.sh`
Executable bash script that:
- Fetches the current issue #169 body
- Guides manual integration of changes
- Updates the issue body on GitHub
- Updates issue labels
- Adds resolution comment
- Closes clarification issue #305

**Usage**:
```bash
cd /home/runner/work/durion-positivity-backend/durion-positivity-backend
./.story-work/apply-clarification-resolution-169.sh
```

**Prerequisites**:
- GitHub CLI (`gh`) installed and authenticated
- Write access to the repository
- Current issue #169 body content

### 3. `clarification-resolution-metadata-169.json`
Machine-readable metadata including:
- Complete clarification question and decision
- Impact analysis
- Data model changes
- Label changes
- Validation status
- Next steps for workflow

## Integration Steps

### Step 1: Fetch Current Issue (Manual if needed)
If you have GitHub CLI access:
```bash
gh issue view 169 -R louisburroughs/durion-positivity-backend --json body -q .body > /tmp/issue-169-current.md
```

### Step 2: Apply Changes
The following changes must be integrated into the story:

#### A. Remove Blocking Elements
- Remove "STOP: Clarification required" line (if present)
- Remove "Open Questions" section

#### B. Add Business Rules
```markdown
**BR-SNAPSHOT-1: Immutable Snapshot Requirement**
[See issue-169-update-summary.md for full text]

**BR-LEGAL-1: Legal Terms Policy Configuration**
[See issue-169-update-summary.md for full text]

**BR-LEGAL-2: Missing Legal Terms Handling**
[See issue-169-update-summary.md for full text]
```

#### C. Update Functional Behavior
Add description of snapshot creation process and policy enforcement

#### D. Add Data Requirements
- EstimateSummarySnapshot entity
- LegalTermsConfiguration entity
- MissingLegalTermsPolicy entity

#### E. Add Acceptance Criteria
- AC-SNAPSHOT-1: Immutable Snapshot Created
- AC-LEGAL-1: Legal Terms Included When Configured
- AC-LEGAL-2: Policy Mode - FAIL
- AC-LEGAL-3: Policy Mode - USE_DEFAULTS
- AC-LEGAL-4: Policy Configuration
- AC-LEGAL-5: Audit Trail Captured

#### F. Update Audit & Observability
Add events for snapshot creation and policy enforcement

### Step 3: Run Application Script
```bash
./.story-work/apply-clarification-resolution-169.sh
```

The script will:
1. Fetch current issue body
2. Guide you through manual changes
3. Update GitHub issue #169
4. Update labels (remove blocked:clarification, add status:needs-review)
5. Add resolution comment
6. Close issue #305

### Step 4: Verification
After application:
- [ ] Issue #169 body contains all new business rules
- [ ] Issue #169 body contains all new data requirements
- [ ] Issue #169 body contains all new acceptance criteria
- [ ] "Open Questions" section is removed
- [ ] "blocked:clarification" label is removed
- [ ] "status:needs-review" label is added
- [ ] Resolution comment is added to issue #169
- [ ] Issue #305 is closed with completion comment

### Step 5: Domain Review
- Issue should be reviewed by workexec domain agent
- After approval, update label to "status:ready-for-dev"

## Key Decisions Made

### Immutable Snapshots
- All estimate summaries use immutable snapshots
- Snapshots cannot be modified after creation
- Complete audit trail captured in snapshot metadata

### Configurable Policy
- Two policy modes: FAIL (safe default) and USE_DEFAULTS
- Policy configurable per shop location or globally
- Policy decision captured in every snapshot

### Data Model
- Three new entities added for comprehensive management
- Full versioning support for legal terms
- Complete audit metadata for compliance

## Dependencies
- GitHub CLI (`gh`) with authentication
- Write access to louisburroughs/durion-positivity-backend
- Understanding of Story Authoring Agent structure

## Troubleshooting

### Can't Fetch Issue #169
If GitHub CLI is not available or not authenticated:
1. Manually access https://github.com/louisburroughs/durion-positivity-backend/issues/169
2. Copy the issue body
3. Save to `/tmp/issue-169-current.md`
4. Apply changes manually using the guide in `issue-169-update-summary.md`

### Can't Update Issue via Script
If the script fails:
1. Apply changes manually to issue #169 through GitHub web interface
2. Manually update labels
3. Manually add comment
4. Manually close issue #305

## Contact
For questions about this clarification resolution:
- Review the Story Authoring Agent protocol in `.github/agents/story-authoring.agent.md`
- Review the workexec domain contract
- Escalate unresolved conflicts to human decision-makers

## Timestamp
- Clarification Issue Created: 2026-01-06T02:49:16Z
- Clarification Response Received: 2026-01-06 (from user comment)
- Integration Artifacts Created: 2026-01-11T10:33:00Z
