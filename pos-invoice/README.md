# pos-invoice

Invoice and payment service for the Durion Positivity ETSMS platform. Creates invoices from workorder completion, collects payments via the configured payment gateway (Stripe), manages receipt generation, handles payment reversals and voids, and enforces billing rules.

## Responsibilities

- Create and finalize invoices from workorder line items
- Collect payments with idempotency enforcement
- Capture, void, and refund payment intents via `PaymentGatewayPort`
- Generate, reprint, and email receipts
- Apply invoice adjustments
- Enforce billing rules per customer or location
- Revert finalised invoices (credit reversal flow)

## Key Classes

- `InvoiceService` — invoice lifecycle (create, retrieve, revert)
- `InvoiceFinalizationService` — transitions invoice DRAFT → FINALIZED (emitting the `invoice.invoice.updated` fact that drives GL posting in pos-accounting) and FINALIZED → POSTED when accounting's GL-posted fact arrives
- `AccountingEventsListener` — consumes `accounting.events.v1`; applies `accounting.invoice.gl-posted` to move a FINALIZED invoice to POSTED and record its `glEntryId` (#1843)
- `PaymentService` — initiates payment intents with idempotency; delegates to gateway adapter
- `PaymentReversalService` — voids and refunds settled or unsettled payments
- `ReceiptService` — generates PDF receipts and dispatches print/email actions
- `BillingRulesService` — reads and enforces per-customer billing rule configurations
- `SettlementSourcePort` — outbound port for a processor settlement (payout) feed; default binding is the `UnavailableSettlementSourceAdapter` placeholder until a real adapter is provided (story F1b, #962)
- `SettlementEventPublisher` — emits the normalized `SettlementReportedV1` fact (`payment.events.v1`, keyed by settlement id) and the compacted `SettlementProviderConfigV1` (`payment.settlement-config.v1`, keyed per provider) to pos-accounting via the invoice transactional outbox

## API Endpoints

- `POST /v1/invoices` — create an invoice
- `POST /v1/invoices/from-order` — create the invoice fronting a sales order at checkout
  (order parity story C2; idempotent on `orderId`, returns the existing workorder invoice when
  `workorderId` matches one)
- `POST /v1/invoices/{invoiceId}/cancel` — terminal cancel of a DRAFT invoice before any money
  moved (order-void path, story C5); 409 once finalized or with authorized/captured payments
- `GET /v1/invoices/{invoiceId}` — retrieve an invoice
- `GET /v1/invoices/by-workorder/{workorderId}` — retrieve the invoice linked to a workorder
  (side-effect-free workorderId → invoiceId lookup for the frontend, #2232); 404 when no invoice
  is linked yet
- `POST /v1/invoices/{invoiceId}/finalize` — finalize an invoice
- `POST /v1/invoices/{invoiceId}/revert` — revert a finalized invoice
- `POST /v1/invoices/{invoiceId}/adjustments` — apply an adjustment
- `POST /v1/invoices/{invoiceId}/payments` — initiate payment (idempotent; `invoice:payment:process`,
  plus `invoice:payment:limit_override` when the amount exceeds 500.00 and
  `invoice:payment:flow_select` to choose `AUTH_ONLY`, #2393)
- `POST /v1/invoices/{invoiceId}/payments/{paymentId}/capture` — capture an authorized payment
  (`invoice:payment:capture`, #2393)
- `GET /v1/invoices/{invoiceId}/payments` — list every payment intent raised against an invoice,
  including its refunded and refundable amounts (#2226, #2215)
- `GET /v1/invoices/{invoiceId}/payments/{paymentId}` — read a single payment intent's detail,
  never the tokenised card reference or raw gateway response (#2226, #2215)
- `POST /v1/invoices/{invoiceId}/payments/{paymentId}/void` — void a payment (`invoice:payment:void`;
  `invoice:payment:override` bypasses the 24-hour window, #2226)
- `POST /v1/invoices/{invoiceId}/payments/{paymentId}/refunds` — refund a payment
  (`invoice:payment:refund`; `invoice:payment:override` bypasses the 180-day window, #2226)
- `POST /v1/invoices/{invoiceId}/receipts` — generate a receipt (`invoice:receipt:generate`, #2226)
- `GET /v1/invoices/{invoiceId}/receipts/{receiptId}` — retrieve full receipt detail (#2214)
- `POST /v1/invoices/{invoiceId}/receipts/{receiptId}/email` — email a receipt
- `POST /v1/invoices/{invoiceId}/receipts/{receiptId}/print` — print a receipt
- `GET /v1/billing/rules/{partyId}` — retrieve billing rules for a party

## Finalization and GL posting

Invoice status moves `DRAFT → FINALIZED → POSTED`; `POSTED` is immutable. pos-invoice never
posts to the ledger itself — the flow is event-only (ADR-0044 §6, #1843):

1. `POST /v1/invoices/{invoiceId}/finalize` freezes tax, totals and due-date facts, saves the
   invoice as `FINALIZED`, and emits `invoice.invoice.updated` (status `FINALIZED`) through the
   transactional outbox on `invoice.events.v1`.
2. pos-accounting consumes that fact and posts the revenue journal entry
   (`Dr Accounts Receivable / Cr Service Revenue / Cr Sales Tax Payable`, dated at
   `finalizedAt`), then publishes `accounting.invoice.gl-posted` on `accounting.events.v1`.
3. `AccountingEventsListener` consumes the fact. A `POSTED` fact whose `finalizedAt` matches the
   invoice's moves it `FINALIZED → POSTED`, records the journal entry id as `glEntryId`
   (`invoices.gl_entry_id`), and emits `invoice.invoice.updated` (status `POSTED`) so downstream
   replicas see it. A duplicate delivery (already `POSTED` under the same entry) is a no-op; a
   fact for an invoice that was reverted or cancelled in the meantime (`DRAFT`/`CANCELLED`) is
   logged and skipped — pos-accounting reverses the entry when it sees that fact and answers
   with a `REVERSED` fact, which changes nothing in pos-invoice.

Active on the Kafka rails (any profile but `dev`/`test`/`pg`, or `local-kafka`); topic and consumer group are
`POS_INVOICE_ACCOUNTING_EVENTS_TOPIC` (`accounting.events.v1`) and
`POS_INVOICE_ACCOUNTING_EVENTS_CONSUMER_GROUP` (`pos-invoice-accounting-events`).

## Customer required to finalize and pay (CAP:550 S9, #2507)

Every invoice that is finalized or paid names a customer (spec `SPEC-accounting-workspace.md`
§4.4 item 1, AW12). pos-order's checkout already refuses a cart without one (S8) and sends the
tenant's CASH house account for walk-in sales; pos-invoice is the backstop behind it, closing gap
G6 (a party-less invoice posted to accounts receivable while pos-accounting skipped its payment):

- `POST /v1/invoices/{invoiceId}/finalize` answers `422 INVOICE_PARTY_REQUIRED` when the invoice's
  `partyId` is null or blank — after the `DRAFT` check and **before** the manager-approval matrix and
  the committable tax calculation, so no provider tax document is created. The invoice stays
  `DRAFT`; `InvoiceFinalizationService.checkEligibility` reports `eligible = false` with the same
  reason, so a detail read and the finalize command agree.
- `POST /v1/invoices/{invoiceId}/payments` answers `422 INVOICE_PARTY_REQUIRED` before the intent is
  saved or the gateway is called (a `SALE_CAPTURE` initiate captures in one step, so the initiate is
  covered as well as the separate capture). No `payment_intents` row is written and no money moves.
- `POST /v1/invoices/{invoiceId}/payments/{paymentId}/capture` answers the same after the
  `AUTHORIZED` state check and before the gateway capture; the hold stays `AUTHORIZED`.
- A replay of an existing intent under the same `idempotencyKey` returns the stored intent
  unchanged, party or not — no new money movement.

Draft creation stays permissive (`POST /v1/invoices`, `/from-order`): a workorder draft can still
acquire its party through `InvoicePartyIdBackfillService` before finalization, and a finalize
attempt before the backfill fills it is refused until the retry succeeds. The CASH house account
is a valid party for finalization and payment. Nothing back-assigns past invoices (AW13), and
reassigning a finalized invoice to another customer is not built (spec §12 OI-5). Each refused
attempt is logged at INFO with the invoice number, the code and the actor from the security context
(ADR-0018), and the `@EmitEvent` wrapper around `INVOICE_FINALIZED` / `INVOICE_PAYMENT_INITIATE` /
`INVOICE_PAYMENT_CAPTURE` writes its `[EVENT-ERROR]` line; those events themselves are published
only when the operation succeeds (`EventEmissionService`), so a refusal does not reach the event
receiver. A failure-audit emission is a platform decision recorded for S34.

**Rollout check.** Orders checked out before S8 can still be `PENDING_PAYMENT` with a party-less
`DRAFT` invoice; once this backstop ships their payment is refused and the session-close guard then
blocks that drawer. Before deploying, run the two queries below against each tenant and settle or
void each hit (re-ring a voided order under S8's rules). The alpha tenant is expected to have none.

```sql
-- party-less DRAFT invoices that front an open order
-- (the bill-to party is stored in invoices.customer_id; the guard treats whitespace as missing)
SELECT id, invoice_number, order_id, created_at
  FROM invoices
 WHERE status = 'DRAFT' AND btrim(coalesce(customer_id, '')) = '' AND order_id IS NOT NULL;

-- AUTHORIZED holds on party-less invoices
SELECT p.id AS payment_intent_id, p.invoice_id, i.invoice_number, p.authorized_amount
  FROM payment_intents p JOIN invoices i ON i.id = p.invoice_id
 WHERE p.status = 'AUTHORIZED' AND btrim(coalesce(i.customer_id, '')) = '';
```

**After deploy, replay the customer party facts per tenant** (pos-customer
`POST /v1/crm/accounts/facts/replay`): builds before S9 ignored `CustomerPartyUpdatedV1.houseAccount`,
so an existing CASH row has `ext_customer_party.house_account` NULL until a replay at the same
aggregate version fills it (the listener applies equal versions). Without the replay, revenue-by-customer
still ranks the CASH account (AC7 does not hold).

## Kafka error handling and dead-lettering (ADR-0044 §4, #2483)

On the Kafka rails (outside dev/test), `KafkaErrorHandlingConfig` installs a `DefaultErrorHandler` on
every pos-invoice listener container: exponential backoff (1s, x2, capped at 30s, 5 retries), then a
`DeadLetterPublishingRecoverer` publishes the record to `{topic}.dlq`. Listeners rethrow retryable
failures (`RetryableConsumerFailures`) so they reach this handler; a record whose retries are
exhausted is dead-lettered rather than logged and skipped.

## Location scope (ADR-0061, #1872)

`@PreAuthorize` answers "may this caller manage invoices"; the caller's `LocationScope` (decoded
by `pos-security-common` from the gateway's `X-Loc-*` headers) answers "…at this location".
Decisions per operation are recorded in [`location-scope.yaml`](location-scope.yaml):

- `POST /v1/invoices` and `POST /v1/invoices/from-order` are **gated** in `InvoiceController` on
  the request's `locationId` with `invoice:manage`. A caller whose grant is location-scoped is
  denied outside its reach with `403 LOCATION_SCOPE_DENIED`; for such a caller an omitted
  `locationId` is denied too (fail closed). Pre-rollout tokens without `loc_*` claims are unchanged.
- `GET /v1/invoices/{invoiceId}` is gated in `InvoiceServiceImpl.loadInvoiceDetail` on the stored
  invoice's location with `invoice:invoice:view`, after the 404, so the create gate cannot be
  bypassed by reading an invoice back and ids cannot be probed through the 403.

The check runs in-process against the `ext_location` replica: `LocationHierarchyService`
implements `LocationAncestorResolver` over the materialised `financial_ancestor_ids` /
`other_ancestor_ids` sets that `LocationEventsListener` recomputes from the `ext_location_parent`
edges on every `location.location.updated` fact (V21). A location the replica does not hold is
denied for scoped callers; pos-location is never called per request.

## Payment settlement events

On the Kafka rails (outside dev/test), per-payment settlement facts are published on
`payment.events.v1`: `payment.payment.settled` when a PaymentIntent captures (sale-capture or
manual capture) and `payment.payment.reversed` on voids and refunds (gateway and standalone).
pos-order's completion handshake is the first consumer (order parity story C3).

`payment.payment.settled` is published at **`schemaVersion` 2** (CAP:550 S9): `partyId` is always
present, because the backstops above never let a party-less invoice reach a capture. The bump is in
place on `payment.events.v1` under ADR-0044 §3 "Event contract standard" (like
`catalog.service.updated`), not a new `.v2` topic: §3 allows only additive changes within a topic
version, and this one adds no field and only tightens a guarantee — every version-2 message is a valid
version-1 message, so a version-1 consumer reads it unchanged. The `PaymentSettledV1` record keeps no
constructor rejection of null, so version-1 facts published before go-live still deserialise on
redelivery or replay (AW13). Today's consumers (pos-accounting `SettlementEventsListener`, pos-order
`PaymentEventsListener`) keep their defensive null handling and do not read `schemaVersion`; telling a
legacy null from a version-2 defect by `schemaVersion` is S11's accounting alert (#2508).
`payment.payment.reversed` keeps a nullable party because reversals of pre-go-live payments must still
flow.

Should a captured intent ever reach `PaymentEventPublisher` without a party, the fact is still queued
(the capture has happened; dropping it would leave pos-order unable to complete the order), logged at
ERROR and counted on **`payment.settled.party_missing`**. The counter fires only on a defect:
alert on any non-zero rate, e.g. `increase(payment_settled_party_missing_total[15m]) > 0`, and treat
each hit as a bug in the backstops rather than a business case.

## Error codes

Every non-2xx response carries the platform `ApiError` envelope. Field semantics, payload examples,
and the platform-wide fallback codes emitted by `pos-web-common` and `pos-security-common` are in
[`durion/docs/architecture/api/ERROR_ENVELOPE.md`](../../durion/docs/architecture/api/ERROR_ENVELOPE.md).
The table below is this module's own codes; any endpoint here may additionally return a platform
fallback code. Add a row in the same pull request as the controller or advice that mints the code.

| Code | Status | Description |
|------|--------|-------------|
| `VALIDATION_ERROR` | 400 | This module's own request validation failure (`InvoiceRequestValidationException`) on the invoice, billing-rules, analytics, search and artifact endpoints |
| `DEPOSIT_CREDIT_INVALID_ARGUMENT` | 400 | The same validation failure raised on a deposit-credit endpoint |
| `ELEVATION_DENIED` | 401 | Manager-approval elevation refused: the employee number does not resolve to an active employee, or that person does not hold `invoice:finalize:override` |
| `FORBIDDEN` | 403 | Caller lacks the required payment permission, or the artifact download token is invalid |
| `MANAGER_APPROVAL_REQUIRED` | 403 | Finalizing this invoice exceeds the amount cap and no manager-approval elevation token was supplied — a step-up credential the caller lacks (ADR-0017 §2 question 1, #1725; introduced by #1694 as a 422). `nextAction` points at `elevateManagerApproval` |
| `MANAGER_APPROVAL_INVALID` | 403 | Supplied manager-approval elevation token does not verify (wrong scope, tampered, or expired) — a step-up credential the server considers insufficient (ADR-0017 §2 question 1, #1725; introduced by #1694 as a 422). `nextAction` points at `elevateManagerApproval` |
| `NOT_FOUND` | 404 | Invoice, receipt, artifact, payment intent or billing rules not found |
| `DEPOSIT_CREDIT_NOT_FOUND` | 404 | Referenced deposit credit does not exist |
| `INVALID_STATE` | 409 | Invoice state transition is not allowed |
| `CONFLICT` | 409 | General state conflict (e.g. already finalized) |
| `INVALID_PAYMENT_STATE` | 409 | The payment intent is not in a state that allows the capture, void or refund |
| `PAYMENT_IDEMPOTENCY_CONFLICT` | 409 | A payment idempotency key was reused with a different payload |
| `REPRINT_LIMIT_EXCEEDED` | 409 | The receipt has already been reprinted the maximum number of times |
| `PAYMENT_DECLINED` | 422 | Payment gateway declined the transaction |
| `PAYMENT_WINDOW_EXPIRED` | 422 | Refund window for the payment has closed |
| `INSUFFICIENT_REFUNDABLE_AMOUNT` | 422 | Refund amount exceeds what was originally paid |
| `EXCESSIVE_ADJUSTMENT` | 422 | Adjustment would drive the invoice total negative; a credit memo is required instead (issue #1694; split out of the former blanket `IllegalArgumentException` 400 catch-all) |
| `INVOICE_PARTY_REQUIRED` | 422 | The invoice has no customer (`partyId` null or blank): finalization, payment initiate and payment capture are refused before any tax document or gateway call; the invoice stays `DRAFT` and an `AUTHORIZED` hold stays `AUTHORIZED` (CAP:550 S9, #2507) |
| `INTERNAL_SERVER_ERROR` | 500 | The payment gateway call failed during a reversal |
| `LOCATION_REPLICATION_PENDING` | 503 | The invoice's `ext_location` row has not replicated from `location.events.v1` yet, so its tax jurisdiction cannot be resolved (create, adjustment, finalize). Carries `Retry-After` and `referenceId` = the location id; not-yet, not no, so retry (#1994). A replicated location missing country/postal code stays `409` |

## Configuration

| Property                          | Default  | Description                                                              |
| --------------------------------- | -------- | ----------------------------------------------------------------------- |
| `SPRING_DATASOURCE_URL`           | required | PostgreSQL connection URL                                               |
| `EUREKA_SERVER_URL`               | required | Eureka service discovery URL                                           |
| `invoice.elevation.token-secret`  | required | HMAC secret for manager-approval elevation tokens (≥32 bytes; service fails fast if unset/short) |
| `invoice.elevation.token-ttl-seconds` | `300` | Elevation token lifetime in seconds                                  |

### Manager-approval elevation — operational setup

Finalizing/reverting an invoice above the service-advisor cap requires override
capability. There are two ways to obtain it:

- **Logged-in manager/admin — auto-approved.** A caller holding the
  `ROLE_SHOP_MANAGER`, `ROLE_LOCATION_MANAGER`, or `ROLE_ADMIN` role (always present in
  the JWT) finalizes/reverts directly, no approval code. This needs **no** extra setup.
- **Service advisor naming a manager (employee-number approval).** The named manager
  must hold the `invoice:finalize:override` **authority**. The permission is registered
  at startup from `permissions.yaml`, and role grants live in pos-security's
  `role_permissions` table. Since #1374, pos-security's baseline seed
  (`R__seed_role_permissions.sql`) grants it to `ADMIN` and the manager roles —
  `ACCOUNT_MANAGER`, `GENERAL_MANAGER`, `LOCATION_MANAGER`, `MANAGER`, and
  `SHOP_MANAGER` — so no manual grant is needed after deploy. The only remaining
  setup is ensuring managers hold one of those roles through an effective role
  assignment: any grant path — user provisioning, the **user-role** admin API's
  `assignUserRole`, or the People access page — creates one, and the
  `person-decision` check resolves authorities through that assignment's effective
  window (`role_assignments`, the only store of a user's roles as of ADR-0061
  amendment phase 2, #1914). Additional roles can still be granted the permission via
  the role-permission admin API
  (`PUT /v1/roles/{roleId}/permissions/invoice:finalize:override`), but keep the
  holder set small: this permission is the control that caps what a service advisor
  can finalize by naming an absent manager.

## Multitenancy (ADR-0062, WS3 wave 9)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`, every scoped entity
extends `TenantScopedEntity`, and the two global tables (`event_outbox`, `processed_events`, listed in
`src/main/resources/db/tenancy-global-tables.txt`) carry `@TenantGlobal`. The request tenant is bound by
`TenantContextFilter` from `X-Tenant-Id` (the gateway injects it from the token's `tid`), the Kafka tenant by
`TenantRecordInterceptor` from the `tenantId` record header on every one of the module's consumers, and every connection checkout binds
`app.current_tenant` for row-level security. `pos.tenancy.default-tenant-id` still binds the alpha default
tenant on every unbound path (tokens issued before `tid`, records without the header).

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, `FlywayConfig`).

The outbox row carries the producing tenant as data (`tenant_id`, stamped from the bound tenant by
`OutboxEventWriter`, added by `V2__event_outbox_tenant_id.sql`):

| Job | Classification | Why |
| --- | --- | --- |
| `OutboxPublisher.publishPending` | platform-scoped | Drains `event_outbox`; each row's `tenant_id` becomes the record header |
| `ManifestPublisher.publishDueManifest` | platform-scoped | Groups the window's `event_outbox` rows by `tenant_id` and publishes one manifest per tenant, stamped with that tenant; every active tenant of the registry gets one, zero-count when it published nothing |
| `InvoicePartyIdBackfillService.backfill` | per-tenant | `invoices` and the `ext_workorder` replica are scoped; the bulk UPDATE runs per tenant with its transaction inside the binding |
The one native query, `InvoiceRepository.backfillPartyIdFromWorkorderReplica`, carries `@TenantAudited`: it
names no tenant because the backfill runs per tenant, so row-level security confines it to the bound tenant.

Proof: `TenantIsolationIT` (tenant A's `ext_location` row is invisible to tenant B and to an unbound
connection, through the repository and through raw SQL) and `TenancySchemaConformanceIT` (every
non-whitelisted table has `tenant_id`, RLS enabled and forced, and the `tenant_isolation` policy; the pool is
`pos_app` with no bypass), both on Testcontainers Postgres (`./mvnw -pl pos-invoice -am verify`).

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-shared-dtos` — shared invoice creation DTOs
- `pos-tax-common` — tax request/response types

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

`ext_customer_party.house_account` (V3, CAP:550 S9) carries the owner's house-account kind from
`CustomerPartyUpdatedV1.houseAccount` (`CASH_SALE` for the tenant's CASH walk-in account, null for
every ordinary party). `GET /v1/invoices/analytics/revenue-by-customer` leaves invoices whose party
is a house account out of the ranking — the CASH account is not a customer and would top every
list (spec §4.4 item 2, ADR-0057) — keyed on that flag only, never on a name or customer number;
`truncated`/`limit` semantics are unchanged. Rows replicated before pos-customer published the field
hold null until a party-fact replay at the same version fills them.

### Tax type on the tax rows (V4, CAP:550 S32a, #2636)

`invoice_line_tax.tax_type` and `invoice_tax_summary.tax_type` (`varchar(32) NULL`, no default, no CHECK) hold the tax-type
code pos-tax priced each row with (`TaxCalculationResponse.LineItemTax.jurisdictions[].taxType`, e.g. `GST`). The vocabulary is configuration only, declared
per country in pos-tax; there is no enum, so a new code needs no code change or migration here.

- **Copy, never infer.** `InvoiceTaxBreakdownWriter` copies a well-formed code exactly as received
  (`TaxTypeCodes.wellFormedOrNull`). An absent or malformed value is stored null and the row is **still written**, so the invoice tax still equals
  the sum of its rows. pos-invoice never derives a type from the jurisdiction type, code, rate or country.
- **Rollup.** The `invoice_tax_summary` key is `jurisdictionType|jurisdictionCode|taxType`, so two tax types sharing a
  jurisdiction are never merged.
- **DRAFT only, no backfill.** The column is written only by the DRAFT re-price path, which rebuilds the rows wholesale; a
  finalized invoice is never re-priced (the existing finalized-state guard), so its rows are frozen. Existing rows stay
  null permanently (all are US, where null is correct).
- **Event.** `InvoiceUpdatedV1.taxBreakdown[].taxType` (`TaxBreakdownLine.taxType`, nullable String, last component) carries the
  stored code; additive within schema version 1, same topic, no dual-publish (ADR-0044 §3). A US invoice's rows carry null.
- **No read contract change.** No invoice or receipt endpoint, DTO or SDK exposes the breakdown or the type.

## Development

```bash
./mvnw -pl pos-invoice -am spring-boot:run
```

## Reconciliation manifest replay requests (#2452)

A manifest listener that finds drift sends the owner's `outbox.replay-requested` command through
`OutboxReplayRequests`, which waits up to 30s for the broker's acknowledgement. A request that cannot
be handed to Kafka, that the broker rejects, or that is not acknowledged in time propagates to the container's error handler. `KafkaErrorHandlingConfig` retries the record with backoff and then dead-letters it to `{topic}.dlq` (#2483).
Swallowing it would lose the repair for good, because each owner publishes a window's manifest once
and no later manifest covers that window again. Redelivery is safe: a manifest writes nothing, the
comparison only reads, and the replay command is keyed by window start. A manifest that does not parse
is still dropped.
