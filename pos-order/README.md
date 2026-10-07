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
| `CASH_MOVEMENT_APPROVAL_DENIED` | 403 | The step-up could not verify a holder of `order:session:approve_cash_movement` whose scope reaches the drawer (wrong or unknown credentials, a locked or inactive account, no permission, out of reach), or the drawer already had five failed approvals — one body for every reason, never 401 |
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
| `CURRENCY_NOT_SUPPORTED` | 422 | A drawer amount (cash movement, approval, drawer policy) in an ISO 4217 currency other than the functional currency `pos.order.functional-currency` (ADR-0067) |
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
| `SUPPLIER_REF_MISSING` | 422 | The purchase order cannot be transmitted: no supplier reference |
| `PURCHASE_ORDER_NOT_APPROVED` | 422 | The purchase order cannot be transmitted: not approved |
| `TRANSMISSION_IN_FLIGHT` | 422 | A transmission of this purchase order is already in flight (ADR-0052) |
| `TRANSMISSION_AWAITING_REVIEW` | 422 | A prior transmission attempt is awaiting review |
| `ARTICLE_NOT_IDENTIFIABLE` | 422 | One or more purchase-order lines name no article the vendor could recognise |
| `FRACTIONAL_QUANTITY` | 422 | A purchase-order line's quantity is not a whole number |
| `TRANSMISSION_UNAVAILABLE` | 422 | The deployment has no event publishing wired, so nothing can reach the vendor |
| `ORDER_CANCEL_REVIEW_REQUIRED` | 500 | The cancellation retry failed again and the order is parked at `CANCEL_REQUIRES_MANUAL_REVIEW`; `nextAction` carries the recovery |
| `ORDER_TAX_UNAVAILABLE` | 503 | pos-tax could not be reached to price the order |
| `ORDER_INVOICING_UNAVAILABLE` | 503 | pos-invoice could not be reached to complete the order |
| `CASH_MOVEMENT_APPROVAL_UNAVAILABLE` | 503 | pos-security-service's step-up check could not be made (unreachable, timed out, or answered anything but a result or `STEP_UP_DENIED`) |

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |

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
`OutboxEventWriter`); the one scheduled job, `OutboxPublisher.publishPending`, is platform-scoped (it drains the
global `event_outbox` and puts each row's `tenant_id` on the record header). The one native query,
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

Forward migrations: `V2__order_prior_transmitted_version.sql` (#2492) and
`V3__ext_customer_house_account.sql` (CAP:550 S8 — the nullable `ext_customer.house_account` flag that marks the
tenant's CASH house account; filled by a party-fact replay).

## Development

```bash
./mvnw -pl pos-order -am spring-boot:run
```
