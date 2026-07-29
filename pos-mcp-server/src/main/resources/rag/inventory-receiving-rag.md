---
rag_id: inventory.receiving
rag_scope: inventory
required_permissions:
  - inventory:receiving:view
---

## Purpose

RAG id: inventory.receiving
RAG scope: inventory
Required permissions: inventory:receiving:view
Audience: internal staff.

This document covers receiving sessions in pos-inventory: staging receipt, variance handling,
and cross-dock to workorder behavior.

## Endpoints, Permissions, and Events

Base path: /v1/inventory/receiving

| Operation | Method and path | Permission | EmitEvent id |
| --- | --- | --- | --- |
| Create session | POST /v1/inventory/receiving/sessions | inventory:receiving:create | INVENTORY_RECEIVING_SESSION_CREATE |
| Get session | GET /v1/inventory/receiving/sessions/{sessionId} | inventory:receiving:view | INVENTORY_RECEIVING_SESSION_GET |
| Complete receive | POST /v1/inventory/receiving/sessions/{sessionId}/receive | inventory:receiving:complete | INVENTORY_RECEIVING_SESSION_COMPLETE |
| Cross-dock line | POST /v1/inventory/receiving/sessions/{sessionId}/lines/{lineId}/cross-dock | inventory:receiving:complete and inventory:issue:parts | INVENTORY_RECEIVING_CROSSDOCK |

## Receiving Status Tokens

ReceivingSessionStatus:

- OPEN
- IN_PROGRESS
- COMPLETED
- CANCELLED

ReceivingLineStatus:

- EXPECTED
- RECEIVED
- RECEIVED_SHORT
- RECEIVED_OVER
- CANCELLED

InventoryVarianceType:

- SHORTAGE
- OVERAGE

## Receive Behavior

For each receiving line, implementation compares received quantity with expected quantity:

- equal -> RECEIVED
- lower -> RECEIVED_SHORT and SHORTAGE variance
- higher -> RECEIVED_OVER and OVERAGE variance

Receipt posting writes GOODS_RECEIPT ledger entries. Session completion is derived from line
states and sets COMPLETED only when all lines are terminal receiving states.

## Cross-Dock Behavior

Cross-dock to workorder enforces:

- non-closed workorder state
- part match or override permission inventory:override:part-match
- cumulative quantity cannot exceed expected

Cross-dock writes paired ledger entries on cross-dock location:

- GOODS_RECEIPT (+qty)
- GOODS_ISSUE (-qty)

## Verified Facts

- _Verified: pos-inventory ReceivingController endpoint mappings, PreAuthorize permissions, and EmitEvent ids._
- _Verified: pos-inventory ReceivingServiceImpl receiveItemsIntoStaging quantity-compare rules and variance creation._
- _Verified: pos-inventory ReceivingServiceImpl crossDockLineToWorkorder dual-ledger posting and guard behavior._
- _Verified: pos-inventory ReceivingSessionStatus, ReceivingLineStatus, and InventoryVarianceType enums._
