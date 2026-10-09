# pos-order

Order management service for the Durion Positivity ETSMS platform. Manages sales order carts, price override requests with approval workflows, and order cancellations.

## Responsibilities

- Create and manage sales order carts and line items
- Accept and validate price override requests from service advisors
- Auto-approve small discounts; queue large discounts for manager approval
- Record approval and rejection decisions with full audit trail
- Cancel orders with inventory release coordination
- Enforce idempotency on price override creation to prevent duplicate submissions

## Key Classes

- `SalesOrderService` — cart lifecycle (create, add/update/remove lines, retrieve)
- `PriceOverrideService` — override request, auto-approval evaluation, approval/rejection
- `OrderCancellationService` — cancellation workflow with pre/post state snapshots
- `PriceOverrideServiceImpl` — auto-approval thresholds (≤10% or ≤$50), idempotency key check, audit record creation

## API Endpoints

- `POST /v1/orders/carts` — create a sales order cart
- `GET /v1/orders/carts/{orderId}` — retrieve a cart
- `POST /v1/orders/carts/{orderId}/items` — add a line item
- `PUT /v1/orders/carts/{orderId}/items/{lineId}` — update a line item
- `DELETE /v1/orders/carts/{orderId}/items/{lineId}` — remove a line item
- `PUT /v1/orders/carts/{orderId}/customer` — set or change a DRAFT cart's customer (`order:order:edit`):
  body `{"customerId": …, "vehicleId": …}` for a registered customer, or `{"walkIn": true}` for the
  Walk-in customer; exactly one of the two. See [Customer at checkout](#customer-at-checkout-and-walk-in-sales-cap550-s8).
- `PUT /v1/orders/carts/{orderId}/discount` / `DELETE …/discount` — order-level discount
- `POST /v1/orders/carts/{orderId}/quote` / `POST …/quote/reopen` — counter-quote lifecycle
- `POST /v1/orders/{orderId}/checkout` — freeze the cart, create the fronting invoice at
  pos-invoice, enter `PENDING_PAYMENT` (`Idempotency-Key` header required; replay-safe).
  Optional body `{"tenderType": "ON_ACCOUNT"}` charges a validated commercial customer's
  account (permission `order:order:charge_on_account`) and completes the order synchronously —
  the accepted AR invoice counts as settlement. The cart must name a customer; for a walk-in cart
  the body's `tenderedAmount` (cash and card taken now) must cover the final grand total.
- `POST /v1/orders/{orderId}/void` — terminal void of a PENDING_PAYMENT order before any
  settlement; cancels the fronting invoice; 409 when settled payments exist (use cancel)
- `POST /v1/orders/{orderId}/cancel` — cancel an order
- `POST /v1/orders/price-overrides` — request a price override (idempotent via `idempotencyKey`)
- `GET /v1/orders/price-overrides/{overrideId}` — retrieve an override
- `GET /v1/orders/price-overrides/pending` — list pending approvals
- `POST /v1/orders/price-overrides/{overrideId}/approve` — approve an override
- `POST /v1/orders/price-overrides/{overrideId}/reject` — reject an override

## Customer at checkout and walk-in sales (CAP:550 S8)

Every sale needs a customer (accounting workspace decision AW12). Checkout refuses a cart without
one — `422 ORDER_CUSTOMER_REQUIRED` — before any demand is registered, and nothing in this module
ever assigns a customer on its own. The rule applies from go-live: carts still open need a customer
at checkout, and orders already past checkout are not re-checked (AW13).

**Walk-in** is the tenant's CASH house account, provisioned by pos-customer and recognised here by
one thing only: the `house_account = 'CASH_SALE'` flag on the `ext_customer` replica row
(`CustomerPartyUpdatedV1.houseAccount`). The customer number and the display name prove nothing.
It is chosen explicitly — `PUT …/customer {"walkIn": true}`, or its id as `customerId` — and a
cart whose customer is that account is a *walk-in cart*:

| Rule | Where it is checked | Refusal |
| --- | --- | --- |
| Never on account (`tenderType = ON_ACCOUNT`) | checkout, before the on-account gate | `422 ORDER_WALK_IN_NOT_ALLOWED`, `fieldErrors[walkIn] = ON_ACCOUNT` |
| Never a deposit take | set-customer, checkout | `422 ORDER_WALK_IN_NOT_ALLOWED`, `DEPOSIT` |
| Never workorder-linked | set-customer, `PATCH …/source`, checkout | `422 ORDER_WALK_IN_NOT_ALLOWED`, `WORKORDER_LINK` |
| Paid in full now | checkout, after the final reprice and tax | `422 ORDER_WALK_IN_NOT_PAID_IN_FULL`, `fieldErrors[tenderedAmount]` names the grand total |
| Refunded to the original tender only | `POST /v1/returns` | `422 RETURN_WALK_IN_NOT_ALLOWED` for `STORE_CREDIT` / `ON_ACCOUNT_CREDIT` |

- `tenderedAmount` is a declaration, compared with the server's own total **at cent scale**
  (`grandTotal` rounded HALF_UP to 2 dp — the figure the register shows and the amount pos-invoice
  settles, S9); the 4-dp calculator residue of a percent discount or line tax is never demanded.
  The payments are still captured in pos-invoice after checkout. A refused cart stays `DRAFT`; retry with a new
  `Idempotency-Key`. For a cart with a registered customer the field is ignored. A replay of a
  completed checkout returns the stored result and re-evaluates nothing.
- When the replica holds no active house account for the tenant (not provisioned yet, or the party
  fact not replayed since the flag was added), Walk-in answers `422 ORDER_WALK_IN_UNAVAILABLE` and
  nothing is substituted. Fill the replica with pos-customer's party-fact replay
  (`POST /v1/crm/accounts/facts/replay`); the listener applies equal versions, so a replay repairs
  existing rows.
- A cart with a linked `WORKORDER` source keeps its customer (`422 ORDER_UNPROCESSABLE` on a change).
- `SalesOrderResponse` carries `walkIn` and `customerDisplayName` (from the replica; null when the
  customer is unknown). X and Z session reports carry `walkInByClerk[]` —
  `{clerkId, orderCount, walkInOrderCount, walkInTotal}` over the session's orders that left
  `DRAFT`, keyed on the order's `clerkId`.
- Refusals are logged at INFO with the order number and code. The `order.checkout.refused` meter
  (tag `code`) counts only the three S8 checkout refusals — `ORDER_CUSTOMER_REQUIRED`,
  `ORDER_WALK_IN_NOT_ALLOWED`, `ORDER_WALK_IN_NOT_PAID_IN_FULL` — not the pre-existing ones
  (empty cart, pending validation, serials, on-account eligibility).

## Location scope (ADR-0061, #1872)

`@PreAuthorize` answers "may this caller do X"; the caller's `LocationScope` (decoded by
`pos-security-common` from the gateway's `X-Loc-*` headers) answers "…at this location".
Decisions per operation are recorded in [`location-scope.yaml`](location-scope.yaml):

- `POST /v1/orders/sessions` (`order:session:open`) and `POST /v1/orders/carts`
  (`order:order:create`) are **gated** in `RegisterSessionServiceImpl.openSession` and
  `SalesOrderServiceImpl.createCart` on the *resolved* location — the request's `locationId`, or
  the default (for a session: the register's float location, else the terminal's previous session;
  for a cart: the terminal's open session) when it is omitted — so omitting
  `locationId` cannot bypass the check. A scoped caller outside its reach gets
  `403 LOCATION_SCOPE_DENIED`; a session that resolves to no location is denied for a scoped caller
  (fail closed). Pre-rollout tokens without `loc_*` claims are unchanged.
- `GET /v1/orders/sessions/{sessionId}` (`order:session:view`) and `GET /v1/orders/carts/{orderId}`
  (`order:order:view`) are gated in the controller on the stored entity's location, after the
  404, so the create gates cannot be bypassed by reading the resource back by id.

The check runs in-process against the `ext_location` replica: `LocationHierarchyService`
implements `LocationAncestorResolver` over the materialised `financial_ancestor_ids` /
`other_ancestor_ids` sets that `LocationEventsListener` recomputes from the `ext_location_parent`
edges carried as `parents` on every `location.location.updated` fact (V22; pos-location publishes
no separate parent-added/removed fact). A location the replica does not hold is denied for scoped
callers; pos-location is never called per request.

## Source-document import (parity story E1)

Estimate and workorder lines import via `PATCH /v1/orders/carts/{orderId}/source` from the
event-fed `ext_estimate(_line)` / `ext_workorder(_line)` replicas (fed by
`workorder.events.v1` snapshots — ADR-0044 bars sync REST toward pos-workorder). Lines arrive
priced-as-approved (`priceSource=SOURCE_DOCUMENT`, never repriced): estimates contribute only
APPROVED items; workorders contribute non-declined lines with their explicit `returnable`
flag. Tracked products (`ext_product.trackingLevel`) require serial/lot capture on counter
lines before checkout (SERIAL: one per unit; LOT: at least one).

## Settlement handshake (parity story C3)

Checkout creates the invoice synchronously (scoped ADR-0044 exception, `pos.invoice.base-url`);
settlement flows back asynchronously: a `payment.events.v1` consumer applies
`payment.payment.settled` / `payment.payment.reversed` facts to the `order_payment_record`
ledger, maintains `amountPaid`/`balanceDue`, and transitions
`PENDING_PAYMENT → COMPLETED` exactly at zero balance, emitting `order.order.completed`.
Over-settlement raises `order.payment.integrity-alert`. Applied price overrides emit
`order.line.commission-impact`. All Kafka paths are tier-1 `@KafkaRails` beans: always on outside the broker-less dev/test profiles, no flag.

## Register session facts (CAP:550 S40, #2578)

A register session publishes two facts on `order.events.v1` through the transactional outbox, both keyed on
the session (`aggregateId = sessionId`, `aggregateVersion` = the session's version), so they share a
partition and stay in order:

- `order.session.opened` (`RegisterSessionOpenedV1`: `sessionId`, `terminalId`, nullable `locationId`,
  `openedAt`) — queued in the open's transaction. A refused open (409 active session, 422
  `REGISTER_FLOAT_LOCATION_MISMATCH`, 403 location scope) queues nothing.
- `order.session.closed` (`RegisterSessionClosedV1`, schema 2) — queued in the confirm-close transaction.

Contract: a terminal has at most one active (OPEN or CLOSING) session per tenant; a session is active from
its opened fact until its closed fact; its `locationId` never changes while it is active; only the
terminal's most recently opened session can be active. pos-accounting's register-relocation guard reads
these facts (ADR-0044 R1: no synchronous call).

At each start, `RegisterSessionFactsBootstrap` re-emits `order.session.opened` for every OPEN or CLOSING
session of every tenant (`TenantIterator`, each tenant in its own transaction) at the session's current
version, and none for a CLOSED session (ADR-0044 §4 backfill). Turn it off with
`POS_ORDER_SESSION_BOOTSTRAP_REPUBLISH_ENABLED=false` (`pos.order.session.bootstrap-republish.enabled`).

## Reconciliation manifest and replay (ADR-0044 §4, #2579)

pos-order reconciles `order.events.v1` the way every other fact owner does:

- **Manifest.** `ManifestPublisher` publishes one `ReconciliationManifestV1` per tenant per closed window on
  `order.manifest.v1` (eventType `order.reconciliation.manifest`): the count, checksum and per-type counts of the
  `order.events.v1` facts that tenant published from `event_outbox` in the window, by eventId (UUIDv7)
  timestamp. Every fact type is counted (sales order, return, purchase order, register session); the
  `supplier.commands.v1` requests queued on the same outbox are not facts and are not counted. Every active tenant
  gets a manifest each window, zero-count when it published nothing. The job is `@PlatformScoped` (it reads the
  global outbox) and sends directly, not through the outbox; a failed window is retried next poll.
- **Replay.** A consumer whose processed-events log disagrees with a window sends
  `{"commandType":"order.outbox.replay-requested","payload":{"since":…,"until":…}}` on `order.commands.v1`.
  `PurchaseOrderCommandListener`, the topic's single consumer, dispatches it before the event-id guard and
  re-queues that tenant's published `order.events.v1` rows of `[since, until)` (everything since `since` without
  an `until`), widened by a second; the outbox publisher re-sends them with their original event ids, which
  consumers dedupe. A window starting more than `pos.order.outbox.replay.max-lookback` (`P30D`) ago, or without a
  parsable `since`, is dropped; a transient database failure is retried by the container.
- **Consumer.** pos-accounting (`OrderManifestListener`) compares each manifest with the `order` rows its
  `OrderEventsListener` records, which records every order fact it reads, not only the session facts it posts.

## Vendor copy and the purchase-order vendor guard (CAP:550 S24, #2517)

A purchase order names its vendor by the pos-supplier vendor id (ADR-0070, G15). pos-order never calls
pos-supplier (ADR-0044 R1): it reads its own copy of the vendor master.

- **Copy.** `ext_supplier_vendor` (`V7`, tenant-scoped with RLS) holds every vendor, active or inactive, at the
  ADR-0044 R3 minimum: `vendor_id`, `vendor_number`, `display_name`, `status`, `status_changed_at`,
  `aggregate_version`. It is written only by the `supplier.vendor.updated` branch of `SupplierOrderResultListener`
  (`SupplierVendorReplica`), the module's one `supplier.events.v1` consumer, under `ReplicaVersionGuard`: an older
  `aggregateVersion` changes nothing; an equal one re-applies, so a replay repairs the copy. No tax registration,
  remit-to or payment term is copied.
- **Schema version (Security ruling on #2617, ADR-0072).** The envelope's `schemaVersion` is read before the payload
  is mapped. A fact below version 2 is marked processed (owner `supplier`), counted as
  `order.supplier_vendor.skipped{eventType, schemaVersion}` (those two tags only) and skipped; its payload is never
  logged, and an unreadable vendor fact (or any unparsable supplier event or manifest) is logged with its exception
  class only. Every database failure of the copy propagates for retry without a mark; a constraint refusal propagates
  as a `DataIntegrityViolationException` naming only the vendor number, the event and the constraint, with no cause.
- **Driver detail off (ADR-0072).** `spring.datasource.hikari.data-source-properties.logServerErrorDetail: false`
  (`application.yml`, and the `pg` test profile): pgjdbc otherwise appends the server's `DETAIL` ("Failing row contains
  (...)") to every `SQLException` message, which Hibernate logs at ERROR before Spring translates it, so a refused row's
  column values would reach the log. Proven by `SupplierVendorCopyIT#aRefusedCopyRowLeaksNoColumnValue`.
- **Seeding.** On first deployment the operator calls pos-supplier's `POST /v1/supplier/vendors/facts/replay` per
  tenant until it reports `complete`. Until then, purchase orders for unseeded vendors answer 503 as below.
- **Guard.** `POST /v1/orders/purchase-orders`, `POST /{poId}/approve` and `POST /{poId}/transmit` refuse an `INACTIVE`
  vendor (**422 `VENDOR_INACTIVE`**). A vendor id the copy does not hold may only not have replicated yet, so it is
  never answered as absent (ADR-0017 §1): **503 `VENDOR_REPLICATION_PENDING`** with `Retry-After` (5 s), the platform
  `ReplicationPendingException` (#1994), rendered by `GlobalApiExceptionHandler`.
  `POST /{poId}/revisions` takes an optional `vendorId`: it changes the vendor of a `DRAFT` order only (another
  vendor on an order past `DRAFT` is 409 `PURCHASE_ORDER_INVALID_STATE`), passes the same guard, and an absent
  `vendorId` keeps the vendor.
- **Requested orders.** An order requested on `order.commands.v1` (pos-inventory's purchase suggestions) is placed in
  `DRAFT` whatever vendor it names, since a suggestion may still name a manufacturer or distributor feed id (S36);
  approval answers 503 `VENDOR_REPLICATION_PENDING` until the buyer revises the vendor to one in the copy.
- **Reconciliation.** `SupplierManifestListener` compares each per-tenant `supplier.manifest.v1` manifest with the
  `processed_events` rows of owner `supplier`, which `SupplierOrderResultListener` stamps on every event it sees,
  handled or ignored. On drift it counts `replica.drift{owner="supplier"}` and sends
  `supplier.outbox.replay-requested` on `supplier.commands.v1` with the tenant header; a manifest without a tenant is
  skipped and counted as `replica.manifest.skipped`.

## Tax registrations replica (CAP:550 S32c, #2638)

pos-order keeps its own copy of the tenant's indirect-tax registrations, `ext_tax_registration` (V8), to decide which
drawer fields to show; it never calls pos-tax for registrations (ADR-0071 §7, AW58).

- `TaxRegistrationEventsListener` applies pos-tax's `tax.registration.changed` from `tax.events.v1`, keyed by the
  registration id and guarded by its version (`ReplicaVersionGuard`): a redelivery or the manifest's re-send applies
  once, an older version changes nothing. Every eventId is recorded in `processed_events` (owner `tax`).
- `TaxManifestListener` compares each `tax.manifest.v1` window with those rows and sends `tax.outbox.replay-requested`
  on `tax.commands.v1` for a drifted one.
- `TaxRegistrationReplica.inEffectOn(countryCode, regime, businessDate)` is the as-of read (both ends inclusive,
  AW49). An empty answer means the copy holds none in effect that day; a caller that must act on absence (S32d)
  retries or holds rather than reading "not registered" from a copy that may not have caught up.
- `TaxRegistrationReplica.inEffectFor(countryCode, businessDate)` lists every registration of the country in effect
  that day, in regime order (at most one per regime): the regimes a drawer may offer (S32d), under the same caveat.
- The copy keeps no registration number: the drawer needs only whether a regime is registered on a date.

## Purchase order transmission timeline (issue #1638)

- `GET /v1/orders/purchase-orders/{poId}/transmission-events` (`listPurchaseOrderTransmissionEvents`,
  permission `order:purchase_order:view` — reused, not a new grant) returns a page of the purchase
  order's append-only vendor observation timeline: every confirmation, rejection, status observation,
  not-dispatched notice and review escalation heard from pos-supplier about the order.
- Ordering is the timeline's semantics, not a client choice: entries sort by the vendor's own clock
  (`observedAt` ascending), ties broken by platform receipt time (`recordedAt`), then by event id. The
  `sort` query parameter is accepted but ignored.
- Both timestamps are returned on every entry — `observedAt` (what the vendor says happened) and
  `recordedAt` (when this platform heard it) — so a late-arriving observation is visible sitting where
  the vendor placed it, rather than silently reshuffling history.
- An order that was never transmitted has an empty timeline (200), not a 404; a 404 means the purchase
  order itself does not exist.

### Not dispatched (#2492)

When the vendor is not set up for electronic ordering (no pos-supplier profile, or a disabled one),
pos-supplier answers with `supplier.order.notdispatched` and `SupplierOrderResultListener` moves the order
from `REQUESTED` to `TransmissionState.NOT_DISPATCHED`. Distinct from `REJECTED`: the vendor never saw the
order, and a revised confirmed order still has its earlier version there.

- Applied only while the order is `REQUESTED` **and** the event's `requestedRevision` equals the revision in
  flight (`transmittedVersionNumber`); otherwise ignored and logged. The event is keyed by purchase order,
  not intent, so it can arrive after a later request or answer.
- The request's bookkeeping is rolled back: `transmissionCount` is decremented and
  `transmittedVersionNumber` restored from `priorTransmittedVersionNumber` (captured at request time,
  Flyway `V2__order_prior_transmitted_version.sql`). The next send is therefore `INITIAL` for a never-sent
  order and `REVISION` for a revised confirmed one.
- The timeline gets a `NOT_DISPATCHED` entry: `status` = `SUPPLIER_NOT_CONFIGURED`, `vendorReason` = "Not
  sent: {supplierRef} is not set up for electronic ordering. The vendor has not received this order." plus
  pos-supplier's detail (no profile for the alias vs profile disabled).
- Next action: an administrator configures or re-enables the vendor profile, then the buyer sends the order
  again (`transmit` is allowed from `NOT_DISPATCHED`) or orders outside the system. Nothing re-sends
  automatically and there is no "mark as sent manually".
- A never-dispatched PO gets no `supplier.orderstatus.changed` events.

## Error codes

Every non-2xx response carries the platform `ApiError` envelope. Field semantics, payload examples,
and the platform-wide fallback codes emitted by `pos-web-common` and `pos-security-common` are in
[`durion/docs/architecture/api/ERROR_ENVELOPE.md`](../../durion/docs/architecture/api/ERROR_ENVELOPE.md).
The table below is this module's own codes; any endpoint here may additionally return a platform
fallback code. Add a row in the same pull request as the controller or advice that mints the code.

| Code | Status | Description |
|------|--------|-------------|
| `ORDER_INVALID_SKU` | 400 | SKU on the order line is not valid |
| `ORDER_INVALID_ARGUMENT` | 400 | Sales-order request validation failure (`SalesOrderRequestValidationException`) |
| `ORDER_PRICE_OVERRIDE_BAD_REQUEST` | 400 | Price-override request validation failure |
| `VALIDATION_FAILED` | 400 | Bean-validation rejection of a price-override body, with `fieldErrors` |
| `PURCHASE_ORDER_BAD_REQUEST` | 400 | Purchase-order request validation failure, including a currency that is not an ISO 4217 code |
| `REGISTER_SESSION_INVALID_ARGUMENT` | 400 | Register-session request validation failure |
| `VALIDATION_ERROR` | 400 | `PUT /v1/orders/session-policy`: justification under 10 characters, a negative limit or tolerance, an allowed type without a cashier limit, vendor cash on delivery switched on (not until S24), or a missing / non-ISO `currencyCode`; also a non-ISO `currencyCode` on a cash-movement or approval body (CAP:550 S16) |
| `RETURN_INVALID_ARGUMENT` | 400 | Return request validation failure |
| `ORDER_FORBIDDEN` | 403 | Caller lacks required order permissions (sales orders, cancellations, price overrides, register sessions) |
| `PURCHASE_ORDER_FORBIDDEN` | 403 | Caller lacks required purchase-order permissions |
| `CASH_MOVEMENT_APPROVAL_REQUIRED` | 403 | A cash movement above the cashier limit on the session's running total of its reason, or any float change, without a manager's `approvalToken` (CAP:550 S16) |
| `CASH_MOVEMENT_APPROVAL_INVALID` | 403 | The approval token is unknown, used, expired, or issued for another session, reason, amount, currency, category or vendor |
| `CASH_MOVEMENT_SELF_APPROVAL` | 403 | The step-up named the caller's own credentials, or the token's approver is the caller recording the movement |
| `CASH_MOVEMENT_CALLER_UNIDENTIFIED` | 403 | The caller's sign-in carries no user id, so an approval cannot be proven to be someone else's |
| `CASH_MOVEMENT_APPROVAL_DENIED` | 403 | The step-up could not verify a holder of `order:session:approve_cash_movement` whose scope reaches the drawer (wrong or unknown credentials, a locked or inactive account, no permission, out of reach), or that manager sign-in name was already refused `pos.order.session.max-denied-approvals` times (3) on this drawer — one body for every reason, never 401 |
| `ORDER_NOT_FOUND` | 404 | Sales order does not exist |
| `ORDER_PRICE_OVERRIDE_NOT_FOUND` | 404 | Price override record not found |
| `PURCHASE_ORDER_NOT_FOUND` | 404 | Purchase order does not exist |
| `REGISTER_SESSION_NOT_FOUND` | 404 | Register session does not exist |
| `RETURN_NOT_FOUND` | 404 | On a return endpoint: the return order, or the sales order it references, does not exist |
| `ORDER_PRICE_OVERRIDE_IDEMPOTENCY_CONFLICT` | 409 | Duplicate idempotency key for price override |
| `ORDER_IDEMPOTENCY_CONFLICT` | 409 | A cart idempotency key was reused with a different payload |
| `IDEMPOTENCY_CONFLICT` | 409 | A cash-movement `requestId` already recorded with a different payload or on another session (CAP:550 S16) |
| `SESSION_POLICY_CONFLICT` | 409 | `PUT /v1/orders/session-policy` named a version other than the current one, or lost a race with another change; read again and retry |
| `ORDER_CANCELLATION_INVALID` | 409 | Order cannot be cancelled in its current state |
| `ORDER_NOT_EDITABLE` | 409 | The order's status no longer allows edits |
| `ORDER_INVALID_STATE_TRANSITION` | 409 | The requested status transition is not allowed from the order's current status |
| `ORDER_CONFLICT` | 409 | The order was modified concurrently; retry with fresh state |
| `ORDER_VOID_BLOCKED` | 409 | A void was requested on an order that already has settled payments; use cancellation so the money is reversed |
| `PURCHASE_ORDER_INVALID_STATE` | 409 | The purchase order's status does not allow the operation |
| `REGISTER_SESSION_CONFLICT` | 409 | A second open on a terminal that already has an OPEN session, a cash movement or close against a non-OPEN session, or a confirm-close before begin-close |
| `SESSION_CLOSE_BLOCKED` | 409 | The register session cannot close while one or more of its orders are still in PENDING_PAYMENT |
| `RETURN_INVALID_STATE` | 409 | The return order's status does not allow the operation |
| `ORDER_PRICE_OVERRIDE_INVALID` | 422 | Price override failed business validation |
| `ORDER_INVALID_CUSTOMER` | 422 | The customer referenced by the order is not valid for it |
| `REGISTER_FLOAT_LOCATION_MISMATCH` | 422 | `POST /v1/orders/sessions` at a location other than the one the register's configured float is held at (#2573: no register moves during an open session); `fieldErrors` name `terminalId`, `requestedLocationId` and, only when the caller's scope covers it, `floatLocationId`. No session is opened |
| `CASH_MOVEMENT_TYPE_NOT_ALLOWED` | 422 | The movement's reason is switched off in the tenant's drawer policy (CAP:550 S16) |
| `PETTY_EXPENSE_CATEGORY_UNKNOWN` | 422 | A petty expense names no ACTIVE category of pos-order's copy of accounting's categories |
| `FLOAT_CHANGE_NOT_RECORDED` | 422 | A float movement that does not close the gap between the register's configured float and the drawer's float exactly, that moves toward a negative float, or on a drawer whose register float is held at another location |
| `CURRENCY_NOT_SUPPORTED` | 422 | A cash movement or approval in an ISO 4217 currency other than the drawer's (stamped from `pos.order.functional-currency` when it opened), a drawer-policy PUT in a currency other than the functional currency, `POST /v1/orders/sessions` for a register whose configured float copy is in another currency than the drawer's (no session is opened), or a `FLOAT_INCREASE`/`FLOAT_DECREASE` on a drawer whose register float copy is in another currency than the drawer's stamp (nothing is recorded) — #2577: a float is never compared across currencies (ADR-0067) |
| `ORDER_CUSTOMER_REQUIRED` | 422 | Checkout of a cart that names no customer (CAP:550 S8) |
| `ORDER_WALK_IN_UNAVAILABLE` | 422 | Walk-in was chosen but the customer replica holds no active CASH house account for the tenant |
| `ORDER_WALK_IN_NOT_ALLOWED` | 422 | A walk-in cart asked for on-account tender, a deposit take or a workorder link; `fieldErrors[walkIn]` is `ON_ACCOUNT`, `DEPOSIT` or `WORKORDER_LINK` |
| `ORDER_WALK_IN_NOT_PAID_IN_FULL` | 422 | A walk-in checkout whose `tenderedAmount` is absent or below the final grand total; `fieldErrors[tenderedAmount]` names the total |
| `ORDER_UNPROCESSABLE` | 422 | The order is refused by an attribute of the target other than its status, or by the state of a referenced resource (`SalesOrderUnprocessableException`) |
| `RETURN_LINE_NOT_RETURNABLE` | 422 | Requested return line is not returnable per policy (issue #1694; split out of the former blanket `RETURN_INVALID_ARGUMENT` 422 catch-all) |
| `RETURN_OVER_CAP` | 422 | The return exceeds the un-refunded remainder of one or more sold lines; `fieldErrors` lists the current `returnableQty` per offending line |
| `RETURN_WARRANTY_ROUTING` | 422 | A WARRANTY-condition return was requested on a non-returnable workorder-consumed line; it routes to pos-warranty instead |
| `RETURN_WALK_IN_NOT_ALLOWED` | 422 | A return against a walk-in sale asked for `STORE_CREDIT` or `ON_ACCOUNT_CREDIT`; only `ORIGINAL_TENDER` is allowed |
| `RETURN_UNPROCESSABLE` | 422 | A structurally valid return that a domain rule refuses: a refund method needing a customer the return lacks, no invoice to refund against, or insufficient settled original tender |
| `UOM_CONVERSION_UNDEFINED` | 422 | A purchase-order line names a `uomCode` with no conversion row for the product |
| `VENDOR_INACTIVE` | 422 | The purchase order's vendor is inactive and takes no new purchase order (create, approve, a vendor-changing revision, transmit) |
| `SUPPLIER_REF_MISSING` | 422 | The purchase order cannot be transmitted: no supplier reference |
| `PURCHASE_ORDER_NOT_APPROVED` | 422 | The purchase order cannot be transmitted: not approved |
| `TRANSMISSION_IN_FLIGHT` | 422 | A transmission of this purchase order is already in flight (ADR-0052) |
| `TRANSMISSION_AWAITING_REVIEW` | 422 | A prior transmission attempt is awaiting review |
| `ARTICLE_NOT_IDENTIFIABLE` | 422 | One or more purchase-order lines name no article the vendor could recognise |
| `FRACTIONAL_QUANTITY` | 422 | A purchase-order line's quantity is not a whole number |
| `TRANSMISSION_UNAVAILABLE` | 422 | The deployment has no event publishing wired, so nothing can reach the vendor |
| `ORDER_CANCEL_REVIEW_REQUIRED` | 500 | The cancellation retry failed again and the order is parked at `CANCEL_REQUIRES_MANUAL_REVIEW`; `nextAction` carries the recovery |
| `VENDOR_REPLICATION_PENDING` | 503 | The purchase order's vendor is not in pos-order's copy of the pos-supplier vendor master yet (create, approve, a vendor-changing revision, transmit); `Retry-After` is set, retry or seed the copy (ADR-0017 §1, #1994) |
| `ORDER_TAX_UNAVAILABLE` | 503 | pos-tax could not be reached to price the order |
| `ORDER_INVOICING_UNAVAILABLE` | 503 | pos-invoice could not be reached to complete the order |
| `CASH_MOVEMENT_APPROVAL_UNAVAILABLE` | 503 | pos-security-service's step-up check could not be made (unreachable, timed out, or answered anything but a result or `STEP_UP_DENIED`) |

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |
| `POS_ORDER_FUNCTIONAL_CURRENCY` | required | ISO 4217 code of drawer money (ADR-0067 R-2; a Stage A interim until step A5 reads the tenant's functional currency). No default: unset or non-ISO fails startup. A drawer is stamped with it when it opens, and its movements, approvals and close fact keep that stamp, so a change applies to drawers opened afterwards. V4 stamps pre-existing drawers with it through the Flyway placeholder `${functional_currency}` (`FlywayConfig`). |
| `POS_ORDER_SESSION_MAX_DENIED_APPROVALS` | `3` | Refused manager approvals per drawer session and manager sign-in name before the step-up stops asking pos-security-service for that name. Keep it below pos-security-service's sign-in lockout (`pos.security.lockout.max-attempts`, 5) so a register cannot lock a manager out; another manager can still approve. |
| `POS_ORDER_SESSION_BOOTSTRAP_REPUBLISH_ENABLED` | `true` | At start, re-emit `order.session.opened` for every OPEN or CLOSING register session, per tenant (see [Register session facts](#register-session-facts-cap550-s40-2578)). |
| `POS_ORDER_MANIFEST_TOPIC` | `order.manifest.v1` | Topic of the reconciliation manifests (`pos.order.manifest.topic`; see [Reconciliation manifest and replay](#reconciliation-manifest-and-replay-adr-0044-4-2579)) |
| `POS_ORDER_MANIFEST_WINDOW` / `POS_ORDER_MANIFEST_GRACE` | `PT1H` / `PT5M` | Manifest window length, and how long after a window closes its manifest is published |
| `POS_ORDER_SUPPLIER_MANIFEST_TOPIC` / `POS_ORDER_SUPPLIER_MANIFEST_CONSUMER_GROUP` | `supplier.manifest.v1` / `pos-order-supplier-manifests` | pos-supplier's reconciliation manifest and this module's group on it (see [Vendor copy](#vendor-copy-and-the-purchase-order-vendor-guard-cap550-s24-2517)) |
| `POS_ORDER_SUPPLIER_COMMANDS_TOPIC` | `supplier.commands.v1` | Where `supplier.outbox.replay-requested` is sent on drift |
| `pos.order.outbox.replay.max-lookback` | `P30D` | Oldest window start an `order.outbox.replay-requested` command is served for |
| `POS_SECURITY_API_SECRET` | required for approvals | Sent as `X-Internal-Api-Secret` on the step-up call; unset, every approval is 503 `CASH_MOVEMENT_APPROVAL_UNAVAILABLE` |

## Multitenancy (ADR-0062, WS3 wave 5)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`, every scoped entity
extends `TenantScopedEntity`, and the global tables listed in `src/main/resources/db/tenancy-global-tables.txt`
carry `@TenantGlobal`. The request tenant is bound by `TenantContextFilter` from `X-Tenant-Id` (the gateway
injects it from the token's `tid`), the Kafka tenant by `TenantRecordInterceptor` from the `tenantId` record
header on every one of the module's consumers, and every connection checkout binds `app.current_tenant` for
row-level security. `pos.tenancy.default-tenant-id` still binds the alpha default tenant on every unbound path
(tokens issued before `tid`, records without the header).

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, `FlywayConfig`).

The outbox row carries the producing tenant as data (`tenant_id`, stamped from the bound tenant by
`OutboxEventWriter`); the outbox jobs are platform-scoped: `OutboxPublisher.publishPending` drains the global
`event_outbox` and puts each row's `tenant_id` on the record header, and `ManifestPublisher.publishDueManifest`
reads it to publish one manifest per tenant, each stamped with its tenant. The one native query,
`PurchaseOrderRepository`'s `nextval('purchase_order_number_seq')`, carries `@TenantAudited`: it reads a
platform-wide sequence, not a table.

Proof: `TenantIsolationIT` (tenant A's `order_number_sequence` row is invisible to tenant B and to an unbound
connection, through the repository and through raw SQL) and `TenancySchemaConformanceIT` (every
non-whitelisted table has `tenant_id`, RLS enabled and forced, and the `tenant_isolation` policy; the pool is
`pos_app` with no bypass), both on Testcontainers Postgres (`./mvnw -pl pos-order -am verify`).

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-tenancy-common` — ADR-0062 tenant context, connection binding, Hibernate resolver, Kafka propagation
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-shared-dtos` — shared DTOs

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`: `V1__baseline_order.sql`, the whole schema
(the 2026-09-09 flattened baseline, edited in place while in alpha): the tenancy schema on every scoped table,
`tenant_id` as data on the global outbox table (see Multitenancy above), and the nullable
`order_payment_record.currency_code` (the ISO 4217 currency of the settled or reversed payment as stamped on the
pos-invoice fact, ADR-0067 DF-3; ON_ACCOUNT rows stay null). Alpha databases are recreated rather than migrated
(`docs/runbooks/flyway-baseline-reset.md`, "Alpha Cutover").

Forward migrations include `V2__order_prior_transmitted_version.sql` (#2492),
`V3__ext_customer_house_account.sql` (CAP:550 S8 — the nullable `ext_customer.house_account` flag that marks the
tenant's CASH house account; filled by a party-fact replay), and
`V6__event_outbox_published_window_index.sql` (#2579 — the partial `(topic, created_at) WHERE published_at IS NOT
NULL` index the reconciliation manifest and its replay read `event_outbox` through, as the other fact owners have), and
`V7__ext_supplier_vendor.sql` (CAP:550 S24 — the tenant-scoped vendor copy `ext_supplier_vendor`, with RLS).

## Development

```bash
./mvnw -pl pos-order -am spring-boot:run
```
