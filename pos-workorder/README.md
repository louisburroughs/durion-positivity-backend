# pos-workorder

Core workorder service for the Durion Positivity ETSMS platform. Manages the full workorder lifecycle from estimate creation through WIP execution, technician time tracking, parts usage, invoice generation, and completion. Integrates with the customer, vehicle, tax, and invoice services.

## Responsibilities

- Create and manage workorders with state machine transitions (estimate, WIP, complete, cancelled)
- Build estimates from appointments with line item services and parts
- Track WIP status and dashboard summaries per shop
- Assign, change and release the service position a workorder occupies — a bay, a mobile unit, or the site's hold (parking) position
- Assign, reassign and release the workorder's technician; a workorder has at most one current technician
- Record labor time entries and work sessions for payroll and billing
- Manage part usage, part substitutions, and part pick coordination
- Apply and validate promotional offers on workorder lines
- Generate invoices at completion by calling `pos-invoice`
- Calculate tax on estimate totals via `pos-tax`, resolving the estimate's shop-location address from `pos-location` as the tax jurisdiction
- Emit Kafka events for cross-service consumption (configurable; off by default)

## Key Classes

- `WorkorderService` — workorder lifecycle: create, update status, retrieve, cancel
- `EstimateService` — estimate creation, item management, and appointment conversion
- `WipService` — WIP board state and job-time totals
- `WorkorderLaborService` — labor line management on workorder service lines
- `WorkorderPartUsageService` — part consumption recording and adjustments
- `WorkorderInvoiceService` — invokes `pos-invoice` to generate an invoice at close
- `TechnicianAssignmentService` — assign, reassign and release the workorder's technician
- `ServicePositionService` — assign, change and release the service position a workorder occupies
- `DashboardService` — aggregated shop dashboard data (bays and mobile units, see below)
- `TaxClient` — outbound client for `pos-tax`; forwards `X-User: pos-workorder` and `X-Authorities: tax:calculate` on the tax-calculate call so the request satisfies `tax:calculate` enforcement (matching `pos-invoice`'s `TaxServiceClient`)

## API Endpoints

- `POST /v1/workorders` — create a workorder
- `GET /v1/workorders/{workorderId}` — retrieve a workorder
- `DELETE /v1/workorders/{workorderId}` — cancel a workorder
- `GET /v1/workorders/customer/{customerId}` — workorders for a customer
- `GET /v1/workorders/location/{locationId}` — workorders for a location
- `POST /v1/estimates` — create an estimate
- `GET /v1/estimates/{estimateId}` — retrieve an estimate
- `GET /v1/estimates/{estimateId}/summary` — estimate summary
- `DELETE /v1/estimates/{estimateId}/items/{itemId}` — remove an estimate line
- `GET /v1/workorders/wip` — WIP board summary
- `GET /v1/workorders/job-time-totals` — job time totals for WIP
- `GET /v1/workorders/pick-list` — pick list for parts
- `GET /v1/workorders/picked-items` — picked item status
- `POST /v1/workexec/time-tracking` — submit labor time entry
- `GET /v1/workexec/adjustments` — time entry adjustments
- `POST /v1/workorders/{workorderId}/notes` — record a note about the customer
- `GET /v1/workorders/{workorderId}/notes` — the workorder's customer notes
- `PUT /v1/workorders/{workorderId}/position` — assign or change the workorder's service position
- `DELETE /v1/workorders/{workorderId}/position` — release it, leaving the workorder unplaced
- `GET /v1/workorders/{workorderId}/position` — the current position and technician, with position history
- `POST /v1/workorders/{workorderId}/technician` — assign the first technician
- `PUT /v1/workorders/{workorderId}/technician` — reassign to a different technician, with a reason
- `DELETE /v1/workorders/{workorderId}/technician` — release the current technician

## Service position and technician assignment (#1983, #1984, #1985, #2001, #2010, #2011)

Where a workorder happens and who works on it are two **independent** assignments of the same
workorder. Each can be assigned, changed and released across the open lifecycle, each keeps its own
append-only history (who, when, why), and neither write touches the other's rows. `GET .../position`
answers both together. The pair does decide one thing jointly — the `ASSIGNED` status, below.

**One open workorder per position.** A `BAY` or a `MOBILE_UNIT` holds at most one open workorder;
a second one is refused with `409 RESOURCE_OCCUPIED`, whose `referenceId` names the occupying
workorder. `HOLD` — the site's parking lot — has no capacity limit, and an unset position is always
allowed. A parked workorder carries `resourceType = HOLD` with `resourceId` set to its own
`locationId`; that pair is what makes a hold site-scoped without a resource aggregate of its own, so
pos-location owns nothing new.

The rule is enforced **twice, on purpose**. `ServicePositionServiceImpl` checks occupancy so the
caller gets a 409 naming the occupant; the partial unique index `workorder_open_position_uniq`
(V3) decides two assigns that race that check, which no application-level check can — both read the
bay as free before either commits. The index's "open" predicate mirrors `Workorder.isLocked()`
exactly, reopened workorders included; the two must be changed together.

**One current technician per workorder.** `POST .../technician` now means *this workorder has no
technician yet* and answers `409 TECHNICIAN_ALREADY_ASSIGNED` (with the incumbent as `referenceId`)
when one is already assigned — it used to overwrite silently, which made an accidental double assign
indistinguishable from a deliberate hand-over. `PUT` (reassign) requires a current technician and
answers `409 TECHNICIAN_NOT_ASSIGNED` without one, rather than being quietly promoted to an assign.
`DELETE` releases without naming a replacement. The partial unique index
`technician_assignment_one_current_uniq` backs the rule the same way.

**A position must be active (#2001).** A `BAY` or a `MOBILE_UNIT` whose replica row is not active —
a bay out of service, a mobile unit that is not deployed — is refused with `422
SERVICE_POSITION_INACTIVE`, and the workorder's position is unchanged. The status is pos-location's,
carried on `location.bay.updated` / `location.mobile-unit.updated` into the `ext_bay` /
`ext_mobile_unit` replicas' `active` column; nothing read it before this, so the dispatch board could
show open work on a bay that was out of service. Its own code rather than `SERVICE_POSITION_INVALID`:
the position exists and is at the right site, so the refusal is about the resource, not the request.
A position whose replica row has not arrived yet is still the unknown-position `422
SERVICE_POSITION_INVALID`, unchanged. Going inactive while a workorder is already there releases
nothing: the job stays put and the dispatch board flags it. The inbound pos-shop-manager assignment
fact is not validated against the replicas, so it does not refuse — an inactive position is dropped
the way an occupied one is, and the location and mechanics are applied with the workorder left
unplaced.

**`ASSIGNED` means a technician *and* somewhere to work (#2010, #2011).** A workorder is `ASSIGNED`
when it has a current technician **and** stands on a `BAY` or a `MOBILE_UNIT`; a `HOLD` is a parking
space, not a place work happens, so it does not count. Either half missing is `APPROVED`. One method
decides it — `WorkorderStateMachine.reconcileAssigned(workorderId, actor, reason)` — and every
trigger calls it after its own write, inside its own transaction: technician assign, reassign and
release; position assign and release; the inbound pos-shop-manager assignment fact; and the
operational-context override. So assigning a technician to an unplaced workorder leaves it
`APPROVED`, placing it then makes it `ASSIGNED`, and either order gives the same answer. Releasing
the only technician, or the position, reverts it to `APPROVED` (#2010). A hand-over — reassigning the
technician, or moving bay to bay — keeps the pair complete and changes nothing.

`WorkorderStatus` carries the rule: `ASSIGNED → APPROVED` was added, `APPROVED → WORK_IN_PROGRESS`
was removed, and `getStartEligibleStatuses()` is `{ASSIGNED}`. **Work therefore starts only from
`ASSIGNED`**; starting an `APPROVED` workorder answers `409` naming what is missing — a technician, a
bay or mobile unit, or both. A workorder that has already started is past the question: no position
or technician change walks `WORK_IN_PROGRESS` or its sub-statuses back. Every revert goes through the
state machine, so it writes a status history row and publishes the `workorder.events.v1` status event
pos-shop-manager's `ext_workorder_replica` reads.

Neither timer nor labor eligibility was **edited** by #2011, but they are not affected equally, and
the difference matters:

- **Timers are unchanged.** `TIMER_ELIGIBLE_STATUSES` (`WorkexecTimeTrackingServiceImpl`) includes
  `APPROVED`, and still does. Narrowing it to `ASSIGNED` would take away clocking that works today,
  for a rule about when work may *start* rather than when time may be recorded. If clocking should
  require the pair too, that is its own change with its own story.
- **Labor sessions are tightened, as a consequence rather than an edit.**
  `WorkorderLaborServiceImpl.LABOR_ALLOWED_STATUSES` is `{ASSIGNED, WORK_IN_PROGRESS,
  AWAITING_PARTS, AWAITING_APPROVAL}` — it never included `APPROVED`. Because reaching `ASSIGNED`
  now needs a bay or mobile unit as well as a technician, a workorder that would once have been
  `ASSIGNED` on a technician alone can no longer start a labor session until it is placed. That
  follows the same principle as the start gate — labor is work, and work needs somewhere to happen —
  so it is left standing rather than papered over by adding `APPROVED` to the set.

`AssignedInvariantMigrationService` carries pre-existing rows over: at startup, once per tenant, every
`ASSIGNED` workorder lacking either half is transitioned to `APPROVED` through the state machine, so
each correction leaves a history row (actor `system`) and publishes its event. It is idempotent — a
corrected workorder no longer matches — and disabled with
`pos.workorder.assigned-invariant-migration.enabled=false`.

**Closing frees the position.** A transition to `COMPLETED` or `CANCELLED` releases the position
from inside `WorkorderStateMachine.transitionWorkorder` — the single funnel every status change goes
through — closing the history row and clearing `resourceId`. Clearing matters: the index reads
`is_reopened`, so a closed workorder that kept its bay would re-enter the index on reopen and
collide with whoever took the bay meanwhile.

**Permissions.** Position assign and release carry `workorder:position:assign`; the read reuses
`workorder:workorder:view`. The two write endpoints share one code deliberately — freeing a bay is
the same decision as filling one — and assign and release are gated on it in both directions.

They reused `workorder:operationalContext:override` until #2059, on the argument that deciding where
a job happens is one authority. The consequence was that the everyday dispatcher persona could hand
a job to a technician on the board but could not put it in a bay without also holding the grant that
rewrites a workorder's mechanics and location. #2059 settled that as Option B: the placement code is
minted separately and seeded to the roles expected to place work (ADMIN, DISPATCHER,
LOCATION_MANAGER, SHOP_MANAGER), while `POST /v1/workorders/{id}/operationalContext/override` keeps
`workorder:operationalContext:override` to itself. That endpoint remains the manager exception path
and routes its position change through the same service, so it gets occupancy enforcement and a
history row, while keeping override semantics: the position is not re-validated against the location
replicas, and it needs no placement grant of its own.

## Estimate/workorder snapshot facts (order parity E1)

`WorkorderFactPublisher` snapshots now carry `declined` and the new explicit `returnable`
flag per part line (resolved order-spec Q6 — set at settlement time, never inferred). A new
`EstimateFactPublisher` emits `workorder.estimate.updated` snapshots (header + full item set
with approval status) on every estimate mutation, feeding pos-order's source-document import
replicas. Both are gated by `workorder.kafka.enabled`.

## Estimated labor hours and guide-time defaulting (#1569)

A LABOR estimate item naming a `serviceId` asks the catalog labor guide for its book time via
`CatalogLaborTimeClientImpl` — the module's one granted synchronous edge to pos-catalog
(ADR-0044 amendment 2026-09-02, file-scoped). The guide answer is always snapshotted onto the
line (`guide_hours` + source/revision/match-grade/overlap metadata) and becomes the `quantity`
only when the writer omitted it: a prefill, never a lock, and `quantity` remains the agreed
hours. The vehicle key comes from `VehicleReferenceService` (CRM year/make/model, fail-soft).
When the edge cannot answer, the `ext_catalog_service` replica's vehicle-agnostic
`default_labor_hours` (fed by `catalog.service.updated` schema v2) prefills instead; failing
that the writer types the hours — estimating never blocks on a guide. Promotion carries the
snapshot onto `workorder_service`, and `EstimatedLaborService` computes the overlap-aware
`estimatedLaborHours` (included operations contribute zero; overlap-group lines contribute
max + `pos.workorder.labor.overlap-additional-factor` × the rest) for the dashboard summary
and the detail response, which also exposes `actualLaborHours` and the labor variance.

## Customer notes on a workorder (#1584)

A note about the customer — something they said while the job was open, not a note about the work —
is recorded through `POST /v1/workorders/{workorderId}/notes` and stored in `workorder_note`, which
this module owns. `WorkorderNoteServiceImpl` publishes `workorder.note.added.v1` to the
transactional outbox in the same transaction, so the note and its fact commit together;
pos-customer projects it onto the party's CRM timeline. Gated by `workorder.kafka.enabled` like the
other fact publishers: with Kafka off the note is still saved, it just is not published.

This is distinct from `workorder.completion_notes`, `workorder.approval_notes`, and
`change_request.approval_note`, which describe the work or a decision about it and are
single-valued.

## Part quantity divisibility (ADR-0055)

A part quantity must be a whole number unless the product it references declares otherwise. The
declaration is `product_uom.precision_scale` on the product's `BASE` row, owned by `pos-catalog`
and replicated here as `ext_product_uom` from `catalog.product.updated` facts (ADR-0044 §6). Scale
`0` — and equally, a product with no unit-of-measure rows, which is every product until seeding
lands — means whole units; a non-zero scale permits that many decimal places.

Enforced at estimate-item creation and update, at estimate-to-workorder promotion, and again on the
issue, consume, return and quantity-correction paths. Parts carrying no `productEntityId` (labour,
shop supplies, non-stocked consumables) are exempt and stay fractional. A violation returns HTTP
422 with `code: FRACTIONAL_QUANTITY_NOT_ALLOWED`, a `quantity` field error, and a `nextAction`
naming the quantity to enter instead.

### Unit of measure on part lines (ADR-0055 stage 3)

`estimate_item` and `workorder_part` carry a nullable `uom_code` column: the unit the line's
quantity is expressed in. **Null means the product's base unit** — today's implicit assumption,
and the default for every row that predates this column. `uomCode` is optional on
`AddEstimateItemRequest`, `UpdateEstimateItemRequest`, `IssuePartRequest`, `ConsumePartRequest`,
`ReturnPartRequest` and `CorrectPartQuantityRequest`. It is snapshotted from the estimate item onto
the promoted `workorder_part` the same way `quantity` itself is snapshotted.

**LABOR rows always carry a null `uomCode`.** `estimate_item.quantity` is shared between PART and
LABOR, but hours are not a catalog unit of measure and have no `product_uom` conversion row to
convert from. A non-null `uomCode` on a LABOR row is rejected with HTTP 400 at both add and update
time — checked in the service layer and enforced by a database check constraint
(`ck_estimate_item_labor_uom_null`) so it cannot be bypassed by a write that skips it.

When a line's `uomCode` differs from the product's base unit, the quantity is converted to base
via the `ext_product_uom` replica's `factor_to_base` — unrounded, so the divisibility check above
sees the true converted value rather than one silently rounded to fit — before the existing
`precision_scale` gate runs. A `uomCode` with no conversion row for the product returns HTTP 422
with `code: UOM_CONVERSION_UNDEFINED`, never a silent 1:1 assumption.

Issuing a part sends `uomCode` through unconverted on the `inventory.reservation.request-requested`
Kafka command; pos-inventory owns the actual document-to-base conversion for the reservation, using
`DOWN` rounding so it never promises more than exists — the same pattern purchase-order, ASN,
receiving and return lines already use via `DocumentQuantityConverter`.

**Read-side display (ADR-0055 stage 4, #1416):** `WorkorderPartResponse.unitOfMeasure` echoes the
line's own `uomCode` verbatim (null means the product's base unit) — no conversion, no catalog
lookup, just the same value the line was keyed in.

## Dispatch board: bays and mobile units (#1656)

`GET /v1/workexec/dashboard/today` returns `bays[]` **and** `mobileUnits[]`. They are separate
arrays because pos-location owns bays and mobile units as separate aggregates with separate
identity and lifecycle; `MobileUnitStatus` mirrors `BayStatus` field-for-field
(`unitId`/`unitName` in place of `bayId`/`bayName`) so the board renders both panels the same way.

`Workorder.resource_type` (`BAY` | `MOBILE_UNIT`, added by V27) is what tells the two apart. It
rides the assignment chain `AssignmentUpdatePayload` → `AssignmentUpdatedEvent` → `Workorder`.
The field is **optional inbound**: pos-shop-manager does not publish it yet, and an assignment that
arrives without it is applied as `BAY` — the meaning every assignment had before mobile units were
representable. V27 backfills existing assigned rows the same way. `AssignmentUpdatedEvent`
`resolveResourceType()` is the single place that fallback happens.

Inbound binding of `resourceType` is **lenient** (`ResourceType.fromJson`): any casing is accepted,
and an unrecognised token is logged by name and then treated as absent, so it lands on the same
`BAY` fallback. The producer is upstream and the value shares a payload with the location, the
resource id and the mechanics; strict enum binding would let one bad token throw out of
`KafkaCommandListener`'s log-and-swallow catch and discard the entire assignment update silently.

The same id+type pair is written together by **both** write paths — the assignment event and
`POST /v1/workorders/{id}/operationalContext/override` — so a bay-to-mobile-unit move can never
half-apply. The override request therefore carries `resourceType` (optional, `BAY` when absent) in
place of the former free-text `bayId`, which was echoed back but never persisted.

The read models name the resource type-neutrally: `WorkorderSummary.assignedResourceId` +
`resourceType` (was `assignedBayId`) and `OperationalContextResponse.resourceId` + `resourceType`
(was `bayId`). The bay-named keys are gone rather than deprecated — they carried mobile-unit ids
that joined to nothing in `bays[]`.

Resource identity comes from the `ext_bay` and `ext_mobile_unit` replicas (V28), fed by
`location.bay.*` / `location.mobile-unit.*` facts on `location.events.v1` per ADR-0044 §6 — no
synchronous call into pos-location and no cross-schema read. This is why `BayStatus.bayName` is
populated at all: it was declared-but-always-null until a replica existed to resolve it from.

A replica row's `active` flag is **derived from the owner's `status`**, allow-listing `ACTIVE`
(any casing); anything else, including an absent or unseen value, is not active. pos-location's
`BayEntity` and `MobileUnitEntity` have no boolean active field at all — a bay's status is
`ACTIVE` | `OUT_OF_SERVICE` and a mobile unit's is a free-text column whose in-use values are
`ACTIVE` | `INACTIVE` — so a deny-list would put an undispatchable unit on the board, and a
consumer-invented `active` boolean would deserialize to `false` on every real event and leave both
panels permanently empty.

Both panels list **every active unit at the location** (bays by `location_id`, units by
`base_location_id`), including units holding no work, which report `assignedWorkorderId: null`.
A unit reads as occupied while **any** still-open workorder holds it, which is why occupancy comes
from `WorkorderRepository.findOpenResourceHoldersAtLocation` rather than from the day's rows: a
multi-day job scheduled on an earlier date is still in its bay today, and a panel that positively
asserts `AVAILABLE` cannot answer that from one date's rows. Work scheduled *after* the requested
date is excluded — it is booked, not occupying. `Workorder.isLocked()` is the sole open/closed
authority (`CANCELLED`, or `COMPLETED` and not reopened), so a reopened completed workorder keeps
its resource rather than being wrongly released.

Conflict detection uses the same two rules, so the panels and `conflicts[]` cannot contradict each
other: double-booking is grouped by resource id **and** type, locked workorders are excluded, and
the conflict is reported as `BAY_DOUBLE_BOOKED` or `MOBILE_UNIT_DOUBLE_BOOKED` with a message
naming the right kind of unit.

### The roster: today's schedule plus carryover (#2002)

`workorders[]` is **not** "rows whose `scheduledDate` is the requested date". It is that set unioned
with the open work still holding a bay or mobile unit at the location on or before that date —
the same `findOpenResourceHoldersAtLocation` result the panels use — deduplicated by workorder id.
Two consequences, both intended:

- A multi-day job appears on the board every day it occupies its resource, not only on the day it
  was booked. Selecting on the date alone made the board contradict itself: `bays[]` reported the
  bay `OCCUPIED` by a `workorderId` that appeared nowhere in `workorders[]`.
- The roster is by construction a superset of every workorder the two resource panels name as an
  occupant. A locked workorder still carrying a stale resource id is excluded from the carryover
  half exactly as it is from the panels (`Workorder.isLocked()`, the one authority). The day's own
  rows are not filtered: a workorder completed this morning stays on today's board as completed
  work.

Mechanic, status, location and skill conflicts are detected over this roster, so a mechanic put on
a new job while still owning yesterday's unfinished one is reported as double-booked.

### Taking a position schedules the workorder (#2002)

`Workorder.ensureScheduledForPosition` gives a workorder today's date when it takes an **exclusive**
position (`BAY` or `MOBILE_UNIT`) with `scheduledDate` still null. It is applied in
`ServicePositionServiceImpl.recordPositionChange`, which every write path that can place a workorder
already funnels through — the assignment endpoints, the inbound `AssignmentUpdated` fact, and
`operationalContext/override` — and it runs ahead of that method's unchanged-placement
short-circuit, so an inbound fact that merely re-asserts a position a workorder already holds still
repairs a missing date.

`HOLD` is exempt: the site parking lot is not dispatch work, and a vehicle can wait there for a date
nobody has set yet. An existing date is never rewritten, a past one included — a job that started on
Monday is genuinely Monday's work, and the roster rule above is what puts it on Wednesday's board.

This is also the module's **only** writer of `scheduledDate`. No endpoint sets it directly and the
inbound assignment fact does not carry it, which is why alpha was found in exactly the state the
invariant prevents: fourteen non-terminal workorders, none dated, and an empty board at every
repair-capable location. Repairing rows that predate the invariant therefore splits in two —
placements go through `PUT /v1/workorders/{workorderId}/position`, which now dates them as a side
effect and publishes the fact; the unplaced open work the roster also needs has no supported writer
and is dated by `docs/sql/2002-alpha-schedule-dashboard-workorders.sql`, which documents what that
costs (no fact, so consumer replicas converge on the next real mutation).

Also worth knowing when reading `findOpenResourceHoldersAtLocation`: it asks only for a non-null
`resource_id`, and a parked workorder has one — `(HOLD, its own locationId)`. The roster filters
those out of the carryover half, because no panel renders a hold. A workorder parked *today* still
reaches the board through the day's schedule.

Two edge behaviours are deliberate and live in `DashboardServiceImpl.buildResourcePanel`:

- **Unknown or inactive resource still holding open work** — the row is rendered anyway (name from
  the replica if the row exists, otherwise null). Hiding it would make live work invisible on the
  board. The lifecycle question "may a decommissioned unit hold open work at all?" belongs to
  pos-location and is an open follow-up.
- **Replica lag** — when an assignment fact overtakes the resource's own fact, the row appears with
  its id and a null name rather than being dropped.

Upstream dependency: pos-location publishes bay and mobile-unit facts as of #1668 —
`location.bay.updated` / `location.bay.deleted` and `location.mobile-unit.updated` /
`location.mobile-unit.deleted` on `location.events.v1`, with the canonical records in
`pos-domain-events` (`com.positivity.domainevents.location`). The replicas still start empty and
stay that way for any bay or mobile unit created before #1668 that has not been touched since: the
facts are forward-only, and outbox replay cannot reach those rows because they have no outbox
history. pos-location's `location.fact-backfill.requested` command regenerates them from current
state (see `pos-location/README.md` and `docs/OPERATIONS_RUNBOOK.md`). The consumer tolerates an
empty or partial replica by design — the panels render what the replica holds and converge as
facts arrive.

## Published workorder fact: assignment block (#1658)

`workorder.workorder.updated` on `workorder.events.v1` (payload `WorkorderUpdatedV1` in
`pos-domain-events`, emitted by `WorkorderFactPublisher`) now carries an assignment block alongside
the existing snapshot: `locationId`, `resourceId`, `resourceType`, `mechanicIds`, `promisedAt` and
`scheduledDate`. It is **additive within schema v1** (ADR-0044 §3) — a pre-#1658 arity constructor
is retained, and consumers that only read the older fields are unaffected.

The block exists so pos-shop-manager's shop dashboard can render every bay and mobile unit at a
location from a local `ext_workorder` replica. Without it a consumer learns that a workorder changed
but not what it occupies or who is on it, and would have to call back into this module
synchronously — which ADR-0044 R1 forbids.

Two details worth knowing:

- `mechanicIds` is a **list**. The owner stores a JSON array and a job may carry more than one
  technician, so a scalar would silently drop assignments. A malformed or non-UUID entry is dropped
  with a warning rather than failing the commit: the fact is assembled at `beforeCommit`, so
  throwing there would roll back the business transaction that produced it.
- `promisedAt` is **null in every fact published today**. `Workorder` has no promise-time field; the
  slot is declared so the contract does not have to change when it grows one.

No endpoint, status semantics or transition changed — this is payload only.

## Published workorder fact: actual-time block (#2021)

`WorkorderUpdatedV1` also carries `workStartedAt`, `completedAt` and `expectedEndAt`, additive within
schema v1. They let pos-shop-manager's dispatch board show the promise against the reality: the
appointment's own `startAt`/`endAt` stay the *planned* window, and these carry the *actual* one —
`workStartedAt` from `startWork`, `completedAt` from the completion state machine transition, both
null until they happen. `expectedEndAt` is declared but **never populated**: a projected finish for a
running job needs estimated remaining labour (ADR-0058/ADR-0059, both PROPOSED and not built), and a
value guessed from `now()` would be indistinguishable from a known one to a consumer — do not
synthesise it.

### Backfilling workorders that started before this fact existed (#2021 AC8)

`workorder.outbox.replay-requested` **cannot** seed `workStartedAt`/`completedAt` on a replica for a
workorder that started before this fact shipped: replay only re-queues rows already in
`event_outbox`, and those rows' stored JSON predates the fields — replaying them re-sends the same
gap.

Use the regenerate-from-state command on `workorder.commands.v1` instead:

```json
{"commandType": "workorder.fact-backfill.requested", "payload": {}}
```

The selection is scoped to workorders that actually need it — a non-null `workStartedAt` and/or
`completedAt` — so a run does not have to walk every workorder in the module to reach the ones that
do. It pages through the owner's table (`pos.workorder.fact-backfill.page-size`, default 500), one
transaction per page, and is idempotent: a replica applies an equal version and skips only a
strictly-greater one, so re-running repairs a stale replica without duplicating rows. Because the
backfill only reads a row and asks the publisher to snapshot it — it never dirties the row — the fact
it re-emits carries the **same** `aggregateVersion` the row already published at; that is intended,
not a bug, since the replica's stale guard is strictly-below.

A run is **bounded** at `pos.workorder.fact-backfill.max-rows-per-run` (default 20000) and resumable,
for the same reason as `pos-location`'s equivalent command (#1668): it executes on the Kafka
command-listener thread shared with `workorder.outbox.replay-requested`, so an unbounded walk risks
exceeding `max.poll.interval.ms`; an evicted consumer never commits its offset, so the command would
be redelivered and the whole backfill would restart in a loop. When a run hits the bound it logs a
WARN naming the cursor to resume from — re-send the command with `payload.afterId` set to that value.

Paging is **keyset** (`id > afterId`), not offset, for the same reason as `pos-location`'s: deleting a
row below an offset shifts every later row back one position, which would skip a surviving row an
offset page would otherwise have reached.

Tenant-scoped per ADR-0062 §3, mirroring `POST /v1/outbox/replay`: an ordinary tenant's run covers
only its own rows, and a platform-tenant operator — who owns no workorder rows — fans out over every
active tenant in turn, each from the beginning of its own bounded run (a cursor cannot span tenants).

## Location scope (ADR-0061, #1871/#1872)

Location-scoped permissions are enforced on top of `@PreAuthorize` using the caller's
`LocationScope` (decoded from the gateway's `X-Loc-Fin-Bits`, `X-Loc-Oth-Bits` and `X-Loc-Scope`
headers). Tokens without those claims are unscoped and behave exactly as before. Ancestor sets
come from this module's own `ext_location` replica via `LocationHierarchyService`, which is the
module's `LocationAncestorResolver`; there is no per-request call to pos-location. A denial is a
403 `ApiError` with code `LOCATION_SCOPE_DENIED`. The full per-operation record CI checks is
`location-scope.yaml` in this module's root.

**Gate** — the location names the site read or acted on; outside the caller's reach is a 403:

| Operation | Permission | Where |
| --- | --- | --- |
| `listWipWorkorders` (`multiLocation=false`) | `workorder:wip:view` | controller, after UUID validation |
| `getWipDetail` | `workorder:wip:view` | controller, off the workorder's shop, after the 404 |
| `getDispatchDashboard` | `workorder:dashboard:view` | controller, after UUID validation |
| `getApplicableApprovalConfiguration` (when `locationId` supplied) | `workorder:approval_config:view` | controller; absent `locationId` resolves the location-less global default |
| `listEstimatesByShop`, `listEstimatesByLocation` | `workorder:estimate:view` | controller |
| `getEstimate`, `getEstimateSummary`, `generateEstimatePdf` | `workorder:estimate:view` | controller, off the loaded estimate's location, after the 404 |
| `createEstimateFromAppointment` | `workorder:estimate:create` | controller, on the body's `locationId` |
| `overrideOperationalContext` | `workorder:operationalContext:override` | `WorkorderServiceImpl`, after the 404 and before any write: first the workorder's current `shopId` (null fails closed), then the body's `locationId` |
| `assignServicePosition`, `releaseServicePosition` | `workorder:position:assign` | `ServicePositionServiceImpl`, after the 404 and before any write, on the workorder's `shopId` (null fails closed). The target position is at the workorder's own site by construction, so there is no second location to check |
| `startWorkexecWorkSession` | `timekeeping:work_session:create` | controller, on the body's `locationId` |

**Narrow** — the location is an optional filter; a supplied one is gated, and without one a scoped
caller sees only the locations within reach (an empty reach is an empty result, never a 403 and
never everything). Reach is expanded once per request by `LocationHierarchyService.reachableLocations`
(inclusive descendants of each assigned node on each scoped dimension):

| Operation | Permission | Where |
| --- | --- | --- |
| `listWipWorkorders` (`multiLocation=true`) | `workorder:wip:view_all_locations` | controller; a location-scoped holder is narrowed to that grant's reach, an unscoped holder sees every location |
| `listEstimates` (unfiltered) | `workorder:estimate:view` | controller, passing the reach to `EstimateService.getEstimatesAtLocations` |
| `getJobTimeTotals` | `workorder:labor:view` | controller, passing the reach to `WorkexecTimeTrackingService` |
| `listLaborIntelligence` | `workorder:labor_intelligence:view` | controller, passing the reach to `LaborIntelligenceService`; `ROLE_ADMIN` without the permission is unrestricted |

## Error codes

Every non-2xx response carries the platform `ApiError` envelope. Field semantics, payload examples,
and the platform-wide fallback codes emitted by `pos-web-common` and `pos-security-common` are in
[`durion/docs/architecture/api/ERROR_ENVELOPE.md`](../../durion/docs/architecture/api/ERROR_ENVELOPE.md).
The table below is this module's own codes; any endpoint here may additionally return a platform
fallback code. Add a row in the same pull request as the controller or advice that mints the code.

| Code | Status | Description |
|------|--------|-------------|
| `NOT_FOUND` | 404 | Workorder does not exist (also used generically by a few older endpoints) |
| `ESTIMATE_NOT_FOUND` | 404 | Estimate does not exist |
| `ESTIMATE_ITEM_NOT_FOUND` | 404 | Line item does not exist on the named estimate |
| `CHANGE_REQUEST_NOT_FOUND` | 404 | Change request does not exist |
| `SERVICE_LINE_NOT_FOUND` | 404 | Workorder service line does not exist, or does not belong to the named workorder |
| `PART_NOT_FOUND` | 404 | Workorder part line does not exist, or does not belong to the named workorder |
| `APPROVAL_CONFIGURATION_NOT_FOUND` | 404 | Approval configuration does not exist |
| `WORK_SESSION_NOT_FOUND` | 404 | Work session does not exist |
| `BREAK_SEGMENT_NOT_FOUND` | 404 | Break segment does not exist |
| `TRAVEL_SEGMENT_NOT_FOUND` | 404 | Travel segment does not exist |
| `SUBSTITUTE_LINK_NOT_FOUND` | 404 | Part-substitution link does not exist |
| `INVALID_ARGUMENT` | 400 | Field-level or request-shape validation failure (`WorkorderRequestValidationException`) |
| `VALIDATION_FAILED` | 400 | Bean-validation failure, with `fieldErrors` |
| `CONFLICT` | 409 | Generic stateful collision: invalid lifecycle transition, or a caller-supplied id that does not match the resource it targets, or an operation that would exceed a quantity the resource's current state actually has available (`WorkorderResourceConflictException`, `IllegalStateException`) |
| `TRAVEL_SEGMENT_CONFLICT` | 409 | An active travel segment already exists for the assignment |
| `DUPLICATE_SUBSTITUTE_LINK` | 409 | A substitute-part link already exists for this pair |
| `STALE_SUBSTITUTE_LINK_VERSION` | 409 | Optimistic-lock version mismatch on a substitute link |
| `CUSTOMER_APPROVAL_INVALID` | 409 | Workorder claims an approval its own state does not back |
| `INSUFFICIENT_PART_AVAILABILITY` | 409 | Requested part quantity exceeds current owned stock (guided, with `nextAction`) |
| `PURCHASE_ORDER_REQUIRED` | 422 | Commercial customer's billing rules require a purchase order that was not supplied |
| `ESTIMATE_INCOMPLETE` | 422 | A DRAFT estimate was submitted for approval with no customer, no vehicle, no line items, or uncalculated totals (`EstimateIncompleteException`) |
| `FRACTIONAL_QUANTITY_NOT_ALLOWED` | 422 | Quantity is not a whole number for a product the catalog declares indivisible |
| `UOM_CONVERSION_UNDEFINED` | 422 | `uomCode` names no conversion row for the referenced product |
| `PROMOTION_IDEMPOTENCY_INCONSISTENT` | 500 | A recorded promotion idempotency key resolves to no workorder (server defect, correlated) |
| Dynamic `PromotionErrorCode` values | 404/409 | Estimate-to-workorder promotion precondition failures; see `PromotionValidationException` |
| Dynamic `CustomerRequirementsNotMetException` codes | 409/503 | Customer-requirements verdict blocked workorder creation; see that exception |
| `FORBIDDEN` | 403 | Caller lacks required workorder permissions |

## Configuration

| Property                       | Default                    | Description                      |
| ------------------------------ | -------------------------- | -------------------------------- |
| `SPRING_DATASOURCE_URL`        | required                   | PostgreSQL connection URL        |
| `EUREKA_SERVER_URL`            | required                   | Eureka service discovery URL     |
| `pos.customer.base-url`        | `http://pos-customer:8084` | Customer service URL             |
| `pos.vehicle.base-url`         | `http://pos-vehicle:8088`  | Vehicle service URL              |
| `pos.tax.base-url`             | `http://pos-tax:8091`      | Tax service URL                  |
| `pos.location.base-url`        | `http://pos-location:8080` | Location service URL             |
| `workorder.kafka.enabled`      | `false`                    | Enable Kafka event emission      |
| `workorder.kafka.events-topic` | `workorder.events.v1`      | Kafka topic for workorder events |
| `workorder.kafka.catalog-events-topic` | `catalog.events.v1` | Catalog fact topic feeding the `ext_product_uom` replica |
| `workorder.kafka.catalog-events-consumer-group` | `pos-workorder-catalog-events` | Consumer group for the catalog fact topic |
| `workorder.kafka.location-events-topic` | `location.events.v1` | Location fact topic feeding the `ext_location`, `ext_bay` and `ext_mobile_unit` replicas |
| `workorder.kafka.location-events-consumer-group` | `pos-workorder-location-events` | Consumer group for the location fact topic |
| `pos.workorder.fact-backfill.page-size` | `500` | Rows per transaction when backfilling actual-time workorder facts |
| `pos.workorder.fact-backfill.max-rows-per-run` | `20000` | Rows per backfill command before it stops and reports a resume cursor |

## Multitenancy (ADR-0062, WS3 wave 3)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`, every scoped entity
extends `TenantScopedEntity`, and the two global tables (`event_outbox`, `processed_events`, listed in
`src/main/resources/db/tenancy-global-tables.txt`) carry `@TenantGlobal`. The request tenant is bound
by `TenantContextFilter` from `X-Tenant-Id` (the gateway injects it from the token's `tid`), the Kafka
tenant by `TenantRecordInterceptor` from the `tenantId` record header on every one of the module's
consumers, and every connection checkout binds `app.current_tenant` for row-level security.
`pos.tenancy.default-tenant-id` still binds the alpha default tenant on every unbound path (tokens
issued before `tid`, records without the header).

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, `FlywayConfig`).

The outbox row carries the producing tenant as data (`tenant_id`, stamped from the bound tenant by
`OutboxEventWriter`, which every fact publisher and `KafkaEventRelay` write through):

| Job | Classification | Why |
| --- | --- | --- |
| `OutboxPublisher.publishPending` | platform-scoped | Drains `event_outbox`; each row's `tenant_id` becomes the record header |
| `ManifestPublisher.publishDueManifest` | platform-scoped | Groups the window's `event_outbox` rows by `tenant_id` and publishes one manifest per tenant, stamped with that tenant; every active tenant of the registry gets one, zero-count when it published nothing |
| `OutboxPurgeJob.purge` | platform-scoped | Deletes published `event_outbox` rows across tenants |
| `ApprovalExpirationJob.expirePendingApprovals` | per-tenant | `estimate` is scoped; one sweep per tenant of the registry, the service opening its own transaction inside the binding |
| `FleetAuthorizationResourceReleaseRunner.releaseOverdue` | per-tenant | `workorder_fleet_authorization` is scoped; one sweep per tenant, each release in its own transaction |

Per-tenant sweeps iterate the static registry (`TenantIterator.forEachActiveTenant`; the default tenant
until the `ext_tenant` replica lands per module). The module has no native queries.

Proof: `TenantIsolationIT` (tenant A's `approval_configuration` row is invisible to tenant B and to an
unbound connection, through the repository and through raw SQL) and `TenancySchemaConformanceIT` (every
non-whitelisted table has `tenant_id`, RLS enabled and forced, and the `tenant_isolation` policy; the pool is
`pos_app` with no bypass), both on Testcontainers Postgres (`./mvnw -pl pos-workorder -am verify`), next to
the existing `FlywayMigrationIT` on the same strict `pg` profile.

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-tenancy-common` — ADR-0062 tenant context, connection binding, Hibernate resolver, Kafka propagation
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-shared-dtos` — invoice generation request DTOs
- `pos-tax-common` — tax calculation request/response types

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`: `V1__baseline_workorder.sql` (the
2026-09-09 flattened baseline with the tenancy schema on every scoped table) and `V2__event_outbox_tenant_id.sql`
(`tenant_id` as data on the global outbox table, see Multitenancy below).

## Development

```bash
./mvnw -pl pos-workorder -am spring-boot:run
```
