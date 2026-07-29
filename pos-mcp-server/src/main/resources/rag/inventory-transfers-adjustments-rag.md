---
rag_id: inventory.transfers-adjustments
rag_scope: inventory
required_permissions:
  - inventory:transfer:view
---

## Purpose

RAG id: inventory.transfers-adjustments
RAG scope: inventory
Required permissions: inventory:transfer:view
Audience: internal staff.

This document describes transfer-order and adjustment behavior in pos-inventory, including
dispatch/receive/short-close transitions and adjustment-request processing.

## Transfer Endpoints

Base path: /v1/inventory/transfer-orders

| Operation | Method and path | Permission | EmitEvent id |
| --- | --- | --- | --- |
| Create transfer | POST /v1/inventory/transfer-orders | inventory:transfer:create | INVENTORY_TRANSFER_ORDER_CREATE |
| Get transfer | GET /v1/inventory/transfer-orders/{transferOrderId} | inventory:transfer:view | none |
| List transfers | GET /v1/inventory/transfer-orders | inventory:transfer:view | none |
| Approve transfer | POST /v1/inventory/transfer-orders/{transferOrderId}/approve | inventory:transfer:dispatch | INVENTORY_TRANSFER_ORDER_APPROVE |
| Dispatch transfer | POST /v1/inventory/transfer-orders/{transferOrderId}/dispatch | inventory:transfer:dispatch | INVENTORY_TRANSFER_ORDER_DISPATCH |
| Receive transfer | POST /v1/inventory/transfer-orders/{transferOrderId}/receive | inventory:transfer:receive | INVENTORY_TRANSFER_ORDER_RECEIVE |
| Short-close transfer | POST /v1/inventory/transfer-orders/{transferOrderId}/short-close | inventory:transfer:short_close | INVENTORY_TRANSFER_ORDER_SHORT_CLOSE |
| Cancel transfer | POST /v1/inventory/transfer-orders/{transferOrderId}/cancel | inventory:transfer:create | INVENTORY_TRANSFER_ORDER_CANCEL |

## Stock Movement and Adjustment Endpoints

| Operation | Method and path | Permission | EmitEvent id |
| --- | --- | --- | --- |
| Create stock movement | POST /v1/inventory/stock-movements | inventory:stock_movement:create | INVENTORY_STOCK_MOVEMENT_CREATE |
| Create adjustment request | POST /v1/inventory/adjustments | inventory:adjustment:create | INVENTORY_ADJUSTMENT_REQUEST_CREATE |
| Approve adjustment request | POST /v1/inventory/adjustments/{adjustmentRequestId}/approve | inventory:adjustment:approve | INVENTORY_ADJUSTMENT_REQUEST_APPROVE |

## Transfer Status Tokens

TransferOrderStatus:

- DRAFT
- APPROVED
- DISPATCHED
- PARTIALLY_RECEIVED
- RECEIVED
- SHORT_CLOSED
- CANCELLED

TransferShortCloseDisposition:

- LOST_IN_TRANSIT
- RETURNED_TO_SOURCE

## Adjustment and Movement Tokens

AdjustmentRequestStatus:

- PENDING
- APPROVED
- REJECTED

AdjustmentStatus:

- PENDING_APPROVAL
- AUTO_APPROVED
- APPROVED
- POSTED
- REJECTED
- FAILED

MovementType:

- RECEIVE
- PUT_AWAY
- PICK
- ISSUE
- RETURN
- TRANSFER
- ADJUST

## Behavioral Highlights

- Dispatch gate depends on configuration pos.inventory.transfer.approval-required.
- Dispatch posts TRANSFER_OUT entries.
- Receive posts TRANSFER_IN entries and transitions to RECEIVED or PARTIALLY_RECEIVED.
- Short-close supports LOST_IN_TRANSIT and RETURNED_TO_SOURCE dispositions and ends at SHORT_CLOSED.
- Direct transfer movement in stock-movement path rejects cross-site transfer with
  CROSS_SITE_TRANSFER_REQUIRES_ORDER.
- Adjustment-request lifecycle in stock-movement service currently sets PENDING then APPROVED.

## Verified Facts

- _Verified: pos-inventory TransferOrderController and StockMovementController endpoint mappings, permissions, and EmitEvent ids._
- _Verified: pos-inventory TransferOrderServiceImpl dispatch/receive/short-close behavior and ledger posting patterns._
- _Verified: pos-inventory StockMovementServiceImpl movement-type handling and adjustment-request lifecycle guards._
- _Verified: pos-inventory TransferOrderStatus, TransferShortCloseDisposition, AdjustmentRequestStatus, AdjustmentStatus, and MovementType enums._
