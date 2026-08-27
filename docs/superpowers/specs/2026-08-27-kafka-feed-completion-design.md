# Kafka Feed Completion Design

**Date:** 2026-08-27

**Status:** Approved for planning

**Issue:** durion-positivity-backend#1537

## Goal

Close the asynchronous feeds still missing from the ADR-0044 target model and
resolve every production Kafka topic that currently has only one side of its
producer/consumer contract, so that the topic graph is complete and provably so.

## Evidence Rules

Every new feed in this design is justified by an existing, unserved need found in
current source. No connector is added because a diagram once drew it. Where the
archived 2026-07-16 target diagram conflicts with a later ACCEPTED ADR-0044
amendment, the amendment wins and the divergence is recorded rather than
implemented.

## Findings That Shape the Design

### F1. The warranty synchronous edge is permanent, not pending

Issue #1537 §"Missing Target Feeds" item 3 asks to retire the
`pos-warranty -> pos-invoice` synchronous settlement edge and delete its
`DomainWallsTest` exception. That request derives from the archived 2026-07-16
target diagram.

**ADR-0044 amendment 2026-07-22 (ACCEPTED) supersedes it six days later.** It
names `InvoiceClient.createAdjustment` / `createRefund` and their reconciliation
reads `getInvoiceAdjustments` / `getInvoiceRefunds`, and holds them synchronous
permanently, because warranty settlement is a money-moving counter-flow that must
fail loudly in the initiating request path. Replacing it with a command topic
would force a pending/confirmation state machine for customer-visible refunds and
weaken the current "not refunded unless invoice accepted it" guarantee.

Independent code evidence agrees that the migration is not merely undesirable but
not currently possible:

- `SettlementReconciliationServiceImpl` consumes, per invoice, `{id,
  externalReference}` per adjustment entry and `{id, externalReference, status}`
  per refund entry.
- `InvoiceUpdatedV1` carries `adjustmentsAmount` as a **scalar rollup only** — no
  per-entry list, no `externalReference`, and **no refund data at all**.
- No refund fact exists on `invoice.events.v1` in any form.

So the reconciliation reads are not replicable without inventing new invoice
facts, which the amendment's rationale explicitly does not want to be the
settlement authority.

`DomainWallsTest` is also file-scoped, not method-scoped: removing the grant
requires deleting every `pos-invoice` token from `pos-warranty`'s
`internal/client` sources. A half-migration that moved only the writes would
still fail the build.

**Decision:** the warranty edge, its `DomainWallsTest` grant, and
`InvoiceClientImpl` stay exactly as they are. Issue #1537's three warranty
acceptance criteria are recorded as superseded by ADR-0044 amendment 2026-07-22,
with this rationale, rather than silently skipped.

**Carried out of this finding:** the amendment's own §Boundaries clause requires
settlement commands to carry strong idempotency keys so retries stay safe. That
obligation is verified rather than assumed (Task 1).

### F2. Accounting owns the receivable; pos-invoice owns the document

`InvoiceBalanceCalculator`'s class javadoc records the ADR-0044 R6 ownership
split verbatim: pos-invoice owns the invoice document (totals, lifecycle status)
and feeds accounting's `ext_invoice` replica; accounting owns everything that
happens to the receivable afterwards — payment applications, reversals, credit
memos — and the balance due is "computed here, never fetched from another
service".

Issue #1537 asks for "accounting's required invoice writes" to get a command
contract. Searching accounting's write surface finds credit memos
(`CreditMemoServiceImpl`), payment application (`PaymentApplicationServiceImpl`)
and invoice payment status (`InvoicePaymentStatusServiceImpl`) — **all of which
are receivable-side state that R6 deliberately keeps out of the invoice
document.** Pushing any of them to pos-invoice as a command would invert the
ownership split the calculator depends on, and risks double-counting if the
mirrored amount ever flowed back through `ext_invoice`.

**Decision:** accounting has no invoice-*document* write it needs, and none is
invented. The genuine, evidenced accounting→invoice gap is different and is what
this design implements: accounting holds `ext_invoice` and `ext_invoice_tax`
replicas with **no reconciliation loop at all**, so they can drift silently.
Every other replica holder in the repo (pos-workorder, pos-invoice, pos-customer,
pos-location, pos-shop-manager, pos-security-service, pos-inventory) runs a
manifest listener that detects drift and requests replay.

### F3. `payment.cleared.v1` carries behaviour nothing else covers

`PaymentApplicationServiceImpl.handlePaymentCleared` is the **only** code path in
the repository that constructs a `ReceivablePayment` — an AR-available,
fully-unapplied receivable. It is reached solely from the `payment.cleared.v1`
listener, which has no producer.

`payment.events.v1` does not cover it. Accounting's `SettlementEventsListener`
handles only `SettlementReportedV1`, which *matches* settlement-batch lines
against receivables that already exist. `PaymentSettledV1` and
`PaymentReversedV1` — published by pos-invoice's `PaymentEventPublisher` — are
consumed today only by pos-order.

So deleting the legacy listener outright would silently delete the AR funds-intake
path. Equivalent `payment.events.v1` behaviour must exist and be verified
**before** the legacy surface is removed, which is exactly the ordering issue
#1537's acceptance criterion requires.

### F4. Catalog replay already exists; only the Kafka trigger is missing

`pos-inventory`'s `CatalogManifestListener` detects drift and deliberately does
not request replay, because `pos-catalog` has no `catalog.commands.v1` listener
(#1023) and a command would be dropped unheard. `pos-inventory`'s
`application.yml` documents the same reason inline and deliberately omits a
`catalog-commands-topic` key.

But `pos-catalog` already owns three paged, resumable, idempotent replay services
— `ProductFactReplayService`, `ServiceFactReplayService`,
`SupplierArticleCodeReplayService` — re-emitting through the same
`CatalogFactPublisher` that live writes use. They are currently reachable only
from REST controllers. Closing this loop is wiring, not new replay machinery.

### F5. The platform sender is documented but unpinned

`docs/PLATFORM_SENDER_CONTRACT.md` already names the external owner of
`sender.outcomes.v1` and tabulates its envelope. What is missing is an automated
test that fails when `DeliveryOutcomeListener` and that document drift apart. The
repo has no Pact tooling; its convention is a plain JUnit `*ContractTest` that
pins a shape across all its instances.

## Scope

In scope:

1. Accounting consumes `workorder.events.v1` and resolves its own outstanding
   invoice-regeneration commands from those facts.
2. Accounting reconciles its invoice replicas and requests replay on
   `invoice.commands.v1`.
3. People replica holders reconcile against `people.manifest.v1` and request
   replay on `people.commands.v1`.
4. Accounting materializes receivables from `payment.events.v1`, after which the
   `payment.cleared.v1` surface is removed.
5. `sender.outcomes.v1` gets an automated envelope contract test.
6. `pos-catalog` gains a `catalog.commands.v1` replay listener, and
   `pos-inventory` starts requesting replay on catalog drift.
7. A topic-inventory guard test fails the build when an internal topic has
   producers without consumers or consumers without producers.
8. The domain interaction diagrams and edge catalogs are regenerated.

Out of scope (unchanged, with reasons recorded):

- The `pos-warranty -> pos-invoice` synchronous settlement edge and its
  `DomainWallsTest` grant (F1).
- Any accounting→invoice command carrying receivable-side state (F2).
- Audit/control-plane `@EmitEvent` registration, DLQ topics, and ADR-0044
  permitted utility HTTP calls, per the issue's own out-of-scope list.

## Design

### D1. `workorder.events.v1` → pos-accounting

Accounting publishes `workorder.invoice.regenerate-requested` to
`workorder.commands.v1` and today learns nothing of the outcome: the endpoint
returns `PENDING` forever and no record of the request survives the call.

A new `invoice_regeneration_request` table records each published command
(`workorderId`, `commandId`, `idempotencyKey`, `status`, `resultInvoiceId`,
timestamps). A new `WorkorderEventsListener` consumes `workorder.events.v1` and,
for a workorder carrying an `invoiceId` on `WorkorderUpdatedV1` or
`WorkorderServiceCompletedV1`, resolves any outstanding request to `COMPLETED`
with the resulting invoice id.

The existing endpoint then becomes genuinely idempotent: a repeat call with an
idempotency key whose request already completed returns the terminal state and
the resulting invoice id without re-publishing the command. **No new endpoint and
no request/response schema change**, so the OpenAPI/SDK contract chain is
untouched.

Listener shape follows `InvoiceEventsListener` exactly: raw `JsonNode` parse,
`eventType` dispatch, `processed_events` dedupe by `eventId`, `@Transactional`,
rethrow `TransientDataAccessException` only so the shared `DefaultErrorHandler`
(5 attempts, 1s→30s exponential, then `{topic}.dlq`) governs retry and DLQ.

### D2. pos-accounting → `invoice.commands.v1`

A new `InvoiceManifestListener` in pos-accounting consumes `invoice.manifest.v1`,
recomputes count and checksum from its own `processed_events` rows over the
manifest window, and on mismatch increments `replica.drift` and publishes
`invoice.outbox.replay-requested` to `invoice.commands.v1`.

This reuses the command type `InvoiceCommandListener` already handles
idempotently, and its authoritative result facts are the replayed
`invoice.events.v1` events. It follows the `PeopleContactManifestListener` /
`WorkorderManifestListener` template that six other modules already run.

### D3. `people.manifest.v1` → replica holders → `people.commands.v1`

Three modules hold `people.events.v1` replicas and none reconciles:

| Module | Replica |
| --- | --- |
| `pos-invoice` | `ExtEmployeeReplica` |
| `pos-shop-manager` | `ExtStaffingAssignmentReplica` (drives `Mechanic`) |
| `pos-workorder` | `ExtStaffingAssignmentReplica` |

Each gains a `PeopleManifestListener` with `owner = "people"` publishing
`people.outbox.replay-requested` to `people.commands.v1`, which
`PeopleCommandListener` already handles. This makes both previously inert
surfaces live rather than removing them, matching how the identical
`people-contact` pair works across five modules.

### D4. `payment.events.v1` intake, then remove `payment.cleared.v1`

Ordering matters and is enforced by the task order:

1. Accounting's `payment.events.v1` listener gains a `PaymentSettledV1` handler
   that materializes the `ReceivablePayment` — reusing
   `PaymentApplicationServiceImpl`'s existing creation logic and its
   `existsBySourceEventId` idempotency, not a reimplementation.
2. Tests prove the new path produces the same receivable the legacy path did.
3. Only then are `PaymentEventListenerConfig`, `PaymentClearedEvent`, the
   `payments-topic` config key, the README row, the docker-compose topic
   provisioning, and the legacy tests removed.

Pre-production policy applies: the legacy path is replaced, not shimmed.

### D5. `sender.outcomes.v1` contract test

A `PlatformSenderContractTest` pins `DeliveryOutcomeListener` against the
envelope tabulated in `docs/PLATFORM_SENDER_CONTRACT.md` §2 — the five event
types and the payload fields each carries — so the consumer cannot drift from the
published external contract silently. The document is extended to name the owner
and point at the test.

### D6. `catalog.commands.v1`

`pos-catalog` gains a `CatalogCommandListener` on `catalog.commands.v1` following
`LocationCommandListener`'s replay-only shape, dispatching to the existing
`ProductFactReplayService` / `ServiceFactReplayService` /
`SupplierArticleCodeReplayService`. `pos-inventory`'s `CatalogManifestListener`
then requests replay on drift like every other manifest listener, its inline
"no replay requested" rationale is removed, and the `catalog-commands-topic` key
is added to `pos-inventory`'s config.

### D7. Topic inventory guard

A test in `pos-archunit` scans production sources and configuration for every
`*.events.v1`, `*.commands.v1`, and `*.manifest.v1` topic, resolving
`${PROP:default}` placeholders, and builds the producer and consumer set per
topic. It fails when an internal topic has one side empty.

External topics are not silently ignored: they must appear in an explicit
allowlist carrying an owner and a contract-test reference, and the test asserts
the referenced test class exists. `sender.outcomes.v1` is the only such entry.

DLQ topics (`{topic}.dlq`) are excluded, matching the issue's out-of-scope list.

## Verification

```bash
./mvnw -pl pos-accounting,pos-invoice,pos-warranty,pos-people,pos-inventory,pos-catalog,pos-marketing,pos-shop-manager,pos-workorder -am test
./mvnw -pl pos-archunit -am -Dtest=DomainWallsTest,TopicInventoryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

`DomainWallsTest` must still pass **unchanged** — under F1 that is the expected
result, not a regression.
