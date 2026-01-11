# README - Clarification Resolution for Issue #155

## Overview
This directory contains complete documentation and automation for resolving clarification issue #302 and updating origin story issue #155.

**Origin Story**: [#155 - [BACKEND] [STORY] Execution: Apply Role-Based Visibility in Execution UI](https://github.com/louisburroughs/durion-positivity-backend/issues/155)  
**Clarification Issue**: [#302 - Missing visibility policy details](https://github.com/louisburroughs/durion-positivity-backend/issues/302)  
**Domain**: workexec  
**Date Resolved**: 2026-01-11

## What Was Clarified

Three critical questions were answered:

### 1. Policy Source of Truth
**Question**: What is the authoritative source for VisibilityPolicy data?  
**Decision**: Security/Policy service is authoritative; domain services enforce server-side and cache with short TTL + invalidation events.

### 2. Field Granularity
**Question**: Is the policy configurable per-field or per-role flag?  
**Decision**: Configurable; use explicit RBAC scopes (domain:resource:action) decoupled from role names.

### 3. API Strategy
**Question**: Single endpoint or role-specific endpoints?  
**Decision**: Use standard best practices with explicit contracts, idempotency, audit trails, UTC timestamps, scoped RBAC, configurable defaults.

## Artifacts Included

### 1. **issue-155-update-summary.md** (Primary Reference)
Complete integration guidance including:
- Full clarification responses and interpretations
- 6 new Business Rules to add
- 3 new Data Requirements (entities)
- 8 new Acceptance Criteria in Given/When/Then format
- Section-by-section update instructions

### 2. **clarification-resolution-metadata-155.json** (Machine-Readable)
Structured metadata for automation including:
- Complete decision tracking with interpretation
- Impact analysis per question
- Business rules, data requirements, and AC mappings
- Validation status and next steps

### 3. **apply-clarification-resolution-155.sh** (Automation Script)
Executable shell script that:
- Fetches current issue #155 body
- Prompts for manual updates (with checklist)
- Updates issue body, labels, and comments
- Closes clarification issue #302
- Logs all actions for audit

### 4. **README-155.md** (This File)
Workflow documentation and quick start guide.

### 5. **NEXT-STEPS-155.md**
Detailed next steps guide including:
- Three application options (automated, manual, partial)
- Complete verification checklist
- Post-application workflow
- Troubleshooting guidance

### 6. **COMPLETION-SUMMARY-155.md**
Executive summary with:
- Quick reference table
- Key decisions and implementation impact
- Timeline and status
- Links to related artifacts

## Quick Start

### Option A: Automated Application (Recommended)

1. **Review the clarification responses**:
   ```bash
   cat .story-work/issue-155-update-summary.md
   ```

2. **Run the automation script**:
   ```bash
   cd /home/runner/work/durion-positivity-backend/durion-positivity-backend
   ./.story-work/apply-clarification-resolution-155.sh
   ```
   
3. **Follow the prompts**:
   - The script will fetch the current issue body
   - You'll manually apply updates to `/tmp/issue-155-updated.md`
   - The script will update the issue, labels, and comments
   - The script will close the clarification issue

4. **Verify the updates**:
   - Check issue #155 on GitHub
   - Verify all Business Rules, Data Requirements, and Acceptance Criteria were added
   - Confirm labels updated (blocked:clarification removed, status:needs-review added)

### Option B: Manual Application

If you prefer to apply updates manually:

1. **Read the integration guide**:
   ```bash
   cat .story-work/issue-155-update-summary.md
   ```

2. **Fetch current issue body**:
   ```bash
   gh issue view 155 -R louisburroughs/durion-positivity-backend --json body -q .body > /tmp/issue-155-current.md
   ```

3. **Apply updates** using the guidance in `issue-155-update-summary.md`:
   - Remove "Open Questions" section
   - Add 6 Business Rules
   - Add 3 Data Requirements
   - Add 8 Acceptance Criteria
   - Update Functional Behavior
   - Update Audit & Observability

4. **Update the issue**:
   ```bash
   gh issue edit 155 -R louisburroughs/durion-positivity-backend --body-file /tmp/issue-155-updated.md
   ```

5. **Update labels**:
   ```bash
   gh issue edit 155 -R louisburroughs/durion-positivity-backend \
     --remove-label "blocked:clarification" \
     --add-label "status:needs-review"
   ```

6. **Add resolution comment** (see script for template)

7. **Close clarification issue**:
   ```bash
   gh issue close 302 -R louisburroughs/durion-positivity-backend \
     --comment "Clarification responses integrated into origin issue #155."
   ```

## Integration Summary

### Business Rules Added (6)
1. **BR-POLICY-1**: Security Service as Authoritative Source
2. **BR-POLICY-2**: Domain Service Enforcement with Caching
3. **BR-RBAC-1**: Explicit RBAC Scope Pattern
4. **BR-RBAC-2**: Role-to-Permission Decoupling
5. **BR-API-1**: Single Endpoint with Dynamic Filtering
6. **BR-API-2**: Audit Trail Requirements

### Data Requirements Added (3)
1. **VisibilityPolicy** (owned by Security service) - 12 fields
2. **VisibilityPolicyCache** (in Workexec domain) - 7 fields
3. **VisibilityAuditEvent** (in Workexec domain) - 11 fields

### Acceptance Criteria Added (8)
1. **AC-POLICY-1**: Security service provides visibility policies
2. **AC-POLICY-2**: Domain service caches policies with short TTL
3. **AC-POLICY-3**: Cache invalidated on policy change events
4. **AC-RBAC-1**: Permission scopes follow domain:resource:action pattern
5. **AC-RBAC-2**: Code checks permission scopes, NOT role names
6. **AC-API-1**: Single endpoint with dynamic DTO filtering
7. **AC-API-2**: HTTP 403 when minimum permissions not met
8. **AC-API-3**: Partial DTO returned with accessible fields only
9. **AC-AUDIT-1**: Audit trail captures full context

## Verification Checklist

After applying updates, verify:
- [ ] Issue #155 body includes all 6 Business Rules
- [ ] Issue #155 body includes all 3 Data Requirements
- [ ] Issue #155 body includes all 8 Acceptance Criteria
- [ ] "Open Questions" section removed from issue #155
- [ ] Label `blocked:clarification` removed from issue #155
- [ ] Label `status:needs-review` added to issue #155
- [ ] Resolution comment added to issue #155
- [ ] Issue #302 closed with resolution comment
- [ ] All decisions are clearly documented and traceable

## Next Steps After Application

1. **Technical Design Phase**
   - Define Security service API contract for visibility policies
   - Design policy cache implementation with TTL and event-based invalidation
   - Specify RBAC scope structure and naming conventions

2. **Implementation Planning**
   - Create implementation tasks for each Business Rule
   - Define test cases for each Acceptance Criterion
   - Plan Security service integration

3. **Security Service Coordination**
   - Engage Security domain team to design VisibilityPolicy API
   - Define policy change event schema
   - Establish cache invalidation protocol

4. **Test Case Development**
   - Write unit tests for permission scope validation
   - Write integration tests for policy caching and invalidation
   - Write end-to-end tests for dynamic DTO filtering

## Troubleshooting

### Issue: Script fails with authentication error
**Solution**: Ensure you're authenticated with GitHub CLI:
```bash
gh auth status
gh auth login  # if not authenticated
```

### Issue: Manual updates are unclear
**Solution**: Refer to `issue-155-update-summary.md` which provides:
- Full text for each Business Rule
- Complete entity definitions for Data Requirements
- Given/When/Then format for each Acceptance Criterion

### Issue: Need to revert changes
**Solution**: The script saves the current issue body to `/tmp/issue-155-current.md` before making changes. You can restore it with:
```bash
gh issue edit 155 -R louisburroughs/durion-positivity-backend --body-file /tmp/issue-155-current.md
```

## Related Documentation

- **Architecture**: See `.github/docs/architecture/` for system design
- **Security Domain**: See `pos-security-service/` for Security service implementation
- **Workexec Domain**: See `pos-work-order/` for Workexec service implementation
- **Agent Framework**: See `pos-agent-framework/` for agent coordination

## Support

For questions or issues with this clarification resolution:
1. Review `NEXT-STEPS-155.md` for detailed guidance
2. Check `COMPLETION-SUMMARY-155.md` for executive overview
3. Consult the Story Authoring Agent protocol documentation
4. Reach out to the Workexec or Security domain teams

---

**Status**: Ready for Application  
**Last Updated**: 2026-01-11T10:48:00Z  
**Agent**: story-authoring
