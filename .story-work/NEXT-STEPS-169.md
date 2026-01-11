# Clarification Resolution: Next Steps

## Summary
Clarification artifacts have been created for resolving issue #305 and updating origin story issue #169. This document explains what has been prepared and what needs to be done to complete the integration.

## What Was Done

### 1. Artifacts Created
All necessary artifacts for applying the clarification response have been prepared:

- **`.story-work/issue-169-update-summary.md`** (273 lines)
  - Complete summary of changes to be applied
  - Detailed content for all new business rules
  - Complete data model specifications
  - All acceptance criteria with Given/When/Then format
  - Step-by-step integration instructions

- **`.story-work/apply-clarification-resolution-169.sh`** (155 lines, executable)
  - Automated script for applying changes to GitHub
  - Handles issue body updates
  - Manages label changes
  - Adds resolution comments
  - Closes clarification issue #305

- **`.story-work/clarification-resolution-metadata-169.json`** (212 lines)
  - Machine-readable metadata
  - Complete decision tracking
  - Impact analysis
  - Validation status
  - Workflow state

- **`.story-work/README-169.md`** (178 lines)
  - Complete workflow documentation
  - Integration steps
  - Troubleshooting guide
  - Verification checklist

### 2. Clarification Response Interpreted

**Original Question (from issue #305)**:
> If required legal terms and disclaimers are not configured in the system, should the summary generation fail with an error (as assumed in this story), or should it proceed with a warning/default text?

**Decision Received**:
> Use immutable snapshots; if legal terms are missing, handle gracefully per policy (fail or use defaults) with clear documentation.

**Our Interpretation**:
1. **Immutable Snapshots**: All estimate summaries MUST use immutable snapshots
2. **Configurable Policy**: System SHALL support two modes:
   - `FAIL` (safe default): Prevent generation if legal terms missing
   - `USE_DEFAULTS`: Generate with system default legal terms
3. **Clear Documentation**: Policy choice and legal terms source MUST be captured in audit metadata
4. **Flexibility**: Policy configurable per shop location or globally

### 3. Changes Prepared

#### Business Rules (3 new):
- **BR-SNAPSHOT-1**: Immutable Snapshot Requirement
- **BR-LEGAL-1**: Legal Terms Policy Configuration
- **BR-LEGAL-2**: Missing Legal Terms Handling

#### Data Model (3 new entities):
- **EstimateSummarySnapshot**: Immutable estimate snapshots with audit metadata
- **LegalTermsConfiguration**: Legal terms versioning and management
- **MissingLegalTermsPolicy**: Policy configuration per location

#### Acceptance Criteria (6 new):
- **AC-SNAPSHOT-1**: Immutable Snapshot Created
- **AC-LEGAL-1**: Legal Terms Included When Configured
- **AC-LEGAL-2**: Policy Mode - FAIL (Safe Default)
- **AC-LEGAL-3**: Policy Mode - USE_DEFAULTS
- **AC-LEGAL-4**: Policy Configuration
- **AC-LEGAL-5**: Audit Trail Captured

## What Needs to Be Done

### Option A: Automated Application (Requires GitHub CLI)

If you have GitHub CLI (`gh`) installed and authenticated with write access:

```bash
# From repository root
cd /home/runner/work/durion-positivity-backend/durion-positivity-backend

# Run the application script
./.story-work/apply-clarification-resolution-169.sh
```

**The script will**:
1. Fetch the current issue #169 body
2. Prompt for manual integration (since we don't have the original story text)
3. Update issue #169 on GitHub
4. Update labels (remove `blocked:clarification`, add `status:needs-review`)
5. Add resolution comment
6. Close issue #305

**Prerequisites**:
- GitHub CLI authenticated: `gh auth status`
- Write access to the repository
- Access to view issue #169 content

### Option B: Manual Application (If GitHub CLI Not Available)

1. **Access Issue #169**:
   - Go to https://github.com/louisburroughs/durion-positivity-backend/issues/169
   - Copy the entire issue body

2. **Apply Changes**:
   - Open `.story-work/issue-169-update-summary.md`
   - Follow the "Specific Changes to Story Sections" guidance
   - Integrate all business rules, data requirements, and acceptance criteria
   - Remove "Open Questions" section
   - Remove "STOP: Clarification required" line (if present)
   - Ensure original story is preserved at the bottom

3. **Update Issue #169**:
   - Paste the updated content back to issue #169
   - Remove label: `blocked:clarification`
   - Add label: `status:needs-review`
   - Add comment (see template in `issue-169-update-summary.md`)

4. **Close Issue #305**:
   - Go to https://github.com/louisburroughs/durion-positivity-backend/issues/305
   - Close with reason "completed"
   - Add comment (see template in `issue-169-update-summary.md`)

### Option C: Partial Automation (Fetch Only)

If you can fetch but not update:

```bash
# Fetch current issue
gh issue view 169 -R louisburroughs/durion-positivity-backend \
  --json body -q .body > /tmp/issue-169-current.md

# Review and edit
# Apply changes from .story-work/issue-169-update-summary.md
# Save result to /tmp/issue-169-updated.md

# Then manually paste to GitHub or use:
gh issue edit 169 -R louisburroughs/durion-positivity-backend \
  --body-file /tmp/issue-169-updated.md
```

## Verification Checklist

After application, verify:

- [ ] Issue #169 body updated with all new content
- [ ] Business Rules BR-SNAPSHOT-1, BR-LEGAL-1, BR-LEGAL-2 added
- [ ] Data Requirements include all 3 new entities with complete schemas
- [ ] Acceptance Criteria AC-SNAPSHOT-1 through AC-LEGAL-5 added
- [ ] "Open Questions" section removed
- [ ] Original story preserved at bottom (MANDATORY)
- [ ] Label `blocked:clarification` removed from issue #169
- [ ] Label `status:needs-review` added to issue #169
- [ ] Resolution comment added to issue #169
- [ ] Issue #305 closed with completion comment

## Next Steps After Application

1. **Domain Agent Review** (agent:workexec)
   - Validate business rules correctness
   - Review data model completeness
   - Confirm workflow logic

2. **Technical Review**
   - Confirm acceptance criteria are testable
   - Review data model for implementation feasibility
   - Identify any technical dependencies

3. **Status Promotion**
   - If approved: Change label to `status:ready-for-dev`
   - If issues found: Add blocking labels and reopen clarification

4. **Development Planning**
   - Estimate implementation effort
   - Plan data model implementation
   - Design snapshot creation logic
   - Implement policy configuration

## Important Notes

### Why Manual Integration May Be Required
We created these artifacts without direct access to the current issue #169 body because:
- GitHub API token not available in this execution context
- Issue #169 content not in current exports
- Story Authoring Agent requires preserving original story text verbatim

### Key Requirements (NON-NEGOTIABLE)
1. **Original Story Preservation**: The complete, unmodified original story MUST be included at the bottom of the updated issue
2. **Story Structure**: Follow Story Authoring Agent contract with all mandatory sections
3. **Audit Trail**: Maintain complete traceability from clarification to integration

### If You Encounter Issues
- Review `.story-work/README-169.md` for detailed workflow documentation
- Check `.story-work/issue-169-update-summary.md` for exact content to add
- Consult `.story-work/clarification-resolution-metadata-169.json` for impact analysis
- Refer to `.github/agents/story-authoring.agent.md` for story structure requirements

## Contact & Escalation

If you need assistance:
1. Review the Story Authoring Agent protocol
2. Consult the workexec domain contract
3. Escalate unresolved questions to business decision-makers
4. Do not proceed with unsafe assumptions

---

**Prepared by**: Story Authoring Agent Integration
**Date**: 2026-01-11T10:38:00Z
**Artifacts Location**: `.story-work/` directory
**Ready for**: Human review and GitHub application
