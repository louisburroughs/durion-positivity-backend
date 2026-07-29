---
rag_id: workorder.estimate-promotion
rag_scope: workorder
required_permissions:
  - workorder:estimate:view
---

## Purpose

RAG id: workorder.estimate-promotion
RAG scope: workorder
Required permissions: workorder:estimate:view
Audience: internal staff.

This document describes estimate-to-workorder promotion behavior implemented in pos-workorder.

## Endpoint, Permission, and Event

Base estimate path: /v1/workorders/estimates

| Operation | Method and path | Permission | EmitEvent id |
| --- | --- | --- | --- |
| Promote estimate | POST /v1/workorders/estimates/{estimateId}/promote | workorder:estimate:promote | WORKORDER_ESTIMATE_PROMOTE |

## Promotion Precondition Gates

PromotionValidationServiceImpl enforces the following gates in order:

- Estimate exists, else ESTIMATE_NOT_FOUND.
- Estimate not already promoted, else ALREADY_PROMOTED.
- Estimate status is APPROVED, else APPROVAL_INVALID.
- Approval not expired, else APPROVAL_EXPIRED.
- At least one approved item exists, else NO_APPROVED_ITEMS.

## Create-Workorder Promotion Facts

- WorkorderServiceImpl creates target workorder in DRAFT status.
- If estimate number starts with EST-, service attempts WO- replacement before fallback sequence generation.
- Only APPROVED estimate items are copied into workorder lines.

## Token Catalog

PromotionErrorCode:
- ALREADY_PROMOTED
- APPROVAL_EXPIRED
- APPROVAL_INVALID
- APPROVAL_NOT_FOUND
- ESTIMATE_NOT_FOUND
- NO_APPROVED_ITEMS
- INVALID_STATE

EstimateStatus:
- DRAFT
- PENDING_APPROVAL
- OPEN
- PENDING_CUSTOMER
- APPROVED
- DECLINED
- EXPIRED
- SCHEDULED
- INVOICED
- CANCELLED
- ARCHIVED

ApprovalStatus:
- PENDING_APPROVAL
- APPROVED
- DECLINED

## Verified Facts

- _Verified: pos-workorder EstimateController promote endpoint, authority check, and @EmitEvent id._
- _Verified: pos-workorder PromotionValidationServiceImpl gate sequence and thrown PromotionErrorCode values._
- _Verified: pos-workorder WorkorderServiceImpl EST->WO number behavior and DRAFT initialization for promoted workorders._
