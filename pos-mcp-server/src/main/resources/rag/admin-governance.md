# Admin Governance and Approval Gates

## Purpose
RAG id: `admin.governance`  
RAG scope: `admin`  
Required permissions: `security:permission:view`, `workorder:approval_config:view`  
Audience: admins only.  
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This document grounds governance, approval, and blast-radius questions. It should be retrieved only for users with appropriate admin or approval-configuration visibility.

## Governance principle
Administrative changes can affect pricing, approvals, scheduling, accounting, security, retrieval visibility, and auditability. The assistant should frame admin actions by blast radius: who is affected, which locations or services are affected, whether historical records change, whether the action is reversible, whether approval is required, and what audit trail must exist.

## Approval gates
Approval gates are controls that prevent a workflow from proceeding until required conditions are met. In the supplied sources, verified approval-related workorder permissions include `workorder:approval_config:view`, `workorder:approval_config:create`, `workorder:approval_config:edit`, and `workorder:approval_config:delete`. Estimate and workorder permissions also include approval-related actions such as `workorder:estimate:approve` and `workorder:workorder:approve` in the extracted bundle text.

The assistant should distinguish configuration of approval rules from execution of an approval. A user may be allowed to view approval configuration but not approve a specific workorder, estimate, or change request.

## What needs approval
Based on the existing RAG documents, these items commonly require approval or controlled handling:

- Estimate approval before promotion to workorder.
- Change request approval before added work proceeds.
- Conflict override reason when scheduling conflicts are overridden.
- Posted accounting corrections through reversing or compensating entries.
- Price or tax overrides if supported by verified policy.
- Administrative configuration changes with broad blast radius.
- Security, role, or permission assignment changes.

## Verified approval policy (pos-workorder)
- Approval configuration (`ApprovalConfiguration`) has **NO monetary or percentage threshold and NO approver-role field**. It carries `approvalMethod` (`CLICK_CONFIRM` default, `SIGNATURE`, `ELECTRONIC_SIGNATURE`, `VERBAL_CONFIRMATION`), `declineExpiryDays` (default 30), `approvalWindowDays` (nullable), `requireSignature` (default false), `priority`.
- Which config applies is resolved by **specificity, not amount**: priority 0 = default, 1 = location-specific, 2 = customer-specific. Threshold-based approval does not exist — do not state dollar thresholds.
- What requires approval (approval-category events): `WORKORDER_APPROVE` (with customer signature), `WORKORDER_ESTIMATE_APPROVE`, `WORKORDER_CHANGE_REQUEST_APPROVE`/`DECLINE`, `WORKORDER_CHANGE_REQUEST_EMERGENCY_OVERRIDE` (**Manager-only**, `workorder:change_request:emergency_override`), time-entry approve/reject.

_Verified: `pos-workorder` `ApprovalConfiguration.java`, `EventTypes.java`, `permissions.yaml`._

## Audit implications
Admin and approval activity should be reconstructable: actor, timestamp, entity, previous value, new value, reason, source request, and correlation ID where available. The assistant should not state that an action is unaudited. If audit evidence is missing, it should say the audit source was not available and ask for the relevant entity identifier.

## Blast-radius framing
For any admin request, answer using this checklist:

1. Entity affected: user, role, approval config, price rule, schedule, workorder, invoice, accounting rule, or RAG document.
2. Scope affected: one record, one location, one account, all locations, or all users.
3. Operational risk: blocked work, incorrect billing, incorrect inventory, incorrect accounting, or unintended data exposure.
4. Reversibility: simple setting reversal, compensating transaction, re-approval, or not safely reversible.
5. Audit need: reason, approver, timestamp, before/after values.

## RAG governance
Gate 5 requires every preloaded RAG document to carry deterministic id, `rag_scope`, and `required_permissions`. Role-only filtering is not sufficient; retrieval visibility must be based on permission codes. Admin/security docs must not be returned to non-admin/non-security fixtures. The assistant should flag any document without permission metadata as non-compliant with Gate 5.

## Safe assistant behavior for admin requests
For admin users, the assistant may explain concepts, identify likely required permissions, and describe verification steps. It should not invent missing permission codes, tool names, or API behavior. Where an admin asks for a change, the assistant should verify the exact target entity and scope before describing or initiating a write action.
