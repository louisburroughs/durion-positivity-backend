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

Three surfaces, four permissions. Everything is under `/v1/supplier/admin`.

### Vendor profile administration — `supplier:profile:read` / `supplier:profile:write`

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
empty replica — Kafka disabled, or nothing consumed yet — reports `CATALOG_UNAVAILABLE` rather than
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

### Full YAML example

Prefix is `supplier`, so this sits at the root of any profile-specific config file:

```yaml
supplier:
  profiles:
    - key: MICHELIN                       # supplierRef: the human-readable alias used everywhere
      displayName: Michelin France
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
- V5 (`protocol_version` widened to 64) has run against H2 in PostgreSQL mode only; this environment has
  no Docker daemon for `FlywayMigrationIT`.
- `EndpointBindingRequest.version` is bounded but not validated against the adapter registry.
