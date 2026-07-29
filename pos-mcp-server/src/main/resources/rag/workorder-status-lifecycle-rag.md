---
rag_id: workorder.status-lifecycle
rag_scope: workorder
required_permissions:
  - workorder:workorder:view
---

## Purpose

RAG id: workorder.status-lifecycle
RAG scope: workorder
Required permissions: workorder:workorder:view
Audience: internal staff.

This document captures the declared WorkorderStatus transition model in pos-workorder.

## WorkorderStatus Tokens

- DRAFT
- APPROVED
- ASSIGNED
- WORK_IN_PROGRESS
- AWAITING_PARTS
- AWAITING_APPROVAL
- READY_FOR_PICKUP
- COMPLETED
- CANCELLED

## Transition Rules

- DRAFT -> APPROVED, CANCELLED
- APPROVED -> ASSIGNED, WORK_IN_PROGRESS, AWAITING_APPROVAL, CANCELLED
- ASSIGNED -> WORK_IN_PROGRESS, CANCELLED
- WORK_IN_PROGRESS -> AWAITING_PARTS, AWAITING_APPROVAL, READY_FOR_PICKUP, COMPLETED, CANCELLED
- AWAITING_PARTS -> WORK_IN_PROGRESS, COMPLETED, CANCELLED
- AWAITING_APPROVAL -> WORK_IN_PROGRESS, COMPLETED, CANCELLED
- READY_FOR_PICKUP -> COMPLETED, CANCELLED
- COMPLETED -> none
- CANCELLED -> none

## Helper Sets

- Start-eligible statuses: APPROVED, ASSIGNED
- Terminal statuses: COMPLETED, CANCELLED
- Open statuses: all non-terminal statuses

## Verified Facts

- _Verified: pos-workorder WorkorderStatus ALLOWED_TRANSITIONS map and canTransitionTo implementation._
- _Verified: pos-workorder WorkorderStatus helper methods getStartEligibleStatuses and getTerminalStatuses._
