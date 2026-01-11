# Clarification Resolution - Issue #158

## Overview
This directory contains artifacts for resolving clarification issue #303 and updating origin story issue #158 according to the Story Authoring Agent protocol.

## Context

- **Origin Issue**: #158 - [BACKEND] [STORY] Execution: Issue and Consume Parts
- **Clarification Issue**: #303 - Missing information about transaction granularity, idempotency, and accounting policy
- **Domain**: workexec
- **Clarification Type**: data
- **Response Received**: 2026-01-06
- **Resolution Date**: 2026-01-11

## Clarification Questions & Responses

### Q1: Issued vs. Consumed Granularity
**Question:** Should issue and consume be two distinct actions/events or a single atomic transaction?

**Decision:** Use single local DB transaction for state + ledger writes and outbox record; publish events asynchronously (no distributed transactions); ensure idempotency keys for retries.

### Q2: Idempotency Key Definition  
**Question:** What is the correct format for idempotency keys?

**Decision:** Apply standard best practices with explicit contracts, idempotency, audit trails, UTC timestamps, scoped RBAC, configurable defaults. Use format: `{workorderId}-{workorderItemId}-{partUsageEventId}`.

### Q3: WIP vs COGS Rule Source
**Question:** Where is the accounting policy configured?

**Decision:** Configurable via policy; Accounting consumes events with policy decision included in payload.

## Artifacts

### 1. issue-158-update-summary.md (Primary Document)
Complete integration guidance with:
- Clarification responses and interpretations
- Business rules to add (5 rules)
- Data requirements (3 entities)
- Acceptance criteria (6 criteria)
- Audit & Observability updates
- Section-by-section update instructions

### 2. clarification-resolution-metadata-158.json
Machine-readable metadata including:
- Complete decision tracking
- Impact analysis
- Data model changes
- Validation status

### 3. apply-clarification-resolution-158.sh
Automated application script that:
- Updates labels on issue #158
- Posts resolution comment to #303
- Closes clarification issue #303
- Provides manual update instructions

### 4. README-158.md (This File)
Workflow documentation and quick reference

### 5. NEXT-STEPS-158.md
Comprehensive next steps guide with:
- Three application methods (automated, manual, partial)
- Verification checklist
- Troubleshooting guidance

### 6. COMPLETION-SUMMARY-158.md
Executive summary with:
- Key decisions
- Quick reference table
- Timeline and status

## Quick Start

### Option 1: Automated (Recommended)
```bash
cd /home/runner/work/durion-positivity-backend/durion-positivity-backend
./.story-work/apply-clarification-resolution-158.sh
```

Note: Automated script handles label updates and clarification issue closure.
Manual update of issue #158 body is required (see script output).

### Option 2: Manual
1. Review `issue-158-update-summary.md` for complete content
2. Open issue #158 in GitHub
3. Edit issue body to add business rules, data requirements, and acceptance criteria
4. Update labels: remove `blocked:clarification`, add `status:needs-review`
5. Post resolution comment to issue #303 and close it
6. Verify all changes applied correctly

### Option 3: Partial (Advanced)
Mix automated and manual steps as needed. See `NEXT-STEPS-158.md` for details.

## Key Decisions Summary

| Question | Decision | Impact |
|----------|----------|--------|
| Transaction Granularity | Single atomic transaction | Added BR-ATOMIC-1, BR-ASYNC-1, OutboxEvent entity |
| Idempotency Key Format | `{workorderId}-{workorderItemId}-{partUsageEventId}` | Added BR-IDEMPOTENCY-1, BR-AUDIT-1, audit fields |
| Accounting Policy | Configurable via policy | Added BR-POLICY-1, AccountingPolicyConfiguration entity |

## Business Rules Added

1. **BR-ATOMIC-1**: Atomic Transaction Requirement
2. **BR-ASYNC-1**: Asynchronous Event Publishing
3. **BR-IDEMPOTENCY-1**: Idempotency Key Standard
4. **BR-AUDIT-1**: Audit Trail Requirements
5. **BR-POLICY-1**: WIP vs COGS Policy Configuration

## Data Entities Added

1. **PartUsageEvent** (12 fields) - Single part consumption event
2. **OutboxEvent** (9 fields) - Asynchronous event publishing
3. **AccountingPolicyConfiguration** (9 fields) - WIP vs COGS policy

## Acceptance Criteria Added

1. **AC-ATOMIC-1**: Single Transaction Guarantees Atomicity
2. **AC-ASYNC-1**: Events Published Asynchronously
3. **AC-IDEMPOTENCY-1**: Idempotency Keys Prevent Duplicate Processing
4. **AC-AUDIT-1**: Complete Audit Trail Captured
5. **AC-POLICY-1**: WIP vs COGS Determined by Configured Policy
6. **AC-POLICY-2**: Policy Decision Included in InventoryIssued Event

## Verification Checklist

- [ ] All three clarification questions have answers integrated into story
- [ ] Business rules are traceable to clarification responses
- [ ] Data requirements support the business rules
- [ ] Acceptance criteria are testable and implementation-ready
- [ ] No new open questions were introduced
- [ ] Audit & Observability requirements are comprehensive
- [ ] Original story text is preserved
- [ ] Labels updated correctly on issue #158
- [ ] Resolution comment posted to issue #303
- [ ] Clarification issue #303 is closed

## Next Steps After Application

1. **Domain Agent Review** (agent:workexec)
   - Validate business rules
   - Review data model
   - Confirm workflow logic

2. **Technical Review**
   - Verify acceptance criteria testability
   - Assess implementation feasibility
   - Identify dependencies

3. **Status Promotion**
   - If approved: `status:needs-review` → `status:ready-for-dev`
   - If issues: Add blocking labels, reopen clarification

4. **Development Planning**
   - Estimate implementation effort
   - Design transaction processing
   - Implement outbox pattern
   - Build policy configuration

## Compliance with Agent Protocol

✅ **No Unsafe Assumptions**: All design based on explicit clarification responses  
✅ **Complete Documentation**: Every decision explained and documented  
✅ **Audit Trail**: Full traceability from question to implementation  
✅ **Domain Boundaries**: Respects workexec domain authority  
✅ **Story Structure**: Follows Story Authoring Agent contract  
✅ **Original Preservation**: Requirement documented

## Troubleshooting

### Issue: GitHub CLI not authenticated
**Solution:** Run `gh auth login` and follow the prompts

### Issue: Label does not exist
**Solution:** Labels may need to be created in the repository first. See `.github/labels.yml` or create manually.

### Issue: Cannot fetch issue
**Solution:** Verify you have read access to the repository and the issue number is correct

### Issue: Merge conflict when updating issue body
**Solution:** Manually merge the changes from `issue-158-update-summary.md` into the current issue body

## Support

For questions or assistance:
1. Start with `COMPLETION-SUMMARY-158.md` for executive overview
2. Review `NEXT-STEPS-158.md` for detailed application steps
3. Check `issue-158-update-summary.md` for specific content to add
4. Consult `.github/agents/story-authoring.agent.md` for protocol details
5. Escalate unresolved issues to business decision-makers

## Timeline

- **2026-01-06T02:37:05Z**: Clarification issue #303 created
- **2026-01-06**: Clarification response provided by @louisburroughs
- **2026-01-11T10:46:57Z**: Artifact creation started
- **Next**: GitHub application (requires authenticated access)

## Files in This Directory

```
.story-work/
├── issue-158-update-summary.md          # Primary integration guide
├── clarification-resolution-metadata-158.json  # Machine-readable metadata
├── apply-clarification-resolution-158.sh       # Application script
├── README-158.md                        # This file - workflow docs
├── NEXT-STEPS-158.md                    # Detailed next steps
└── COMPLETION-SUMMARY-158.md            # Executive summary
```

## Related Documents

- Origin Story: https://github.com/louisburroughs/durion-positivity-backend/issues/158
- Clarification Issue: https://github.com/louisburroughs/durion-positivity-backend/issues/303
- Agent Protocol: `.github/agents/story-authoring.agent.md`
- Domain Sub-Contract: `.github/agents/story-authoring.agent.md` (Section 13.7)

---

**Status**: ✅ READY FOR APPLICATION  
**Prepared by**: Story Authoring Agent Integration  
**Date**: 2026-01-11T10:46:57Z
