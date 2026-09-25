# pos-inventory

Inventory management service for the Durion Positivity ETSMS platform. Manages stock levels, receiving and putaway workflows, pick lists, cycle counting, purchase orders, ASNs, replenishment, reservations, allocation, shortages, and returns.

## Responsibilities

- Receive goods against ASNs and purchase orders; generate putaway tasks
- Manage storage locations and bin-level inventory inquiry
- Track stock movements and maintain real-time availability
- Generate and execute pick lists for workorder part fulfillment
- Run cycle count plans and process variance adjustments
- Drive replenishment recommendations and purchase order creation
- Handle back-order reservations and allocation/reallocation
- Ingest manufacturer and distributor stock feeds
- Support bulk inventory import via `POST /v1/inventory/bulk-ingest`

## Key Classes

- `ReceivingService` — goods receipt against ASN/PO; triggers putaway generation
- `PickListGenerationService` — creates pick lists for workorder demand
- `CycleCountService` — manages cycle count sessions and variance adjustments
- `InventoryAvailabilityService` — real-time available-to-promise queries
- `ReservationService` — reserves inventory against workorder demand
- `PurchaseOrderService` — PO lifecycle and vendor communication

## API Endpoints

- `GET /v1/inventory/{locationId}/inventory-inquiry` — location-level stock inquiry
- `GET /v1/inventory/{productId}` — product stock summary
- `GET /v1/inventory/ledger` — paged inventory ledger query with filter params
- `GET /v1/inventory/ledger/{entryId}` — fetch a single inventory ledger entry
- `GET /v1/inventory/asns/{asnId}` — retrieve an ASN
- `GET /v1/inventory/goods-receipts/{receiptId}` — retrieve a goods receipt
- `GET /v1/inventory/{poId}` — retrieve a purchase order
- `GET /v1/inventory/{pickListId}` — retrieve a pick list
- `GET /v1/inventory/{pickListId}/tasks` — pick list task detail
- `GET /v1/inventory/{planId}` — retrieve a cycle count plan
- `GET /v1/inventory/lead-time` — supplier lead time data
- `GET /v1/inventory/policies` — replenishment policies
- `GET /v1/inventory/returns/returnable-items` — returnable items for a workorder's part lines (quantity consumed minus quantity already returned, per line)
- `GET /v1/inventory/returns/reason-codes` — return reason code catalog (closed set: `NOT_NEEDED`, `WRONG_PART`, `CUSTOMER_REFUSED`, CAP-218 Story #177)
- `POST /v1/inventory/returns/submit-to-stock` — post return lines to stock: persists the return record and posts a `RETURN_TO_STOCK` ledger entry per line
- `GET /v1/inventory/receiving/workorders` — search cross-dock-eligible workorders by number (contains) or exact id (#2211)
- `GET /v1/inventory/shortage/options` — compute shortage resolution options (BACKORDER, SUBSTITUTE, TRANSFER_IN, EMERGENCY_PURCHASE, CANCEL_LINE), each with an expected-resolution date and cost delta where computable (params: `allocationId`, `sku`, `shortQuantity`, optional `workorderLineId`, `locationId`)
- `POST /v1/inventory/shortage/resolve` — execute the chosen option atomically, creating the backing artifact (backorder / substitute reservation / transfer order / purchase suggestion); requires an `idempotencyKey` (retry-safe)
- `GET /v1/inventory/backorders` — list backorders (filters: status, sku, location, workorderLine)
- `GET /v1/inventory/backorders/{backorderId}` — retrieve a backorder
- `GET /v1/inventory/locations` — paged location reference data
- `GET /v1/inventory/storage-locations` — paged storage-location reference data
- `GET /v1/inventory/location-zones` — paged location-zone reference data
- `POST /v1/inventory/bulk-ingest` — bulk import inventory records
- `GET /v1/inventory/sourcing-strategies` — list sourcing strategy configuration rows
- `PUT /v1/inventory/sourcing-strategies` — upsert the sourcing strategy for one scope
- `DELETE /v1/inventory/sourcing-strategies/{configId}` — deactivate a sourcing strategy configuration
- `GET /v1/inventory/lots` — list lot master records (filters: stockItemId, status, lotNumber)
- `GET /v1/inventory/lots/{lotId}` — lot details with per-location on-hand from the per-lot summary rows
- `GET /v1/inventory/putaway/rules` — list putaway rules in the order the matcher tries them
- `GET /v1/inventory/putaway/rules/{ruleId}` — retrieve one putaway rule
- `POST /v1/inventory/putaway/rules` — create a putaway rule (409 if a second enabled `ANY` rule)
- `PUT /v1/inventory/putaway/rules/{ruleId}` — full replacement of a putaway rule
- `DELETE /v1/inventory/putaway/rules/{ruleId}` — delete a putaway rule permanently

## Location scope (ADR-0061, #1872)

Every endpoint that names a location applies the caller's location scope on top of its
`@PreAuthorize` permission. The scope comes from the gateway's `X-Loc-*` headers; a token without
them (pre-rollout) is unscoped and behaves exactly as before. A denial is `403` with code
`LOCATION_SCOPE_DENIED`, distinct from the plain `FORBIDDEN`. The per-operation decisions live in
[`location-scope.yaml`](location-scope.yaml) (read by `scripts/audit-rbac.py --check`).

- **Gate** — the request names the location acted on, so it must be within the caller's reach:
  `createAdjustmentRequest`/`approveAdjustmentRequest`, `createCycleCountPlan`,
  `createCycleCountSchedule`, `createGoodsReceipt`, `createReplenishmentPolicy`, `createScrap`, `submitReturnToStock`
  (every line's location), `deactivateInventoryLocation` (source and destination),
  `getLocationInventory`, `listLocationInventoryItems`, `getLocationInventoryRollup`,
  `queryLeadTime` (when a location or storage location is given), `listShortageOptions` (when a
  location is given) and `resolveShortage` (location and source location when given). The by-id
  reads of narrowed lists are gated on the loaded record's location after the 404 so ids cannot
  be probed: `getBackorder`, `getCycleCountPlan`, `getCycleCountSchedule`, `getLedgerEntry`,
  `getPurchaseSuggestion`, `getScrap`. Pick-list view/execute (#2204) gate the same way, but on a
  server-derived site (no request parameter names a location): `getPickList`,
  `getPickTasksForPickList`, `releasePickList`, `confirmPickTask`, `updatePickListStatus` (only
  when the pick list already exists) and `cancelPickList` (only when it exists) resolve the site
  from the pick list's tasks' suggested locations (first one that resolves); a list with no tasks,
  or none resolving a site, is not gated.
- **Narrow** — `locationId` is an optional filter. Named, it is gated; absent, a scoped caller
  sees only rows within their reach and an empty reach is an empty result (never a 403):
  `listBackorders`, `listCycleCountPlans`, `listCycleCountSchedules`, `listLedgerEntries`,
  `listInventoryStorageLocations`, `listInventoryLocationZones`, `listPurchaseSuggestions`,
  `getAvailablePutawayTasks`, `getReplenishmentPolicies`, `listScraps`, `getValuation`,
  `getAvailabilityBySku`/`listAvailabilityBySku` (SKU-wide view summed over the reach; a storage
  location or location, when named, is gated).

The reach is expanded over this module's own replica (`LocationHierarchyService.descendantsOf`
on `location_ref` + `ext_location_parent`); there is no per-request call to pos-location. A
storage-location (bin) id resolves to its site's ancestors through `ext_storage_location`, and a
narrowed query admits a bin through its `site_id`, so covering a site covers every bin in it.
`LocationScopeService` is the single entry point (`require`, `narrowTo`, `withinLocations`);
endpoints guarded by `hasAnyAuthority(a, b)` deny only when no held alternate covers the location
and narrow only when every held alternate is scoped. A caller who holds _none_ of the alternates
has no scope to read, so neither decision applies — `@PreAuthorize` guarantees a held alternate on
every HTTP path, so that state only arises with no caller at all.

Most decisions sit in the service, on the loaded record's location. `getAvailabilityBySku` and
`listAvailabilityBySku` decide in the controller instead (#1887): their service read is the same
one the outbox snapshot in `InventoryFactPublisher` takes at `beforeCommit`, which has no caller,
so the controller resolves the reach and passes it to
`InventoryAvailabilityService.queryAvailabilityWithinReach`. A scope decision on a shared read
path denied that internal actor inside the writing command's transaction and turned a completed
write into a `500`.

### Approving a cycle count write-off is an adjustment grant, not a cycle-count one (#2149)

The cycle-count family stops at `inventory:cycle_count:initiate`, `:view` and `:complete`. There is
no `inventory:cycle_count:approve`. A count that finds a variance raises a *cycle count adjustment*,
and posting or rejecting that adjustment is governed by the adjustment family —
`CycleCountAdjustmentController` enforces `inventory:adjustment:approve` on both
`POST /{adjustmentId}/approve` and `POST /{adjustmentId}/reject`. #2149 was a `LOCATION_MANAGER` on
alpha refused the approval with a bare `403 FORBIDDEN / Access denied` while holding the whole
cycle-count family, because the role held nothing from the adjustment family at all.

Two consequences worth knowing before diagnosing the next one:

- **`inventory:adjustment:approve` is sufficient on its own.** Every read on that controller is
  `hasAnyAuthority(ADJUSTMENT_VIEW, ADJUSTMENT_APPROVE)`, so an approver reaches the adjustment
  detail, the status list and the pending-approvals queue without `inventory:adjustment:view`.
  LOCATION_MANAGER is granted only the approve code for that reason. Narrowing any of those reads to
  `hasAuthority(ADJUSTMENT_VIEW)` would leave the manager able to approve an adjustment it cannot
  open; `CycleCountAdjustmentControllerTest` pins each read against an approve-only caller, and
  `CycleCountApprovalGrantsTest` in pos-security-service pins the role grants per source.
- **Counting and approving stay different people.** `INVENTORY_LEAD` (the parts-clerk persona) holds
  `inventory:adjustment:create` and not `:approve`; `LOCATION_MANAGER` now holds the reverse. Neither
  role gets `inventory:adjustment:override`, which waives the negative-stock policy on overridable
  postings and remains with ADMIN and INVENTORY_CONTROLLER.

**This endpoint is not location-scoped.** `StockMovementController.approveAdjustmentRequest` is a
recorded ADR-0061 decision (see `location-scope.yaml`), but only
`CycleCountAdjustmentController.createAdjustment` has one (gated on its resolved location since
#2167); approval there is not confined to the caller's reach even though LOCATION_MANAGER and INVENTORY_MANAGER both carry
`LOCATION` scope on `roles.location_scope`. The asymmetry between the two adjustment-approval paths
predates #2149 — INVENTORY_MANAGER already held the grant under it — and closing it is a separate
change, not one #2149's grant introduces.

Unlike #2138, the grant was missing from `scripts/fixtures/seed/alpha/security/role-permissions.csv`
as well as from the tenant, so re-seeding alpha alone would not have fixed it. LOCATION_MANAGER is
bulk-loaded (#1613 D8) and the Flyway seed never provisions it, so the CSV is its only grant source;
how a new grant reaches an existing tenant is
[docs/OPERATIONS_RUNBOOK.md](../docs/OPERATIONS_RUNBOOK.md) → "Adding a permission". A bare
`FORBIDDEN` is a missing authority; a location-scope refusal is `403 LOCATION_SCOPE_DENIED` and says
which permission and location it denied.

### A count the ledger refuses is a 422, not a posting failure (#2167)

Approving a cycle count adjustment (or creating one below the approval threshold, which posts at
once) runs the variance through the ledger's negative-stock policy. `COUNT_VARIANCE_OUT` is
floor-at-zero: a count may zero a shelf but never drive it negative, and no override lifts that.
When the policy refuses, the caller gets `422 NEGATIVE_STOCK_FLOOR_VIOLATION` with the projected
on-hand in the message, and the transaction rolls back. On approval the adjustment stays
`PENDING_APPROVAL`; on a below-threshold create, which posts in the same transaction, nothing is
recorded and the corrected count is resubmitted. The count is what is wrong (usually a
`quantityOnHandBefore` that did not match the ledger), so retrying will not help; recount, or
reject the pending adjustment. `500 ADJUSTMENT_LEDGER_POST_FAILED` now
means only an unexpected posting failure.

### An unexpected posting failure leaves the record FAILED (#2170)

When the ledger posting fails for any other reason (`500 ADJUSTMENT_LEDGER_POST_FAILED` for a cycle
count adjustment, `500 SCRAP_LEDGER_POST_FAILED` for a scrap), the partial posting rolls back, and
the adjustment or scrap is then left `FAILED` with the cause in `errorMessage`. The error `message`
names the record (`Adjustment <id>: …`, `Scrap <id>: …`). The `FAILED` state is written by
`LedgerPostingFailureRecorder` after the request's transaction has rolled back, in a transaction of
its own; saved inside the request's transaction, it used to be rolled back too and was never
persisted. On approval the existing record turns `FAILED`; on a below-threshold create, whose insert
rolled back, the record is inserted `FAILED` under the id the error names. List them with
`status=FAILED`. The failure was unexpected, so it is the retryable case: approving a `FAILED`
adjustment or scrap posts it again, and a successful post clears `errorMessage`.

**Which shelf the variance posts against.** An adjustment created from a task takes the task's bin
location. A task-less adjustment takes the optional `locationId` on the create request; without
one it posts against the stock item's location-less balance, which is almost never where counted
stock sits, and the policy then judges the count against that balance (the `at location null` in
#2167's rejection). Name the location. A `locationId` that contradicts the task's bin is refused
with `400 VALIDATION_ERROR`. The resolved location is gated against the caller's reach
(`inventory:adjustment:create`, `403 LOCATION_SCOPE_DENIED`; see `location-scope.yaml`), stored on
the adjustment (`cycle_count_adjustment.location_id`), returned as `locationId`, and used both for
posting and for the variance recompute on a `CONFLICT` task.

### Scrap and adjustment postings are published as facts (#1030, #2190)

Two value-changing postings each put one occurrence fact on `inventory.events.v1` through the
outbox, written at `beforeCommit` by `InventoryFactPublisher`, so a posting that rolls back
(`FAILED`, or a `422` refusal) leaves no row. pos-accounting consumes both to post the journal entry.

| Fact | `eventType` | Aggregate id | Emitted when |
| --- | --- | --- | --- |
| `ScrapPostedV1` | `inventory.scrap.posted` | `scrapId` | a scrap's `SCRAP_OUT` entry posts (auto-approved or approved) |
| `InventoryAdjustedV1` | `inventory.adjustment.posted` | `adjustmentId` | a cycle-count adjustment posts `COUNT_VARIANCE_IN`/`_OUT` (auto-approved or approved; `adjustmentKind = CYCLE_COUNT`), or an approved manual adjustment request posts `ADJUSTMENT_IN`/`_OUT` (`adjustmentKind = MANUAL_ADJUSTMENT`) |

`InventoryAdjustedV1` carries the posted `ledgerEntryId`, `ledgerEventType`, `sku`, `locationId`,
`taskId` (cycle count only), `reasonCode`, and a signed `quantityDelta`: positive is a gain,
negative a loss. No fact is emitted for a rejected adjustment, a variance that recomputes to zero on
a `CONFLICT` task (nothing posts). A manual request cannot have a zero quantity: create refuses
it (400, field error on `quantity`), and approving one stored before that check marks it
`REJECTED` and answers `422 ADJUSTMENT_QUANTITY_ZERO` without posting (#2201). Re-approving a `FAILED`
adjustment posts once and emits once.

Cost, on both facts, is the one the costing engine stamped on the posted ledger entry: `unitCost`
is that entry's `unitCost`, and `costSource` is the SKU's costing method (`AVERAGE`/`STANDARD`), or
`NONE` with a null `unitCost` for an uncosted SKU, which accounting records and skips. It is never the
create-time snapshot (`costAtTimeOfAdjustment`, `unitCostSnapshot`), which only sets the approval
tier. A count variance posts with no document cost of its own, so a gain enters at the current
method cost: it leaves the running average unchanged under `AVERAGE` and does not overwrite the
latest-receipt memo under `STANDARD`. Both paths also queue the `InventoryAvailabilityUpdatedV1` and
`StorageLocationOnHandUpdatedV1` snapshots for the stock item and location they moved.

### Receipt cost (#2203, ADR-0048 IMP-002)

Every `GOODS_RECEIPT` row carries a document cost per base unit, which the costing engine blends
into the running average under `AVERAGE` and keeps as the latest-receipt memo under `STANDARD`:

| Receipt path | Document cost |
| --- | --- |
| `POST /v1/inventory/goods-receipts` | the line's `unitCostMinor`, divided by the document-UoM conversion factor when one is keyed |
| Receiving session (receive into staging, cross-dock) | the purchase order line's `unitCostMinor`, divided by the factor the order line was keyed at (`ext_purchase_order_line.conversion_factor`, published by pos-order on `purchaseorder.updated`) |
| `POST /v1/inventory/stock-movements` `RECEIVE` | none: it enters at the current average and never gives an uncosted SKU a cost |

Minor units become major units by the order currency's ISO 4217 digits (two when unknown), at the
ledger's `numeric(19,4)` scale. A session line posts without a cost when it cannot be priced: an
order line projected before pos-order published its factor (until the order's next fact replaces
it), or a session line opened before `receiving_line.source_line_id` existed whose SKU appears on
more than one order line. Receipts posted before #2203 stay uncosted (ADR-0048 §3: cost at posting
time); such a SKU gains a cost from its next priced receipt or from a revaluation.

### Work-order linkage on the ledger, returns, and cross-dock search (#2206, #2211)

`inventory_ledger_entry` carries nullable `workorder_id`/`workorder_line_id` columns, stamped by
every posting path that knows a work order: pick-task consumption (`WORKORDER_CONSUMPTION`,
`ConsumptionServiceImpl`), the cross-dock `GOODS_RECEIPT`/`GOODS_ISSUE` pair (`workorderId` and,
when the request's `workorderLineId` parses as a UUID, the line), and `RETURN_TO_STOCK` postings
from both return paths. `fromLocationId`/`toLocationId` already carry the storage location (bin)
or site the posting used — the UI reads those directly rather than a separate field. No backfill:
a row posted before this column existed carries no link.

Returns to stock have two paths. `POST /v1/inventory/returns/submit-to-stock`
(`ReturnController.submitToStock`) is the work-order-line-keyed path: the named workorder must be
`COMPLETED` or `CLOSED` (`ext_workorder` replica status; 422 `WORKORDER_NOT_RETURNABLE` otherwise,
404 `NOT_FOUND` when the replica has no row for it at all — CAP-218 Story #177, parts are handed
back once the job is done, not mid-repair), no two lines may name the same `itemId` (400
`VALIDATION_ERROR` — each workorder line is validated once, not aggregated), each line's `itemId`
names a work-order part line (`ext_workorder_part`), `reasonCode` must be one of the closed set
`NOT_NEEDED`/`WRONG_PART`/`CUSTOMER_REFUSED` (400 `VALIDATION_ERROR` otherwise), and the quantity
may not exceed that line's returnable balance — quantity consumed (`WORKORDER_CONSUMPTION` ledger
rows for the line) minus quantity already returned (`inventory_return_line.workorder_line_id` rows
for the line), floored at zero (422 `RETURN_QUANTITY_EXCEEDED` otherwise, 404 `NOT_FOUND` when
`itemId` does not name a real part line). It persists an `InventoryReturnEntity`/
`InventoryReturnLineEntity` pair and posts one `RETURN_TO_STOCK` ledger entry per line.
`GET /v1/inventory/returns/returnable-items` reads that same consumed-minus-returned balance per
part line. The older `returnItemsToStock` internal path (SKU/quantity against consumption history,
no work-order-line key) is unchanged.

`GET /v1/inventory/receiving/workorders?query=` (`ReceivingController.searchCrossDockWorkorders`,
same dual-authority gate as `crossDockLineToWorkorder`: `inventory:receiving:complete` AND
`inventory:issue:parts`) finds workorders eligible for cross-dock — status not `COMPLETED`,
`CANCELLED` or `CLOSED` (`WorkorderValidationService.isClosedWorkorderStatus`) and at least one
demanded part line — matching `query` against `workorderNumber` (case-insensitive contains) or an
exact workorder UUID; a blank/omitted query returns up to 50 most-recently-updated eligible rows.
Eligibility and the match are both expressed in the repository query, not filtered in memory.

## Lot Tracking — Inbound Capture (odoo-parity E1)

Products whose catalog replica (`ext_product.tracking_level`) says `LOT` require a `lotNumber`
on every inbound receipt line (goods receipt, receive-into-staging, PO receive); a missing lot
number is a deterministic `422 LOT_NUMBER_REQUIRED`. The lot is found-or-created per
(stockItemId, lotNumber) in `inventory_lot` (receivedAt/vendorId stamped on first sight) and
its id is stamped on the receipt's ledger entries (`inventory_ledger_entry.lot_id`).

The stock summary uses dual-row bookkeeping: every posting updates the lot-agnostic
(`lot_id IS NULL`) row exactly as before — that row remains what all availability, rollup, and
forecast readers consume — and a lot-tagged posting additionally applies the same deltas to a
per-lot row keyed `(stock_item_id, location_id, lot_id)` (unique `NULLS NOT DISTINCT`), from
which the lot read API serves per-lot on-hand. Rebuild and drift verification replay the
identical rule.

Untracked products (tracking level `NONE`, unknown products, free-text SKUs) see zero behavior
change: no validation, `lot_id` null everywhere, a single summary row. `SERIAL` is treated as
`NONE` until parity-E4; outbound lot stamping (picks, consumption, transfers, scraps — and the
cross-dock receipt+issue pair) is parity-E2. Expiry (`expiration_date`) is populated by the
parity-E3 flows.

## Sourcing Strategy Engine (odoo-parity H1/H2)

`SourcingStrategyService` (internal) orders candidate locations for a SKU per a configured
removal/sourcing strategy — consumed by consumption allocation-close ordering, pick-task
location suggestion, and (from parity-F5) replenishment source selection via
`selectSource(selection, neededQuantity)`.

- **Strategies**: `FIFO` (earliest `GOODS_RECEIPT`/`TRANSFER_IN` ledger timestamp per location —
  a documented per-location approximation), `FEFO` (earliest lot expiry via the
  `LotExpiryProvider` SPI; falls back to FIFO until the lot stories E2/E3 register a real
  provider), `PROXIMITY` (hop distance to a reference location, BFS over
  `ext_storage_location` parent links plus `ext_location_parent` edges; falls back to FIFO
  without a reference), `HIGHEST_STOCK` (most `onHand - allocated` first). All orderings
  tie-break by ascending location id. LIFO and least-packages are explicit non-goals.
- **Resolution**: active `SKU_CATEGORY` config → active `SITE` config → active `DEFAULT` config →
  platform default FIFO. Configuration lives in `sourcing_strategy_config` (V17), administered
  via the `sourcing-strategies` endpoints (`inventory:location:admin`).
  The `SKU_CATEGORY` scope resolves through the `SkuCategoryProvider` SPI, which is gated by
  `pos.inventory.sku-category.resolve-from-replica` and **defaults off** — so the scope is skipped
  and this is a three-step chain in practice. Since #1514 the catalog replica does carry the
  category, so turning the flag on would make the scope reachable for the first time. Note that
  `SKU_CATEGORY` is the **highest**-precedence sourcing scope, so a category row that starts
  resolving overrides even a deliberate per-site strategy. The same flag also makes the
  `SKU_CATEGORY` scope of `sku_cost_method_config` reachable, which would flip matching SKUs off
  `DEFAULT` costing at their next ledger posting. Audit both before flipping it:
  `GET /v1/inventory/valuation/methods/sku-category-impact` (`inventory:location:admin`) reports
  exactly which SKUs would change and works while the flag is still off; the full procedure is
  "SKU_CATEGORY costing and sourcing cut-over (#1535)" in `docs/OPERATIONS_RUNBOOK.md`. Putaway does
  **not** go through this SPI; it reads the unconditional `SkuCategoryLookup`.
- **Audit**: the effective strategy is recorded as `sourcingReason` on pick tasks
  (and, from F5, replenishment tasks) so ops can answer "why this bin".

## Category-based putaway (#1514)

Putaway routes on what the item _is_ and on what the destination is _fit to hold_, rather than on
per-SKU replenishment configuration. Full rules: [Putaway Validation Business Rules](https://github.com/louisburroughs/durion/blob/master/domains/inventory/putaway-validation-rules.md).

- **Rules match per line**, in the strict precedence `SKU > SUBCATEGORY > CATEGORY > ANY`;
  `priority` only breaks ties within a tier. `putaway_rule.match_type` / `match_value` (V42)
  replaced the never-read `criteria` JSON column, and matching is on catalog **ids**, never on name
  snapshots. Rules are managed over `/v1/inventory/putaway/rules`
  (`inventory:putaway_rule:view` / `inventory:putaway_rule:manage`).
- **Exactly one enabled `ANY` rule** may exist; it is the terminal fallback that guarantees a
  brand-new uncategorised SKU never dead-ends, and it replaced a hardcoded default-location UUID no
  environment ever had. Its absence raises `NO_PUTAWAY_RULE_MATCH` (422) naming the remedy.
- **Destination eligibility is `storage_compatibility`** (V43): a Flyway matrix of
  (catalog category or subcategory id) → accepted storage classes, with subcategory rows _replacing_
  their parent's. `STAGING` and `QUARANTINE` accept nothing — they are putaway sources.
  `BATTERY_RACK` and `OIL_STORAGE` require the destination to declare `hazard_containment`, and an
  item whose every accepted class demands containment carries that requirement itself, so it is
  refused even by a `GENERAL` bin.
- **A replenishment policy is no longer required for putaway.** Both `(itemSKU, locationId)`
  policy-row gates are gone, and capacity no longer falls back to summed replenishment maximums:
  an undeclared capacity is uncapped, a declared zero still refuses. `ReplenishmentPolicy` keeps
  doing its own job for the restock scan.

**Rollout requirement.** `V41` adds `ext_product.category_id`/`subcategory_id` and
`ext_storage_location.storage_category_code`/`hazard_containment`/`allow_new_product` **empty, with
no backfill**. Category matching has nothing to match on until a pos-catalog product-fact replay and
a pos-location storage-location republish have run — and pos-location's generic outbox replay does
_not_ work for this, because it re-emits stored payloads that predate the fields. See
`docs/OPERATIONS_RUNBOOK.md` → "Replica seeding and drift repair (replay)" →
"Issue #1514: rehydrating the putaway replica columns".

## Staging location resolution (#2009)

Receiving puts goods into a site's **staging** location and putaway takes them out of it, so both
have to agree on which location that is. The staging location is a property of a site, and
`StagingLocationResolver` picks the site in this order:

1. the request's `X-Site-Id` header or its `{siteId}` URI variable,
2. the configured `pos.inventory.receiving.site-id`,
3. **the site of the entity being acted on** — the goods receipt's own location for putaway
   generation, the site stamped on the receiving session for receive-into-staging.

`receiving_session.site_id` (V4) is captured from the source order's ship-to when the session
opens, and read from the session thereafter. Re-reading the projection per receive call would let a
mid-session revision move the site: `revisePurchaseOrder` overwrites `shipToLocationId` in any
lifecycle state, `PARTIALLY_RECEIVED` included, and a session's stock does not move site because
somebody edited the order behind it. A session opened before the column existed carries no site and
falls back to the projection. The staging location is resolved once per receive call, not per line.

The chosen site's declared default comes from the `location_ref` replica, fed by
`location.location.updated` when a site configures defaults through
`PUT /v1/locations/{id}/defaults` in pos-location. A site that declares none falls back to
`pos.inventory.receiving.staging-location-id`, then to `00000000-0000-0000-0000-000000000002`.

Step 3 is what makes the header optional. `POST /v1/inventory/putaway/tasks/generate` and
`POST /v1/inventory/receiving/sessions/{id}/receive` are not site-scoped by path, and no client
sends `X-Site-Id`, so before #2009 both used the constant — and a receipt booked into a site's own
declared staging location was refused with `422 RECEIPT_NOT_STAGED`. The header and the property
remain as explicit overrides.

## Counter-sale consumption (order parity H2)

When `pos.inventory.kafka.enabled` is on, `OrderEventsListener` consumes
`order.order.completed` and posts a `GOODS_ISSUE` ledger movement per fulfillable line
(`stockItemId` = the line's SKU, at the order's shop location; WORKORDER-sourced lines never
move stock — spec R7.5). Each line posts in its own transaction: a rejected post (insufficient
stock, unknown item) raises `inventory.counter-sale.consumption-failed` on
`inventory.events.v1` and never affects the completed sale.

`PurchaseOrderProjectionListener` consumes the same topic for the purchase-order projection. Each
of the two needs every order event, so each has its own consumer group
(`POS_INVENTORY_ORDER_EVENTS_CONSUMER_GROUP`, default `pos-inventory-order-events`;
`POS_INVENTORY_PURCHASE_ORDER_PROJECTION_CONSUMER_GROUP`, default
`pos-inventory-purchase-order-projection`) and its own `processed_events` owner (`order`,
`order:purchase-order-projection`). `processed_events` is keyed by `(event_id, owner)`, so one
consumer's mark never makes another skip the event (#2176). Before, the shared group split the
partitions between the two, and the shared key let whichever recorded an event first make the other
skip it; either way an order event silently reached only one of them.

## Supplier availability hints (CAP-322, #1312)

`SupplierStockHintEventsListener` consumes `supplier.stockreport.updated` from
`supplier.events.v1` (producer #1228) into `supplier_stock_hint` — one row per
`(vendorProfileId, article identity)` holding what that vendor last said about its own stock.

**This is not owned stock.** It never enters valuation (ADR-0048), it is never part of on-hand
ATP, and it never satisfies a gate on committing stock. The guarantee is structural rather than
procedural: the hints live in their own tables that no valuation or ATP query joins, and two
ArchUnit rules in `ArchitectureTest` hold the repository to the supplier-hint classes.

What the feed's shape forces, and how each is handled:

- **No terminal event, no republish command, and an empty snapshot publishes nothing.**
  Completeness is in-band only: `supplier_stock_snapshot_chunk` logs the sequences that arrived
  and `supplier_stock_snapshot_receipt` compares the count against the `chunkCount` every chunk
  states. Incomplete is the resting state, so a lost chunk needs no timer to be noticed and every
  hint from that snapshot reads as `snapshotComplete: false`.
- **Three-way quantity distinction.** A stated quantity (`0` included — an explicit "we have
  none"), a `NULL` quantity (the vendor listed the article and stated no quantity), and no row at
  all (the vendor never mentioned it). A snapshot that omits a previously reported article leaves
  its hint standing with its own `asOf`; a vendor's silence never reads as an out-of-stock.
- **Two timestamps.** `snapshotAsOf` is the vendor's own figure and is nullable; `fetchedAt` is
  when we asked. Freshness is judged on the vendor's figure where there is one, and `asOfSource`
  says which is in play. Supersession is ordered on `fetchedAt`, the one instant that always
  exists.
- **Staleness ceiling.** Past `pos.inventory.supplier-hints.staleness-ceiling` (per-vendor
  override available) a hint reads as `STALE_UNKNOWN` with its quantity suppressed — never as
  zero.
- **Resolution is out of band, against a local replica.** `SupplierStockHintResolver` sweeps
  `PENDING` hints against `ext_product_code` — this module's own copy of pos-catalog's product
  identity codes, maintained by `CatalogEventsListener` from `catalog.product.updated` facts.
  Never a synchronous call to pos-catalog: ADR-0044 R1 forbids it and R3 makes the replica the
  sanctioned read path, and pos-supplier resolves PRICAT lines the same way against its own copy
  (CAP-318 #1224). Only EAN is matched; vendor and buyer article codes carry no uniqueness
  guarantee and are never guessed at. Unresolved hints are retained and remain readable by code,
  and an unseeded replica defers rather than reporting every hint as a catalog miss.

Read path: `GET /v1/inventory/supplierStockHints/byProduct/{productId}` and
`GET /v1/inventory/supplierStockHints/byCode` (`inventory:supplier_stock_hint:view`). Results are
per vendor and never aggregated.

Not in this module: publishing hints onward as an `inventory.supplier-availability.updated` fact
for estimates. That waits on resolution being settled — see #1312.

## Decimal quantities, gated by the product's declaration (ADR-0055, #1414)

The ledger and everything derived from it carry `numeric(19,4)` / `BigDecimal` quantities:
`inventory_ledger_entry.change_in_quantity` and `quantity_after`, the reservation / allocation /
backorder chain, the `inventory_stock_summary` read model, the costing state, cycle-count
variances, returns, manual adjustments and shortage records.

**Widening the columns did not make stock divisible.** A product's divisibility is declared by the
catalog as the `precision_scale` of its `BASE` row in `product_uom`, replicated here as
`ext_product_uom`. Scale `0` — and equally a product with no unit-of-measure rows, which is every
product until seeding lands — means whole units and is still refused a fraction. A non-zero scale
permits that many decimal places and no more. `UomConversionService.declaredBaseScale` answers the
question; `QuantityScaleGuard` enforces it.

Enforcement is symmetric. pos-workorder gates the demand side at estimate-item entry and part issue
(#1413); this module gates the supply side and both raise HTTP 422
`FRACTIONAL_QUANTITY_NOT_ALLOWED` from the same declaration. The guard took over the three
`intValueExact()` calls in receiving, ASN and returns — keeping their fail-closed character,
losing their hardcoding — and manual stock movements, which post to the ledger just as directly,
gained the same gate.

On the read side, the `Math.toIntExact` calls that used to narrow the availability math are
replaced by the same scale check (`QuantityScaleGuard.requireReportable`). Those calls threw rather
than wrapping, and that fail-loud property is preserved: a stored quantity carrying more precision
than the product declared stops the computation instead of being reported as though it were fine.
The check only runs when the ledger's `stockItemId` names a catalog product. The ledger's posting
paths disagree about whether that column holds a product UUID or a human SKU, and a reference that
names no product declares nothing — which cannot be evidence that a value already in the ledger is
wrong. Such a row is reported as stored rather than refused; refusing would turn an availability
read into a 500 and stall the replicas behind it.

**Still integral, deliberately:** pick-task and putaway/transfer-order-line quantities. The
cycle-count capture DTOs (`SubmitCountRequest.actualQuantity`, `CountEntryResponse`) widened to
`BigDecimal` in stage 4 (#1416, see below) — where the still-integral surfaces meet the ledger they
widen at the boundary (`BigDecimal.valueOf`), never narrow it. Sales-order line quantities stay
`int` by decision — see ADR-0055.

Compare these quantities with `compareTo`, never `equals`: PostgreSQL returns `numeric(19,4)` at
scale 4, so a stored `4.0000` and a computed `4` are the same quantity and are not equal.

### Unit of measure on the reservation command (ADR-0055 stage 3, #1415)

`inventory.reservation.request-requested` carries an optional `uomCode` alongside
`requiredQuantity`. `uomCode` absent (or `null`) means `requiredQuantity` is already in the
product's base unit — the pre-#1415 shape, unchanged. When present, `ReservationRequestHandler`
converts to base via `UomConversionService.toBaseQuantityForReservation` (`DOWN` rounding — a
reservation must never promise more than exists) before registering demand, evaluating ATP, or
opening a backorder; the converted quantity is what the resulting `ReservationOutcomeV1` fact
reports. The converted value is also re-checked with `QuantityScaleGuard.requirePostable`, mirroring
how PO/ASN/receiving/return lines validate after `DocumentQuantityConverter` — a no-op when the
conversion already rounded cleanly, and the real guard for a `requiredQuantity` sent with no
`uomCode` at all. A `uomCode` with no conversion row for the product raises
`UomConversionUndefinedException` (422 `UOM_CONVERSION_UNDEFINED`); on the command's async path this
surfaces as a logged, permanently-failed command with no outcome fact emitted, same as any other
business validation failure here.

### Tolerance-based cycle-count reconciliation (ADR-0055 stage 4, #1416)

Bulk stock (tanks, drums, bins of loose material) never counts exactly to book — evaporation,
meter variance, and measurement precision all contribute a small honest gap. Requiring an exact
match, as the platform did before this stage, turns every bulk count into a manual-adjustment
review. Tolerance-based reconciliation fixes that: a count within the configured tolerance is
accepted with no adjustment and no review; a count outside it is flagged and follows the existing
adjustment/approval path unchanged.

**Configuring a tolerance.** `POST /v1/inventory/cycleCountTolerances` (permission
`inventory:cycle_count_tolerance:manage`) creates a row scoped by `productId`, `storageLocation`
(matching `cycle_count_task.binLocation` — free text, may hold a location UUID's string form),
both, or neither (the global default), with an `absoluteTolerance`, a `percentageTolerance`, or
both. `PUT .../{toleranceId}` replaces the bounds and active flag; `GET`/`GET .../{id}`
(`inventory:cycle_count:view`) list and read; `DELETE` removes a row outright. **Resolution is
most-specific-first:** product+location, then product alone, then location alone, then the global
default, then — if nothing matches — zero tolerance (today's pre-#1416 behavior, unchanged). When
both an absolute and a percentage bound are configured, a count is within tolerance if it fits
under _either_ — the effective allowance is the larger of the two, the conventional "±50 gal or
±1%, whichever is greater" shape of real tank-tolerance policy. See
`CycleCountToleranceResolver`'s class javadoc for the full reasoning.

**Reconciliation flow.** `POST /v1/inventory/cycleCount/submit` and `.../recount` now also accept
`unitOfMeasure` (the unit physically measured in — converted to base UoM via
`UomConversionService.toBaseQuantity`, `HALF_UP`, before variance is computed; a count is a
measurement, not a reservation promise) and `measurementMethod` (`MANUAL_COUNT`, `GAUGE`, `DIP`,
`SCALE`, `METER`, or `SENSOR`; defaults `MANUAL_COUNT`). Every submission resolves the applicable
tolerance and compares `|measured − book|` against it:

- **Within tolerance:** the task closes `ACCEPTED_WITHIN_TOLERANCE` — reconciled, no
  `CycleCountAdjustment` is ever created, on-hand is untouched. The count and an adjustment remain
  separate transactions, per the owner's spec, so there is nothing to "not post."
- **Exceeding tolerance:** unchanged pre-#1416 behavior — `COUNTED_PENDING_REVIEW` (or `CONFLICT`
  if in-window stock movements were detected), same as an unconfigured zero-tolerance variance
  always has. A reviewer investigates and explicitly calls the existing
  `POST /v1/inventory/cycleCountAdjustments` to create an adjustment, which goes through
  `ApprovalThresholdEvaluator` and posts as its own, separate transaction on approval.
- A **movement conflict always takes precedence**: a task whose expected-quantity snapshot is
  already known stale (odoo-parity I2) is never auto-accepted, regardless of how the variance
  compares to tolerance — that comparison would be against a number already known to be wrong.

`CountEntryResponse` carries the owner spec's full "Required Data" list per count: book quantity
(`expectedQuantity`), measured quantity (`measuredQuantity` + `unitOfMeasure`), measurement method,
measurement timestamp (`countedAt`), variance quantity and percentage, the resolved tolerance
snapshot (`allowedToleranceAbsolute`/`allowedTolerancePercentage`), `withinTolerance`, and an
optional `varianceReason`. Adjustment quantity and approval status are deliberately _not_
duplicated onto the count — they live on the linked `CycleCountAdjustment`, queryable by
`cycleCountTaskId`, since a count and its adjustment are separate records for separate
transactions.

**The `FLOOR_AT_ZERO` decision.** `NegativeStockPolicy.forEventType` maps `COUNT_VARIANCE_OUT` and
`ADJUST_CYCLE_COUNT` to `FLOOR_AT_ZERO`. Read literally, that name suggested a risk: silently
clamping a bulk product's downward variance to zero rather than surfacing it. Reading the
mechanism (`LedgerPostingServiceImpl.rejectNegativeProjection`) shows it does the opposite —
it **throws** `NegativeStockPolicyViolationException` rather than clamping, and does so on a
`quantityAfter` that is algebraically guaranteed non-negative for this path: an adjustment posts a
_to-measured_ change (`quantityAfter = currentOnHand + quantityChange`, recomputed against current
on-hand immediately before posting), and the measured quantity behind that change is validated
`>= 0` at every entry point. The floor is kept as-is — structurally unreachable defense-in-depth
for this path, not dead weight — documented and pinned by test rather than silently inherited. See
the enum javadoc on `NegativeStockPolicy.FLOOR_AT_ZERO` for the full argument, and
`NegativeStockPolicyEnforcementTest`'s ADR-0055-stage-4 tests for the pin.

### Rollout: breaking on the wire, deployed in lockstep

`InventoryAvailabilityUpdatedV1`, `ReservationOutcomeV1`, `BackorderCreatedV1`,
`BackorderResolvedV1`, `ProductValueChangedV1` and `StorageLocationOnHandUpdatedV1` changed their
quantity fields from `int`/`long` to `BigDecimal`. That is a **breaking payload change** for every
consumer: the `ext_inventory_availability` replicas in pos-order, pos-workorder and pos-catalog, and
the `ext_storage_location_on_hand` replica in pos-location.

**No dual-read shim was added, deliberately.** The platform is pre-production with no live data, and
every producer and consumer of these topics ships from this one Maven reactor, so there is no
version skew to bridge — only a deployment ordering to respect. The repository's pre-production
policy is explicit that clean code beats compatibility scaffolding, and a versioned-claim or
dual-read path here would be scaffolding for a skew that cannot occur.

What that costs is a constraint on the rollout, and it is stated rather than mitigated:

- **Deploy the fleet together.** pos-inventory, pos-order, pos-workorder, pos-catalog and
  pos-location must go out in the same release. A consumer running the old code against a new
  payload rejects it as a databind failure (counted on `replica.payload.rejected`) rather than
  landing a wrong number — loud, not silent — but the replica stops advancing until it is upgraded.
- **Run the migrations first.** Each module's widening migration (`V39` here; `V21`, `V18`, `V13`,
  `V5` in pos-order, pos-workorder, pos-catalog and pos-location) is a pure `ALTER … TYPE`
  widening. `numeric(19,4)` holds every value the integer columns could, so the implicit cast
  preserves existing rows exactly and needs no data-preservation logic.
- **Drain or accept a stalled topic.** In-flight events published under the old shape deserialize
  with the three core availability quantities missing, which the record constructor rejects. The
  forecast triple defaults to zero as it always did for schema-v1 payloads.

## Error codes

Every non-2xx response carries the platform `ApiError` envelope. Field semantics, payload examples,
and the platform-wide fallback codes emitted by `pos-web-common` and `pos-security-common` are in
[`durion/docs/architecture/api/ERROR_ENVELOPE.md`](../../durion/docs/architecture/api/ERROR_ENVELOPE.md).
The table below is this module's own codes; any endpoint here may additionally return a platform
fallback code. Add a row in the same pull request as the controller or advice that mints the code.

| Code | Status | Description |
|------|--------|-------------|
| `VALIDATION_ERROR` | 400 | Request parameter or body validation failed: bean validation, a type mismatch, an unreadable body, a constraint violation, an invalid availability request, an invalid PO reference, an invalid count quantity, or an `IllegalArgumentException` from a service |
| `RECOUNT_LIMIT_EXCEEDED` | 400 | A cycle-count task has already been recounted the maximum number of times |
| `SOURCE_DOCUMENT_ALREADY_RECEIVED` | 400 | A receiving session was opened for a source document that is already fully received |
| `WORKORDER_CLOSED` | 400 | The workorder the consumption or pick targets is closed |
| `INVALID_PARAM_COMBINATION` | 400 | The query parameters supplied cannot be combined |
| `FORBIDDEN` | 403 | Caller lacks the required permission, or the request's location falls outside the caller's scope |
| `PART_MATCH_PERMISSION_REQUIRED` | 403 | Confirming a part match needs a permission the caller lacks |
| `NOT_FOUND` | 404 | Inventory resource not found: product, location, task, cycle-count plan, transfer order, scrap record, source document, receiving session, work order part line (`submitReturnToStock`) or allocation (`listShortageOptions`/`resolveShortage`, when `sku`/`shortQuantity` are omitted and `allocationId` is unknown) |
| `CONFLICT` | 409 | An `IllegalStateException` from a service, or a duplicate ASN |
| `DUPLICATE_ENABLED_ANY_PUTAWAY_RULE` | 409 | An enabled `ANY`-scope putaway rule already exists |
| `CYCLE_COUNT_CONFLICT` | 409 | Cycle-count approval rejected; the task is flagged CONFLICT and the reviewer must choose a recount or a recomputed approval |
| `SOURCE_DOCUMENT_LINES_UNAVAILABLE` | 409 | The purchase-order line projection has not caught up (or the id is unknown); `nextAction` says to retry |
| `PURCHASE_SUGGESTION_INVALID_STATE` | 409 | Accepting or dismissing a purchase suggestion from a terminal status |
| `OVER_RECEIPT_NOT_PERMITTED` | 422 | The goods receipt would push the received total past the purchase order's open balance and the caller lacks `inventory:goods_receipt:override` |
| `ROLLUP_EXPANSION_TOO_LARGE` | 422 | `expand=tree` was requested on a parent-location rollup whose descendant site count exceeds the configured cap |
| `INSUFFICIENT_STOCK` | 422 | Not enough on-hand stock to fulfill |
| `NEGATIVE_STOCK_OVERRIDE_REQUIRED` | 422 | The movement would drive stock negative and the policy requires an explicit override |
| `NEGATIVE_STOCK_FLOOR_VIOLATION` | 422 | The movement would breach the negative-stock floor, which no override lifts; also answered when a cycle count adjustment's variance would take on-hand below zero (on approval the adjustment stays `PENDING_APPROVAL`; on an auto-approved create nothing is recorded) |
| `AS_OF_IN_FUTURE` | 422 | A point-in-time query names a future instant |
| `VALUATION_AS_OF_SKU_CAP_EXCEEDED` | 422 | An as-of valuation covers more SKUs than the cap allows |
| `REPLENISHMENT_SNOOZE_NOT_IN_FUTURE` | 422 | A replenishment snooze instant is not in the future |
| `INSUFFICIENT_ATP` | 422 | Available-to-promise quantity is insufficient |
| `PICK_SCAN_MISMATCH` | 422 | The scanned item does not match the pick line |
| `WORKORDER_CONSUMPTION_ERROR` | 422 | Consuming parts against the workorder failed a business rule |
| `RETURN_QUANTITY_EXCEEDED` | 422 | Return exceeds original purchase quantity (`returnItemsToStock`), or exceeds a work order line's remaining returnable quantity — consumed minus already returned (`submitReturnToStock`) |
| `TRANSFER_DISPATCH_EXCEEDS_REQUESTED` | 422 | A transfer dispatch exceeds the requested quantity |
| `TRANSFER_RECEIVE_EXCEEDS_DISPATCHED` | 422 | A transfer receipt exceeds the dispatched quantity |
| `CROSS_SITE_TRANSFER_REQUIRES_ORDER` | 422 | Immediate stock movements are intra-site; a cross-site move needs a transfer order |
| `ADJUSTMENT_QUANTITY_ZERO` | 422 | Approving a manual adjustment request whose quantity is zero; the request is marked `REJECTED` and nothing posts (create rejects a zero quantity with a 400 field error) |
| `TRANSFER_LOCATION_NOT_ELIGIBLE` | 422 | An INACTIVE or PENDING site cannot take part in a movement |
| `SCRAP_INSUFFICIENT_STOCK` | 422 | Not enough on hand at the source location to scrap |
| `LOCATION_NOT_VALID_FOR_SKU` | 422 | Putaway target location is not valid for the SKU |
| `LOCATION_AT_CAPACITY` | 422 | Putaway target location is at capacity |
| `NO_ON_HAND_AT_SOURCE_LOCATION` | 422 | Putaway source location has no on-hand quantity to move |
| `NO_PUTAWAY_RULE_MATCH` | 422 | No putaway rule matches the receipt |
| `RECEIPT_NOT_STAGED` | 422 | Putaway was requested for a receipt that is not staged |
| `UNSUPPORTED_SOURCE_DOCUMENT_TYPE` | 422 | Receiving cannot resolve the source document type to an owning service |
| `FRACTIONAL_QUANTITY_NOT_ALLOWED` | 422 | The quantity carries more decimals than the product's catalog declaration allows (ADR-0055) |
| `UOM_CONVERSION_UNDEFINED` | 422 | No conversion path from `uomCode` to the product's base unit |
| `LOT_NUMBER_REQUIRED` | 422 | A LOT-tracked product was posted without a lot number |
| `LOT_UNKNOWN` | 422 | An outbound flow named a lot that does not exist |
| `LOT_NOT_AVAILABLE` | 422 | The lot is QUARANTINED, RECALLED or CONSUMED and cannot leave stock |
| `LOT_INSUFFICIENT_STOCK` | 422 | The lot has less on hand than the posting needs; the per-lot floor has no override |
| `SERIAL_COUNT_MISMATCH` | 422 | A serialized posting does not enumerate exactly one serial per unit |
| `SERIAL_ALREADY_IN_STOCK` | 422 | A serial being received is already in stock |
| `SERIAL_NOT_AVAILABLE` | 422 | An outbound posting named an unknown or already-consumed serial |
| `PURCHASE_SUGGESTION_NOT_ACCEPTED` | 422 | Only an ACCEPTED purchase suggestion can be converted |
| `PURCHASE_SUGGESTION_VENDOR_MISMATCH` | 422 | The suggestions being converted name different vendors |
| `PURCHASE_SUGGESTION_MISSING_VENDOR` | 422 | The suggestion names no vendor to order from |
| `PURCHASE_SUGGESTION_MISSING_UNIT_COST` | 422 | The suggestion carries no unit cost |
| `PURCHASE_SUGGESTION_SITE_MISMATCH` | 422 | The suggestions being converted belong to different sites |
| `SHORTAGE_RESOLVE_MISSING_FIELD` | 422 | The shortage resolution omits a field its strategy requires |
| `SHORTAGE_RESOLVE_SUBSTITUTE_UNAVAILABLE` | 422 | The substitute named for the shortage is not available |
| `SHORTAGE_RESOLVE_INVALID_IDENTIFIER` | 422 | The shortage resolution names an identifier that does not resolve |
| `WORKORDER_NOT_RETURNABLE` | 422 | `submitReturnToStock` was called against a workorder whose status is not `COMPLETED` or `CLOSED` (CAP-218 Story #177) |
| `SHORTAGE_DERIVED_QUANTITY_NOT_POSITIVE` | 422 | The allocation's reservation was used to derive `shortQuantity` (both omitted from the request) and the result is not positive — nothing is actually short |
| `ADJUSTMENT_LEDGER_POST_FAILED` | 500 | Ledger post for adjustment failed unexpectedly; the adjustment is left `FAILED` with the cause in `errorMessage`, and approving it retries (#2170) |
| `SCRAP_LEDGER_POST_FAILED` | 500 | Ledger post for scrap failed unexpectedly; the scrap is left `FAILED` with the cause in `errorMessage`, and approving it retries (#2170) |
| `NOT_IMPLEMENTED` | 501 | The operation is deliberately unimplemented; enveloped rather than answered with an empty body (#1720) |
| `LOCATION_SERVICE_UNAVAILABLE` | 503 | pos-location could not be reached, or answered a server error, while a rollup read needed authoritative topology |

## Configuration

| Property                                            | Default  | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| --------------------------------------------------- | -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SPRING_DATASOURCE_URL`                             | required | PostgreSQL connection URL                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `EUREKA_SERVER_URL`                                 | required | Eureka service discovery URL                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `POS_INVENTORY_SUPPLIER_HINT_STALENESS_CEILING`     | `PT24H`  | Age past which a supplier hint reads as unknown                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `POS_INVENTORY_SUPPLIER_HINT_RESOLUTION_ENABLED`    | `false`  | Run the EAN resolution sweep against pos-catalog                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `POS_INVENTORY_SUPPLIER_HINT_RESOLUTION_BATCH_SIZE` | `200`    | Hints resolved per pass                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `POS_INVENTORY_SKU_CATEGORY_RESOLVE_FROM_REPLICA`   | `false`  | Resolve the `SkuCategoryProvider` SPI from the catalog replica. Off by default — enabling it makes the `SKU_CATEGORY` scope of `sku_cost_method_config` and of `sourcing_strategy_config` reachable, changing both costing method and sourcing strategy for matching SKUs. Audit first with `GET /v1/inventory/valuation/methods/sku-category-impact` (valid while the flag is off), then follow "SKU_CATEGORY costing and sourcing cut-over (#1535)" in `docs/OPERATIONS_RUNBOOK.md`. Putaway does not use this SPI. |
| `POS_INVENTORY_SKU_CATEGORY_IMPACT_SKU_CAP`         | `5000`   | Maximum products the SKU_CATEGORY impact report scans. Past this it sets `truncated: true` rather than silently shortening; raise it and re-run.                                                                                                                                                                                                                                                                                                                                                                      |

## Multitenancy (ADR-0062, WS3 wave 1)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`, every scoped entity
extends `TenantScopedEntity`, and the two global tables (`event_outbox`, `processed_events`, listed in
`src/main/resources/db/tenancy-global-tables.txt`) carry `@TenantGlobal`. The request tenant is bound
by `TenantContextFilter` from `X-Tenant-Id` (the gateway injects it from the token's `tid`), the Kafka
tenant by `TenantRecordInterceptor` from the `tenantId` record header, and every connection checkout
binds `app.current_tenant` for row-level security. `pos.tenancy.default-tenant-id` still binds the alpha
default tenant on every unbound path (tokens issued before `tid`, records without the header).

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, `FlywayConfig`).

Platform-scoped schedulers (run with no tenant bound; touch only global tables):

| Job                                    | Why                                                                                                                                                                                                        |
| -------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `OutboxPublisher.publishPending`       | Drains `event_outbox`; each row's `tenant_id` becomes the record header                                                                                                                                    |
| `ManifestPublisher.publishDueManifest` | Groups the window's `event_outbox` rows by `tenant_id` and publishes one manifest per tenant, stamped with that tenant; every active tenant of the registry gets one, zero-count when it published nothing |

Per-tenant schedulers run once per tenant of the registry (`TenantIterator.forEachActiveTenant`; the
static registry lists the default tenant until the `ext_tenant` replica lands per module): the cycle-count
schedule pass, the lot expiry scan, the replenishment scan, the supplier stock-hint resolution pass, and the
three report-only verifiers (stock summary drift, serial unit on-hand, allocation consistency), each of whose
passes opens its read-only transaction inside the tenant binding. The six native queries
(`AllocationRepository`, `InventoryStockSummaryRepository`) carry `@TenantAudited`: they read scoped tables
only, and row-level security binds their rows to the tenant.

Proof: `TenantIsolationIT` (tenant A's `replenishment_policy` row is invisible to tenant B and to an
unbound connection, through the repository and through raw SQL) and `TenancySchemaConformanceIT` (every
non-whitelisted table has `tenant_id`, RLS enabled and forced, and the `tenant_isolation` policy; the pool is
`pos_app` with no bypass), both on Testcontainers Postgres (`./mvnw -pl pos-inventory verify`).

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-tenancy-common` — ADR-0062 tenant context, connection binding, Hibernate resolver, Kafka propagation
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-shared-dtos` — shared DTOs
- `pos-bulk-ingest-lib` — bulk-ingest base controller

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-inventory -am spring-boot:run
```
