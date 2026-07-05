# Frontend Site-Audit — Backend Route Reconciliation (Issue #813)

Backend answers to the API failures raised by the frontend Playwright site audit
(frontend PR louisburroughs/durion-positivity-frontend#144, triage doc
`docs/testing/frontend-audit-api-error-triage.md` in the frontend repo).

Path convention reminder: the gateway maps external
`/api/{domain}/v1/{domain}/{resource}` → service-internal `/v1/{domain}/{resource}`.
All paths below are given in the **service-internal** form; prepend `/api/{domain}`
(and send `X-API-Version: 1`) for the external call.

## 1. `GET /v1/accounting/posting-rules` 500 — FIXED

Root cause: `PostingRuleServiceImpl.listRuleSetsAsResponse` passed the raw `sort`
request parameter straight into `Sort.by(Direction.DESC, sort)`. Two failures
compounded:

1. The SDK sends Spring-style `sort=modifiedAt,desc`; the whole string
   (`"modifiedAt,desc"`) was treated as one JPA property path.
2. Even the bare field name `modifiedAt` does not exist on the `PostingRuleSet`
   entity — the Java property is `updatedAt` (column `modified_at`); only the
   *response DTO* exposes it as `modifiedAt`.

Both raised `PropertyReferenceException` → unhandled → 500.

Fix (`pos-accounting`): a shared `SortParamParser` now parses
`property[,direction]`, maps API field names to entity properties
(`modifiedAt` → `updatedAt`), and rejects unknown properties/directions with
`UnsupportedSortPropertyException`, which the module's advice maps to a **400**
`ApiError` (`UNSUPPORTED_SORT_PROPERTY`) instead of a 500.

Posting-rules sort fields: `createdAt`, `modifiedAt`, `updatedAt`, `name`,
`eventType`, `description`, `createdBy`, `modifiedBy`, `postingRuleSetId`.
Direction defaults to `desc` when omitted.

The identical bug existed on two sibling endpoints and is fixed the same way:

- `GET /v1/accounting/journal-entries` — sort fields `createdAt`, `modifiedAt`,
  `updatedAt`, `transactionDate`, `postedAt`, `status`, `entryType`,
  `description`, `createdBy`, `journalEntryId`; direction defaults to `desc`.
- `GET /v1/accounting/gl-accounts` — sort fields `accountCode`, `accountName`,
  `accountType`, `description`, `activationDate`, `deactivationDate`,
  `createdAt`, `modifiedAt`, `updatedAt`, `glAccountId`; direction defaults to
  `asc` (matching its previous behavior).

## 2. pos-people endpoints — both implemented; 404s are semantic

### `GET /v1/people/me/primary-location`

Implemented (`PeopleAvailabilityController`). **404 is the expected "no primary
location" answer**, not a routing gap. The service resolves the caller's active
`EmployeeLocationAssignment` for today **flagged as primary** and returns 404
(`ProblemDetail`) when:

- the user has no active assignment flagged primary for the date
  (`"No primary location assignment exists for requester on <date>"`), or
- no user→person link exists (`"No person link found for username: …"`).

(Previously the endpoint fell back to any active assignment even when none was
flagged primary; it now strictly returns 404 when the primary location is null.
The availability fallback below still accepts any active assignment.)

So that existing flows keep resolving a location under the stricter rule
(pos-workorder derives estimate/workorder locations from this endpoint):

- Creating a staffing assignment now **defaults to primary** when the person has
  no active primary assignment — a person's first location is their primary.
- Migration `V3__backfill_primary_location_assignments.sql` promotes the single
  active assignment of any employee who has exactly one and none flagged
  primary. Employees with several active assignments and no primary still 404
  (genuinely ambiguous; fix via the staffing assignment API).

The frontend's graceful degradation to a location picker is the right behavior;
treat this 404 as a normal domain outcome and exclude it from audit error counts.

Note: both endpoints require the `people:availability:view` authority, which no
standard role grants by default — a missing grant surfaces as 403, not 404.

### `GET /v1/people/availability?locationId=&date=`

Implemented and deployed at exactly that path (`PeopleAvailabilityController`,
`@GetMapping("/availability")`). Both params optional; `date` is ISO `yyyy-MM-dd`;
omitting `locationId` falls back to the caller's active assignment and can itself
404 with the same semantics as above. A 404 seen by the audit on this route with
an **empty `locationId`** is therefore the requester-has-no-assignment case, not a
missing route.

## 3. pos-inventory endpoint confirmations

| Frontend path (internal form) | Status | Canonical route |
|---|---|---|
| `GET /v1/inventory/cycleCountPlans` (LIST) | ✅ **Added** | Collection LIST now implemented: `GET /v1/inventory/cycleCountPlans` with optional `locationId` and `status` (`PLANNED`, …) filters, newest first, paged via `page`/`size` (default size 50; response stays a plain array). Requires `inventory:cycle_count:view`. The SDK should add a matching `listPlans` operation. |
| `GET /v1/inventory/putaway/tasks` | ✅ Exists | Optional params `locationId`, `storageLocationId`. |
| `GET /v1/inventory/replenishment/tasks` | ✅ Exists | No params. |
| `GET /v1/inventory/locations` | ✅ Exists | `InventoryReferenceDataController`. Also `GET /v1/inventory/storage-locations`, `GET /v1/inventory/location-zones`. |
| `GET …/locations/sync-logs` | **Does not exist** (whole repo) | No backend equivalent; frontend should remove or file a feature request. |
| `GET …/meta/storage-types` | **Does not exist** (whole repo) | Closest is `GET /v1/inventory/storage-locations`. |
| `POST …/locations/sync` | **Does not exist** (whole repo) | No backend equivalent. |

## 4. Divergent-route reconciliation (canonical paths)

| Frontend/SDK ambiguity | Canonical backend route |
|---|---|
| `reasons` | No generic reasons endpoint. Return reason codes: `GET /v1/inventory/returns/reason-codes`. No adjustment-reasons REST endpoint exists (internal enums only). |
| `movements/return-to-stock` | `POST /v1/inventory/returns/submit-to-stock` (operationId `submitReturnToStock`). No `movements/*` route exists; stock movements are `POST /v1/inventory/stock-movements` (create only). |
| `workorders/{id}/returnable-items` vs `returns/returnable-items` | **`GET /v1/inventory/returns/returnable-items?workorderId={id}`** — the SDK shape is correct; the workorder is a query param, not a path segment. No workorder-scoped variant exists. |
| `workorders/{id}/allocations/{id}/shortage-options` / `…/resolve-shortage` | `GET /v1/inventory/shortage/options?allocationId={id}` and `POST /v1/inventory/shortage/resolve`. Nothing is nested under workorders/allocations. |
| `ledger` | Confirmed: `GET /v1/inventory/ledger` (+ `GET /v1/inventory/ledger/{entryId}`). Matches the SDK. |

## 5. OpenAPI reference for the frontend

- **Aggregated, live**: the gateway serves Swagger UI at `/swagger-ui.html` with a
  drop-down per service (Accounting, Inventory, People, …); raw specs at
  `/{domain}/v3/api-docs` through the gateway (e.g. `/inventory/v3/api-docs`).
- **Checked-in snapshots**: each module keeps `openapi.yaml` at its root —
  `pos-accounting/openapi.yaml`, `pos-inventory/openapi.yaml`,
  `pos-people/openapi.yaml` — updated to reflect the changes in this issue
  (cycleCountPlans LIST, sort/400 contracts, primary-location 404 wording).
  Regenerate with `./mvnw -Popenapi clean verify -DskipTests` (see
  `docs/DEVELOPMENT_GUIDE.md` → "OpenAPI Documentation"). The `openapi.json`
  snapshots are older-generation artifacts and materially stale (pos-people's
  predates the primary-location endpoint entirely); prefer the YAML or the live
  gateway docs.

These give the frontend a way to verify paths without authenticated production
probing.
