# Returns and Refunds Guide

## Purpose
RAG id: `order.returns-refunds`
RAG scope: `order`
Required permissions: `order:order:view`
Audience: internal staff.
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This document grounds returns- and refunds-domain questions for the natural-language assistant: how a
customer return against a completed order is created, capped, approved, refunded, and restocked. It
describes the `pos-order` returns implementation (parity stories F1/F2, issues #1086/#1087, spec
R5.1–R5.5). It does not define customer-facing return policy windows, restocking fees, or core charges
— those are not modeled in `pos-order` and must not be inferred from this document.

## Core concepts
A return order is a line-by-line request to send goods back against exactly one original sales order,
producing a refund. A return is only allowed against an original order in `COMPLETED` status; a return
against any other status is rejected. Each return line links to one sold line on the original order and
is capped at that sold line's un-refunded remainder.

The assistant should ask for the original order number/id, the specific line(s) and return quantity, the
condition (restock, scrap, or warranty), and the refund method when a user asks a broad question such as
"how do I return this?"

## Endpoints and permissions
All returns endpoints live under `/v1/returns` (class-level `isAuthenticated()`, with a per-method
permission):

| Operation | Method / path | Permission |
|---|---|---|
| Create a return | `POST /v1/returns` | `order:return:create` |
| Get a return by id | `GET /v1/returns/{returnOrderId}` | `order:return:view` |
| List returns for an order | `GET /v1/returns?originalOrderId=` | `order:return:view` |
| Per-line returnable quantities | `GET /v1/returns/returnable?orderId=` | `order:return:view` |
| Approve a pending return | `POST /v1/returns/{id}/approve` | `order:return:approve` |
| Reject a pending return | `POST /v1/returns/{id}/reject` | `order:return:approve` |
| Process the saga (refund + restock signal) | `POST /v1/returns/{id}/process` | `order:return:create` |
| Retry after a refund failure | `POST /v1/returns/{id}/retry` | `order:return:create` |

Create supports an `Idempotency-Key` header: a replayed key returns the original return rather than
creating a second one. An over-cap create returns HTTP 422 listing each line's `returnableQty`.

## The return cap (per-line remainder)
Each return line is capped at the sold line's remaining un-returned quantity:
`returnableQty = soldQuantity − Σ returnQty of prior returns for that sold line`, where prior returns in
`CANCELLED` or `REJECTED` status do **not** count against the cap. The sold line is row-locked at
creation so concurrent returns of the same remainder serialize. A request exceeding the cap fails the
whole create (no partial return). Duplicate return lines for the same sold line are rejected — the
quantity must be combined into a single line.

A line is returnable when its `returnable` flag is `TRUE`; not returnable when `FALSE`; and when the flag
is null it is returnable unless the line was sourced from a workorder (`SourceType.WORKORDER`). A
non-returnable line submitted with condition `WARRANTY` is routed out as a warranty claim (to
pos-warranty), not processed as a counter return.

## Refund amount
Each line's refund is pro-rata on the sold line total (tax included):
`lineRefund = lineTotal × returnQty ÷ soldQuantity`, rounded HALF_UP at scale 4. The return's total
refund is the sum of its line refunds.

## Refund methods
Refund method is one of `ORIGINAL_TENDER`, `STORE_CREDIT`, or `ON_ACCOUNT_CREDIT`.

- `ORIGINAL_TENDER` reverses the original order's settled tender via the invoicing service, per payment
  intent, netting settled minus reversed amounts and refunding the largest net intents first up to the
  return total. A missing original invoice or insufficient settled tender parks the return at
  `REFUND_FAILED`.
- `STORE_CREDIT` and `ON_ACCOUNT_CREDIT` require a customer on the return. The saga records the refund
  intent; the credit-issuance artifacts (store-credit ledger / AR credit memo) are owned by pos-invoice /
  pos-accounting as a paired follow-up.

## Status lifecycle and approval
Return status is one of `PENDING_APPROVAL`, `RETURN_REQUESTED`, `REFUND_ISSUED`, `COMPLETED`,
`REFUND_FAILED`, `REJECTED`, `CANCELLED`.

Creation lands in `RETURN_REQUESTED`, or in `PENDING_APPROVAL` when the total refund exceeds the approval
threshold (default `$250.00`, `pos.order.return.approval-threshold`). Approving a pending return moves it
to `RETURN_REQUESTED`; rejecting it moves it to `REJECTED` with a reason. The processing saga advances
`RETURN_REQUESTED → REFUND_ISSUED → COMPLETED`; a refund failure parks the return at `REFUND_FAILED`
(retryable via retry) **before any stock movement**. The refund must succeed before any stock signal is
emitted.

## Restock hand-off to inventory
Restock is event-driven and fire-and-forget: on completion the saga emits `order.order.returned`, which
`pos-inventory` consumes to restock. Lines with condition `RESTOCK` are restocked; `SCRAP` lines are
skipped; there is no synchronous pos-order → pos-inventory stock call (ADR-0044). If a caller asks whether
stock is back on hand, answer from the return's completion state and say that on-hand confirmation needs
inventory visibility.

## Not modeled here
Core charges (a deposit on a rebuildable/exchangeable part), restocking fees, and time-boxed return
windows are **not** implemented in `pos-order` and are not covered by this document. The assistant must
not invent a core-charge workflow, a restocking-fee calculation, or a return-window rule; if asked, it
should say the platform does not model these and offer the verified returns behaviour above.

## Verified facts (pos-order)
- **Returns are line-by-line against one COMPLETED sales order**, per-line capped at the un-refunded
  remainder (row-locked at creation); prior `CANCELLED`/`REJECTED` returns do not consume the cap.
_Verified: `pos-order` `ReturnOrderServiceImpl.createReturn`, `ReturnOrderServiceImpl.CAP_EXCLUDED_STATUSES`._
- **Refund per line is pro-rata tax-included:** `lineTotal × returnQty ÷ soldQuantity`, HALF_UP scale 4.
_Verified: `pos-order` `ReturnOrderServiceImpl.perLineRefund`._
- **Return status machine** is `PENDING_APPROVAL, RETURN_REQUESTED, REFUND_ISSUED, COMPLETED, REFUND_FAILED, REJECTED, CANCELLED`; creation parks in `PENDING_APPROVAL` when the refund exceeds the approval threshold (default `$250.00`), else `RETURN_REQUESTED`.
_Verified: `pos-order` `ReturnOrderStatus`, `ReturnOrderServiceImpl.createReturn` (`pos.order.return.approval-threshold:250.00`)._
- **Refund methods** are `ORIGINAL_TENDER, STORE_CREDIT, ON_ACCOUNT_CREDIT`; `ORIGINAL_TENDER` reverses settled tender per payment intent (settled − reversed, largest first) and parks at `REFUND_FAILED` on missing invoice or insufficient tender.
_Verified: `pos-order` `RefundMethod`, `ReturnOrderServiceImpl.issueRefund`, `ReturnOrderServiceImpl.reverseOriginalTender`._
- **Restock is event-driven:** completion emits `order.order.returned`; pos-inventory restocks `RESTOCK` lines and skips `SCRAP` (ADR-0044, no synchronous stock call). Return conditions are `RESTOCK, SCRAP, WARRANTY`.
_Verified: `pos-order` `ReturnOrderServiceImpl.runSaga`, `ReturnCondition`._
- **Returns permissions** are `order:return:{create,view,approve}`; the returns endpoints are under `/v1/returns` and emit `ORDER_RETURN_{CREATE,GET,LIST,RETURNABLE,APPROVE,REJECT,PROCESS,RETRY}`.
_Verified: `pos-order` `OrderPermissions`, `ReturnOrderController`, `permissions.yaml`._
- **Core charges are NOT modeled in pos-order** — no core-charge entity, permission, or calculation exists.
_Verified: `pos-order` source (no core-charge implementation)._
