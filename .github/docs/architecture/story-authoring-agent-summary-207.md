# Story Authoring Agent: Issue #207 Clarification Summary

**Date:** 2026-01-08  
**Clarification Issue:** [CLARIFICATION] Origin #207  
**Origin Story:** https://github.com/louisburroughs/durion-positivity-backend/issues/207  
**Story Title:** [BACKEND] [STORY] Approval: Capture Digital Customer Approval

---

## Status: ✅ CLARIFICATION COMPLETE

All critical questions have been answered with sufficient detail to proceed with story finalization.

---

## Quick Reference: Answers

| Question | Answer | Documentation |
|----------|--------|---------------|
| **Entity Type** | Both Estimates and Work Orders | `approval-workflow-clarification.md` §1 |
| **Signature Format** | JSON + PNG (dual format) | `approval-workflow-clarification.md` §2, `approval-domain-model.md` |
| **State Model** | Pending → Approved/Denied + 4 other states | `approval-workflow-clarification.md` §3 |
| **Legal Requirements** | Not specified - use best practices | `approval-workflow-clarification.md` §4 |
| **Versioning** | Full rules provided for estimates and work orders | `approval-workflow-clarification.md` §5 |

---

## Actions Required by Story Authoring Agent

### 1. Update Origin Story (#207)

**Update Sections:**

#### A. Entity Scope
- ✅ **Decision:** Approval system is **generic** - supports both ESTIMATE and WORK_ORDER entity types
- **Impact:** API design should use `/api/approvals` with `entityType` parameter
- **AC Update Required:** Add acceptance criteria for both entity types

#### B. Signature Data Format
- ✅ **Decision:** Dual format - Base64 PNG + JSON stroke data
- **Details:** See `approval-domain-model.md` for complete JSON schema
- **AC Update Required:** 
  - Must store both formats
  - Must validate JSON schema on input
  - Must support rendering from either format

#### C. State Model
- ✅ **Decision:** Complete state machine defined with 6 work order states
- **States:** Pending Customer Approval, Approved, Denied (requires reason), In Process, On Hold, Transferred
- **AC Update Required:**
  - Add state transition validation
  - Enforce denial reason requirement
  - Document all valid transitions

#### D. Versioning Rules
- ✅ **Decision:** Comprehensive versioning rules provided
- **Key Rules:**
  - Estimates editable until Approved
  - Post-approval changes create new version
  - Work orders must transfer to Transferred status
  - Must maintain references to old and new versions
- **AC Update Required:**
  - Add versioning acceptance criteria
  - Add work order cascade acceptance criteria
  - Add version history tracking

#### E. Legal Requirements
- ⚠️ **Decision:** Not explicitly provided - follow best practices
- **Recommendation:** Capture minimum metadata for legal compliance readiness
- **AC Update Required:**
  - Add metadata capture (IP, timestamp, user agent, etc.)
  - Add audit trail requirement
  - Flag for legal review before production

### 2. Remove Blockers

**Action:** Remove `blocked:clarification` label from issue #207

### 3. Set Next Status

**Recommended Status:** `status:needs-review`

**Reason:** Legal requirements (Question 4) were not fully answered. Recommend:
1. Tag legal/compliance team for review
2. Get explicit legal requirements before production deployment
3. Technical implementation can proceed with best-practices approach in the meantime

**Alternative:** If legal review can be deferred, set `status:ready-for-dev`

---

## Documentation Created

The following documentation has been created and committed:

1. **`approval-workflow-clarification.md`**
   - Complete Q&A for all clarification questions
   - Implementation recommendations
   - Technical architecture guidance
   - Testing requirements
   - 12,000+ words of comprehensive guidance

2. **`approval-domain-model.md`**
   - Complete entity definitions with Java code
   - All enumerations and state machines
   - Signature data format specifications
   - Database schema and indexes
   - API contracts and examples
   - 18,000+ words of technical specifications

3. **`Durion-Processing.md`**
   - Updated with clarification tracking
   - Quick reference to answers

---

## Acceptance Criteria Updates

### New Acceptance Criteria to Add

Based on the clarifications, add these acceptance criteria to issue #207:

#### Generic Approval System
- [ ] System supports approvals for both ESTIMATE and WORK_ORDER entity types
- [ ] API accepts `entityType` enum parameter (ESTIMATE | WORK_ORDER)
- [ ] API accepts `entityId` for the specific estimate or work order
- [ ] System validates entity exists and is in appropriate state before creating approval

#### Signature Capture
- [ ] System accepts and stores signature as Base64-encoded PNG
- [ ] System accepts and stores signature as JSON stroke data
- [ ] System validates JSON stroke data against defined schema
- [ ] System can render signature from either PNG or JSON format
- [ ] Signature data is immutable after approval is created

#### State Management
- [ ] System implements all defined states: Pending Customer Approval, Approved, Denied, In Process, On Hold, Transferred
- [ ] System enforces valid state transitions per state machine diagram
- [ ] Denied status requires `denialReason` field (non-null, non-empty)
- [ ] System records timestamp for all state changes
- [ ] System prevents invalid state transitions and returns clear error messages

#### Versioning (Estimates)
- [ ] Estimates in DRAFT status are editable without creating new version
- [ ] Changes to APPROVED estimate trigger automatic version creation
- [ ] New version increments version number (base-v1, base-v2, etc.)
- [ ] Old version is marked as SUPERSEDED with reference to new version
- [ ] Version history is queryable via API

#### Work Order Cascade (Versioning)
- [ ] When new estimate version created, all related work orders update to TRANSFERRED status
- [ ] Transferred work orders maintain reference to old estimate version (audit trail)
- [ ] Transferred work orders capture reference to new estimate version
- [ ] Transfer reason is recorded (required field)
- [ ] Transfer timestamp is recorded
- [ ] Transfer operation is atomic (all or nothing)

#### Legal/Audit Metadata
- [ ] System captures IP address at approval time
- [ ] System captures user agent string
- [ ] System captures timestamp with timezone
- [ ] System captures authentication method used
- [ ] System captures consent version displayed to customer
- [ ] (Optional) System captures geolocation if available and permitted
- [ ] All metadata is immutable after approval creation

#### Audit Trail
- [ ] All approval-related actions are logged to audit table
- [ ] Audit log captures: action type, actor, timestamps, before/after state
- [ ] Audit log is queryable by approval ID
- [ ] Audit log entries are immutable

#### API Endpoints
- [ ] `POST /api/approvals` - Create approval (generic)
- [ ] `GET /api/approvals/{id}` - Get approval details
- [ ] `GET /api/approvals?entityType={type}&entityId={id}` - Query approvals by entity
- [ ] `POST /api/approvals/{id}/deny` - Deny approval with reason
- [ ] `POST /api/estimates/{id}/versions` - Create new estimate version
- [ ] `GET /api/estimates/{baseId}/versions` - List all versions
- [ ] `GET /api/work-orders/{id}/transfer-history` - View transfer history

---

## Related Stories to Consider

Based on the clarifications, the following related stories may be needed:

### High Priority
1. **Estimate Management Service**
   - Full CRUD for estimates
   - Version management
   - State machine implementation

2. **Estimate-to-Work-Order Integration**
   - Work order creation from estimate
   - Cascade logic for transfers
   - State synchronization

3. **Audit Trail Service**
   - Complete audit logging
   - Query and export capabilities
   - Compliance reporting

### Medium Priority
4. **Signature Rendering Service**
   - Convert JSON to PNG
   - Convert PNG to displayable format
   - Signature comparison/verification

5. **Legal Compliance Review**
   - E-SIGN Act compliance validation
   - GDPR/CCPA compliance validation
   - Retention policy definition

### Low Priority
6. **Version Comparison UI**
   - Side-by-side estimate version comparison
   - Change highlighting
   - Version history visualization

---

## Technical Dependencies

### New Entities Required
- `CustomerApproval` (new)
- `ApprovalAuditLog` (new)
- `Estimate` (if not exists, or enhance existing)
- `EstimateVersion` (or version fields in Estimate)

### Enhancements to Existing Entities
- `WorkOrder`:
  - Add `approval_id`
  - Add `current_estimate_id`
  - Add `original_estimate_id`
  - Add transfer tracking fields

### New Enums
- `ApprovalEntityType`
- `ApprovalStatus`
- `EstimateStatus` (if not exists)
- `WorkOrderStatus` (enhance if exists)

---

## Testing Requirements

### Unit Tests Required
- State transition validation logic
- Versioning logic (estimate and work order cascade)
- Signature data validation
- Denial reason enforcement

### Integration Tests Required
- End-to-end approval workflow (both entity types)
- Estimate versioning with work order cascade
- Audit trail generation
- API contract validation

### Acceptance Tests Required
- Customer approves estimate → work order moves to Approved
- Customer denies estimate without reason → error
- Customer denies with reason → estimate moves to Denied
- Approved estimate change → new version created, work orders transferred
- Transfer maintains complete audit trail

---

## Security Considerations

### Data Protection
- Signature data contains PII - encrypt at rest
- Audit logs contain sensitive actions - restrict access
- IP addresses are PII - handle per privacy policy

### Access Control
- Only authorized customers can approve/deny their own estimates/work orders
- Shop staff can view but not create approvals (customer action)
- Audit logs accessible only to authorized personnel

### Compliance
- Signature data must be immutable
- Audit trail must be tamper-proof
- Retention policy must be configurable per jurisdiction

---

## Definition of Done

For issue #207 to be considered "Ready for Development":

- [x] All clarification questions answered
- [ ] Origin story (#207) updated with clarification decisions
- [ ] Acceptance criteria updated based on clarifications
- [ ] Related stories identified and created
- [ ] Technical dependencies documented
- [ ] Domain model documented
- [ ] API contracts defined
- [ ] Testing requirements defined
- [ ] Security considerations documented
- [ ] `blocked:clarification` label removed
- [ ] `status:ready-for-dev` or `status:needs-review` label applied

---

## Next Steps

### Immediate (Story Authoring Agent)
1. Review this summary document
2. Update issue #207 with all clarification details
3. Add/update acceptance criteria per the list above
4. Remove `blocked:clarification` label
5. Set appropriate status:
   - `status:needs-review` (with legal team tag) - **RECOMMENDED**
   - OR `status:ready-for-dev` (if legal review can be deferred)

### Short Term (Product Owner / Tech Lead)
1. Review legal requirements with compliance team
2. Prioritize related stories
3. Assign development team
4. Schedule technical design review

### Medium Term (Development Team)
1. Review domain model documentation
2. Create database migration scripts
3. Implement entities and enums
4. Implement API endpoints
5. Write comprehensive tests
6. Deploy to staging for acceptance testing

---

## References

- **Clarification Document:** `.github/docs/architecture/approval-workflow-clarification.md`
- **Domain Model:** `.github/docs/architecture/approval-domain-model.md`
- **Process Log:** `Durion-Processing.md`
- **Origin Story:** https://github.com/louisburroughs/durion-positivity-backend/issues/207

---

## Contact Information

**Clarification Completed By:** Durion Agent (GitHub Copilot)  
**Date:** 2026-01-08T18:19:35.204Z  
**Domain:** workexec  
**Clarification Type:** workflow

---

*This summary is authoritative for the Story Authoring Agent to finalize issue #207. All technical details are captured in the referenced documentation files.*
