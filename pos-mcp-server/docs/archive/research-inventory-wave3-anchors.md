---
title: Research Inventory Wave 3 Anchors
issue: 1124
wave: 3
date: 2026-07-29
sourceBoundary: pos-inventory only
---

## Scope

- Objective: Source-verified anchors for Wave 3 inventory docs.
- Target docs:
  - inventory.purchase-orders
  - inventory.receiving
  - inventory.transfers-adjustments
  - inventory.codes
- Source boundary enforced: only files under pos-inventory were used.

## Verified source inventory table

| Source file | Anchor focus | Why it matters |
| --- | --- | --- |
| pos-inventory/src/main/java/com/positivity/inventory/internal/controller/PurchaseOrderController.java | lines 36, 45-50, 78-83, 108-113, 128-133, 175-180, 216-221, 252-257 | Canonical PO endpoints, PreAuthorize strings, EmitEvent ids |
| pos-inventory/src/main/java/com/positivity/inventory/internal/service/PurchaseOrderServiceImpl.java | lines 81, 166-170, 290-294, 346-365, 453-460 | PO lifecycle transitions, receive and cancel guards, status transitions |
| pos-inventory/src/main/java/com/positivity/inventory/internal/enums/PurchaseOrderStatus.java | enum constants | Exact PO status token payloads |
| pos-inventory/src/main/java/com/positivity/inventory/internal/controller/ReceivingController.java | lines 34, 43-48, 88-93, 125-130, 180-185 | Receiving endpoints, permissions, EmitEvent ids |
| pos-inventory/src/main/java/com/positivity/inventory/internal/service/ReceivingServiceImpl.java | lines 119-226, 230-365, 558-581 | receiveItemsIntoStaging behavior, variance semantics, crossDock path, closed workorder checks |
| pos-inventory/src/main/java/com/positivity/inventory/internal/enums/ReceivingSessionStatus.java | enum constants | Session status tokens |
| pos-inventory/src/main/java/com/positivity/inventory/internal/enums/ReceivingLineStatus.java | enum constants | Receiving line status tokens |
| pos-inventory/src/main/java/com/positivity/inventory/internal/enums/InventoryVarianceType.java | enum constants | Variance token payloads |
| pos-inventory/src/main/java/com/positivity/inventory/internal/controller/TransferOrderController.java | lines 46, 60-65, 118-122, 148-152, 180-185, 227-232, 293-298, 361-366, 433-438 | Transfer endpoints, permissions, EmitEvent ids |
| pos-inventory/src/main/java/com/positivity/inventory/internal/service/TransferOrderServiceImpl.java | lines 163-166, 214-247, 250-330, 334-375, 377-534, 535-545 | Dispatch/receive/short-close behavior and ledger movement facts |
| pos-inventory/src/main/java/com/positivity/inventory/internal/controller/StockMovementController.java | lines 45-50, 71-76, 116-121 | Stock movement and adjustment endpoints, permissions, EmitEvent ids |
| pos-inventory/src/main/java/com/positivity/inventory/internal/service/StockMovementServiceImpl.java | lines 61-123, 154-188, 200-219, 228-240 | Direct movement logic, transfer guard, adjustment approval posting |
| pos-inventory/src/main/java/com/positivity/inventory/internal/enums/TransferOrderStatus.java | enum + helper methods | Transfer lifecycle token payloads and state predicates |
| pos-inventory/src/main/java/com/positivity/inventory/internal/enums/TransferShortCloseDisposition.java | enum constants | Short-close disposition token payloads |
| pos-inventory/src/main/java/com/positivity/inventory/internal/enums/AdjustmentRequestStatus.java | enum constants | Adjustment request status tokens |
| pos-inventory/src/main/java/com/positivity/inventory/internal/enums/AdjustmentStatus.java | enum constants | Cycle-count adjustment status tokens |
| pos-inventory/src/main/java/com/positivity/inventory/internal/enums/MovementType.java | enum constants | Movement type tokens |
| pos-inventory/src/main/java/com/positivity/inventory/internal/config/EventTypes.java | lines 42-60, 205-261 | Declared event type registry vs emitted ids |
| pos-inventory/src/main/java/com/positivity/inventory/internal/security/InventoryPermissionRegistry.java | lines 33-51, 126-148, 175, 190, 459 | Permission constants source for partial code paths |
| pos-inventory/src/main/java/com/positivity/inventory/internal/config/PermissionInitializer.java | lines 29-42 | Confirms runtime permission registration comes from permissions.yaml |
| pos-inventory/src/main/resources/permissions.yaml | lines 5-107 | Canonical permission code list used at startup |

## inventory.purchase-orders facts

### Controller endpoints, permissions, EmitEvent ids

Base mapping: /v1/inventory/purchase-orders (PurchaseOrderController line 36)

| Endpoint | Permission | EmitEvent id | Anchor |
| --- | --- | --- | --- |
| POST /v1/inventory/purchase-orders | inventory:purchase_order:create | INVENTORY_PURCHASE_ORDER_CREATE | controller lines 45, 49-50 |
| GET /v1/inventory/purchase-orders/{poId} | inventory:purchase_order:view | INVENTORY_PURCHASE_ORDER_GET | controller lines 78, 82-83 |
| GET /v1/inventory/purchase-orders | inventory:purchase_order:view | INVENTORY_PURCHASE_ORDER_LIST | controller lines 108, 112-113 |
| POST /v1/inventory/purchase-orders/{poId}/approve | inventory:purchase_order:approve | INVENTORY_PURCHASE_ORDER_APPROVE | controller lines 128, 132-133 |
| POST /v1/inventory/purchase-orders/{poId}/revisions | inventory:purchase_order:create | INVENTORY_PURCHASE_ORDER_REVISE | controller lines 175, 179-180 |
| POST /v1/inventory/purchase-orders/{poId}/cancel | inventory:purchase_order:approve | INVENTORY_PURCHASE_ORDER_CANCEL | controller lines 216, 220-221 |
| POST /v1/inventory/purchase-orders/{poId}/receive | inventory:purchase_order:receive | INVENTORY_PURCHASE_ORDER_RECEIVE | controller lines 252, 256-257 |

### Service behavior anchors

- Create initializes status to DRAFT.
  - PurchaseOrderServiceImpl line 81.
- Approve enforces DRAFT-only and sets status APPROVED.
  - Guard: lines 166-168.
  - Transition: line 170.
- Receive path validates status and blocks DRAFT and CANCELLED.
  - receivePurchaseOrder invokes validateReceivableStatus: lines 346 and 364-367.
- Receiving outcome status logic:
  - FULLY_RECEIVED when all line open quantities are zero or below: lines 453-457.
  - PARTIALLY_RECEIVED otherwise: line 459.
- Cancel guard blocks cancellation for FULLY_RECEIVED and CLOSED, then sets CANCELLED.
  - Guard: line 290.
  - Transition: line 294.

### CLOSED reachability detail

- PurchaseOrderStatus enum declares CLOSED.
  - PurchaseOrderStatus enum constant list.
- Within PurchaseOrderServiceImpl, CLOSED is checked in cancel guard but never assigned.
  - Check: line 290.
- Global module grep showed no setStatus(PurchaseOrderStatus.CLOSED) assignment under src/main/java.
  - Evidence search result: only PurchaseOrderServiceImpl line 290 reference for CLOSED.
- Practical implication: CLOSED appears declared and guarded, but currently unreachable in this module's write paths.

### PurchaseOrderStatus token payload

- DRAFT
- APPROVED
- PARTIALLY_RECEIVED
- FULLY_RECEIVED
- CLOSED
- CANCELLED

### PO EmitEvent ids (exact)

- INVENTORY_PURCHASE_ORDER_CREATE
- INVENTORY_PURCHASE_ORDER_GET
- INVENTORY_PURCHASE_ORDER_LIST
- INVENTORY_PURCHASE_ORDER_APPROVE
- INVENTORY_PURCHASE_ORDER_REVISE
- INVENTORY_PURCHASE_ORDER_CANCEL
- INVENTORY_PURCHASE_ORDER_RECEIVE

## inventory.receiving facts

### Controller endpoints, permissions, EmitEvent ids

Base mapping: /v1/inventory/receiving (ReceivingController line 34)

| Endpoint | Permission | EmitEvent id | Anchor |
| --- | --- | --- | --- |
| POST /v1/inventory/receiving/sessions | inventory:receiving:create | INVENTORY_RECEIVING_SESSION_CREATE | controller lines 43, 47-48 |
| GET /v1/inventory/receiving/sessions/{sessionId} | inventory:receiving:view | INVENTORY_RECEIVING_SESSION_GET | controller lines 88, 92-93 |
| POST /v1/inventory/receiving/sessions/{sessionId}/receive | inventory:receiving:complete | INVENTORY_RECEIVING_SESSION_COMPLETE | controller lines 125, 129-130 |
| POST /v1/inventory/receiving/sessions/{sessionId}/lines/{lineId}/cross-dock | inventory:receiving:complete and inventory:issue:parts | INVENTORY_RECEIVING_CROSSDOCK | controller lines 180, 184-185 |

### receiveItemsIntoStaging behavior

Anchors: ReceivingServiceImpl lines 119-226.

- Loads session and line map from session lines.
- For each requested line:
  - Applies optional document-UoM conversion before comparisons/posting.
  - Resolves lot using lot capture service (tracking-gated).
  - Sets line status by received vs expected compare:
    - RECEIVED when equal.
    - RECEIVED_SHORT when received < expected.
    - RECEIVED_OVER when received > expected.
  - Posts GOODS_RECEIPT ledger entry via createGoodsReceiptLedgerEntry.
  - For non-equal quantities, creates InventoryVariance:
    - varianceType SHORTAGE if received < expected.
    - varianceType OVERAGE if received > expected.
    - varianceQuantity = abs(expected - received).
- Session status transition:
  - COMPLETED if all lines are in RECEIVED/RECEIVED_SHORT/RECEIVED_OVER/CANCELLED.
  - IN_PROGRESS otherwise.

### crossDockLineToWorkorder path

Anchors: ReceivingServiceImpl lines 230-365 and 558-581.

- Resolves session and line; rejects missing line/session.
- Validates workorder status and blocks COMPLETED, CANCELLED, CLOSED as closed workorders.
  - Closed-check helper: lines 569-577.
- Validates part match; allows override only with inventory:override:part-match.
  - Override permission constant: line 57.
  - Validation method: lines 526-566.
- Enforces cumulative received quantity does not exceed expected quantity.
- Resolves cross-dock location and lot, then posts paired ledger entries atomically:
  - GOODS_RECEIPT (+qty)
  - GOODS_ISSUE (-qty)
  - both on cross-dock location, same lot id, same source transaction root.
- Updates line/session status with same completion criteria used in receiveItemsIntoStaging.

### Receiving enums token payloads

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

### Receiving EmitEvent ids (exact)

- INVENTORY_RECEIVING_SESSION_CREATE
- INVENTORY_RECEIVING_SESSION_GET
- INVENTORY_RECEIVING_SESSION_COMPLETE
- INVENTORY_RECEIVING_CROSSDOCK

## inventory.transfers-adjustments facts

### TransferOrderController endpoints and permissions

Base mapping: /v1/inventory/transfer-orders (TransferOrderController line 46)

| Endpoint | Permission | EmitEvent id | Anchor |
| --- | --- | --- | --- |
| POST /v1/inventory/transfer-orders | inventory:transfer:create | INVENTORY_TRANSFER_ORDER_CREATE | lines 60-65 |
| GET /v1/inventory/transfer-orders/{transferOrderId} | inventory:transfer:view | none | lines 118-122 |
| GET /v1/inventory/transfer-orders | inventory:transfer:view | none | lines 148-152 |
| POST /v1/inventory/transfer-orders/{transferOrderId}/approve | inventory:transfer:dispatch | INVENTORY_TRANSFER_ORDER_APPROVE | lines 180-185 |
| POST /v1/inventory/transfer-orders/{transferOrderId}/dispatch | inventory:transfer:dispatch | INVENTORY_TRANSFER_ORDER_DISPATCH | lines 227-232 |
| POST /v1/inventory/transfer-orders/{transferOrderId}/receive | inventory:transfer:receive | INVENTORY_TRANSFER_ORDER_RECEIVE | lines 293-298 |
| POST /v1/inventory/transfer-orders/{transferOrderId}/short-close | inventory:transfer:short_close | INVENTORY_TRANSFER_ORDER_SHORT_CLOSE | lines 361-366 |
| POST /v1/inventory/transfer-orders/{transferOrderId}/cancel | inventory:transfer:create | INVENTORY_TRANSFER_ORDER_CANCEL | lines 433-438 |

### StockMovementController endpoints and permissions

| Endpoint | Permission | EmitEvent id | Anchor |
| --- | --- | --- | --- |
| POST /v1/inventory/stock-movements | inventory:stock_movement:create | INVENTORY_STOCK_MOVEMENT_CREATE | StockMovementController lines 45, 49-50 |
| POST /v1/inventory/adjustments | inventory:adjustment:create | INVENTORY_ADJUSTMENT_REQUEST_CREATE | lines 71, 75-76 |
| POST /v1/inventory/adjustments/{adjustmentRequestId}/approve | inventory:adjustment:approve | INVENTORY_ADJUSTMENT_REQUEST_APPROVE | lines 116, 120-121 |

### TransferOrderServiceImpl behavior and ledger facts

- Dispatch gate is flag-dependent.
  - requireDispatchableStatus: line 535.
  - If pos.inventory.transfer.approval-required=false, expected dispatch state is DRAFT.
  - If true, expected state is APPROVED.
- dispatchTransferOrder posts TRANSFER_OUT per line via ledger posting service.
  - Entry shape and posting: lines 250-330.
  - Line dispatch quantity cannot exceed requested.
  - Sets order status DISPATCHED.
- receiveTransferOrder posts TRANSFER_IN per line and computes status:
  - RECEIVED if all receivedQty == dispatchedQty.
  - PARTIALLY_RECEIVED otherwise.
  - Anchors: lines 334-375.
- shortCloseTransferOrder:
  - Allowed only when order.status.receivable() (DISPATCHED or PARTIALLY_RECEIVED).
  - Requires positive outstanding remainder.
  - LOST_IN_TRANSIT disposition posts constructive TRANSFER_IN plus SCRAP_OUT (LOST reason).
  - RETURNED_TO_SOURCE posts constructive TRANSFER_IN, then TRANSFER_OUT back, then TRANSFER_IN at source.
  - Sets terminal status SHORT_CLOSED and stores disposition/reason/notes.
  - Anchors: lines 377-534.

### AdjustmentRequestStatus vs AdjustmentStatus

- AdjustmentRequestStatus is used by StockMovementServiceImpl adjustment-request workflow:
  - PENDING default on create.
  - PENDING required for approval.
  - APPROVED set on approval.
  - Anchors: StockMovementServiceImpl lines 137, 161, 184.
- AdjustmentStatus is used by cycle-count adjustment workflow (separate model/service), not stock-movement adjustment-request workflow.
  - Usage anchors: CycleCountAdjustmentServiceImpl references include PENDING_APPROVAL, AUTO_APPROVED, APPROVED, POSTED, REJECTED, FAILED.

### MovementType token payload and mapping facts

MovementType constants:
- RECEIVE
- PUT_AWAY
- PICK
- ISSUE
- RETURN
- TRANSFER
- ADJUST

StockMovementServiceImpl mapMovementTypeToEventType mapping:
- RECEIVE -> GOODS_RECEIPT
- PUT_AWAY -> PUTAWAY
- PICK -> GOODS_ISSUE
- ISSUE -> GOODS_ISSUE
- RETURN -> RETURN_TO_STOCK
- TRANSFER -> TRANSFER_OUT (+ separate TRANSFER_IN post for destination)
- ADJUST -> rejected for direct movement path, must use adjustment workflow

Cross-site direct transfer guard in stock movement path:
- For MovementType.TRANSFER, reject cross-site with CROSS_SITE_TRANSFER_REQUIRES_ORDER.
- Anchors: StockMovementServiceImpl lines 74-97 and 200-219.

### Transfer/adjustment EmitEvent ids (exact)

Transfer:
- INVENTORY_TRANSFER_ORDER_CREATE
- INVENTORY_TRANSFER_ORDER_APPROVE
- INVENTORY_TRANSFER_ORDER_DISPATCH
- INVENTORY_TRANSFER_ORDER_RECEIVE
- INVENTORY_TRANSFER_ORDER_SHORT_CLOSE
- INVENTORY_TRANSFER_ORDER_CANCEL

Stock movement and adjustments:
- INVENTORY_STOCK_MOVEMENT_CREATE
- INVENTORY_ADJUSTMENT_REQUEST_CREATE
- INVENTORY_ADJUSTMENT_REQUEST_APPROVE

## inventory.codes token catalog seed lists

### Enum constants seed

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

### Permission codes seed (Wave 3-focused)

Purchase order:
- inventory:purchase_order:create
- inventory:purchase_order:view
- inventory:purchase_order:approve
- inventory:purchase_order:receive

Receiving:
- inventory:receiving:create
- inventory:receiving:view
- inventory:receiving:complete
- inventory:issue:parts
- inventory:override:part-match

Transfers and adjustments:
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

### Event ids seed (Wave 3-focused)

Purchase orders:
- INVENTORY_PURCHASE_ORDER_CREATE
- INVENTORY_PURCHASE_ORDER_GET
- INVENTORY_PURCHASE_ORDER_LIST
- INVENTORY_PURCHASE_ORDER_APPROVE
- INVENTORY_PURCHASE_ORDER_REVISE
- INVENTORY_PURCHASE_ORDER_CANCEL
- INVENTORY_PURCHASE_ORDER_RECEIVE

Receiving:
- INVENTORY_RECEIVING_SESSION_CREATE
- INVENTORY_RECEIVING_SESSION_GET
- INVENTORY_RECEIVING_SESSION_COMPLETE
- INVENTORY_RECEIVING_CROSSDOCK

Transfers and adjustments:
- INVENTORY_TRANSFER_ORDER_CREATE
- INVENTORY_TRANSFER_ORDER_APPROVE
- INVENTORY_TRANSFER_ORDER_DISPATCH
- INVENTORY_TRANSFER_ORDER_RECEIVE
- INVENTORY_TRANSFER_ORDER_SHORT_CLOSE
- INVENTORY_TRANSFER_ORDER_CANCEL
- INVENTORY_STOCK_MOVEMENT_CREATE
- INVENTORY_ADJUSTMENT_REQUEST_CREATE
- INVENTORY_ADJUSTMENT_REQUEST_APPROVE

## Declared-but-unused or mismatch notes

- PurchaseOrderStatus.CLOSED appears declared and read-guarded, but no write transition was found in src/main/java.
  - Evidence: PurchaseOrderServiceImpl line 290 check; no setStatus(PurchaseOrderStatus.CLOSED) occurrences.
- AdjustmentRequestStatus.REJECTED is declared but not set in StockMovementServiceImpl request lifecycle.
  - Evidence: create sets PENDING (line 137), approve requires PENDING and sets APPROVED (lines 161, 184).
- Event registry contains PO event ids that are registered but not emitted by controllers in this source boundary:
  - INVENTORY_PURCHASE_ORDER_ENCUMBRANCE
  - INVENTORY_PURCHASE_ORDER_ACCOUNTING_ERROR
  - Evidence: EventTypes lines 249 and 253; source grep found no controller EmitEvent usage.
- Permission source-of-truth split is real:
  - Runtime registration loads permissions.yaml through PermissionInitializer, not InventoryPermissionRegistry.
    - PermissionInitializer lines 29-42.
  - InventoryPermissionRegistry is partial and omits multiple active codes used by controllers and present in permissions.yaml, including:
    - inventory:purchase_order:create/view/approve/receive
    - inventory:receiving:create/view/complete
    - inventory:issue:parts
    - inventory:override:part-match
    - inventory:goods_receipt:override

## Open risks/ambiguities

- PO CLOSED semantics are ambiguous for downstream docs because CLOSED is present in enum and guards but has no in-module transition path.
- Transfer approval path is configuration-dependent (pos.inventory.transfer.approval-required), so status-flow docs must include both enabled and default-disabled paths.
- Receiving receiveItemsIntoStaging silently skips unknown line IDs in request payload (line missing in map leads to continue), which may be surprising if API consumers expect hard validation failures.
- Event type registry includes non-controller operational event ids; docs should distinguish between annotation-emitted API events and internal/operational event registrations.
