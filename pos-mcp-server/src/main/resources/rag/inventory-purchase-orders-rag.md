---
rag_id: inventory.purchase-orders
rag_scope: inventory
required_permissions:
  - order:purchase_order:view
---

# Purchase Orders

## Purpose

RAG id: `inventory.purchase-orders`
RAG scope: `inventory`
Required permissions: `order:purchase_order:view`
Audience: internal staff who order stock from vendors or receive it.
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This document describes the purchase-order lifecycle as it is implemented: the endpoints, the permission codes, the
status values and what changes them, and the event identifiers. The purchase order is owned by pos-order. Receiving
goods against a purchase order is owned by pos-inventory. Where the platform has no behaviour for something, this
document says so instead of describing it.

## Ownership

- The purchase-order aggregate lives in pos-order. Its controller is `PurchaseOrderController` and its base path is
  `/v1/orders/purchase-orders`. Every permission code that gates a purchase-order endpoint has the form
  `order:purchase_order:<action>`.
- pos-inventory does not own purchase orders and cannot create or change one. It stores a read-only copy of each
  order, which it builds from the `purchaseorder.updated` facts pos-order publishes on the `order.events.v1` topic,
  and it uses that copy to check an order before goods are received against it.
- Receiving is the part pos-inventory still owns. pos-inventory records what arrived and publishes a
  `goodsreceipt.recorded` fact on `inventory.events.v1`. pos-order consumes that fact and updates the order's open
  quantities, open balance and status.
- The earlier purchase-order endpoints of pos-inventory, and its create, view and approve permission codes for
  purchase orders, no longer exist. One earlier code, `inventory:purchase_order:receive`, is still declared in
  pos-inventory and gates no endpoint; "Receiving against a purchase order" below says what gates receiving.

## Endpoints, permissions and events

All paths below are served by pos-order.

| Operation | Method and path | Permission | EmitEvent id |
| --- | --- | --- | --- |
| Create a purchase order | `POST /v1/orders/purchase-orders` | `order:purchase_order:create` | `ORDER_PURCHASE_ORDER_CREATE` |
| Get one purchase order | `GET /v1/orders/purchase-orders/{poId}` | `order:purchase_order:view` | `ORDER_PURCHASE_ORDER_GET` |
| List purchase orders | `GET /v1/orders/purchase-orders` | `order:purchase_order:view` | `ORDER_PURCHASE_ORDER_LIST` |
| Summarize purchase orders | `GET /v1/orders/purchase-orders/summary` | `order:purchase_order:view` | `ORDER_PURCHASE_ORDER_SUMMARY` |
| Approve a purchase order | `POST /v1/orders/purchase-orders/{poId}/approve` | `order:purchase_order:approve` | `ORDER_PURCHASE_ORDER_APPROVE` |
| Revise a purchase order | `POST /v1/orders/purchase-orders/{poId}/revisions` | `order:purchase_order:create` | `ORDER_PURCHASE_ORDER_REVISE` |
| Cancel a purchase order | `POST /v1/orders/purchase-orders/{poId}/cancel` | `order:purchase_order:approve` | `ORDER_PURCHASE_ORDER_CANCEL` |
| Transmit to the vendor | `POST /v1/orders/purchase-orders/{poId}/transmit` | `order:purchase_order:transmit` | `ORDER_PURCHASE_ORDER_TRANSMIT` |
| Read live vendor availability | `GET /v1/orders/purchase-orders/{poId}/supplier-availability` | `order:purchase_order:availability_view` | `ORDER_PURCHASE_ORDER_AVAILABILITY` |
| List transmission events | `GET /v1/orders/purchase-orders/{poId}/transmission-events` | `order:purchase_order:view` | `ORDER_PURCHASE_ORDER_TRANSMISSION_EVENTS` |

## Permission codes

pos-order registers five purchase-order permission codes.

| Code | What it allows |
| --- | --- |
| `order:purchase_order:view` | Get, list and summarize purchase orders, and list an order's transmission events |
| `order:purchase_order:create` | Create a purchase order and revise one |
| `order:purchase_order:approve` | Approve a purchase order and cancel one |
| `order:purchase_order:transmit` | Send an approved purchase order to its vendor |
| `order:purchase_order:availability_view` | Ask a vendor what it can supply against the lines of a purchase order |

Approving and transmitting are separate codes. Approving commits the business to the spend and sends nothing to
anyone. Transmitting puts the order in front of the vendor.

## Purchase order status values

`PurchaseOrderStatus` values:

| Status | How an order reaches it |
| --- | --- |
| `DRAFT` | Set when the order is created |
| `APPROVED` | Set by the approve endpoint |
| `PARTIALLY_RECEIVED` | Set by pos-order when it applies a goods receipt and at least one line still has an open quantity |
| `FULLY_RECEIVED` | Set by pos-order when it applies a goods receipt and no line has an open quantity left |
| `CLOSED` | Declared; no current write path sets it |
| `CANCELLED` | Set by the cancel endpoint |

`APPROVED` and `PARTIALLY_RECEIVED` are the two statuses that count as incoming supply.

## Lifecycle rules

- **Create.** The order starts in `DRAFT` with a generated PO number and version number 1. Totals are computed in
  minor currency units and the open balance starts equal to the grand total. The currency must be an ISO 4217 code;
  anything else is refused with 400 `PURCHASE_ORDER_BAD_REQUEST`.
- **Approve.** Only a `DRAFT` order can be approved. Any other status is refused with 409
  `PURCHASE_ORDER_INVALID_STATE`. Approval records the approver, the approval time and the optional approval notes.
- **Revise.** The service applies no status check. A revision adds 1 to the version number, overwrites the header
  fields, replaces every line, recomputes the totals and resets the open balance to the value of the new lines. It
  does not change the status.
- **Cancel.** An order that is `FULLY_RECEIVED` or `CLOSED` cannot be cancelled; the request is refused with 409
  `PURCHASE_ORDER_INVALID_STATE`. An order in any other status, including `DRAFT`, moves to `CANCELLED`. Cancelling
  does not set the open quantity of the lines to zero.
- **Receive.** No pos-order endpoint receives goods. `PARTIALLY_RECEIVED` and `FULLY_RECEIVED` are reached only when
  pos-order applies a `goodsreceipt.recorded` fact from pos-inventory. Each receipt line reduces the open quantity of
  the order line it names, and the receipt's accrued amount reduces the open balance; neither goes below zero. The
  status is decided from the lines, not from the balance.
- Every one of these changes publishes a `purchaseorder.updated` fact that carries the order's full state.
- A purchase order that does not exist is answered with 404 `PURCHASE_ORDER_NOT_FOUND`.

## Listing and summarizing

- The list endpoint returns one page of orders. Its optional filters are `vendorId`, `status`, `currency` (matched
  without regard to case) and `locationId` (the ship-to location).
- The summary endpoint returns totals over every matching order: order and line counts, units ordered, units still
  open, units received, grand total and open balance, with the same figures per status. Its optional filters are
  `vendorId` and `status`. Without a `status` filter it counts only `APPROVED` and `PARTIALLY_RECEIVED` orders.
- Use the summary endpoint for any total. A sum over one page of the list endpoint is partial.

## Sending an order to the vendor

The transmit endpoint asks pos-supplier to send the order to the vendor and answers 202. The vendor's answer arrives
later. The request is refused with 422 and one of these codes when the order cannot be sent:

| Code | Reason |
| --- | --- |
| `SUPPLIER_REF_MISSING` | The order names no supplier reference |
| `PURCHASE_ORDER_NOT_APPROVED` | The order is not `APPROVED` or `PARTIALLY_RECEIVED` |
| `TRANSMISSION_IN_FLIGHT` | A transmission was requested and has no answer yet |
| `TRANSMISSION_AWAITING_REVIEW` | An earlier transmission is waiting for an operator's decision |
| `ARTICLE_NOT_IDENTIFIABLE` | A line has neither an EAN nor a vendor article code |
| `FRACTIONAL_QUANTITY` | A line's outstanding quantity is not a whole number |
| `TRANSMISSION_UNAVAILABLE` | The deployment has no event publishing, so nothing can reach the vendor |

`TransmissionState` values are `NOT_TRANSMITTED`, `REQUESTED`, `CONFIRMED`, `REJECTED` and `MANUAL_REVIEW`. The
transmission events endpoint returns the order's vendor timeline, ordered by the time the vendor observed each event.

## Receiving against a purchase order

Receiving is implemented in pos-inventory.

- **Goods receipt.** `POST /v1/inventory/goods-receipts` records a receipt against a purchase order. It needs
  `inventory:goods_receipt:create` and emits `INVENTORY_GOODS_RECEIPT_CREATE`. The order must be `APPROVED` or
  `PARTIALLY_RECEIVED` in pos-inventory's copy; otherwise the request is refused with 400. When the order has not
  reached that copy yet, the answer is 503 `PURCHASE_ORDER_REPLICATION_PENDING` with a `Retry-After` header, which
  means "not yet", not "no". A receipt whose value exceeds the order's open balance also needs
  `inventory:goods_receipt:override`.
- This endpoint is what publishes `goodsreceipt.recorded`, so it is what moves the order to `PARTIALLY_RECEIVED` or
  `FULLY_RECEIVED` in pos-order.
- **Receiving session.** `POST /v1/inventory/receiving/sessions` (`inventory:receiving:create`) starts a line-by-line
  receiving session. The receiving document (`inventory.receiving`) describes it. The receiving-session service does
  not publish `goodsreceipt.recorded`.
- **Advance shipping notice.** `POST /v1/inventory/asns` (`inventory:asn:create`) is refused unless every purchase
  order it names is `APPROVED`.
- **Purchase suggestions.** `POST /v1/inventory/purchase-suggestions/convert` turns accepted replenishment suggestions
  into purchase orders. The caller needs both `inventory:replenishment:manage` and `order:purchase_order:create`.
  pos-inventory sends a command and pos-order creates the order.
- `inventory:purchase_order:receive` is still declared in pos-inventory's permission manifest, where it is marked
  deprecated, and no endpoint is gated by it. The event id `INVENTORY_PURCHASE_ORDER_RECEIVE` is still registered in
  pos-inventory, and no controller emits it.

## Verified facts

- _Verified: pos-order `PurchaseOrderController` mappings, `@PreAuthorize` codes and `@EmitEvent` ids;
  `PurchaseOrderPermissions`, pos-order `permissions.yaml` and `EventTypes`._
- _Verified: pos-order `PurchaseOrderServiceImpl` create, approve, revise, cancel, list and summary rules;
  `PurchaseOrderStatus`; no code sets `PurchaseOrderStatus.CLOSED`._
- _Verified: pos-order `InventoryEventsListener` applies `goodsreceipt.recorded` and decides `PARTIALLY_RECEIVED` or
  `FULLY_RECEIVED` from the lines' open quantities._
- _Verified: pos-order `PurchaseOrderTransmissionService` guards, `PurchaseOrderNotTransmittableException` codes,
  `PurchaseOrderExceptionHandler` statuses and `TransmissionState`._
- _Verified: pos-inventory `AsnController` and `AsnServiceImpl` (goods receipt and ASN guards, the only caller of
  `GoodsReceiptFactPublisher`), `ReceivingController`, `PurchaseSuggestionController`, `permissions.yaml` and
  `EventTypes`._

## Sources

Platform sources (repository-relative):

- `pos-order/src/main/java/com/positivity/order/internal/controller/PurchaseOrderController.java`
- `pos-order/src/main/java/com/positivity/order/internal/controller/PurchaseOrderExceptionHandler.java`
- `pos-order/src/main/java/com/positivity/order/internal/service/PurchaseOrderServiceImpl.java`
- `pos-order/src/main/java/com/positivity/order/internal/service/PurchaseOrderTransmissionService.java`
- `pos-order/src/main/java/com/positivity/order/internal/service/InventoryEventsListener.java`
- `pos-order/src/main/java/com/positivity/order/internal/service/PurchaseOrderFactPublisher.java`
- `pos-order/src/main/java/com/positivity/order/internal/service/PurchaseOrderCommandListener.java`
- `pos-order/src/main/java/com/positivity/order/internal/enums/PurchaseOrderStatus.java`
- `pos-order/src/main/java/com/positivity/order/internal/enums/TransmissionState.java`
- `pos-order/src/main/java/com/positivity/order/internal/security/PurchaseOrderPermissions.java`
- `pos-order/src/main/java/com/positivity/order/internal/config/EventTypes.java`
- `pos-order/src/main/resources/permissions.yaml`
- `pos-inventory/src/main/java/com/positivity/inventory/internal/controller/AsnController.java`
- `pos-inventory/src/main/java/com/positivity/inventory/internal/controller/ReceivingController.java`
- `pos-inventory/src/main/java/com/positivity/inventory/internal/controller/PurchaseSuggestionController.java`
- `pos-inventory/src/main/java/com/positivity/inventory/internal/receiving/service/AsnServiceImpl.java`
- `pos-inventory/src/main/java/com/positivity/inventory/internal/service/PurchaseOrderProjectionListener.java`
- `pos-inventory/src/main/java/com/positivity/inventory/internal/config/EventTypes.java`
- `pos-inventory/src/main/resources/permissions.yaml`
