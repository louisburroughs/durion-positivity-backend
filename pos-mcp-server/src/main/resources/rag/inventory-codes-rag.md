---
rag_id: inventory.codes
rag_scope: inventory
required_permissions:
  - inventory:purchase_order:view
---

## Purpose

RAG id: inventory.codes
RAG scope: inventory
Required permissions: inventory:purchase_order:view
Audience: internal staff.

Token catalog for inventory-domain lexical retrieval. This document is intentionally dense.

## Enum Tokens

PurchaseOrderStatus:

- DRAFT
- APPROVED
- PARTIALLY_RECEIVED
- FULLY_RECEIVED
- CLOSED
- CANCELLED

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

## Wave 3 Permission Tokens

- inventory:purchase_order:create
- inventory:purchase_order:view
- inventory:purchase_order:approve
- inventory:purchase_order:receive
- inventory:receiving:create
- inventory:receiving:view
- inventory:receiving:complete
- inventory:issue:parts
- inventory:override:part-match
- inventory:transfer:create
- inventory:transfer:view
- inventory:transfer:dispatch
- inventory:transfer:receive
- inventory:transfer:short_close
- inventory:stock_movement:create
- inventory:adjustment:create
- inventory:adjustment:approve
- inventory:adjustment:override
- inventory:goods_receipt:override

## Wave 3 Event Tokens

- INVENTORY_PURCHASE_ORDER_CREATE
- INVENTORY_PURCHASE_ORDER_GET
- INVENTORY_PURCHASE_ORDER_LIST
- INVENTORY_PURCHASE_ORDER_APPROVE
- INVENTORY_PURCHASE_ORDER_REVISE
- INVENTORY_PURCHASE_ORDER_CANCEL
- INVENTORY_PURCHASE_ORDER_RECEIVE
- INVENTORY_RECEIVING_SESSION_CREATE
- INVENTORY_RECEIVING_SESSION_GET
- INVENTORY_RECEIVING_SESSION_COMPLETE
- INVENTORY_RECEIVING_CROSSDOCK
- INVENTORY_TRANSFER_ORDER_CREATE
- INVENTORY_TRANSFER_ORDER_APPROVE
- INVENTORY_TRANSFER_ORDER_DISPATCH
- INVENTORY_TRANSFER_ORDER_RECEIVE
- INVENTORY_TRANSFER_ORDER_SHORT_CLOSE
- INVENTORY_TRANSFER_ORDER_CANCEL
- INVENTORY_STOCK_MOVEMENT_CREATE
- INVENTORY_ADJUSTMENT_REQUEST_CREATE
- INVENTORY_ADJUSTMENT_REQUEST_APPROVE

## Split Source-of-Truth Note

- Runtime permission registration uses permissions.yaml via PermissionInitializer.
- InventoryPermissionRegistry is partial and does not include every active controller permission.

## Verified Facts

- _Verified: pos-inventory enums listed in internal/enums._
- _Verified: pos-inventory Wave 3 permission tokens from permissions.yaml and controller PreAuthorize checks._
- _Verified: pos-inventory Wave 3 event ids from controller EmitEvent and EventTypes registrations._
