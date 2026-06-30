# Order Guide

## Purpose
RAG id: `order.guide`  
RAG scope: `order`  
Required permissions: `order:order:view`  
Audience: internal staff.  
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This document grounds order-domain questions for the natural-language assistant. It focuses on purchase/order context, order lines, receiving dependencies, and reconciliation hand-offs. It does not define customer-facing sales policy.

## Core concepts
An order records requested goods or services before downstream fulfillment, receipt, or reconciliation. In tire/service operations, order questions commonly involve purchase orders, order lines, supplier references, SKU quantities, location, expected receipt, and exceptions. The verified permission samples include `order:order:view`, `order:order:create`, and `order:line:create`. Use view-level access for retrieval and reserve create actions for workflows that have explicit user intent and permission.

An order line is the specific SKU/service, quantity, unit, cost or expected price, and target location associated with an order. The order header gives the supplier/customer context, dates, status, and reference numbers. The assistant should ask for the order number, PO number, supplier, SKU, or location when a user asks a broad question such as "what happened to my order?"

## Staff questions and answer patterns
Common questions:

| Question | Interpretation |
|---|---|
| "Show this PO." | Retrieve order header, lines, status, supplier, and receiving status when visible. |
| "What has not been received yet?" | Compare ordered quantity against received quantity; do not assume receipt without inventory evidence. |
| "Why is this order stuck?" | Look for missing supplier reference, open lines, receiving exception, damaged goods, AP mismatch, or approval issue. |
| "Can I add this line?" | Requires an order-line creation permission and source validation; otherwise explain the needed information. |
| "What did we order for this location?" | Filter by location, order status, date, and SKU/category if provided. |

## Hand-off to inventory receiving
Order data alone does not prove stock is on hand. The inventory guide states that receipt converts in-transit inventory into on-hand inventory and that all inventory changes should be modeled as auditable events. When goods arrive, the receiving operation should identify the PO/order, SKU, quantity, UoM, location, and any lot/serial data required by the item.

The assistant should distinguish these states: ordered, in transit, partially received, fully received, received with exception, cancelled, and reconciled. If the receiving source is unavailable, answer from the order perspective only and say that receipt status needs inventory visibility.

## Hand-off to accounting reconciliation
Reconciliation compares ordered quantity and expected cost with received quantity and supplier invoice/AP information. A PO can be valid while reconciliation is blocked by missing receipt, quantity mismatch, cost mismatch, wrong SKU, damaged goods, duplicate supplier invoice, or missing payable. Accounting treatment should follow the accounting RAG rule that posted entries are immutable and corrections require reversing or compensating entries.

## Error and exception patterns
The assistant should flag these order-domain risks:

- Duplicate order or duplicate line request.
- Missing or ambiguous supplier/account reference.
- SKU mismatch between order line and received item.
- Quantity mismatch between order, receipt, and invoice.
- UoM mismatch between cases, eaches, tires, or other units.
- Location mismatch where items were ordered for one location but received elsewhere.
- Missing permission for read or create actions.

## Verified facts (pos-order / pos-inventory)
- **Sales-order statuses** (`pos-order` `SalesOrderStatus`): `DRAFT, QUOTED, COMPLETED, VOIDED, CANCEL_REQUESTED, WORKORDER_CANCELLED, PAYMENT_REVERSED, CANCELLED, CANCEL_FAILED_WORKEXEC, CANCEL_FAILED_BILLING, CANCEL_REQUIRES_MANUAL_REVIEW`.
- **Order permissions** (`pos-order` permissions.yaml): `order:order:{view,create,edit,cancel}`, `order:line:{view,create,edit,delete,enter_manual_price}`, `order:price_override:{view,apply,approve,reject}`. Order "approval" is price-override-scoped (apply → approve/reject); there is no generic order approve/reject.
- **Purchase orders are NOT in pos-order** — they are owned by `pos-inventory` (`inventory:purchase_order:{create,view,approve,receive}`). `PurchaseOrderStatus`: `DRAFT, APPROVED, PARTIALLY_RECEIVED, FULLY_RECEIVED, CLOSED, CANCELLED`. PO number = 8-char base-36 sequence (see glossary); receipt number = `GR-<UUID8>`.

> TODO(verify): exact pos-order OpenAPI operationIds/paths for tool naming (read pos-order/openapi.yaml).
