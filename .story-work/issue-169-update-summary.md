# Issue #169 Update Summary

## Task
Update GitHub issue #169 with clarification responses from issue #305, per Story Authoring Agent protocol.

## Clarification Responses (from Issue #305)

### Question 1: Missing Terms & Conditions Policy
**Question:** If required legal terms and disclaimers are not configured in the system, should the summary generation fail with an error (as assumed in this story), or should it proceed with a warning/default text?

**Decision:** Use immutable snapshots; if legal terms are missing, handle gracefully per policy (fail or use defaults) with clear documentation.

**Interpretation:**
- The system SHALL use immutable snapshots of estimate data when generating summaries
- Legal terms and disclaimers SHALL be captured as part of the snapshot
- The handling of missing legal terms SHALL be configurable per business policy
- Policy options include:
  - **Fail** (safe default): Prevent summary generation if legal terms are not configured
  - **Use defaults**: Generate summary with system-configured default legal terms
- The chosen policy MUST be clearly documented and configurable
- All snapshots MUST include timestamp and configuration state for audit purposes

## Actions Required

### 1. Update Issue #169 Body

Key changes to be made:
- **Remove** "STOP: Clarification required before finalization" line (if present)
- **Remove** "Open Questions" section
- **Update Business Rules** to include:
  - BR: Immutable Snapshot Requirement
  - BR: Legal Terms Policy Configuration
  - BR: Missing Legal Terms Handling
- **Update Functional Behavior** to specify:
  - Snapshot creation process
  - Legal terms validation and inclusion
  - Policy-based error handling
- **Update Data Requirements** to include:
  - EstimateSummarySnapshot entity/structure
  - Legal terms configuration storage
  - Policy configuration fields
- **Update Acceptance Criteria** to add:
  - AC: Immutable snapshot created with all required data
  - AC: Legal terms included in snapshot when configured
  - AC: Configurable policy for missing legal terms (fail vs. default)
  - AC: Clear error message when policy is "fail" and terms are missing
  - AC: Default legal terms applied when policy is "use defaults"
  - AC: Snapshot includes configuration metadata for audit trail
- **Update Audit & Observability** to capture:
  - Snapshot creation events
  - Legal terms inclusion/default application
  - Policy enforcement decisions

### 2. Update Issue #169 Labels

**Remove:**
- `blocked:clarification`

**Add:**
- `status:needs-review`

### 3. Close Issue #305
Mark as resolved since clarification has been provided and integrated.

### 4. Add Comment to Issue #169
Add a comment linking to issue #305 and noting that clarifications have been resolved and integrated.

## How to Apply Updates

### Option 1: Using apply-clarification-resolution.sh script
```bash
cd /home/runner/work/durion-positivity-backend/durion-positivity-backend
./.story-work/apply-clarification-resolution-169.sh
```

### Option 2: Using gh CLI directly
```bash
# First, fetch the current issue body
gh issue view 169 -R louisburroughs/durion-positivity-backend --json body -q .body > /tmp/issue-169-current.md

# Manual edit required: Apply the changes described above to /tmp/issue-169-current.md
# Then update the issue:

gh issue edit 169 -R louisburroughs/durion-positivity-backend \
  --body-file /tmp/issue-169-updated.md

# Update labels
gh issue edit 169 -R louisburroughs/durion-positivity-backend \
  --remove-label "blocked:clarification" \
  --add-label "status:needs-review"

# Add comment
gh issue comment 169 -R louisburroughs/durion-positivity-backend \
  --body "## Clarification Resolution Complete

All clarification questions from issue #305 have been reviewed and integrated into this story.

### Decision Integrated:
**Missing Terms & Conditions Policy**: Use immutable snapshots; if legal terms are missing, handle gracefully per configurable policy (fail or use defaults) with clear documentation.

### Story Updates:
- Business Rule added: Immutable Snapshot Requirement
- Business Rule added: Legal Terms Policy Configuration  
- Business Rule added: Missing Legal Terms Handling
- Functional Behavior updated to specify snapshot creation and policy enforcement
- Data Requirements extended with EstimateSummarySnapshot structure and policy configuration
- Acceptance Criteria added for snapshot creation, legal terms handling, and policy configuration
- Audit & Observability updated for snapshot and policy events

**Status**: Ready for domain agent review and development planning.

---
*Updated by Story Authoring Agent - $(date -u +'%Y-%m-%dT%H:%M:%SZ')*"

# Close clarification issue
gh issue close 305 -R louisburroughs/durion-positivity-backend \
  --reason completed \
  --comment "## Clarification Complete

Clarification has been provided and successfully integrated into the origin story (issue #169).

The Story Authoring Agent will update the origin story with:
- Immutable snapshot requirement for estimate summaries
- Configurable policy for handling missing legal terms
- Clear documentation of snapshot structure and audit requirements

The origin story is now unblocked and ready for review.

---
*Closed by Story Authoring Agent - $(date -u +'%Y-%m-%dT%H:%M:%SZ')*"
```

## Specific Changes to Story Sections

### Business Rules (Add)
```markdown
**BR-SNAPSHOT-1: Immutable Snapshot Requirement**
- When an estimate summary is generated for review, the system SHALL create an immutable snapshot of:
  - All estimate line items and pricing
  - Customer information
  - Vehicle information
  - Legal terms and disclaimers (if configured)
  - Configuration state and policy settings
  - Generation timestamp and generating user
- The snapshot SHALL NOT be modified after creation
- The snapshot SHALL be used as the authoritative source for the presented estimate

**BR-LEGAL-1: Legal Terms Policy Configuration**
- The system SHALL support a configurable policy for handling missing legal terms
- Available policy options:
  - `FAIL`: Prevent estimate summary generation if legal terms are not configured (safe default)
  - `USE_DEFAULTS`: Generate estimate summary with system-configured default legal terms
- The policy SHALL be configurable per shop location or globally
- The active policy SHALL be captured in the estimate snapshot for audit purposes

**BR-LEGAL-2: Missing Legal Terms Handling**
- When policy is `FAIL` and legal terms are not configured:
  - System SHALL return error: "Cannot generate estimate summary: Legal terms and conditions not configured"
  - System SHALL log the configuration error
  - System SHALL NOT create an estimate snapshot
- When policy is `USE_DEFAULTS` and legal terms are not configured:
  - System SHALL use the system default legal terms
  - System SHALL log a warning indicating default terms were used
  - System SHALL include policy decision in snapshot metadata
- When legal terms are configured:
  - System SHALL use the configured legal terms regardless of policy
  - System SHALL include the source and version of legal terms in the snapshot
```

### Data Requirements (Add)
```markdown
**EstimateSummarySnapshot**
- `snapshotId` (UUID, PK, not null) - Unique identifier for the snapshot
- `estimateId` (UUID, FK to Estimate, not null) - Reference to source estimate
- `snapshotTimestamp` (TIMESTAMP, not null) - When snapshot was created
- `snapshotData` (JSONB, not null) - Complete estimate data including line items, pricing, customer, vehicle
- `legalTermsSource` (VARCHAR(50), nullable) - Source of legal terms: 'CONFIGURED', 'DEFAULT', or null if missing
- `legalTermsVersion` (VARCHAR(50), nullable) - Version identifier of legal terms used
- `legalTermsText` (TEXT, nullable) - Full text of legal terms included in summary
- `policyMode` (VARCHAR(20), not null) - Active policy: 'FAIL' or 'USE_DEFAULTS'
- `createdBy` (UUID, FK to User, not null) - User who generated the summary
- `auditMetadata` (JSONB, not null) - Additional audit data (IP, user agent, shop location, etc.)

**LegalTermsConfiguration**
- `configId` (UUID, PK, not null) - Unique identifier
- `shopLocationId` (UUID, FK to Location, nullable) - Specific shop location (null = global)
- `termsText` (TEXT, not null) - Legal terms and disclaimers content
- `termsVersion` (VARCHAR(50), not null) - Version identifier
- `effectiveDate` (DATE, not null) - When these terms become effective
- `expirationDate` (DATE, nullable) - When these terms expire (null = no expiration)
- `isDefault` (BOOLEAN, not null, default false) - Whether this is the system default
- `createdAt` (TIMESTAMP, not null) - Creation timestamp
- `createdBy` (UUID, FK to User, not null) - User who created the configuration

**MissingLegalTermsPolicy**
- `policyId` (UUID, PK, not null) - Unique identifier
- `shopLocationId` (UUID, FK to Location, nullable) - Specific shop location (null = global)
- `policyMode` (VARCHAR(20), not null) - 'FAIL' or 'USE_DEFAULTS'
- `effectiveDate` (DATE, not null) - When this policy becomes effective
- `updatedAt` (TIMESTAMP, not null) - Last update timestamp
- `updatedBy` (UUID, FK to User, not null) - User who last updated the policy
```

### Acceptance Criteria (Add)
```markdown
**AC-SNAPSHOT-1: Immutable Snapshot Created**
- Given an estimate is ready for customer review
- When the service advisor generates an estimate summary
- Then the system SHALL create an immutable snapshot containing all estimate data
- And the snapshot SHALL include a unique snapshotId and timestamp
- And the snapshot SHALL be stored before presenting to the customer

**AC-LEGAL-1: Legal Terms Included When Configured**
- Given legal terms are configured for the shop location
- When an estimate summary is generated
- Then the system SHALL include the configured legal terms in the snapshot
- And the snapshot SHALL record legalTermsSource as 'CONFIGURED'
- And the snapshot SHALL include the termsVersion identifier

**AC-LEGAL-2: Policy Mode - FAIL (Safe Default)**
- Given the missing legal terms policy is set to 'FAIL'
- And legal terms are NOT configured for the shop location
- When an estimate summary generation is attempted
- Then the system SHALL prevent the generation
- And return error message: "Cannot generate estimate summary: Legal terms and conditions not configured"
- And log a configuration error event
- And NOT create an estimate snapshot

**AC-LEGAL-3: Policy Mode - USE_DEFAULTS**
- Given the missing legal terms policy is set to 'USE_DEFAULTS'
- And legal terms are NOT configured for the shop location
- When an estimate summary is generated
- Then the system SHALL use the system default legal terms
- And the snapshot SHALL record legalTermsSource as 'DEFAULT'
- And log a warning: "Estimate summary generated with default legal terms"
- And include the policy decision in snapshot metadata

**AC-LEGAL-4: Policy Configuration**
- Given an administrator is configuring missing legal terms policy
- When they set the policy mode for a shop location
- Then the system SHALL validate the mode is either 'FAIL' or 'USE_DEFAULTS'
- And store the policy with effectiveDate and updatedBy
- And the new policy SHALL be used for all subsequent estimate summaries at that location

**AC-LEGAL-5: Audit Trail Captured**
- Given any estimate summary is generated
- When the snapshot is created
- Then the system SHALL capture in auditMetadata:
  - Active policy mode
  - Legal terms source and version
  - Generating user, timestamp, and shop location
  - IP address and user agent (if available)
- And this metadata SHALL be immutable after snapshot creation
```

## Verification Steps
1. Verify issue #169 body has been updated with all clarified policies
2. Verify "Open Questions" section has been removed
3. Verify labels have been updated correctly
4. Verify issue #305 is closed
5. Verify comment linking the two issues has been added

## Status
- [x] Update summary prepared
- [ ] Issue #169 body updated (requires GitHub API access and current issue content)
- [ ] Issue #169 labels updated (requires GitHub API access)
- [ ] Issue #305 closed (requires GitHub API access)
- [ ] Comment added to issue #169 (requires GitHub API access)

## Notes
- This update requires access to the current issue #169 body to properly integrate changes
- The story structure should follow the Story Authoring Agent contract with all mandatory sections
- Original story text MUST be preserved verbatim at the bottom of the updated issue
