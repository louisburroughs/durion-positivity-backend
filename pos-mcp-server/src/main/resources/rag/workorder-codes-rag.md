---
rag_id: workorder.codes
rag_scope: workorder
required_permissions:
  - workorder:estimate:view
---

## Purpose

RAG id: workorder.codes
RAG scope: workorder
Required permissions: workorder:estimate:view
Audience: internal staff.

Token catalog for workorder-domain lexical retrieval.

## Permission Tokens

Estimate and promotion:
- workorder:estimate:view
- workorder:estimate:create
- workorder:estimate:submit
- workorder:estimate:approve
- workorder:estimate:decline
- workorder:estimate:reopen
- workorder:estimate:promote
- workorder:estimate:edit
- workorder:estimate:calculate

Approval config:
- workorder:approval_config:view
- workorder:approval_config:create
- workorder:approval_config:edit
- workorder:approval_config:delete

Workorder status access:
- workorder:workorder:view

## Token Families

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

WorkorderStatus:
- DRAFT
- APPROVED
- ASSIGNED
- WORK_IN_PROGRESS
- AWAITING_PARTS
- AWAITING_APPROVAL
- READY_FOR_PICKUP
- COMPLETED
- CANCELLED

ApprovalMethod:
- CLICK_CONFIRM
- SIGNATURE
- ELECTRONIC_SIGNATURE
- VERBAL_CONFIRMATION

## Event Tokens

- WORKORDER_ESTIMATE_PROMOTE
- WORKORDER_APPROVAL_CONFIG_LIST
- WORKORDER_APPROVAL_CONFIG_CREATE
- WORKORDER_APPROVAL_CONFIG_UPDATE
- WORKORDER_APPROVAL_CONFIG_DELETE

## Verified Facts

- _Verified: pos-workorder permissions.yaml and PermissionRegistration permission sources for listed tokens._
- _Verified: pos-workorder enum token sets for estimate/promotion/status/approval configuration._
- _Verified: pos-workorder EventTypes registry and controller @EmitEvent ids for listed tokens._
