---
rag_id: order.lifecycle
rag_scope: order
required_permissions:
  - order:order:view
---

## Purpose

RAG id: order.lifecycle
RAG scope: order
Required permissions: order:order:view
Audience: internal staff.

This document describes the implemented sales-order lifecycle in pos-order: cart creation,
line editing, quote, checkout, settlement-driven completion, and void/cancel transitions.
It is implementation-bound and should be used for exact status/permission/event-token lookup.

## Lifecycle Endpoints and Permissions

All lifecycle endpoints are under /v1/orders.

| Operation | Method and path | Permission | EmitEvent id |
| --- | --- | --- | --- |
| Create cart | POST /v1/orders/carts | order:order:create | ORDER_CART_CREATE |
| List carts | GET /v1/orders/carts | order:order:view | ORDER_CART_LIST |
| Add line | POST /v1/orders/carts/{orderId}/items | order:line:create | ORDER_CART_ITEM_ADD |
| Update line quantity | PUT /v1/orders/carts/{orderId}/items/{lineId} | order:line:edit | ORDER_CART_ITEM_UPDATE |
| Remove line | DELETE /v1/orders/carts/{orderId}/items/{lineId} | order:line:delete | ORDER_CART_ITEM_REMOVE |
| Get cart | GET /v1/orders/carts/{orderId} | order:order:view | none |
| Apply discount | PUT /v1/orders/carts/{orderId}/discount | order:order:discount | ORDER_CART_DISCOUNT_APPLY |
| Remove discount | DELETE /v1/orders/carts/{orderId}/discount | order:order:discount | ORDER_CART_DISCOUNT_REMOVE |
| Quote cart | POST /v1/orders/carts/{orderId}/quote | order:order:quote | ORDER_CART_QUOTE |
| Reopen quote | POST /v1/orders/carts/{orderId}/quote/reopen | order:order:quote | ORDER_CART_QUOTE_REOPEN |
| Checkout | POST /v1/orders/{orderId}/checkout | order:order:checkout | ORDER_CHECKOUT |
| Void order | POST /v1/orders/{orderId}/void | order:order:void | ORDER_VOID |
| Link source | PATCH /v1/orders/carts/{orderId}/source | order:order:edit | ORDER_LINK_SOURCE |

## SalesOrderStatus Tokens

- DRAFT
- QUOTED
- PENDING_PAYMENT
- COMPLETED
- VOIDED
- CANCEL_REQUESTED
- WORKORDER_CANCELLED
- PAYMENT_REVERSED
- CANCELLED
- CANCEL_FAILED_WORKEXEC
- CANCEL_FAILED_BILLING
- CANCEL_REQUIRES_MANUAL_REVIEW

## Canonical Transition Rules

The state machine allows these transitions:

- DRAFT -> QUOTED, PENDING_PAYMENT, CANCEL_REQUESTED
- QUOTED -> DRAFT, PENDING_PAYMENT, CANCEL_REQUESTED
- PENDING_PAYMENT -> COMPLETED, VOIDED, CANCEL_REQUESTED
- CANCEL_REQUESTED -> WORKORDER_CANCELLED, PAYMENT_REVERSED, CANCELLED, CANCEL_FAILED_WORKEXEC, CANCEL_FAILED_BILLING
- WORKORDER_CANCELLED -> PAYMENT_REVERSED, CANCELLED, CANCEL_FAILED_BILLING
- PAYMENT_REVERSED -> CANCELLED
- CANCEL_FAILED_WORKEXEC -> CANCEL_REQUESTED, CANCEL_REQUIRES_MANUAL_REVIEW
- CANCEL_FAILED_BILLING -> CANCEL_REQUESTED, PAYMENT_REVERSED, CANCEL_REQUIRES_MANUAL_REVIEW
- CANCEL_REQUIRES_MANUAL_REVIEW -> CANCEL_REQUESTED
- COMPLETED -> none
- VOIDED -> none
- CANCELLED -> none

## Checkout and Settlement Facts

- Checkout requires Idempotency-Key and moves an eligible order to PENDING_PAYMENT.
- Checkout path performs validation and creates the fronting invoice.
- Payment completion is event-driven. The order is transitioned to COMPLETED only when
  amountPaid >= grandTotal while status is PENDING_PAYMENT.
- Over-settlement publishes an integrity alert.

## Not Modeled in This Doc

- Return/refund lifecycle details belong to order.returns-refunds.
- Workorder execution lifecycle belongs to pos-workorder.

## Verified Facts

- _Verified: pos-order SalesOrderController endpoint mappings, PreAuthorize permissions, and EmitEvent ids for cart, quote, checkout, and void operations._
- _Verified: pos-order SalesOrderStatus enum values and OrderStateMachine.ALLOWED transition map in OrderStateMachine._
- _Verified: pos-order SalesOrderServiceImpl.checkout performs idempotency checks and transitions to PENDING_PAYMENT through OrderStateMachine.transition._
- _Verified: pos-order PaymentEventsListener recomputeSettlement transitions PENDING_PAYMENT to COMPLETED on settled-in-full payment events._
