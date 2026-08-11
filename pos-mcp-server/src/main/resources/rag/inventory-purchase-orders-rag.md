---
rag_id: inventory.purchase-orders
rag_scope: inventory
required_permissions:
  - inventory:purchase_order:view
---

## Purpose

RAG id: inventory.purchase-orders
RAG scope: inventory
Required permissions: inventory:purchase_order:view
Audience: internal staff.

This document describes purchase-order lifecycle behavior implemented in pos-inventory,
including endpoints, permission gates, status transitions, and event identifiers.

## Endpoints, Permissions, and Events

Base path: /v1/inventory/purchase-orders

| Operation | Method and path | Permission | EmitEvent id |
| --- | --- | --- | --- |
| Create PO | POST /v1/inventory/purchase-orders | inventory:purchase_order:create | INVENTORY_PURCHASE_ORDER_CREATE |
| Get PO | GET /v1/inventory/purchase-orders/{poId} | inventory:purchase_order:view | INVENTORY_PURCHASE_ORDER_GET |
| List POs | GET /v1/inventory/purchase-orders | inventory:purchase_order:view | INVENTORY_PURCHASE_ORDER_LIST |
| Approve PO | POST /v1/inventory/purchase-orders/{poId}/approve | inventory:purchase_order:approve | INVENTORY_PURCHASE_ORDER_APPROVE |
| Revise PO | POST /v1/inventory/purchase-orders/{poId}/revisions | inventory:purchase_order:create | INVENTORY_PURCHASE_ORDER_REVISE |
| Cancel PO | POST /v1/inventory/purchase-orders/{poId}/cancel | inventory:purchase_order:approve | INVENTORY_PURCHASE_ORDER_CANCEL |
| Receive PO | POST /v1/inventory/purchase-orders/{poId}/receive | inventory:purchase_order:receive | INVENTORY_PURCHASE_ORDER_RECEIVE |

## PurchaseOrderStatus Tokens

- DRAFT
- APPROVED
- PARTIALLY_RECEIVED
- FULLY_RECEIVED
- CLOSED
- CANCELLED

## Lifecycle Rules

- Create initializes DRAFT.
- Approve requires DRAFT and transitions to APPROVED.
- Receive rejects DRAFT and CANCELLED.
- Receive sets:
  - FULLY_RECEIVED when all open quantities are zero or below.
  - PARTIALLY_RECEIVED otherwise.
- Cancel rejects FULLY_RECEIVED and CLOSED, and transitions to CANCELLED when allowed.

## Status Reachability Note

- CLOSED is declared in PurchaseOrderStatus and checked by cancel guard.
- No in-module write transition to CLOSED was found in current pos-inventory main source.

## Verified Facts

- _Verified: pos-inventory PurchaseOrderController endpoint mappings, PreAuthorize permissions, and EmitEvent ids._
- _Verified: pos-inventory PurchaseOrderServiceImpl create, approve, receive, and cancel status guard logic._
- _Verified: pos-inventory PurchaseOrderStatus enum tokens including CLOSED._
