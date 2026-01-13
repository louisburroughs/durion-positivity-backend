# Clarification Issue #222 Resolution - Issue #24

## Summary
Clarification issue #222 has been answered by @louisburroughs. All three questions have concrete, implementation-ready answers that have been incorporated into the story.

## Questions Resolved

### 1. Starvation Prevention Rules
**Answer:** Mandatory time-based priority aging with hard caps.

**Implementation Details:**
- Base priority: `basePriority` (1-5 or LOW/MED/HIGH)
- Computed field: `effectivePriority`
- Grace period: 24 hours
- Aging step: +1 priority level per 24 hours
- Max effective priority: CRITICAL
- Formula: `effectivePriority = min(basePriority + floor((now - waitingSince - gracePeriod) / agingInterval), maxEffectivePriority)`

**Constraints:**
- Aging applies only while blocked on inventory
- Aging resets when stock is successfully allocated
- Manual priority overrides are allowed but audited

### 2. Reallocation Sorting Logic
**Answer:** Complete 5-key stable sort (deterministic).

**Sorting Order:**
1. `effectivePriority DESC` (highest effective priority first)
2. `dueDateTime ASC` (earliest commitment first)
3. `waitingSince ASC` (oldest blocked first)
4. `scheduleStartTime ASC` (earlier scheduled work)
5. `workOrderCreatedAt ASC` (final deterministic tie-breaker)

**Explicit Exclusions:**
- No randomization
- No user/customer-based sorting unless explicitly part of priority policy

### 3. Audit Reason Codes
**Answer:** Fixed enumeration of 10 reason codes (v1).

**Required Enum:**
- `SCHEDULE_CHANGE` - Work order schedule/due time modified
- `PRIORITY_CHANGE` - Work order priority manually changed
- `PRIORITY_AGED` - Automatic starvation prevention increased priority
- `MANUAL_OVERRIDE` - User manually reallocated stock
- `STOCK_SHORTAGE` - Insufficient stock triggered reallocation
- `STOCK_REPLENISHED` - New stock arrival triggered reallocation
- `LOCATION_CHANGE` - Work order location changed
- `WORK_ORDER_CANCELLED` - Work order was cancelled
- `WORK_ORDER_COMPLETED` - Work order was completed
- `SYSTEM_REBALANCE` - Bulk or automated reallocation

## Story Updates Applied

### Business Rules Section
- Added **BR1: Starvation Prevention (Mandatory)** with complete formula and constraints
- Updated **BR2: Reallocation Sorting Order** with full 5-key stable sort specification
- Retained **BR3: Full Allocation Only** (unchanged)

### Data Requirements Section
- Added WorkOrder extended fields: `basePriority`, `effectivePriority`, `waitingSince`, `dueDateTime`, `scheduleStartTime`, `workOrderCreatedAt`
- Added AuditLog fields: `previousAllocationState`, `newAllocationState`, `triggeredBy`, `triggerReferenceId`, `occurredAt`
- Added **Audit Reason Codes (Required Enumeration v1)** with complete list

### Acceptance Criteria Section
- Updated Scenario 1 to use `basePriority` instead of `Priority`
- Updated Scenario 2 to use `basePriority` instead of `Priority`
- Retained Scenario 3 (unchanged)
- Added **Scenario 4: Priority aging increases effective priority after grace period**
- Added **Scenario 5: Stable multi-key sorting resolves tie-breakers**

### Audit & Observability Section
- Updated to reference the enumerated reason codes
- Added requirement for complete before/after allocation states
- Added requirement for triggeredBy (USER|SYSTEM) tracking
- Added priority aging metrics tracking

### Open Questions Section
- **REMOVED** - All questions have been answered

## Manual GitHub Actions Required

**Note:** The following actions cannot be performed automatically by the Story Authoring Agent due to GitHub API permission constraints. These must be executed manually or through GitHub CLI with appropriate authentication.

### 1. Update Issue #24 Body
Replace the body of issue #24 with the content in `/tmp/issue-24-updated-body.md`

**GitHub CLI Command:**
```bash
gh issue edit 24 \
  --repo louisburroughs/durion-positivity-backend \
  --body-file /tmp/issue-24-updated-body.md
```

**Alternative (Web UI):**
1. Navigate to https://github.com/louisburroughs/durion-positivity-backend/issues/24
2. Click "Edit" on the issue description
3. Replace with content from `/tmp/issue-24-updated-body.md`
4. Click "Update comment"

### 2. Update Issue #24 Labels
Remove: `blocked:clarification`, `status:draft`
Add: `status:ready-for-dev`

**GitHub CLI Command:**
```bash
gh issue edit 24 \
  --repo louisburroughs/durion-positivity-backend \
  --remove-label "blocked:clarification" \
  --remove-label "status:draft" \
  --add-label "status:ready-for-dev"
```

**Alternative (Web UI):**
1. Navigate to https://github.com/louisburroughs/durion-positivity-backend/issues/24
2. Click on the gear icon next to "Labels"
3. Remove: `blocked:clarification`, `status:draft`
4. Add: `status:ready-for-dev`

### 3. Assign Issue #24
Assign to: `github-copilot` (for implementation support)

**Note:** Assignment of "principal-software-engineer-agent" may not be possible as it's not a GitHub user. The assignment to @github-copilot should be sufficient for triggering implementation support.

**GitHub CLI Command:**
```bash
gh issue edit 24 \
  --repo louisburroughs/durion-positivity-backend \
  --add-assignee "Copilot"
```

**Alternative (Web UI):**
1. Navigate to https://github.com/louisburroughs/durion-positivity-backend/issues/24
2. Click on the gear icon next to "Assignees"
3. Search for and select "Copilot"

### 4. Post Handoff Comment on Issue #24
Post the content in `/tmp/issue-24-handoff-comment.md` as a new comment.

**GitHub CLI Command:**
```bash
gh issue comment 24 \
  --repo louisburroughs/durion-positivity-backend \
  --body-file /tmp/issue-24-handoff-comment.md
```

**Alternative (Web UI):**
1. Navigate to https://github.com/louisburroughs/durion-positivity-backend/issues/24
2. Paste content from `/tmp/issue-24-handoff-comment.md` into the comment box
3. Click "Comment"

### 5. Post Completion Comment on Issue #222
Post the content in `/tmp/clarification-222-completion.md` as a new comment.

**GitHub CLI Command:**
```bash
gh issue comment 222 \
  --repo louisburroughs/durion-positivity-backend \
  --body-file /tmp/clarification-222-completion.md
```

**Alternative (Web UI):**
1. Navigate to https://github.com/louisburroughs/durion-positivity-backend/issues/222
2. Paste content from `/tmp/clarification-222-completion.md` into the comment box
3. Click "Comment"

### 6. Close Issue #222
Close the clarification issue as resolved.

**GitHub CLI Command:**
```bash
gh issue close 222 \
  --repo louisburroughs/durion-positivity-backend \
  --comment "Clarification resolved. All decisions incorporated into issue #24. Story is now ready for development."
```

**Alternative (Web UI):**
1. Navigate to https://github.com/louisburroughs/durion-positivity-backend/issues/222
2. Click "Close issue" button
3. Optionally add closing comment: "Clarification resolved. All decisions incorporated into issue #24. Story is now ready for development."

## Files Created

The following files contain the content needed for the manual actions:

- `/tmp/issue-24-updated-body.md` - Complete updated body for issue #24
- `/tmp/issue-24-handoff-comment.md` - Handoff comment for issue #24
- `/tmp/clarification-222-completion.md` - Completion comment for issue #222

## Verification Checklist

After performing the manual actions, verify:

- [ ] Issue #24 body has been updated with all clarification decisions
- [ ] Issue #24 no longer has `blocked:clarification` label
- [ ] Issue #24 no longer has `status:draft` label
- [ ] Issue #24 has `status:ready-for-dev` label
- [ ] Issue #24 is assigned to @github-copilot (or Copilot bot)
- [ ] Issue #24 has handoff comment posted
- [ ] Issue #222 has completion comment posted
- [ ] Issue #222 is closed

## Story Authoring Agent Completion

This clarification resolution represents the successful completion of the Story Authoring Agent's responsibilities for issue #24. The story is now:

✅ **Complete** - All open questions resolved
✅ **Validated** - Domain-correct business rules defined
✅ **Testable** - Acceptance criteria are specific and measurable
✅ **Implementable** - No guessing required for development
✅ **Ready for Handoff** - Technical execution team can begin implementation

**Next Phase:** Technical implementation by Principal Software Engineer Agent and @github-copilot.
