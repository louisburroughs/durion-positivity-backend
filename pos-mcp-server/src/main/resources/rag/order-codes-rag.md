---
rag_id: order.codes
rag_scope: order
required_permissions:
  - order:order:view
---

## Purpose

RAG id: order.codes
RAG scope: order
Required permissions: order:order:view
Audience: internal staff.

This is a token-catalog document for order-domain lexical retrieval. It aggregates concrete
status, permission, reason-code, and event-id tokens from pos-order source.

## SalesOrderStatus

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

## OverrideStatus

- PENDING_APPROVAL
- APPROVED
- REJECTED
- APPLIED
- CANCELLED

## PriceOverrideReasonCode

- CUSTOMER_LOYALTY
- PRICE_MATCH
- PROMOTIONAL_PRICING
- PRICING_ERROR_CORRECTION
- VOLUME_DISCOUNT
- GOODWILL_ADJUSTMENT
- MANAGER_DISCRETION
- OTHER

## OrderPermissions Constants

- order:order:view
- order:order:create
- order:order:edit
- order:order:cancel
- order:order:discount
- order:order:quote
- order:order:checkout
- order:order:void
- order:order:charge_on_account
- order:line:view
- order:line:create
- order:line:edit
- order:line:delete
- order:session:open
- order:session:view
- order:session:cash_movement
- order:session:close
- order:session:approve_variance
- order:return:create
- order:return:approve
- order:return:view

## PriceOverridePermissions Constants

- order:price_override:apply
- order:price_override:approve
- order:price_override:view
- order:price_override:reject

## Order Event Tokens

- ORDER_CART_CREATE
- ORDER_CART_LIST
- ORDER_CART_ITEM_ADD
- ORDER_CART_ITEM_UPDATE
- ORDER_CART_ITEM_REMOVE
- ORDER_LINK_SOURCE
- ORDER_CART_QUOTE
- ORDER_CART_QUOTE_REOPEN
- ORDER_CART_DISCOUNT_APPLY
- ORDER_CART_DISCOUNT_REMOVE
- ORDER_CHECKOUT
- ORDER_VOID
- ORDER_PRICE_OVERRIDE_APPLY
- ORDER_PRICE_OVERRIDE_APPROVE
- ORDER_PRICE_OVERRIDE_REJECT
- ORDER_PRICE_OVERRIDE_SEARCH
- ORDER_PRICE_OVERRIDE_LIST_PENDING
- ORDER_CART_CANCEL_REQUEST
- ORDER_CART_CANCEL_RETRY
- ORDER_RETURN_CREATE
- ORDER_RETURN_GET
- ORDER_RETURN_LIST
- ORDER_RETURN_RETURNABLE
- ORDER_RETURN_APPROVE
- ORDER_RETURN_REJECT
- ORDER_RETURN_PROCESS
- ORDER_RETURN_RETRY

## Declared but Not Actively Transitioned

- OverrideStatus.APPLIED is declared.
- OverrideStatus.CANCELLED is declared.
- Current price-override service transitions assign PENDING_APPROVAL, APPROVED, and REJECTED.

## Verified Facts

- _Verified: pos-order SalesOrderStatus, OverrideStatus, and PriceOverrideReasonCode enums._
- _Verified: pos-order OrderPermissions and PriceOverridePermissions constants._
- _Verified: pos-order EventTypes constants and EventTypes.ALL_EVENT_TYPES registrations for listed order and price-override events._
