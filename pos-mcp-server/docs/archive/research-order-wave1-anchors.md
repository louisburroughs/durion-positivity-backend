## Scope

- Goal: source-verified Wave 1 anchor findings for order.lifecycle, order.price-override, and order.codes.
- Source boundary: only primary source files under /home/n541342/IdeaProjects/durion-positivity-backend/pos-order.
- Verification focus requested: SalesOrderController, OrderStateMachine.ALLOWED, SalesOrderServiceImpl.checkout path, PaymentEventsListener transition behavior, PriceOverrideController, PriceOverrideServiceImpl thresholds/guards, enums, permissions, EmitEvent IDs.

## Verified source inventory

| File | Symbols and methods verified | Why it matters |
| --- | --- | --- |
| /home/n541342/IdeaProjects/durion-positivity-backend/pos-order/src/main/java/com/positivity/order/internal/controller/SalesOrderController.java | class SalesOrderController, checkout(...), @PostMapping/@GetMapping/@PutMapping/@DeleteMapping/@PatchMapping, @EmitEvent ids, @PreAuthorize tokens | Lifecycle endpoints, permissions, and emitted lifecycle event IDs |
| /home/n541342/IdeaProjects/durion-positivity-backend/pos-order/src/main/java/com/positivity/order/internal/service/SalesOrderServiceImpl.java | checkout(UUID,String,String), requireOnAccountEligibility(...), completeOnAccount(...) | Concrete checkout execution path and guards |
| /home/n541342/IdeaProjects/durion-positivity-backend/pos-order/src/main/java/com/positivity/order/internal/service/OrderStateMachine.java | OrderStateMachine.ALLOWED, transition(...), requireEditable(...) | Canonical lifecycle transition matrix |
| /home/n541342/IdeaProjects/durion-positivity-backend/pos-order/src/main/java/com/positivity/order/internal/service/PaymentEventsListener.java | onPaymentEvent(...), applySettled(...), applyReversed(...), recomputeSettlement(...) | Payment-driven transition behavior into COMPLETED |
| /home/n541342/IdeaProjects/durion-positivity-backend/pos-order/src/main/java/com/positivity/order/internal/controller/PriceOverrideController.java | class PriceOverrideController, apply/approve/reject/search/list endpoints, @EmitEvent ids, @PreAuthorize tokens | Price-override endpoint surface, permissions, and emitted IDs |
| /home/n541342/IdeaProjects/durion-positivity-backend/pos-order/src/main/java/com/positivity/order/internal/service/PriceOverrideServiceImpl.java | APPROVAL_THRESHOLD_PERCENTAGE, APPROVAL_THRESHOLD_AMOUNT, requiresApproval(...), validateOverrideRequest(...), applyPriceOverride(...), approveOverride(...), rejectOverride(...) | Override thresholds, guards, and state progression |
| /home/n541342/IdeaProjects/durion-positivity-backend/pos-order/src/main/java/com/positivity/order/internal/entity/SalesOrderStatus.java | enum SalesOrderStatus | Exact lifecycle status tokens |
| /home/n541342/IdeaProjects/durion-positivity-backend/pos-order/src/main/java/com/positivity/order/internal/entity/OverrideStatus.java | enum OverrideStatus | Exact override status tokens and unused candidates |
| /home/n541342/IdeaProjects/durion-positivity-backend/pos-order/src/main/java/com/positivity/order/internal/entity/PriceOverrideReasonCode.java | enum PriceOverrideReasonCode | Exact override reason-code tokens |
| /home/n541342/IdeaProjects/durion-positivity-backend/pos-order/src/main/java/com/positivity/order/internal/security/OrderPermissions.java | class OrderPermissions constants | Canonical lifecycle permission tokens |
| /home/n541342/IdeaProjects/durion-positivity-backend/pos-order/src/main/java/com/positivity/order/internal/security/PriceOverridePermissions.java | class PriceOverridePermissions constants | Canonical price-override permission tokens |
| /home/n541342/IdeaProjects/durion-positivity-backend/pos-order/src/main/java/com/positivity/order/internal/config/EventTypes.java | EventTypes.ALL_EVENT_TYPES, ORDER_* registrations | Event registry alignment for emitted IDs |
| /home/n541342/IdeaProjects/durion-positivity-backend/pos-order/src/main/java/com/positivity/order/internal/config/EventTypeInitializer.java | run(...), initializerSupport.registerEventTypes(EventTypes.ALL_EVENT_TYPES, ...) | Confirms startup registration path for declared event IDs |
| /home/n541342/IdeaProjects/durion-positivity-backend/pos-order/src/main/java/com/positivity/order/internal/controller/PriceOverrideExceptionHandler.java | handleIllegalArgument(...) | Confirms invalid enum parse path returns 400 for bad reason/status strings |

## order.lifecycle facts

- Endpoint surface (SalesOrderController):
  - POST /v1/orders/carts
  - GET /v1/orders/carts
  - POST /v1/orders/carts/{orderId}/items
  - PUT /v1/orders/carts/{orderId}/items/{lineId}
  - DELETE /v1/orders/carts/{orderId}/items/{lineId}
  - GET /v1/orders/carts/{orderId}
  - PUT /v1/orders/carts/{orderId}/discount
  - DELETE /v1/orders/carts/{orderId}/discount
  - POST /v1/orders/carts/{orderId}/quote
  - POST /v1/orders/carts/{orderId}/quote/reopen
  - POST /v1/orders/{orderId}/checkout
  - POST /v1/orders/{orderId}/void
  - PATCH /v1/orders/carts/{orderId}/source
  - Evidence: SalesOrderController class mappings and methods.

- Checkout call path:
  - Controller method SalesOrderController.checkout(...) calls SalesOrderService.checkout(orderId, idempotencyKey, tenderType).
  - Implementation method SalesOrderServiceImpl.checkout(UUID orderId, String idempotencyKey, String tenderType) performs:
    - Idempotency-Key required, with replay and cross-order conflict checks.
    - Tender validation: ON_ACCOUNT or DEFAULT/null only.
    - Cart must have lines.
    - CustomerValidationStatus.PENDING blocks checkout.
    - ON_ACCOUNT path requires ORDER_CHARGE_ON_ACCOUNT permission and validated commercial customer billing eligibility.
    - Inventory availability checks for each line.
    - Serial/lot guard via requireSerialsForTrackedProducts(order).
    - Final reprice and tax recompute before freeze.
    - State transition to SalesOrderStatus.PENDING_PAYMENT via OrderStateMachine.transition(..., PENDING_PAYMENT, "checkout").
    - Synchronous invoice creation via invoicingPort.createInvoiceForOrder(...).
    - Optional ON_ACCOUNT synchronous completion path transitions to COMPLETED in completeOnAccount(...).
  - Evidence: SalesOrderController.checkout; SalesOrderServiceImpl.checkout, requireOnAccountEligibility, completeOnAccount.

- Canonical transition matrix (OrderStateMachine.ALLOWED):
  - DRAFT -> QUOTED, PENDING_PAYMENT, CANCEL_REQUESTED
  - QUOTED -> DRAFT, PENDING_PAYMENT, CANCEL_REQUESTED
  - PENDING_PAYMENT -> COMPLETED, VOIDED, CANCEL_REQUESTED
  - CANCEL_REQUESTED -> WORKORDER_CANCELLED, PAYMENT_REVERSED, CANCELLED, CANCEL_FAILED_WORKEXEC, CANCEL_FAILED_BILLING
  - WORKORDER_CANCELLED -> PAYMENT_REVERSED, CANCELLED, CANCEL_FAILED_BILLING
  - PAYMENT_REVERSED -> CANCELLED
  - CANCEL_FAILED_WORKEXEC -> CANCEL_REQUESTED, CANCEL_REQUIRES_MANUAL_REVIEW
  - CANCEL_FAILED_BILLING -> CANCEL_REQUESTED, PAYMENT_REVERSED, CANCEL_REQUIRES_MANUAL_REVIEW
  - CANCEL_REQUIRES_MANUAL_REVIEW -> CANCEL_REQUESTED
  - COMPLETED -> (no outgoing)
  - VOIDED -> (no outgoing)
  - CANCELLED -> (no outgoing)
  - Evidence: OrderStateMachine.ALLOWED map.

- Payment event transition behavior:
  - PaymentEventsListener consumes topic payment.events.v1 (configurable property).
  - Handles only PaymentSettledV1.EVENT_TYPE and PaymentReversedV1.EVENT_TYPE.
  - Deduplicates by processed_events (ProcessedEventRepository.existsById(eventId)).
  - applySettled(...) writes SETTLED record; applyReversed(...) writes REVERSED only for reversalType REFUND (VOID reversals ignored for ledger).
  - recomputeSettlement(...):
    - amountPaid = settled minus reversed records.
    - balanceDue = max(grandTotal - amountPaid, 0).
    - If amountPaid > grandTotal then publishPaymentIntegrityAlert(order).
    - If status is PENDING_PAYMENT and amountPaid >= grandTotal then transition to COMPLETED with reason "settled in full" and publishOrderCompleted(...).
  - Evidence: PaymentEventsListener.onPaymentEvent, applySettled, applyReversed, recomputeSettlement.

- Lifecycle permissions used in path:
  - Controller permissions: ORDER_CREATE, ORDER_VIEW, ORDER_LINE_CREATE, ORDER_LINE_EDIT, ORDER_LINE_DELETE, ORDER_DISCOUNT, ORDER_QUOTE, ORDER_CHECKOUT, ORDER_VOID, ORDER_EDIT.
  - Service guard permission: ORDER_CHARGE_ON_ACCOUNT.
  - Evidence: SalesOrderController @PreAuthorize, SalesOrderServiceImpl.requireOnAccountEligibility.

- Lifecycle EmitEvent IDs from SalesOrderController:
  - ORDER_CART_CREATE
  - ORDER_CART_LIST
  - ORDER_CART_ITEM_ADD
  - ORDER_CART_ITEM_UPDATE
  - ORDER_CART_ITEM_REMOVE
  - ORDER_CART_DISCOUNT_APPLY
  - ORDER_CART_DISCOUNT_REMOVE
  - ORDER_CART_QUOTE
  - ORDER_CART_QUOTE_REOPEN
  - ORDER_CHECKOUT
  - ORDER_VOID
  - ORDER_LINK_SOURCE
  - Evidence: SalesOrderController @EmitEvent annotations; EventTypes.ALL_EVENT_TYPES contains matching IDs.

## order.price-override facts

- Endpoint surface (PriceOverrideController):
  - POST /v1/orders/price-overrides
  - POST /v1/orders/price-overrides/{overrideId}/approve
  - POST /v1/orders/price-overrides/{overrideId}/reject
  - GET /v1/orders/price-overrides/{overrideId}
  - GET /v1/orders/price-overrides
  - GET /v1/orders/price-overrides/pending
  - Evidence: PriceOverrideController mappings.

- Threshold and guard behavior (PriceOverrideServiceImpl):
  - Threshold constants:
    - APPROVAL_THRESHOLD_AMOUNT = 50.0
    - APPROVAL_THRESHOLD_PERCENTAGE = 10.0
  - Approval rule:
    - requiresApproval = (discountAmount >= 50.0) OR (discountPercentage > 10.0)
  - Input guards:
    - overridePrice must not exceed originalPrice.
    - overridePrice must not be negative.
  - Lifecycle/editability guard:
    - Override allowed only when order status is DRAFT, otherwise InvalidPriceOverrideException.
  - Reason code parsing:
    - PriceOverrideReasonCode.valueOf(request.getReasonCode()) used directly (case-sensitive enum token expected).
  - Status progression in service logic:
    - Apply: initial status PENDING_APPROVAL or APPROVED (auto-approve path).
    - Approve endpoint: PENDING_APPROVAL -> APPROVED.
    - Reject endpoint: PENDING_APPROVAL -> REJECTED.
  - Evidence: PriceOverrideServiceImpl.applyPriceOverride, validateOverrideRequest, requiresApproval, approveOverride, rejectOverride.

- Price-override permissions:
  - PRICE_OVERRIDE_APPLY
  - PRICE_OVERRIDE_APPROVE
  - PRICE_OVERRIDE_REJECT
  - PRICE_OVERRIDE_VIEW
  - Evidence: PriceOverrideController @PreAuthorize and scopes; PriceOverridePermissions constants.

- Price-override EmitEvent IDs:
  - ORDER_PRICE_OVERRIDE_APPLY
  - ORDER_PRICE_OVERRIDE_APPROVE
  - ORDER_PRICE_OVERRIDE_REJECT
  - ORDER_PRICE_OVERRIDE_SEARCH
  - ORDER_PRICE_OVERRIDE_LIST_PENDING
  - Evidence: PriceOverrideController @EmitEvent annotations; EventTypes constants and ALL_EVENT_TYPES list.

## order.codes token catalog seed lists

- SalesOrderStatus tokens (from enum SalesOrderStatus):
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

- OverrideStatus tokens (from enum OverrideStatus):
  - PENDING_APPROVAL
  - APPROVED
  - REJECTED
  - APPLIED
  - CANCELLED

- PriceOverrideReasonCode tokens (from enum PriceOverrideReasonCode):
  - CUSTOMER_LOYALTY
  - PRICE_MATCH
  - PROMOTIONAL_PRICING
  - PRICING_ERROR_CORRECTION
  - VOLUME_DISCOUNT
  - GOODWILL_ADJUSTMENT
  - MANAGER_DISCRETION
  - OTHER

- OrderPermissions tokens (from class OrderPermissions):
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

- PriceOverridePermissions tokens (from class PriceOverridePermissions):
  - order:price_override:apply
  - order:price_override:approve
  - order:price_override:view
  - order:price_override:reject

- EmitEvent ID tokens relevant to Wave 1 anchors:
  - ORDER_CART_CREATE
  - ORDER_CART_LIST
  - ORDER_CART_ITEM_ADD
  - ORDER_CART_ITEM_UPDATE
  - ORDER_CART_ITEM_REMOVE
  - ORDER_CART_DISCOUNT_APPLY
  - ORDER_CART_DISCOUNT_REMOVE
  - ORDER_CART_QUOTE
  - ORDER_CART_QUOTE_REOPEN
  - ORDER_CHECKOUT
  - ORDER_VOID
  - ORDER_LINK_SOURCE
  - ORDER_PRICE_OVERRIDE_APPLY
  - ORDER_PRICE_OVERRIDE_APPROVE
  - ORDER_PRICE_OVERRIDE_REJECT
  - ORDER_PRICE_OVERRIDE_SEARCH
  - ORDER_PRICE_OVERRIDE_LIST_PENDING

- Declared-but-unused findings (source-level, static reference check in src/main/java):
  - OverrideStatus.APPLIED: declared but no assignment or status transition call observed.
  - OverrideStatus.CANCELLED: declared but no assignment or status transition call observed.
  - OrderPermissions.ORDER_LINE_VIEW (order:line:view): declared but no current @PreAuthorize or service guard reference observed.

## Open risks and ambiguities

- Override status taxonomy mismatch risk:
  - OverrideStatus includes APPLIED and CANCELLED, but PriceOverrideServiceImpl uses PENDING_APPROVAL/APPROVED/REJECTED paths only.
  - Impact for RAG anchor docs: if documentation presents APPLIED or CANCELLED as active runtime states, it may overstate current behavior.
  - Evidence: OverrideStatus enum vs PriceOverrideServiceImpl status setStatus and filters.

- Case-sensitivity ambiguity for reason/status query inputs:
  - PriceOverrideReasonCode parsing in apply path uses valueOf(request.getReasonCode()) without normalization.
  - Status filtering path does normalize to uppercase before OverrideStatus.valueOf(status.toUpperCase()).
  - Impact for docs and prompts: request examples should use exact reason-code tokens; status filter is more tolerant than reasonCode input.
  - Evidence: PriceOverrideServiceImpl.applyPriceOverride and getOverridesByStatus; PriceOverrideExceptionHandler.handleIllegalArgument.

- Event registration visibility caveat:
  - Event IDs are declared in EventTypes and registered at startup via EventTypeInitializer, but registration can be disabled or fail with warning.
  - Impact for operators: emitted annotations still exist, but upstream event-type catalog completeness depends on runtime registration success.
  - Evidence: EventTypeInitializer.run and registerEventTypes usage.
