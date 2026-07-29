---
rag_id: workorder.approval-config
rag_scope: workorder
required_permissions:
  - workorder:approval_config:view
---

## Purpose

RAG id: workorder.approval-config
RAG scope: workorder
Required permissions: workorder:approval_config:view
Audience: internal staff.

This document describes approval-configuration APIs and specificity-based matching behavior in pos-workorder.

## Endpoints, Permissions, and Events

Base path: /v1/workexec

| Operation | Method and path | Permission | EmitEvent id |
| --- | --- | --- | --- |
| List configurations | GET /v1/workexec | workorder:approval_config:view | WORKORDER_APPROVAL_CONFIG_LIST |
| Get configuration | GET /v1/workexec/approvalConfigurations/{approvalId} | workorder:approval_config:view | none |
| Get applicable configuration | GET /v1/workexec/approvalConfigurations/applicable | workorder:approval_config:view | none |
| Create configuration | POST /v1/workexec/approvalConfigurations | workorder:approval_config:create | WORKORDER_APPROVAL_CONFIG_CREATE |
| Update configuration | PUT /v1/workexec/approvalConfigurations/{approvalId} | workorder:approval_config:edit | WORKORDER_APPROVAL_CONFIG_UPDATE |
| Delete configuration | DELETE /v1/workexec/approvalConfigurations/{approvalId} | workorder:approval_config:delete | WORKORDER_APPROVAL_CONFIG_DELETE |

## Matching and Priority Rules

Applicable configuration selection order:

- customer-specific (locationId + customerId)
- location-specific (locationId only)
- global default (no locationId and no customerId)

Priority calculation is deterministic in ApprovalConfiguration:

- customerId present -> priority 2
- else locationId present -> priority 1
- else priority 0

## ApprovalMethod Tokens

- CLICK_CONFIRM
- SIGNATURE
- ELECTRONIC_SIGNATURE
- VERBAL_CONFIRMATION

## Monetary Threshold Clarification

- No monetary threshold field is used for approval-configuration matching in this module.
- Matching is specificity/priority based on customerId and locationId.

## Verified Facts

- _Verified: pos-workorder ApprovalConfigurationController endpoint mappings, PreAuthorize permissions, and @EmitEvent ids._
- _Verified: pos-workorder ApprovalConfigurationServiceImpl applicable configuration ordering (customer -> location -> global)._
- _Verified: pos-workorder ApprovalConfiguration.calculatePriority logic and ApprovalMethod enum tokens._
