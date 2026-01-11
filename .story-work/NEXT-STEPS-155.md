# Next Steps - Issue #155 Clarification Resolution

## Current Status
✅ **Clarification Complete** - All questions from issue #302 have been answered and documented.

📋 **Ready for Application** - Documentation and automation artifacts are prepared for integration into issue #155.

## Three Application Options

Choose the approach that best fits your workflow:

### Option 1: Fully Automated (Recommended for Speed)

**Time**: ~5 minutes  
**Complexity**: Low  
**Best for**: Quick integration, minimal manual editing

#### Steps:
1. Review the clarification decisions:
   ```bash
   cat .story-work/issue-155-update-summary.md | less
   ```

2. Run the automation script:
   ```bash
   ./.story-work/apply-clarification-resolution-155.sh
   ```

3. When prompted, manually apply updates to `/tmp/issue-155-updated.md`:
   - The script provides a checklist of updates to make
   - Refer to `issue-155-update-summary.md` for full text
   - Save the updated content

4. Verify the results on GitHub:
   - Check issue #155 for all updates
   - Verify labels changed
   - Confirm clarification issue #302 is closed

### Option 2: Manual with Guidance (Recommended for Precision)

**Time**: ~15 minutes  
**Complexity**: Medium  
**Best for**: Careful review, custom modifications

#### Steps:
1. Open the integration guide:
   ```bash
   cat .story-work/issue-155-update-summary.md
   ```

2. Fetch the current issue body:
   ```bash
   gh issue view 155 -R louisburroughs/durion-positivity-backend --json body -q .body > /tmp/issue-155-current.md
   ```

3. Create a copy to edit:
   ```bash
   cp /tmp/issue-155-current.md /tmp/issue-155-updated.md
   ```

4. Apply updates manually using your editor:
   ```bash
   # Open in your preferred editor
   vim /tmp/issue-155-updated.md
   # or
   code /tmp/issue-155-updated.md
   ```

5. Follow the section-by-section guidance in `issue-155-update-summary.md`:
   - **Remove**: "Open Questions" section
   - **Add**: 6 Business Rules (full text provided)
   - **Add**: 3 Data Requirements (entity definitions provided)
   - **Add**: 8 Acceptance Criteria (Given/When/Then format provided)
   - **Update**: Functional Behavior section (guidance provided)
   - **Update**: Audit & Observability section (guidance provided)

6. Update the issue on GitHub:
   ```bash
   gh issue edit 155 -R louisburroughs/durion-positivity-backend --body-file /tmp/issue-155-updated.md
   ```

7. Update labels:
   ```bash
   gh issue edit 155 -R louisburroughs/durion-positivity-backend \
     --remove-label "blocked:clarification" \
     --add-label "status:needs-review"
   ```

8. Add resolution comment:
   ```bash
   gh issue comment 155 -R louisburroughs/durion-positivity-backend \
     --body "$(cat <<'EOF'
## ✅ Clarification Resolution Complete

All clarification questions from issue #302 have been reviewed and integrated into this story.

### Decisions Integrated:
**Q1 – Policy Source of Truth**: Security/Policy service is authoritative for permissions and visibility rules; domain services enforce server-side and cache with short TTL + invalidation events.

**Q2 – Field Granularity**: Configurable; use explicit RBAC scopes (domain:resource:action) decoupled from role names.

**Q3 – API Strategy**: Use standard best practices with explicit contracts, idempotency, audit trails, UTC timestamps, scoped RBAC, configurable defaults.

### Impact:
- 6 new Business Rules added
- 3 new Data Requirements
- 8 new Acceptance Criteria
- Updated Functional Behavior and Audit & Observability

Story is now ready for implementation planning and technical design.
EOF
   )"
   ```

9. Close the clarification issue:
   ```bash
   gh issue close 302 -R louisburroughs/durion-positivity-backend \
     --comment "Clarification responses have been integrated into origin issue #155. All questions resolved."
   ```

### Option 3: Review Only (For Stakeholder Approval)

**Time**: ~10 minutes  
**Complexity**: Low  
**Best for**: Review cycles, approval workflows

#### Steps:
1. Review all artifacts:
   ```bash
   # Executive summary
   cat .story-work/COMPLETION-SUMMARY-155.md
   
   # Detailed decisions
   cat .story-work/issue-155-update-summary.md
   
   # Machine-readable metadata
   cat .story-work/clarification-resolution-metadata-155.json | jq
   ```

2. Review the impact:
   - 6 Business Rules defined
   - 3 Data Requirements specified
   - 8 Acceptance Criteria in testable format

3. **Approve or Request Changes**:
   - If approved: Proceed with Option 1 or Option 2
   - If changes needed: Document required changes and re-engage clarification process

## Post-Application Workflow

After applying the clarification resolution, follow these steps:

### 1. Verification (5 minutes)
Use the verification checklist:

```bash
# Verify issue #155 content
gh issue view 155 -R louisburroughs/durion-positivity-backend

# Check for required sections:
# - [ ] Business Rules section includes BR-POLICY-1, BR-POLICY-2, BR-RBAC-1, BR-RBAC-2, BR-API-1, BR-API-2
# - [ ] Data Requirements section includes VisibilityPolicy, VisibilityPolicyCache, VisibilityAuditEvent
# - [ ] Acceptance Criteria section includes AC-POLICY-1 through AC-AUDIT-1
# - [ ] "Open Questions" section is removed
# - [ ] Functional Behavior updated with policy retrieval and caching
# - [ ] Audit & Observability updated with visibility events

# Verify labels
gh issue view 155 -R louisburroughs/durion-positivity-backend --json labels -q '.labels[].name'
# Should show: status:needs-review (and NOT blocked:clarification)

# Verify clarification issue closed
gh issue view 302 -R louisburroughs/durion-positivity-backend --json state -q .state
# Should show: CLOSED
```

### 2. Technical Design Phase (1-2 days)
Now that the story is clarified, begin technical design:

#### Security Service API Contract
- Define API endpoints for visibility policy retrieval:
  - `GET /api/policies/visibility?domain={domain}&resource={resource}`
  - `GET /api/policies/visibility/{policyId}`
- Define policy change event schema
- Document authentication and authorization requirements

#### Domain Service Implementation
- Design policy cache layer in Workexec service
- Specify cache TTL configuration (default: 10 minutes)
- Design cache invalidation event handler
- Plan permission scope validation logic

#### RBAC Scope Definitions
Create explicit scope definitions:
```yaml
workexec:
  workorder:
    scopes:
      - view: "View basic work order information"
      - view-pricing: "View pricing fields (unitPrice, lineTotal, tax, discount)"
      - view-cost: "View cost fields (cost, margin)"
      - view-labor: "View labor details (hours, mechanic, rates)"
      - view-parts: "View parts usage details"
      - edit: "Edit work order details"
```

#### API Contract Design
- Define request/response DTOs
- Document field filtering behavior
- Specify error responses (403, 404, etc.)
- Create OpenAPI/Swagger documentation

### 3. Implementation Planning (1 day)
Break down implementation into tasks:

#### Task 1: Security Service - Visibility Policy API
- Implement policy storage (VisibilityPolicy entity)
- Implement policy retrieval endpoints
- Implement policy change event publishing
- Add unit and integration tests

#### Task 2: Workexec Service - Policy Cache
- Implement VisibilityPolicyCache entity
- Implement policy retrieval and caching logic
- Implement cache invalidation event handler
- Configure cache TTL
- Add unit and integration tests

#### Task 3: Workexec Service - Permission Validation
- Implement permission scope validation service
- Integrate with Spring Security
- Add method-level security annotations
- Add unit tests

#### Task 4: Workexec Service - Dynamic DTO Filtering
- Implement DTO field filtering logic
- Integrate with API controllers
- Handle partial vs. full responses
- Add integration tests

#### Task 5: Audit Logging
- Implement VisibilityAuditEvent entity
- Implement audit event generation
- Configure audit log storage
- Add observability metrics

#### Task 6: Documentation
- Complete OpenAPI documentation
- Write integration guide for Security service
- Document RBAC scope definitions
- Create runbook for operations

### 4. Test Case Development (2-3 days)
Develop test cases for each Acceptance Criterion:

#### AC-POLICY-1 Test Cases
- Test policy retrieval from Security service
- Test error handling when Security service unavailable
- Test policy response validation

#### AC-POLICY-2 Test Cases
- Test policy caching on first retrieval
- Test cache hit on subsequent requests within TTL
- Test cache miss after TTL expiry
- Test cache refresh on expiry

#### AC-POLICY-3 Test Cases
- Test cache invalidation on policy change event
- Test fresh policy retrieval after invalidation
- Test event handling errors

#### AC-RBAC-1 Test Cases
- Test valid scope format validation
- Test invalid scope format rejection
- Test scope documentation completeness

#### AC-RBAC-2 Test Cases
- Test permission scope checks (positive cases)
- Test permission scope checks (negative cases)
- Test that code does NOT check role names

#### AC-API-1 Test Cases
- Test single endpoint returns filtered DTO
- Test different permission scopes return different fields
- Test response structure consistency

#### AC-API-2 Test Cases
- Test HTTP 403 when minimum permission lacking
- Test error message content
- Test audit logging on 403

#### AC-API-3 Test Cases
- Test partial DTO with subset of permissions
- Test full DTO with all permissions
- Test empty/minimal DTO with minimal permissions

#### AC-AUDIT-1 Test Cases
- Test audit event generation on every request
- Test audit event content completeness
- Test audit event storage
- Test audit query capabilities

### 5. Security Review (1 day)
Engage Security domain team for review:
- API contract validation
- RBAC scope definitions review
- Cache security review (prevent cache poisoning)
- Audit logging completeness

### 6. Implementation (5-7 days)
Execute the implementation tasks in order:
1. Security Service - Policy API (2 days)
2. Workexec Service - Policy Cache (1 day)
3. Workexec Service - Permission Validation (1 day)
4. Workexec Service - Dynamic DTO Filtering (2 days)
5. Audit Logging (1 day)
6. Documentation (ongoing)

### 7. Testing & QA (3-5 days)
- Unit tests (throughout implementation)
- Integration tests (after component completion)
- End-to-end tests (after full integration)
- Security testing (penetration, authorization bypass attempts)
- Performance testing (cache effectiveness, response times)

### 8. Deployment Planning (1 day)
- Create deployment runbook
- Plan rollout strategy (canary, blue-green)
- Define rollback procedure
- Configure monitoring and alerts

### 9. Production Deployment (1 day)
- Deploy Security service updates first
- Deploy Workexec service updates
- Verify cache behavior in production
- Monitor audit logs and metrics

### 10. Post-Deployment (Ongoing)
- Monitor cache hit/miss rates
- Monitor authorization failures
- Review audit logs for anomalies
- Gather user feedback
- Plan refinements

## Troubleshooting

### Issue: GitHub CLI not authenticated
**Symptom**: Script fails with "not authenticated" error  
**Solution**:
```bash
gh auth status
gh auth login  # if not authenticated
```

### Issue: Cannot find issue #155
**Symptom**: "Issue not found" error  
**Solution**: Verify repository access and issue number:
```bash
gh issue view 155 -R louisburroughs/durion-positivity-backend
```

### Issue: Updates are not clear
**Symptom**: Unsure what to add/change in issue body  
**Solution**: Refer to `issue-155-update-summary.md` which provides:
- Full text for every Business Rule
- Complete entity definitions
- Full Given/When/Then for every Acceptance Criterion

### Issue: Need to revert changes
**Symptom**: Applied changes but need to undo  
**Solution**: The script saves original to `/tmp/issue-155-current.md`:
```bash
gh issue edit 155 -R louisburroughs/durion-positivity-backend \
  --body-file /tmp/issue-155-current.md
```

### Issue: Clarification issue already closed
**Symptom**: Cannot close issue #302  
**Solution**: Skip the close step; verify it was already resolved:
```bash
gh issue view 302 -R louisburroughs/durion-positivity-backend
```

## Success Criteria

The clarification resolution is successfully applied when:
- ✅ Issue #155 body includes all 6 Business Rules
- ✅ Issue #155 body includes all 3 Data Requirements
- ✅ Issue #155 body includes all 8 Acceptance Criteria
- ✅ "Open Questions" section is removed from issue #155
- ✅ Label `blocked:clarification` is removed from issue #155
- ✅ Label `status:needs-review` is added to issue #155
- ✅ Resolution comment is added to issue #155
- ✅ Issue #302 is closed with resolution comment
- ✅ All decisions are clearly documented and traceable
- ✅ Story is ready for technical design and implementation

## Timeline Estimate

| Phase | Duration | Dependencies |
|-------|----------|--------------|
| Application (Option 1) | 5 min | None |
| Application (Option 2) | 15 min | None |
| Verification | 5 min | Application complete |
| Technical Design | 1-2 days | Application verified |
| Implementation Planning | 1 day | Technical design approved |
| Test Case Development | 2-3 days | Implementation plan complete |
| Security Review | 1 day | Test cases ready |
| Implementation | 5-7 days | Security review passed |
| Testing & QA | 3-5 days | Implementation complete |
| Deployment Planning | 1 day | QA passed |
| Production Deployment | 1 day | Deployment plan approved |

**Total**: Approximately 2-3 weeks from application to production deployment

## Questions or Issues?

If you encounter any issues or have questions:
1. Review `README-155.md` for workflow documentation
2. Check `COMPLETION-SUMMARY-155.md` for executive overview
3. Consult `clarification-resolution-metadata-155.json` for machine-readable details
4. Reach out to the Story Authoring Agent or Workexec domain team

---

**Status**: Ready for Application  
**Last Updated**: 2026-01-11T10:48:00Z  
**Agent**: story-authoring
