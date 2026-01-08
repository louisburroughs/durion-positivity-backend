# Durion Process Log

## User Request
- **Issue:** [CLARIFICATION] Origin #207: [BACKEND] [STORY] Approval: Capture Digital Customer Approval
- **Type:** Clarification Request
- **Origin Story:** https://github.com/louisburroughs/durion-positivity-backend/issues/207

## Issue Summary
This is a clarification request issue that has already been answered by the user. The questions were about:
1. Entity Type (Estimates vs Work Orders)
2. Signature Data Format
3. State Model for approvals
4. Legal Requirements
5. Versioning process

All questions have been answered in the problem statement.

## Action Plan
1. [ ] Understand the clarification request and answers provided
2. [ ] Create documentation capturing the clarification decisions
3. [ ] Update relevant domain models or documentation to reflect the decisions
4. [ ] Create a summary document for the Story Authoring Agent

## Clarification Answers Summary

### 1. Entity Type
**Answer:** Approvals can be for Estimates or Work Orders
**Implication:** The endpoint should be generic (e.g., `/approvals`) to handle both entity types

### 2. Signature Data Format
**Answer:** JSON + PNG format
**Details:** User has added a comment to the original issue describing signature capture process

### 3. State Model
**Answer:** 
- `Pending Customer Approval` → `Approved`
- Other states: `Denied` (Must have cause), `In process`, `On Hold`, `Transferred`

### 4. Legal Requirements
**Answer:** Not explicitly answered - needs follow-up

### 5. Versioning
**Answer:** 
- Estimates are editable until 'Approved'
- Changes to an estimate in 'Approved' state require a new version
- If a new version is created, any work orders connected to the old version must be put into 'Transferred' status, with a reference to the new estimate
