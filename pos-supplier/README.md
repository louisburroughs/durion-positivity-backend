# pos-supplier

Supplier integration service: vendor connection configuration, the outbound transport that talks to
supplier APIs, and the exchange audit trail of what was sent and received.

| | |
| --- | --- |
| Eureka name | `SUPPLIER` |
| Base package | `com.positivity.supplier` |
| Database | `pos_supplier_db` |
| Compose port | `8096` → container `8080` |
| Governing ADRs | ADR-0050 (vendor profile configuration), ADR-0051 (protocol adapter versioning), ADR-0052 (outbound idempotency / duplicate-order prevention) |

This module owns **how** we reach a supplier, and — as the codec waves land — **what** we say to
them for each capability. Codecs exist today for PRICAT B4.0 (#1224) and Stock Report B2.1 (#1228) in
`internal.adapter.ediwheelb`, order create/status C1.0/C1.1 (#1226) in `internal.adapter.ediwheelc1`,
and live stock inquiry A2.5 (#1225) in `internal.adapter.ediwheela2`. The remaining capabilities still
have SPI ports in `internal.spi` and no codec behind them. ADR-0053 governs the price-catalog
behaviour described below.

**Shipment tracking is out of scope for this module**, and not as a gap awaiting a codec (#1313).
EDIWheel shipment tracking is an exchange between logistics providers and suppliers; a service
provider is not a party to it in either direction. That is why the LEX v1 document offers a single
`POST /shipment-tracking` — a write, for a carrier announcing a notice — and no read at all. There
is no `SHIPMENT_TRACKING` capability, port or service here, and V12 dropped the key from the
bindable set. Shipment milestones from some non-EDIWheel source later (a carrier API, a Michelin
S2S operation) would be a new capability with its own spec, not a revival of this one.

---

## API surface

The vendor master is under `/v1/supplier/vendors`; connection configuration is under `/v1/supplier/admin`.

### Vendor master (#2516, ADR-0070 Decision 2) — `supplier:vendor:read` / `supplier:vendor:write` / `supplier:vendor_remit:approve` / `supplier:fact:replay` / `supplier:vendor_tax_id:reveal`

One vendor for every party the shop buys from or pays, with or without a supplier connection. Every
connection profile belongs to exactly one vendor. No endpoint deletes a vendor.

| Method | Route | Permission | Codes |
| --- | --- | --- | --- |
| GET | `/v1/supplier/vendors?q=&status=&page=&size=` | `supplier:vendor:read` | 200 |
| GET | `/v1/supplier/vendors/{vendorId}` | `supplier:vendor:read` | 200, 404 `SUPPLIER_VENDOR_NOT_FOUND` |
| POST | `/v1/supplier/vendors` | `supplier:vendor:write` | 201, 400 `VALIDATION_ERROR`, 409 `SUPPLIER_VENDOR_NUMBER_TAKEN` |
| PUT | `/v1/supplier/vendors/{vendorId}` (carries `version`) | `supplier:vendor:write` | 200, 400, 404, 409 `CONFLICT` |
| POST | `…/{vendorId}/deactivation` · `…/reactivation` `{reason}` | `supplier:vendor:write` | 200, 400, 404, 409 `CONFLICT` |
| POST | `…/{vendorId}/remit-to-changes` `{remitTo, reason}` | `supplier:vendor:write` | 201, 400, 404, 409 `SUPPLIER_VENDOR_REMIT_CHANGE_PENDING` |
| GET | `…/{vendorId}/remit-to-changes?status=` | `supplier:vendor:read` | 200 |
| POST | `…/remit-to-changes/{changeId}/approval` `{verificationNote}` | `supplier:vendor_remit:approve` | 200, 400, 403 `SUPPLIER_VENDOR_REMIT_SELF_APPROVAL`, 409 `SUPPLIER_VENDOR_REMIT_CHANGE_NOT_PENDING` |
| POST | `…/remit-to-changes/{changeId}/rejection` `{note}` | `supplier:vendor_remit:approve` | 200, 400, 409 `SUPPLIER_VENDOR_REMIT_CHANGE_NOT_PENDING` |
| POST | `/v1/supplier/vendors/facts/replay?afterVendorId=&limit=` | `supplier:fact:replay` | 200 (`emitted`, `nextAfterVendorId`, `complete`) |
| POST | `…/{vendorId}/tax-registrations/{registrationId}/reveal` `{reason}` | `supplier:vendor_tax_id:reveal` | 200 (`Cache-Control: no-store`), 400 `JUSTIFICATION_REQUIRED` / `VALIDATION_ERROR`, 404 `SUPPLIER_VENDOR_NOT_FOUND` / `SUPPLIER_VENDOR_TAX_REGISTRATION_NOT_FOUND`, 500 `SUPPLIER_VENDOR_TAX_ID_UNREADABLE` |
| GET | `…/{vendorId}/tax-id-reveals?page=&size=` | `supplier:audit:read` | 200 (newest first, default size 20, max 200), 404 |

- **`vendorNumber`** is optional on create: give one (`^[A-Z0-9][A-Z0-9-]{0,29}$`, unique in the tenant)
  or get `V-000001`, `V-000002`, … from a per-tenant counter. It never changes afterwards: YAML profiles
  bind to it and people quote it (ADR-0064). `vendorId` is never display text.
- **Remit-to changes need a second person.** A remit-to given at creation is version 1 without approval.
  Every later remit-to — including a first one on a vendor created without — is a `PENDING` change
  request (reason ≥ 10 characters), at most one per vendor. Only a holder of
  `supplier:vendor_remit:approve` **who is not the requester** can approve it (verification note ≥ 10
  characters); that applies it and raises `remitToVersion` by one. Rejection (note ≥ 10 characters)
  changes nothing. A pending change is never on the vendor and never published.
- **Status** is `ACTIVE ⇄ INACTIVE` with a reason. Deactivation does not disable the vendor's profiles:
  its EDI documents still arrive, and accounting records them as exceptions.
- **No bank details** — not on the record, not on Kafka (OI-14).
- **Tax registrations are RESTRICTED, encrypted and masked (#2621, Security ruling on #2617).** Every
  number, whatever its scheme, is sealed with AES-256-GCM under `SUPPLIER_VENDOR_TAXID_ENC_KEY` (its own
  key, never the exchange-audit one; same envelope and fail-closed key policy, see "Encryption" below),
  with the tenant, vendor and registration ids bound as AAD so a ciphertext copied into another row fails.
  Each stored element is `{registrationId, scheme, region, last4, numberCiphertext}`. Every read, and every
  create, update, status and remit-to response, returns `taxRegistrations[]` as
  `{registrationId, scheme, region, last4}` and never decrypts. `last4` is the last four alphanumerics
  once separators are removed, `null` under 8 (shown as "on file").
  - **Update rule.** Send a stored registration's `registrationId` without `number` to keep it (its
    `scheme` and `region` must be unchanged, or 400 `VALIDATION_ERROR` with
    `fieldErrors[taxRegistrations[i].number]` "re-enter the number to change its scheme or region"); with
    `number` to replace its number; and a new registration without `registrationId` and with its `number`
    (1–64 characters). An id the vendor does not hold is 400; a stored registration left out is removed. A
    PUT that keeps every registration unchanged publishes nothing. No message or log echoes a number.
  - **Shapes (ADR-0072 Decision 2).** `scheme` and `region` are trimmed and upper-cased (`gst_hst` → `GST_HST`,
    `qc` → `QC`). On every entry that carries a `number`, `scheme` must match `^[A-Z][A-Z _/-]{0,15}$` and
    `region` `^[A-Z]{2}(-[A-Z]{1,3})?$` — no digits, so neither can hold part of a number — or 400
    `VALIDATION_ERROR` on `taxRegistrations[i].scheme` / `.region`, never echoing the value. `V4` and `V5`
    refuse to run (counts only) if a stored or queued registration breaks them.
  - **Reveal.** Only `supplier:vendor_tax_id:reveal` (ADMIN, CONTROLLER) sees a number, with a reason of
    10–500 characters. The `supplier_vendor_tax_id_reveal` audit row (actor, roles, reason, correlation id,
    outcome `REVEALED` | `UNREADABLE` | `REASON_REJECTED`; never the number or `last4`) is written in the same
    transaction before the number is returned: if it cannot be written, nothing is revealed. A reason that
    contains the number itself (separators and case ignored) is 400 `VALIDATION_ERROR`, reveals nothing, and
    records `REASON_REJECTED` with a null reason; the reason is never logged. An `UNREADABLE` row keeps no
    reason either (it could not be checked against a number that could not be read); a CHECK constraint holds
    `reason` NULL exactly on those two outcomes. Every outcome's row commits: the service returns the outcome and
    only after commit does the controller answer 200, 400 or 500 (ADR-0072 Decision 4); a number is released
    only after commit, and a failed insert or commit releases nothing.
  - **A supplied number is always a change** (ADR-0072 Decision 4, CHK-011): re-sent under the same id, scheme
    and region it is re-sealed, its `last4` recomputed and the masked fact published. "Unchanged" is decided on
    ids and stored attributes only, never on ciphertext or `last4`. The rows are read through `supplier:audit:read`, so a
    controller's reveals are reviewed by someone else. A 403 or 404 writes nothing.
  - **Never an agent tool** (ADR-0072 Decision 4, CHK-010). pos-mcp-server's discovery drops, in code and on
    every method, any operation whose path ends in `/reveal` or whose `x-required-permissions` holds a
    `…:reveal` permission. No configuration re-includes it: an agent cannot reveal a number even for a user who
    holds the permission; the person uses the reveal dialog. The audit read (metadata only) stays a tool.
- **`supplier.vendor.updated` schema version 2** (`SupplierVendorUpdatedV1`) on `supplier.events.v1`, key
  `vendorId`, `aggregateVersion` = the vendor's `@Version`: queued through the outbox in the transaction of
  every create, update, status change and remit-to approval. Version 2 (#2621) carries tax registrations as
  `{scheme, region, last4}` only; version 1 carried the number, and `V5` rewrote every queued vendor fact
  in `supplier_event_outbox` to version 2 so no replay can publish one again. Consumers apply only version
  2 or later. It carries every vendor field plus
  `remitToChangedAt`, `remitToRequestedBy`, `remitToApprovedBy` (security-context principal names),
  `createdBy`, `createdAt`, `occurredAt`. Consumers apply it under `ReplicaVersionGuard`: skip only when they hold a newer version; an equal version (a replay) re-applies. A no-op update publishes nothing.
- **Replay (ADR-0044 §4).** `POST /v1/supplier/vendors/facts/replay` re-emits one page (limit clamped to
  1–1000, default 200) of the caller's tenant's vendors at their current version. The
  `supplier.outbox.replay-requested` command on `supplier.commands.v1`
  (`{"commandType":"supplier.outbox.replay-requested","payload":{"since":…,"until":…}}`, tenant from the
  record header) re-queues that tenant's published `supplier.events.v1` rows of the window with their
  original event ids; a window older than `pos.supplier.outbox.replay.max-lookback` (default `P30D`) is
  logged and dropped, and an inverted or empty window is logged at error and dropped.
- **Not yet:** the per-tenant reconciliation manifest on `supplier.manifest.v1`. `TopicInventoryTest`
  refuses a `*.manifest.v1` topic without a production consumer, so the manifest publisher ships with
  S24's first `supplier.manifest.v1` listener (durion-positivity-backend#2517).

### Vendor profile administration — `supplier:profile:read` / `supplier:profile:write`

Every profile names its vendor: `vendorId` is required on create and update (a vendor of the caller's
tenant — **422 `SUPPLIER_VENDOR_NOT_FOUND`** otherwise — and `ACTIVE` on create, **422
`SUPPLIER_VENDOR_INACTIVE`**); an ADMIN profile may be re-pointed to another **active** vendor (422 `SUPPLIER_VENDOR_INACTIVE`
otherwise), and stays editable when its own vendor goes inactive. The view carries
`vendorId`, `vendorNumber` and `vendorDisplayName`; `GET …/profiles?vendorId=` lists one vendor's
profiles.

| Route | Operations |
| --- | --- |
| `/v1/supplier/admin/profiles` | `listProfiles`, `createProfile`, `getProfile`, `updateProfile`, `deleteProfile` |
| `…/profiles/{vendorProfileId}/auth-configs` | `listAuthConfigs`, `createAuthConfig`, `updateAuthConfig`, `deleteAuthConfig` |
| `…/profiles/{vendorProfileId}/accounts` | `listAccounts`, `createAccount`, `updateAccount`, `deleteAccount` |
| `…/profiles/{vendorProfileId}/bindings` | `listBindings`, `createBinding`, `updateBinding`, `deleteBinding` |

### Exchange audit — `supplier:audit:read` only

Deliberately tighter than profile admin: these endpoints return commercial documents. Holding
`supplier:profile:read` **and** `supplier:profile:write` does not grant access, and a test asserts that.

| Route | Operation | Notes |
| --- | --- | --- |
| `GET …/audit/exchanges` | `listExchanges` | Metadata only, half-open `[from, to)` window, `size` ≤ 200 |
| `GET …/audit/exchanges/by-correlation/{correlationId}` | `traceCorrelation` | One logical call including its retries, oldest first |
| `GET …/audit/exchanges/{exchangeAuditId}` | `getExchange` | Metadata for one attempt |
| `GET …/audit/exchanges/{exchangeAuditId}/payload` | `readPayload` | **Decrypts stored content. Writes an access record.** |
| `GET …/audit/exchanges/{exchangeAuditId}/accesses` | `listAccesses` | Who read this exchange's payload, and when |

Metadata reads never touch the payload columns — the listing query is a JPQL constructor expression
that does not name them, so a row whose payload cannot be decrypted still lists. That is deliberate:
the row an investigation needs must not be the row that breaks the listing.

`readPayload` records the access **in the same transaction as the read**, with no catch. If the access
record cannot be written the read fails and returns nothing. ADR-0050 §7 makes the record a
precondition of access, not a side effect of it — see [Three failure semantics](#three-failure-semantics).

### Price catalog (PRICAT) — `supplier:pricecatalog:read` / `supplier:pricecatalog:import`

Two authorities, because triggering a run is not the same act as reading its results: a trigger calls a
trading partner and publishes an import's worth of events.

| Route | Operation | Notes |
| --- | --- | --- |
| `POST …/price-catalog/{vendorProfileId}/imports` | `triggerSupplierPriceCatalogImport` | Synchronous. Returns a terminal summary whose status may be `COMPLETED`, `EMPTY` or `FAILED` |
| `GET …/price-catalog/{vendorProfileId}/imports` | `listSupplierPriceCatalogImports` | Run history including failures, newest first, `size` ≤ 200 |
| `GET …/price-catalog/{vendorProfileId}/unmatched-lines` | `listSupplierPriceCatalogUnmatchedLines` | The open quarantine worklist, newest first |

**No prices are served here.** Vendor price rows leave this module only as
`supplier.pricecatalog.updated` events on `supplier.events.v1`, which is what makes ADR-0053 §4 —
supplier cost participates in no sell-price resolution — structural rather than a rule to remember.

A failed or empty fetch never destroys data. Staging is append-only, no path deletes or supersedes
prior entries, and a failed exchange records a `FAILED` import row and stops: a vendor outage is not a
statement that the catalog is empty.

Every line the vendor sent is accounted for. A line becomes either a staged entry with a matched
product or a quarantine row with a reason, and the writer asserts
`matched + unmatched + duplicate == fetched` before marking an import complete, so a silent drop fails
the import rather than under-reporting it.

Matching is deterministic and exact (ADR-0053 §5): EAN against a catalog product code of type EAN,
then the line's `xReferenceCode` against type UPC. `supplierCode` is stored as a display alias and is
never a match key.

Matching reads the local `ext_product_code` replica, not pos-catalog. ADR-0044 R1 forbids
domain-to-domain synchronous calls and R3 makes replicas the read path, so pos-catalog publishes
product identity codes on `catalog.events.v1` and `CatalogProductEventsListener` maintains the copy
(`processed_events` idempotency, stale guard on `aggregateVersion`). The trade is staleness: a product
created seconds ago may not be matchable yet, and its line is quarantined until the next import. An
empty replica — a broker-less `dev`/`test`/`pg` profile, or nothing consumed yet — reports `CATALOG_UNAVAILABLE` rather than
turning a whole vendor catalog into `NO_CATALOG_MATCH` misses an operator would go hunting for.

**Freshness and run metadata (#1637 decisions 3-5).** `GET …/price-catalog/{vendorProfileId}/freshness`
(`getSupplierPriceCatalogFreshness`) answers "can these vendor prices be trusted today" in one read:
`latestEffectiveDate` — the newest catalog document date the vendor itself stated — is kept apart from
`lastFetchedAt`/`lastCompletedAt`, this platform's own retrieval record, alongside the open
unmatched-line count and the stale verdict computed against `pos.supplier.pricat.staleness-threshold`
(default `P7D`, ISO-8601 duration). A profile with no completed import reports `stale=true` with null
timestamps, not an error.

V19 (`supplier_pricat_import`: `binding_id`, `window_from`/`window_to`, `checkpoint_state`/
`checkpoint_at`, `error_code`) adds real run metadata rather than aliasing existing columns:
`binding_id` distinguishes a profile's multiple feeds (nullable forward-only — runs recorded before
this migration carry none); window/checkpoint are populated only for an incremental retrieval
protocol — every PRICAT protocol in service today is full-snapshot (B4.0 returns the whole catalog),
so all four stay null; `error_code` (`FETCH_FAILED` or `DECODE_FAILED`, CHECK-constrained) is a stable
machine-readable failure category alongside the free-text `failure_detail`.

`listSupplierPriceCatalogImports` gained `bindingId`, `status`, and a half-open `dateFrom`/`dateTo`
window on `fetchedAt`. `listSupplierPriceCatalogUnmatchedLines` gained `reason`, `search`
(case-insensitive contains-match over EAN, vendor article code and cross-reference code, LIKE
metacharacters literal), the same `dateFrom`/`dateTo` window, and `resolved` (default `false` — the
open worklist; `true` lists closed lines instead, for auditing what a catalog fix healed).

### Product-keyed availability fan-out (#1637 decision 1) — `supplier:stockavailability:read`

`GET /v1/supplier/stock/availability` (`getSupplierStockAvailability`) resolves one catalog product —
by exactly one of `productId` or `sku` — to its vendor-queryable identity from the local replica and
asks every enabled `STOCK_INQUIRY` binding concurrently, on virtual threads
(`StockAvailabilityFanoutConfig`), under a deadline: `pos.supplier.stockinquiry.fanout-deadline`
(default `PT10S`). A vendor that has not answered by the deadline is reported `SUPPLIER_UNAVAILABLE`
alongside the vendors that did, so the response is a 200 with a per-vendor status even when nobody
answered in time — an empty or all-unavailable `vendors` list is a valid answer, not an error.

Each vendor result carries **two clocks**: `fetchedAt` is when this platform obtained the answer (a
cached answer keeps its original fetch instant), `asOf` is the vendor's own stated observation time.
`stale` is judged from `asOf` against `pos.supplier.availability.staleness-threshold` (default
`PT15M`), echoed on the response as `stalenessThreshold` so every client applies the same freshness
rule.

`availableQuantity` is the canonical A2.5 item/piece count — the same quantity unit the per-vendor
stock inquiry uses; no unit of measure or warehouse name travels because neither exists in the
supplier wire data. **No EAN, UPC or vendor article code appears in the request or response** — which
codes a product resolved to is this module's implementation detail, kept internal so the frontend
cannot orchestrate per-vendor inquiries itself.

`sku` resolves entirely from the local replica, never a synchronous call to pos-catalog (ADR-0044
R1/R3): V20 adds `ext_product_code.sku`, populated from the `catalog.product.updated` fact pos-catalog
already publishes, matched case-insensitively over a non-unique index. A SKU that ambiguously names
more than one replicated product is a 409, refused rather than guessed at.

### Live stock inquiry (A2.5) — `supplier:stock:inquire`

| Route | Operation | Notes |
| --- | --- | --- |
| `POST …/stock/inquiries` | `inquireSupplierStock` | Always 200 when the request is well formed. Vendor failure is a status, not an error status |

Its own permission rather than a reuse of the price-catalogue ones: reading a vendor's already-staged
prices and calling that vendor on every customer page view are different acts with different costs, and
a deployment must be able to have the first without the second.

This is the platform's **single approved synchronous cross-module supplier read** (ADR-0044 amendment,
2026-08-10). Approved callers are pos-catalog's Product Detail composition and pos-order procurement,
and the grant is per calling class, not per module — `DomainWallsTest` allowlists the one client file
by name, so a second client in the same module reaching for pos-supplier still fails the build.

It **never throws for a vendor-side failure**. Both callers have something useful to render without
live stock and nothing useful to render if this call blows up, so every failure is a status:

| Status | Means |
|---|---|
| `OK` | The vendor answered. Per-article outcomes are on the lines. |
| `SUPPLIER_UNAVAILABLE` | Unreachable, timed out, breaker open, or answered something unreadable. |
| `NOT_LISTED` | The vendor carries none of the inquired articles. |
| `CAPABILITY_NOT_CONFIGURED` | No `STOCK_INQUIRY` binding on the profile (ADR-0050 §3). |
| `CONFIGURATION_ERROR` | The profile is switched on and wrong — an unmapped delivery location, a missing agency code. Raised **before** any network call. |

Per line: `AVAILABLE`, `UNAVAILABLE`, `NOT_LISTED`, `NOT_ANSWERED`. The distinction that matters is
`UNAVAILABLE` (the vendor said it has none — quantity `0`, a fact) versus `NOT_ANSWERED` (the vendor
said nothing — quantity `null`). Only the first justifies telling a customer an article is out of
stock, and nothing in this path coerces one into the other.

**A2.5 carries no price.** The norm answers availability and delivery dates only; the sibling C1.0
inquiry is the one with `PriceDetails`. The quote fields on the response stay null here, and a
supplier price comes from the PRICAT price entries that own it (CAP-318).

**The delivery location is part of the question, not a refinement of it.** Availability is
consignee-specific, so the inquiry requires a location, the codec refuses to encode without the
vendor account mapped to it, and the cache key carries it — a shared entry would tell a customer that
stock at another branch is available at the one fitting the tyre.

Caching is per article, not per inquiry (`pos.supplier.stockinquiry.cache-ttl`, default 60s): a
product page asks about one article repeatedly and a procurement screen asks about several at once.
Only `AVAILABLE` and `UNAVAILABLE` are stored. Failures are not — caching one bad moment would extend
it into a minute of identical failures — and neither is `NOT_LISTED`, so an operator who fixes a
catalogue mapping sees the fix on the next page load.

### Stock report (B2.1) — no API surface

A scheduled snapshot feed with no endpoints: pos-supplier fetches the vendor's country-level stock
report on the binding's cron and publishes it as chunked `supplier.stockreport.updated` events for
pos-inventory to hold as **availability hints**. Hints are not owned stock and must never enter
valuation or on-hand ATP (ADR-0048).

Three states, kept distinct end to end, because collapsing them is how a vendor's silence becomes a
false out-of-stock:

| Vendor said | Stored / published as | Means |
| --- | --- | --- |
| `"quantityValue": "0"` | `0` | The vendor reports it has none |
| `"quantityValue": ""` or absent | `null` | The vendor listed the article without stating a quantity |
| article not in the document | no line at all | The vendor did not mention it |

The snapshot carries **two timestamps**: `snapshotAsOf` is the vendor's own statement of when the
snapshot was taken, `fetchedAt` is when we asked. A report fetched at noon may describe stock as of
06:00, and staleness is judged against the vendor's figure.

Snapshots are append-only. A failed or undecodable fetch records a `FAILED` snapshot and stops, so
the previous snapshot stays the last thing the vendor actually said — a vendor outage is not a
statement that a warehouse is empty.

Four terminal statuses, and the distinction between the middle two is the point: `COMPLETED` (at
least one usable line), `EMPTY` (the vendor sent no lines), `REJECTED` (the vendor sent lines and
none of them decoded — a codec or vendor-format break, not a quiet warehouse), `FAILED` (no usable
answer at all).

### Stock snapshot reads (CAP-322, #1638 decision 5) — `supplier:stocksnapshot:read`

Two-step browse, by immutable `snapshotId`, never contacting a vendor. `GET
…/vendor-profiles/{vendorProfileId}/stock-snapshots/latest` (`getLatestSupplierStockSnapshot`)
resolves the profile's newest snapshot — newest by the vendor-stated `snapshotAsOf`, never by fetch
time — to its metadata, without lines. The ordering is `NULLS LAST` by explicit query rather than a
derived method: PostgreSQL sorts nulls **first** on `DESC`, which would let a snapshot carrying no
vendor-stated instant (a failed or unparseable fetch) outrank one that has one; the snapshot id
tie-breaks rows sharing an instant, newest fetch first.

`GET …/stock-snapshots/{snapshotId}/lines` (`listSupplierStockSnapshotLines`) pages that snapshot's
lines, addressed by the id resolved above rather than "latest" — a snapshot is append-only and never
changes, so every page of one browse describes the same document even if a newer report lands
mid-browse, where paging "latest" directly would silently switch documents between pages. A line's
`availableQuantity` is nullable and the nullability is the contract: null means the vendor reported
the article without stating a quantity, zero means it explicitly reported none — the same distinction
the fetch pipeline preserves end to end (see above).

### Transmission search (ADR-0052, issue #1638 decision 6) — `supplier:transmission:read`

`GET /v1/supplier/transmissions` (`searchSupplierTransmissions`) pages the transmission ledger across
every purchase order, newest first, filterable by `attemptState`, `vendorProfileId`, `search`
(case-insensitive contains-match against the purchase-order number and the vendor's own order number)
and a half-open `dateFrom`/`dateTo` window on the intent's immutable `createdAt`.
`attemptState=MANUAL_REVIEW` is the operator worklist this filter exists for: transmissions whose
outcome could not be established automatically and are waiting on a human. It sits alongside
`listSupplierTransmissionsForPurchaseOrder` (one order's history) and `getSupplierTransmission` (one
intent by id) on the same controller.

Read-only: nothing is transmitted, retried or changed by this endpoint. Resolving a `MANUAL_REVIEW` row
found here is `resolveSupplierTransmission` (`supplier:transmission:resolve`), and **no endpoint
re-sends a transmission** — ADR-0052 treats a blind re-send as how a purchase order becomes two
deliveries, so recovery from `MANUAL_REVIEW` is always a person's resolution, never a retry.

### Quarantine re-application

`POST …/price-catalog/{vendorProfileId}/quarantine/reapply` re-matches the profile's open quarantine
against the current product-code replica and applies whatever now resolves — **with no vendor call**
(ADR-0053 §5). An hourly sweep does the same for every enabled profile; the cadence is its own,
because what makes a line matchable is a change in the catalog, not the vendor's next fetch.

Skipped on purpose: `NO_IDENTIFIER` and `MALFORMED_LINE`. No catalog fix rescues a line that carried
nothing to match on, so retrying them would keep the worklist permanently non-empty.

A re-application creates **its own manifest** referencing the import it healed
(`reapplied_from_import_id`), rather than editing the original's counters. Those counters record
what the vendor sent and how much matched *at the time*, and a fourth chunk of a three-chunk import
is not something a consumer's completeness check can accept.

### Re-publication on consumer request

pos-catalog publishes `supplier.pricecatalog.republish.requested` on `supplier.commands.v1` when it
applied fewer chunks than an import's completion event declared. This module answers it by re-emitting
that import's chunk events, followed by its completion event, from the staged lines (ADR-0044 §4).

The consumer cannot fetch what it missed — ADR-0044 R1 forbids the synchronous read — so recovery is
a request to the owner and a re-publication down the same path the original import took. The request
names the **import**, not the missing chunks, because a consumer only knows how many it is short. So
the whole import is re-emitted: a chunk the consumer already applied is skipped on its applied-chunk
log, whereas re-emitting too little would leave the gap that prompted the request.

Two things make this safe to run against a live topic:

- **`supplier_pricat_entry.chunk_sequence`** is recorded at staging, so a re-emit reproduces the
  original chunk boundaries exactly. The consumer deduplicates on `(importManifestId, chunkSequence)`
  — a re-emit carries new event ids, so its ordinary event-id guard cannot fire — and a boundary that
  moved would make it skip a sequence it had already applied, losing the very lines being re-sent.
- **A cooldown and an attempt cap** (`pos.supplier.pricat.republish-cooldown`,
  `…-republish-max-attempts`). Serving a request does not guarantee recovery; a consumer that stays
  short asks again on its next completion event. The cooldown collapses a burst into one re-emit; the
  cap stops a genuinely broken consumer and logs at error, leaving an operator a visible stuck import
  rather than a broker quietly drowning in re-published catalogues.

Refused, with the reason logged: an import that was never staged here, a request naming a profile
that does not own the import, and a `FAILED` import — which staged no lines, so only the vendor's
next fetch can help.

`supplier.commands.v1` has exactly **one** consumer group in this module
(`internal.command.service.SupplierCommandListener`), which dispatches by event type. `processed_events`
is keyed by event id alone and every consumer records every event it sees, so a second group on this
topic would record ids the first group still had to act on — silently dropping purchase orders or
recoveries depending on which group won the race.

### Order commands for an unusable vendor — `supplier.order.notdispatched` (#2492)

A `supplier.order.requested` command naming a vendor alias with no profile, or a disabled one, mints no
transmission intent. `SupplierCommandListener` answers it with `supplier.order.notdispatched` v1 on
`supplier.events.v1` (`SupplierOrderNotDispatchedV1`, reason `SUPPLIER_NOT_CONFIGURED`), written to the
outbox in the **same transaction** as the command's `processed_events` mark, so a command is never
recorded as consumed without pos-order being told. `vendorProfileId` is null when no profile exists and set
when the profile is disabled; `detail` says which. The Kafka key and aggregate id are the purchase order id,
the aggregate version is the requested revision, and `commandEventId` names the command answered.

This is deliberately not `supplier.order.rejected`: no intent was minted, so there is no
`transmissionIntentId` or document id and nothing the vendor refused. A never-dispatched PO gets **no**
`supplier.orderstatus.changed` events, because there is no intent to poll. Nothing here re-sends;
pos-order allows a manual re-send once the profile is configured or re-enabled. Deploy pos-order (which
understands the event) before this producer.

### MKCAT re-publication on request (#2356)

`supplier.catalog.republish.requested` on `supplier.commands.v1` (payload
`SupplierCatalogRepublishRequestedV1`: `vendorProfileId`, `requestedBy`, optional `reason`) asks this
module to re-emit **every** MKCAT tread design variant it has staged for one vendor profile. It is the
ADR-0044 §4 administrative re-emit-all for marketing enrichment, the counterpart of the PRICAT
re-publication above, and it exists because nothing else can bring a lost enrichment back:

- a replay of the original `supplier.catalog.updated` event carries the event id the consumer already
  recorded, so its `processed_events` guard skips it again;
- an ordinary import publishes a variant only when its `contentHash` changed
  (`MktCatVariantStager.stageAndPublish`), so a vendor that keeps sending the same content never
  causes a second event;
- the consumer cannot read the staged rows — ADR-0044 R1 forbids the synchronous call.

`MktCatRepublisher` answers the command by reading `supplier_mktcat_variant` for the profile a page
at a time (`pos.supplier.mktcat.republish-page-size`, default 200) and queueing each row through the
outbox as a `supplier.catalog.updated` event with a **new `eventId`**, the row's own id as the record
key, and the payload the original publication carried — stored `contentHash`, stored texts and
images, and `occurredAt` read back from `last_published_at`, with no vendor call. The payload is the
same to the field because `MktCatVariantStager` cuts the fetch instant to microseconds, the precision
the `timestamp(6)` column keeps, before it both stores and publishes it; only the envelope differs
(`eventId`, `occurredAtUtc`).

The whole run is **one transaction**, shared with the command's `processed_events` mark, so it is all
or nothing: a failure on any page rolls back every event queued before it and leaves the command
unmarked. Paging bounds the heap, not the unit of work — after each page the outbox rows are flushed
and the persistence context is cleared, so memory holds one page of variants and events rather than
the catalogue, while the flushed rows stay uncommitted until the end. A very large catalogue is
therefore still one long transaction on the command-listener thread. The run ends with one `supplier.catalog.republish.completed` event
(`SupplierCatalogRepublishCompletedV1`: `vendorProfileId`, `supplierRef`, `variantCount`,
`requestedBy`, `completedAt`), keyed on the vendor profile, which is what lets the consumer compare
its own count against this module's.

What it deliberately does not do:

- **It does not touch the hash guard.** Nothing is written back to the staged rows, so the import
  after a re-publication still sees the hash it stored and still publishes nothing for an unchanged
  variant.
- **It has no cooldown and no attempt cap**, unlike the PRICAT path. No consumer requests it
  automatically, so there is no loop to bound. A redelivery of the same command stops at the
  listener's event-id guard (the outbox rows and the `processed_events` mark commit together); a
  *second* command re-emits everything again. That is acceptable only because the consumer is
  idempotent on content: pos-catalog treats an unchanged `contentHash` for a design it already holds
  as a no-op.
- **It never emits a partial catalogue as if it were whole.** A staged row whose stored JSON cannot be
  read back fails the whole run (`IllegalStateException`, rethrown by the listener as this module's
  own inconsistent state). A profile with nothing staged in the command's tenant emits nothing, not
  even a completion with a count of zero, and logs at error.

The tenant is the command's: the record's `tenantId` header (else the transitional default tenant)
binds it, the staged rows read are that tenant's, and the outbox stamps the same tenant on every
re-emitted event.

**Sending it.** There is no endpoint; like the PRICAT request it is a command on the topic. One
command per vendor profile that has a `MARKETING_CATALOG` binding (`GET /v1/supplier/admin/profiles`
lists them; `GET /v1/supplier/mktcat/{supplierRef}/variants` shows what is staged):

```bash
# supplier.commands.v1 — eventId must be a fresh UUID per command; a reused one is skipped as a repeat
docker exec -i kafka-positivity /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic supplier.commands.v1 <<'EOF'
{"eventId":"<fresh uuid>","eventType":"supplier.catalog.republish.requested","schemaVersion":1,"aggregateId":"<vendorProfileId>","aggregateVersion":0,"occurredAtUtc":"<now, ISO-8601 UTC>","sourceService":"pos-catalog","payload":{"vendorProfileId":"<vendorProfileId>","requestedBy":"operator","reason":"#2356 recover lost MKCAT enrichments"}}
EOF
```

Sent like this the record has no `tenantId` header and is processed as the default tenant
(`pos.tenancy.default-tenant-id`), which is the alpha tenant. For any other tenant the header is
required: add `--property parse.headers=true` and prefix the line with `tenantId:<tenant uuid>` and a
tab.

Expected on this side: a WARN `Re-published N MKCAT variants of vendor profile …` and `N + 1` new
`supplier_event_outbox` rows. The consumer side, and how to read the result, is in
`pos-catalog/README.md` → "Recovering lost enrichments".

### Gateway routing

`Path=/supplier/**` with `StripPrefix=1`, plus the gateway's global `ApiVersionHeaderToPathFilter`:

```
GET /supplier/supplier/admin/profiles   + X-API-Version: 1   → service GET /v1/supplier/admin/profiles
GET /supplier/v1/supplier/admin/profiles                     → service GET /v1/supplier/admin/profiles
```

The first `/supplier` routes to the service and is stripped; the second is the API's own domain
segment. The doubled segment is the fleet convention (compare `/customer/v1/customers`,
`/warranty/v1/warranty/...`), not a mistake.

---

## Two sources of truth

A profile is either `YAML`-managed or `ADMIN`-managed, recorded on the row.

- **`YAML`** — declared in configuration and reconciled into the database at startup. Configuration
  wins: an edit to the YAML is applied on the next boot. Mutating one of these through the admin API
  is rejected with **409 `SUPPLIER_PROFILE_YAML_MANAGED`**.
- **`ADMIN`** — created through the admin API and owned by it. Nothing reconciles these.

Use YAML for suppliers that belong to the deployment (reproducible, reviewable, in git). Use the admin
API for suppliers an operator onboards at runtime.

**Every YAML profile names its vendor by `vendorNumber`** (ADR-0050 amendment, #2516); a spec without
one fails startup. The number is resolved in each tenant's own vendor master. Where a tenant does not
have it, the profile is not created there (or an existing one is disabled and keeps its vendor), a WARN
names the profile and tenant, `supplier.yaml.profile.vendor_unresolved{profile,tenant}` is counted, and
the next startup tries again — other tenants and startup are unaffected. YAML never creates a vendor.

### Full YAML example

Prefix is `supplier`, so this sits at the root of any profile-specific config file:

```yaml
supplier:
  profiles:
    - key: MICHELIN                       # supplierRef: the human-readable alias used everywhere
      displayName: Michelin France
      vendorNumber: MICHELIN              # required: the vendor of each tenant's vendor master this binds to
      enabled: true
      protocolDefaults:
        family: MICHELIN_S2S
        connectTimeoutMs: 5000
        readTimeoutMs: 30000
        retry:
          maxAttempts: 3
          backoff: EXPONENTIAL            # FIXED | EXPONENTIAL
      accounts:
        billing:
          accountNumber: "0092331"
          agencyCode: "FR01"
        delivery:
          - locationId: 0192f3c4-5b6a-7c8d-9e0f-1a2b3c4d5e6f   # pos-location UUID
            accountNumber: "0092331-01"
            agencyCode: "FR01"
        sellerPartyId: 0192f3c4-1111-7c8d-9e0f-1a2b3c4d5e6f
        sellerAgencyCode: "FR01"
      auth:
        - name: primary
          type: OAUTH2_CLIENT_CREDENTIALS
          tokenUrlRef: env:MICHELIN_TOKEN_URL
          clientIdRef: env:MICHELIN_CLIENT_ID
          clientSecretRef: env:MICHELIN_CLIENT_SECRET
        - name: legacy-stock
          type: BASIC_PLUS_APIKEY
          usernameRef: env:MICHELIN_EDI_USER
          passwordRef: env:MICHELIN_EDI_PASSWORD
          apiKeyHeader: apikey            # default when omitted
          apiKeyRef: env:MICHELIN_API_KEY
      bindings:
        - capability: STOCK_INQUIRY
          family: MICHELIN_S2S
          version: S2S_V1
          baseUrl: https://api.michelin.example/s2s
          path: /stock/inquiry
          auth: primary                   # references auth[].name
          enabled: true
          captureLevel: REDACTED
        - capability: PRICE_CATALOG
          family: MICHELIN_S2S
          version: S2S_V1
          baseUrl: https://api.michelin.example/s2s
          path: /pricat
          auth: primary
          schedule: "0 0 2 * * *"         # batch pull; coordinated by the scheduler lease
          captureLevel: METADATA_ONLY
      sandbox:
        enabled: false
```

**Every credential is a reference, never a value.** `AuthReferenceRules` rejects anything whose scheme
is not backed by a registered resolver — today `env:` only — at admin write time *and* at startup, so
`MYDOMAIN:hunter2` fails immediately rather than at first call. Adding a scheme (`vault:`, AWS) is a
resolver bean, not a config flag.

A `baseUrl` containing userinfo (`https://user:pass@host/…`) is rejected with
**400 `SUPPLIER_URL_CONTAINS_CREDENTIALS`** — that is a plaintext credential, and ADR-0050 §4 says those
never persist.

**Valid values.** Capabilities: `ORDER_CREATE`, `ORDER_STATUS`, `STOCK_INQUIRY`, `STOCK_REPORT`,
`PRICE_CATALOG`, `INVOICE_FETCH`, `WORKORDER_AUTHORIZATION`, `MARKETING_CATALOG`,
`TIRE_IDENTIFICATION`. Families: `EDIWHEEL_A25`, `EDIWHEEL_C1`, `EDIWHEEL_B`, `EDIWHEEL_JSON`,
`MICHELIN_S2S`. Auth types: `BASIC_PLUS_APIKEY`, `OAUTH2_CLIENT_CREDENTIALS`, `BEARER`. Versions are
free-form strings matched against the adapter registry — `A2_5`, `B2_1`, `B3_3`, `B4_0`, `C1_0`, `C1_1`,
`C1_2`, `S2S_V1` ship today, and **a version is not validated on write**, so a typo persists happily and
then resolves every call to `CAPABILITY_NOT_CONFIGURED`.

### Disabling versus deleting

`enabled: false` is the reversible control, at either level:

- a disabled **profile** resolves every capability to `SUPPLIER_PROFILE_DISABLED`;
- a disabled **binding** behaves as absent — `CAPABILITY_NOT_CONFIGURED`.

Both leave the configuration in place, so re-enabling restores it exactly. **Prefer this.**

`DELETE` is a hard cascade: the profile, its bindings, auth configs and commercial accounts are all
removed, and nothing is recoverable. Exchange-audit rows survive by design — they hold no foreign key
to the profile and snapshot both `vendorProfileId` and `supplierRef` — so deleting a supplier does not
erase the record of what was exchanged with it. That is the point: the trail of a *deleted* supplier is
exactly what a dispute needs.

---

## Exchange audit

Every attempt against a vendor produces one row, including failures and each retry. The writer is an
`ExchangeObserver`, so the transport never depends on a repository.

### Capture levels

Per binding, defaulting to `supplier.audit.default-capture-level` (`REDACTED`):

| Level | Stored |
| --- | --- |
| `FULL` | Request and response bodies as sent, encrypted |
| `REDACTED` | Bodies with credential-bearing fields replaced, encrypted |
| `METADATA_ONLY` | No bodies at all; URI query string also stripped |

`METADATA_ONLY` carrying a payload is impossible by schema, not merely by code — V3 declares
`chk_saudit_metadata_only_has_no_payload`. An unknown or missing binding falls back to `REDACTED`,
never `FULL`.

**Redaction is name-based and its field set is compiled in.** It matches XML elements and attributes,
JSON fields and form fields called things like `Password`, `ApiKey`, `client_secret`, `access_token`.
It therefore cannot redact a credential carried **positionally** — an EDIFACT `UNB` segment holds the
recipient password by position — and it does not know about per-binding data classification.
ADR-0050 §7 requires both; they are owed by CAP-318 alongside the codecs. Until then
`METADATA_ONLY` is the only level that guarantees a positional format retains nothing.

`endpoint_uri` is redacted before storage (sensitive query parameters and any userinfo) and has its
query string removed entirely at `METADATA_ONLY`. Redaction happens at capture time and is not
reversible — the original is never stored.

### Encryption

AES-256-GCM through a JPA `AttributeConverter`. Envelope: `0x01 || key-id || 12-byte nonce ||
ciphertext`, with the header bound as AAD so a rewritten key id fails authentication.

**The service will not start without a key unless the `dev` or `test` profile is active — and *every*
active profile must be one of those.** `prod,dev` requires a key. This is deliberately fail-closed:
starting without one mints an ephemeral per-JVM key, which silently makes every payload written
unreadable after the next restart, and the loss then surfaces as an authentication failure — i.e. as
suspected tampering — for data the deployment destroyed itself.

| Variable | Meaning |
| --- | --- |
| `SUPPLIER_AUDIT_ENC_KEY` | Active key, 32 bytes base64. **Provision before first deploy.** |
| `SUPPLIER_AUDIT_ENC_KEY_ID` | Key id recorded in each envelope (default `k1`) |
| `SUPPLIER_AUDIT_ENC_PREVIOUS_KEYS` | Decrypt-only keys, `keyId:base64` comma-separated |
| `SUPPLIER_VENDOR_TAXID_ENC_KEY` | Vendor tax-registration number key (#2621), 32 bytes base64. A **different** key, same rules: `deploy-backend.sh` and startup (`SupplierEncryptionKeySeparation`) both refuse one key for both purposes. **Provision before first deploy**, from the secret store. |
| `SUPPLIER_VENDOR_TAXID_ENC_KEY_ID` | Its key id (default `k1`) |
| `SUPPLIER_VENDOR_TAXID_ENC_PREVIOUS_KEYS` | Its decrypt-only keys; a retired key must stay while any number it sealed is stored |

The envelope and key policy are shared by both ciphers (`AesGcmEnvelopeCipher`); the vendor tax-id cipher
additionally binds `tenantId`, `vendorId` and `registrationId` into the AAD. `V4` (a Flyway **Java**
migration, a Spring bean with the cipher injected, because SQL cannot hold the key) encrypted every
number stored before #2621; it logs counts only.

#### Deploying V4–V6: stop-the-world only

- **V4 is a Java migration** (`internal.migration.VendorTaxRegistrationEncryptionMigration`, version `4`). Never
  add a `V4__*.sql`; `VendorTaxRegistrationMigrationHygieneTest` fails if anyone does.
- **Stop-the-world deploy only.** Stop pos-supplier, migrate, then start it: no old instance may write a clear
  number after V4 has run, and none may publish a v1 fact after V5.
- **Hard gate before the deploy: the counts** (ADR-0072 Decision 9). Run the read-only counts in PR #2624 through
  SSM as `pos_user`: registrations grouped by **validated** scheme (a misshapen scheme counts as `UNVALIDATED`, never
  printed), split by **verified fixture provenance** versus **unknown provenance**, vendors holding one, and the two
  shape-break counts. Counts only, never values. Unknown provenance counts as potentially real and stops the
  rollout as a data incident; any non-zero shape-break count stops the deploy.
- **Order and purge:** follow `docs/OPERATIONS_RUNBOOK.md`, "Withdrawing a RESTRICTED field in place": consumers
  first, stop every old writer, V4–V6, fixed per-partition cutoffs on `supplier.events.v1` and its DLQ, consumer
  progress, DLQ inventory and recovery, then `kafka-delete-records.sh` up to the cutoffs.
- **If V4 or V5 refuses** ("N stored vendor tax registration(s) …" or "N supplier.vendor.updated outbox
  registration(s) …"): nothing was written; the migration rolled back and the previous release keeps running.
  Never `SELECT` the offending values.
  - A stored registration: correct it through the vendor form on the running (pre-#2621) release, re-entering it
    with a conforming scheme and region.
  - A queued outbox row: it is a v1 copy of a fact and is never needed again. Delete exactly those rows with the
    shape-break predicate of the count (`DELETE FROM supplier_event_outbox o USING … WHERE <same predicate>`),
    never by listing them, and re-emit the vendor's facts (`POST /v1/supplier/vendors/facts/replay`) after the
    deploy.
  - Re-run the counts until both are zero, then deploy again.

To rotate: move the current key into `previous-keys`, set a new `key` and a new `key-id`. **A retired
key must stay in `previous-keys` for the whole retention window** — remove it and every payload it
sealed becomes permanently unreadable, reported as `SUPPLIER_AUDIT_PAYLOAD_UNKNOWN_KEY_ID`.

### Retention

`supplier.audit.retention` (default `P400D`) — thirteen months, covering an annual dispute cycle. The
purge nulls payload columns and stamps `payloads_purged_at`, so "purged" stays distinguishable from
"never captured". Metadata rows are kept permanently. It runs as a bulk `UPDATE`, so it never decrypts
a payload only to discard it and cannot be blocked by a row whose key rotated out.

---

## Outbound transport

`SupplierBaseClient` resolves a binding, applies credentials at call time, stamps `X-Correlation-Id`
(reusing an inbound one when present), classifies the outcome, and notifies observers on **every**
attempt.

### Retry safety (ADR-0052 §5)

Only `PRE_SEND_FAILURE` is retried. A post-send ambiguity is never retried automatically, whatever the
retry budget says — the failure mode is a duplicate purchase order.

| Outcome | Retried |
| --- | --- |
| `PRE_SEND_FAILURE` — connection refused, unknown host, connect timeout, breaker open, token-leg transport failure | yes |
| `POST_SEND_AMBIGUOUS` — read timeout, 5xx, TLS or conversion failure | no, unless the caller opted in via `asIdempotentRead()` |
| `DEFINITIVE_REJECTION` — 4xx | no |
| `CONFIGURATION_ERROR` | no |

Connect and read timeouts are distinguished **by exception type**, which is why this module uses
`JdkClientHttpRequestFactory` rather than the `SimpleClientHttpRequestFactory` used elsewhere in the
fleet: `HttpURLConnection` reports both as the same `SocketTimeoutException`, separable only by message
text, and getting that wrong in the unsafe direction duplicates orders. Both the business call and the
OAuth2 token leg share `SupplierHttpClients`.

Batch reads (`PRICAT`, stock report, invoice fetch) are idempotent by checkpointed window and may opt
into retrying an ambiguity. That is opt-in per request, never a default.

### Circuit breakers and health

One breaker per `(vendorProfileId, capability)`. Only transport failures count toward it — a 4xx
rejection or a configuration error is not a statement about vendor availability, and counting them
would relabel a permanent rejection as a transient, safe-to-redispatch one.

`SupplierClientHealthIndicator` **never reports DOWN.** Breaker states appear in `details` only.
A supplier being unreachable is the expected condition a breaker exists to handle; reporting it as
DOWN would fail `/actuator/health`, which the compose healthcheck reads, and one vendor's outage would
restart this service. Alert on the Micrometer breaker-state gauge instead.

### Scheduler lease

Scheduled batch pulls are coordinated through `supplier_schedule_lease`: an atomic compare-and-claim
`UPDATE` whose winner is decided by the database, never by a read-then-write. All lease times are
computed in SQL (`now() + interval`), never in the JVM, so the guarantee does not depend on pod clocks
agreeing. The checkpoint commits in the **same transaction** as its batch page, and losing the lease
rolls the page back with it — so a takeover reprocesses from the last committed checkpoint and work
happens exactly once.

### Three failure semantics

Three components in this module fail deliberately differently. They are not inconsistent — copying one
onto another is a silent bug:

| Component | On its own failure | Why |
| --- | --- | --- |
| `ExchangeAuditObserver` | swallows, logs ERROR | A failed audit write must not fail live vendor traffic |
| Scheduler checkpoint | rolls back with its page | A committed page with no checkpoint silently skips a window |
| `AuditAccessRecorder` | fails the read | Payload content must never be disclosed unrecorded |

---

## Multitenancy (ADR-0062, WS3 wave 7)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`, every scoped entity
extends `TenantScopedEntity`, and the two global tables listed in
`src/main/resources/db/tenancy-global-tables.txt` (`processed_events`, `supplier_event_outbox`) carry
`@TenantGlobal`. The request tenant is bound by `TenantContextFilter` from `X-Tenant-Id` (the gateway
injects it from the token's `tid`), the Kafka tenant by `TenantRecordInterceptor` from the `tenantId` record
header on all three consumers (catalog facts, workorder events, supplier commands), and every connection
checkout binds `app.current_tenant` for row-level security. `pos.tenancy.default-tenant-id` still binds the
alpha default tenant on every unbound path (tokens issued before `tid`, records without the header).

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, `FlywayConfig`).

The outbox row carries the producing tenant as data (`tenant_id`, stamped from the bound tenant by
`SupplierOutboxEventWriter`, added by `V2__supplier_event_outbox_tenant_id.sql`); `SupplierOutboxPublisher`
is the one platform-scoped job (it drains the global outbox and puts each row's `tenant_id` on the record
header). Every other scheduled sweep is per tenant through `TenantIterator.forEachActiveTenant`: the
MKCAT, PRICAT, stock-report and invoice schedulers, the MKCAT image retry, the quarantine re-application,
the three order-transmission polls, the two workorder authorization polls, and the exchange-audit purge
(which opens its transaction inside the binding). `SupplierYamlBootstrap` reconciles the YAML profiles into
every active tenant the same way. The stock-availability fan-out re-binds the request tenant on each
virtual-thread leg, since virtual threads do not inherit the binding. The schedule-lease UPDATE/COUNT
statements are native and carry `@TenantAudited`: they name no tenant because the sweeps that run them are
per tenant, so row-level security confines each to the bound tenant's leases.

Every persistence test in this module runs against real PostgreSQL in Testcontainers: the
`@SpringBootTest` ones on `PostgresTenancyTestBase`, the `@DataJpaTest` slices on
`PostgresSliceTestBase`, both sharing one container through `SupplierPostgresContainer`, the pool
connected as `pos_app` and the tenant bound by `TenantBindingTestExecutionListener`. The slices
therefore boot the same baseline the application meets, row-level security included; the hand-forked
H2 chain they used to boot (`db/h2-migration`) is gone.

Proof: `TenantIsolationIT` (tenant A's `ext_product_code` row is invisible to tenant B and to an unbound
connection, through the repository and through raw SQL) and `TenancySchemaConformanceIT` (every
non-whitelisted table has `tenant_id`, RLS enabled and forced, and the `tenant_isolation` policy; the pool is
`pos_app` with no bypass), both on Testcontainers Postgres (`./mvnw -pl pos-supplier -am verify`).

### Before enabling on an existing environment (#2463)

Kafka used to be off by default here (a per-module opt-in flag, retired); the rails are now always on outside the broker-less `dev`/`test`/`pg` profiles. On an environment that has run this module with them off (alpha did), audit before the first start of the new image:

1. **Vendor bindings.** In `pos_supplier_db`, list enabled vendor profiles that carry an order-transmission or workorder-authorization binding with resolvable credentials. This decides step 3 only: with no live binding, replayed commands and completions fail harmlessly against an unknown supplier; steps 2 and 4 apply either way.
2. **Unpublished outbox rows.** `SELECT count(*), min(created_at) FROM supplier_event_outbox WHERE published_at IS NULL;` Everything queued while the rails were off drains on start, whatever the bindings. A queued `SupplierInvoiceReceivedV1` reaches accounting AP, so read what is in there first.
3. **Consumer-group reset (only with a live binding).** Reset both groups to latest before the first start, so retained history is not replayed as new orders or sign-offs (`auto-offset-reset: earliest` applies to a group with no committed offset, which is every group here). On the alpha host, with pos-supplier not yet running:

   ```bash
   docker exec kafka-positivity /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
     --group pos-supplier-commands --topic supplier.commands.v1 --reset-offsets --to-latest --execute
   docker exec kafka-positivity /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
     --group pos-supplier-workorder-events --topic workorder.events.v1 --reset-offsets --to-latest --execute
   ```

   The reset fails while a group has active members; stop pos-supplier first if it is running.
4. **Catalog replica.** After the first start, trigger a catalog re-emit-all so `ext_product_code` is backfilled; until then price-catalog matching reports `CATALOG_UNAVAILABLE`.

## Working on this module

Java 25 is required (`.sdkmanrc`; the enforcer fails the build otherwise).

```bash
# Tests
./mvnw -pl pos-supplier -am -DskipTests=false verify

# Architecture rules — must run in-reactor via `test`, not `verify` (repo issue #909)
./mvnw -pl pos-archunit -am test

# Formatting
./mvnw -pl pos-supplier spotless:apply

# Regenerate openapi.yaml (boots the app under the `openapi` profile)
./mvnw -pl pos-supplier -Popenapi verify -DskipTests
```

**Running a Spring context test outside Maven** (from an IDE, say) fails closed on the encryption key,
because the `test` profile is activated by surefire configuration in `pos-supplier/pom.xml`. Pass
`-Dspring.profiles.active=test` in the run configuration.

**Do not run `scripts/generate-openapi.sh pos-supplier`.** Its aggregation step reuses the filtered
module list, so a single-module invocation rewrites `pos-api-gateway/docs/openapi-aggregate.yaml` with
only this module's paths and drops the other 25. Regenerate the module spec with Maven as above, then
rebuild the aggregate with the full discovered module list.

Changing a controller means changing the contract: update the OpenAPI annotations, regenerate
`openapi.yaml`, verify the regenerated artifact (not the annotations — springdoc infers a response body
from the return type when `content` is absent, so *removing* an annotation is not the same as declaring
nothing), then update the Angular SDK.

### Known gaps

- Manufacturer-part matching, ADR-0053 §5's third match step, needs a supplier-to-manufacturer mapping
  that no vendor profile carries yet.
- The 500-line chunk default is ADR-0053's estimate and is still owed a validation against the first
  Michelin sandbox pull.
- The `ext_product_code` replica is seeded by pos-catalog's product-fact replay
  (`POST /v1/products/facts/replay`, #1309); a first deployment must run it before PRICAT lines can
  match, because the replica holds only facts published after its consumer started.
- Re-publication accounting (`republish_count`, `last_republished_at`) is visible only in the logs and
  the table. An import stuck at the attempt cap is the signal an operator most needs and the admin API
  does not surface it yet.
- The MKCAT re-publication is sent by an operator. Nothing requests it automatically and no staged
  count is published outside a re-publication, so the staged-versus-held comparison in pos-catalog
  is as fresh as the last command, not continuous. Publishing the count at the end of every sweep
  would close that, at the cost of an event per sweep from an import that today emits nothing for
  unchanged content.
- `EndpointBindingRequest.version` is bounded but not validated against the adapter registry.
