# PRD: Backend Work to Unblock Angular SDK Migration

**Status:** Execution-ready
**Date:** 2026-04-26
**Owner:** Platform API
**Related:**

- `louisburroughs/durion#320`
- `../durion-positivity-frontend/docs/PRD-sdk-migration-completion.md` (authoritative blocker list B-1..B-22)
- `./PRD-missing-backend-endpoints.md` (companion — Workstream Matrix and high-level rationale)

---

## Problem Statement

The companion PRD `PRD-missing-backend-endpoints.md` lists the issue-320 backend
workstream matrix (`B-1..B-22`) but its Detailed Endpoint Specifications cover only the
original five legacy groups (Accounting Report Export, CRM Billing Terms, Catalog
Supplier Cost list, Bulk Loader Job Retry, Bulk Loader Correction). Those five are
already implemented in the codebase. As a result, an executor agent reading the backend
PRD finds nothing actionable for `B-1..B-22` and reports "nothing to execute".

This PRD is the missing actionable contract. It specifies, per module and per endpoint,
exactly what must be added, expanded, or aligned in the backend so that the Angular SDK
can be regenerated and the frontend Wave 3/Wave 4 migration can finish.

---

## Solution

For each blocker `B-1..B-22`:

- Identify the owning module
- Specify the exact OpenAPI path, HTTP method, query/path/body schema, and response shape
- State whether the operation must be **ADDED**, **EXPANDED** (existing path; missing
  params or response fields), or **ALIGNED** (existing path; field/parameter rename or
  semantic clarification)
- Apply the mandatory annotation surface (Springdoc + `@PreAuthorize` + `@EmitEvent`)
- Regenerate `pos-<module>/openapi.yaml` and the matching `sdk-<module>` package

A blocker is **closed** only when:

1. Backend module tests pass (`./mvnw -pl pos-<module> -am test`)
2. `pos-<module>/openapi.yaml` shows the new path/parameters/schema after running
   `scripts/generate-openapi.sh pos-<module>`
3. `sdk-<module>` exports a strongly-typed Angular service method (no `Observable<any>`
   leakage) after running `scripts/generate-openapi.sh --module <name>` and
   `npm run build` in the SDK repo
4. The frontend wave PR adopts the SDK call without local DTO shims

---

## Scope

In scope: every backend-owned blocker `B-1..B-22` from
`../durion-positivity-frontend/docs/PRD-sdk-migration-completion.md`.

Out of scope: frontend service migration, SDK template changes, generator script
changes, and any new product capability not already covered by an existing blocker.

---

## Delivery Order

Same order as `PRD-missing-backend-endpoints.md` §Delivery Order:

1. `pos-security-service` + `pos-shop-manager` (B-1, B-2, B-3, B-4)
2. `pos-bulk-loader` (B-5 — contract decision + verification)
3. `pos-catalog` + `pos-customer` (B-13, B-21, B-22)
4. `pos-accounting` (B-16, B-17, B-18, B-19, B-20)
5. `pos-inventory` (B-6, B-7, B-8, B-9, B-10, B-11, B-12, B-14, B-15)

---

## Cross-Cutting Annotation Requirements

Every NEW or CHANGED controller method in this PRD MUST carry:

- Class: `@Tag(name = "<Domain>", description = "...")`,
  `@RestController`, `@RequestMapping`, `@SecurityRequirement(name = "bearerAuth")`
- Method: `@Operation(summary, description)`,
  `@ApiResponse(responseCode, description, content)` for **every** documented status
  (200/201/400/403/404/409/500 as applicable)
- `@Parameter(description, required, example)` on every `@PathVariable` /
  `@RequestParam` / `@RequestHeader`
- `@PreAuthorize("hasAuthority('<scope>')")` with the scope strings noted per blocker
- All write operations (POST/PUT/PATCH/DELETE):
  `@EmitEvent(id = "<MODULE>_<RESOURCE>_<ACTION>", apiVersion = "1")` and
  registration in the module's `*EventTypes` registry per backend AGENTS.md
- All DTOs: `@Schema(description, example)` on the class and on every field. Validation
  annotations on request DTOs (`@NotNull`, `@Size`, `@Pattern`, etc.). `@NonNull`
  (`org.jspecify.annotations.NonNull`) on non-null service-layer parameters and return
  types per backend AGENTS.md.
- Architecture: implementation classes in `com.positivity.<module>.internal.*`; only
  `com.positivity.<module>.service` interfaces exposed cross-module. ArchUnit tests
  must continue to pass.

---

## 1. `pos-security-service` — B-1, B-3, B-4

Existing: `/v1/permissions`, `/v1/permissions/{id}`, `/v1/permissions/domain/{domain}`,
`/v1/audit/events`, `/v1/audit/events/{eventId}` are present. No audit export
endpoints exist.

### B-1 — `GET /v1/permissions` query and pagination — EXPAND

| Field            | Value                                                                                                                    |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------ |
| Operation ID     | `listPermissions`                                                                                                        |
| Query params     | `domain` (string, optional), `page` (int, default 0), `size` (int, default 20, max 200), `sort` (string, optional)       |
| Response 200     | `Page<PermissionDto>` (Spring Data page envelope: `content`, `totalElements`, `totalPages`, `number`, `size`)            |
| Permission scope | `security:permission:view`                                                                                               |
| Notes            | Keep `/v1/permissions/domain/{domain}` for backwards compatibility OR remove and document migration. Default to keeping. |

### B-3 — `GET /v1/audit/events` filter expansion — EXPAND

Add the following optional query parameters (all `@RequestParam(required = false)`):

| Param           | Type                 | Notes                                     |
| --------------- | -------------------- | ----------------------------------------- |
| `fromDate`      | `Instant` (ISO-8601) | inclusive                                 |
| `toDate`        | `Instant` (ISO-8601) | exclusive                                 |
| `actorId`       | `String` (UUID)      |                                           |
| `workorderId`   | `String` (UUID)      | one-word `workorder` per workspace policy |
| `movementId`    | `String` (UUID)      |                                           |
| `productId`     | `String` (UUID)      |                                           |
| `sku`           | `String`             |                                           |
| `eventType`     | `String`             |                                           |
| `aggregateId`   | `String`             |                                           |
| `correlationId` | `String` (UUID)      |                                           |
| `reasonCode`    | `String`             |                                           |
| `pageToken`     | `String`             | for cursor-based paging where applicable  |
| `locationIds`   | `List<String>`       | repeated query param                      |

Response remains `Page<AuditEventDto>` (or existing cursor envelope if already used).
Permission scope: `security:audit:view`.

### B-4 — Audit Export job endpoints — ADD

```text
POST   /v1/audit/exports
GET    /v1/audit/exports/{jobId}
```

| Endpoint                        | Request                                                                                                        | Response                                                                                           | Status codes  |
| ------------------------------- | -------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- | ------------- |
| `POST /v1/audit/exports`        | `AuditExportRequest { filters: AuditEventFilter, format: "CSV"\|"JSON", deliveryMode: "DOWNLOAD"\|"WEBHOOK" }` | `AuditExportJobResponse { jobId, status, requestedAt }`                                            | 202, 400, 403 |
| `GET /v1/audit/exports/{jobId}` | —                                                                                                              | `AuditExportJobResponse { jobId, status, requestedAt, completedAt?, downloadUrl?, errorMessage? }` | 200, 403, 404 |

- `status` enum: `PENDING`, `IN_PROGRESS`, `COMPLETED`, `FAILED`
- `@EmitEvent(id = "SECURITY_AUDIT_EXPORT_REQUEST", apiVersion = "1")` on POST
- `@PreAuthorize("hasAuthority('security:audit:export')")`
- New `@Tag("Audit Exports")`
- DTOs in `internal/dto/`; service interface `AuditExportService` in `service/`

---

## 2. `pos-shop-manager` — B-2

Existing: `/v1/shop/audit` and `/v1/shop/audit/{id}` already in
`pos-shop-manager/openapi.yaml`.

### B-2 — Shop audit ownership / SDK routing — VERIFY + EXPAND if needed

- Confirm `scripts/generate-openapi.sh --module shop-manager` produces `sdk-shop-manager`
  with `searchShopAudit` and `getShopAuditEntry` methods.
- If the frontend filter set (date range, actor, workorderId, action type) is not
  exposed on `/v1/shop/audit`, EXPAND `@RequestParam` coverage to match the frontend
  query surface used in `wave-3` migration.
- Confirm `@Tag` and `operationId` strings produce the expected SDK class name
  (`ShopAuditService`).
- No new endpoint required.
- Permission scope: `shop:audit:view`.

---

## 3. `pos-bulk-loader` — B-5

Existing: `POST /v1/bulk-jobs/{jobId}/retry`, `POST /v1/bulk-jobs/{jobId}/corrections`.

### B-5 — Correction contract decision — DECIDE + DOCUMENT

Decision required before any code change:

- **Option A (recommended):** the bulk corrections endpoint is the canonical contract;
  remove any single-record correction surface from the frontend PRD; frontend wraps
  single-record submissions as a one-element bulk request.
- **Option B:** add a new `POST /v1/bulk-jobs/{jobId}/corrections/single` returning the
  same `CorrectionResultDto` for parity with frontend `submitCorrection`.

Document the chosen option in `PRD-missing-backend-endpoints.md` §5 and update the
frontend PRD blocker B-5 row. If Option B, mark write with
`@EmitEvent(id = "BULK_LOADER_CORRECTION_SUBMIT_SINGLE", apiVersion = "1")`.

---

## 4. `pos-catalog` — B-13

Existing: `GET /v1/products/supplier-costs` collection at line 1182 of
`pos-catalog/openapi.yaml`.

### B-13 — Supplier costs query parameters — VERIFY + EXPAND

Required contract:

| Param        | Type                       | Notes |
| ------------ | -------------------------- | ----- |
| `itemId`     | `String` (UUID), optional  |       |
| `supplierId` | `String` (UUID), optional  |       |
| `page`       | `int`, default 0           |       |
| `size`       | `int`, default 20, max 200 |       |

Response: `Page<SupplierItemCostDto>`.

If any of `itemId`/`supplierId`/pagination params are missing from the current spec,
add them with `@Parameter`. Confirm `SupplierItemCostDto` `@Schema` annotations are
complete. Permission scope: `catalog:supplier-cost:view`.

---

## 5. `pos-customer` — B-21, B-22

Existing: only `/v1/crm/snapshot/party/{partyId}/billing-rules` (read-only snapshot).
No duplicate-check, no PUT for billing rules.

### B-21 — Party duplicate check — ADD

```text
GET /v1/crm/accounts/parties/duplicate-check?legalName={string}
```

| Field            | Value                                                                          |
| ---------------- | ------------------------------------------------------------------------------ |
| Query            | `legalName` (string, required, min length 2)                                   |
| Response 200     | `DuplicateCheckResponse { matches: PartyMatch[], exactMatchPartyId?: string }` |
| `PartyMatch`     | `{ partyId, legalName, score: number, matchType: "EXACT"\|"FUZZY" }`           |
| Status codes     | 200, 400, 403                                                                  |
| Permission scope | `crm:party:view`                                                               |
| `@Tag`           | `"CRM Accounts"`                                                               |

### B-22 — Party billing rules update — ADD

```text
PUT /v1/crm/accounts/parties/{partyId}/billing-rules
```

| Field            | Value                                                                                                                       |
| ---------------- | --------------------------------------------------------------------------------------------------------------------------- |
| Path             | `partyId` (UUID, required)                                                                                                  |
| Request body     | `BillingRulesUpdateRequest { paymentTerms, creditLimit, currency, taxExempt, billingContactId?, … }`                        |
| Response 200     | `BillingRulesResponse { partyId, paymentTerms, creditLimit, currency, taxExempt, billingContactId?, updatedAt, updatedBy }` |
| Status codes     | 200, 400, 403, 404, 409                                                                                                     |
| Permission scope | `crm:party:billing:write`                                                                                                   |
| `@EmitEvent`     | `id = "CRM_PARTY_BILLING_RULES_UPDATE", apiVersion = "1"`                                                                   |
| Notes            | Conflict 409 on optimistic-lock version mismatch if entity uses `@Version`.                                                 |

---

## 6. `pos-accounting` — B-16, B-17, B-18, B-19, B-20

Existing: `/v1/accounting/events`, `/v1/accounting/events/{eventId}`,
`/v1/accounting/events/{eventId}/processing-log`,
`/v1/accounting/events/{eventId}/retry`,
`/v1/accounting/events/{eventId}/reprocess`,
`/v1/accounting/events/{eventId}/reprocessing-history`,
`/v1/accounting/ap/bills`, `/v1/accounting/reports/export*`.

### B-16 — Event list filters — EXPAND

`GET /v1/accounting/events`:

- `organizationId` MUST become **optional** (currently required).
- Add optional query params: `eventType`, `idempotencyOutcome` (`ACCEPTED`/`REJECTED`/
  `DUPLICATE`/`SUPPRESSED`), `receivedAtFrom` (Instant), `receivedAtTo` (Instant),
  `eventId` (UUID), `ingestionId` (UUID), `domainKeyId` (string), `invoiceId` (UUID).
- Standard `Pageable` (`page`, `size`, `sort`).
- Response: `Page<AccountingEventDto>`.
- Permission scope: `accounting:event:view`.

### B-17 — Processing log response shape — ALIGN

`GET /v1/accounting/events/{eventId}/processing-log`:

- Change response from `string` (or wrapped log blob) to
  `array<EventProcessingLogEntry>` with:
  `{ entryId, occurredAt, severity: "INFO"\|"WARN"\|"ERROR", message, contextJson? }`.
- Add `EventProcessingLogEntry` `@Schema`.
- Backwards-incompatible — coordinate frontend wave PR.

### B-18 — AP bills paged list — EXPAND

`GET /v1/accounting/ap/bills`:

- Confirm an unfiltered paged variant exists (no required filter). If filters are
  currently mandatory, make all of them optional and add `Pageable`. Response:
  `Page<ApBillDto>`. Permission scope: `accounting:ap:view`.

### B-19 — Generic export job endpoints — ADD

Distinct from the existing `/v1/accounting/reports/export` (financial reports). For
timekeeping and other generic exports:

```text
POST   /v1/accounting/export/request
GET    /v1/accounting/export/status/{jobId}
GET    /v1/accounting/export/history
```

| Endpoint                        | Request                                                                              | Response                                                                                      | Status   |
| ------------------------------- | ------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------- | -------- |
| `POST .../export/request`       | `ExportRequest { exportType, filters: object, format: "CSV"\|"JSON", deliveryMode }` | `ExportJobResponse { jobId, status, requestedAt }`                                            | 202      |
| `GET .../export/status/{jobId}` | —                                                                                    | `ExportJobResponse { jobId, status, requestedAt, completedAt?, downloadUrl?, errorMessage? }` | 200, 404 |
| `GET .../export/history`        | `Pageable`, optional `exportType`, `requestedAtFrom`, `requestedAtTo`                | `Page<ExportJobResponse>`                                                                     | 200      |

- `@EmitEvent(id = "ACCOUNTING_EXPORT_REQUEST", apiVersion = "1")` on POST
- Permission scope: `accounting:export:request` for POST; `accounting:export:view` for GET
- New `@Tag("Accounting Exports")`

### B-20 — Event envelope contract introspection — ADD

```text
GET /v1/accounting/events/contract
```

| Field            | Value                                                                                               |
| ---------------- | --------------------------------------------------------------------------------------------------- |
| Response 200     | `EventEnvelopeContract { version: string, fields: ContractField[], examples: object[] }`            |
| `ContractField`  | `{ name, jsonPath, type, required, description, enumValues? }`                                      |
| Permission scope | `accounting:event:view`                                                                             |
| Notes            | Read-only. Used by SDK consumers to validate inbound payloads against the current envelope version. |

---

## 7. `pos-inventory` — B-6..B-12, B-14, B-15

Existing inventory paths use the `/v1/inventory/...` prefix. The frontend PRD references
`/inventory/v1/...` — there is a **prefix mismatch** that must be reconciled.

### B-0 (prerequisite) — Path prefix reconciliation — DECIDE

Recommend keeping the current `/v1/inventory/...` prefix to avoid breaking existing
consumers. Update the frontend PRD blocker rows and SDK call sites to match. Document
the decision in `PRD-missing-backend-endpoints.md`. Do **not** add duplicate paths
under both prefixes.

### B-6 — Availability filter expansion — EXPAND

`GET /v1/inventory/availability/by-sku` and `GET /v1/inventory/availability/lead-time`:

- Add optional query params `locationId` (UUID), `storageLocationId` (UUID).
- Document `sourceType` semantics in `@Parameter(description = ...)`. See B-14.

### B-7 — Locations / storage-locations / location-zones reference — ADD

```text
GET /v1/inventory/locations              -> Page<LocationDto>
GET /v1/inventory/storage-locations      -> Page<StorageLocationDto>
GET /v1/inventory/location-zones         -> Page<LocationZoneDto>
```

- Standard `Pageable` plus optional `siteId`, `locationId` filters where relevant.
- Permission scope: `inventory:location:view`.
- Read-only; no `@EmitEvent`.

### B-8 — Inventory ledger — ADD (new service + controller)

New `InventoryLedgerService` interface in `service/`; implementation
`InventoryLedgerServiceImpl` in `internal/service/`; controller
`InventoryLedgerController` in `internal/controller/`; entity
`InventoryLedgerEntry` in `internal/entity/`.

```text
GET /v1/inventory/ledger
GET /v1/inventory/ledger/{id}
```

`GET /v1/inventory/ledger` query params (all optional):

| Param                 | Type                        |
| --------------------- | --------------------------- |
| `productSku`          | `String`                    |
| `locationId`          | `String` (UUID)             |
| `storageLocationId`   | `String` (UUID)             |
| `dateFrom`            | `Instant`                   |
| `dateTo`              | `Instant`                   |
| `sourceTransactionId` | `String` (UUID)             |
| `workorderId`         | `String` (UUID)             |
| `workorderLineId`     | `String` (UUID)             |
| `pageSize`            | `int` (default 50, max 500) |
| `pageToken`           | `String`                    |
| `movementTypes`       | `List<MovementType>`        |

Response: cursor-paged `LedgerPage<InventoryLedgerEntryDto>`
(`{ entries, nextPageToken? }`). `GET .../{id}` returns a single
`InventoryLedgerEntryDto`. Permission scope: `inventory:ledger:view`.
Flyway migration required for `inventory_ledger_entry` table.

### B-9 — Putaway task filtering — EXPAND

`/v1/inventory/putaway/tasks/*`: add `locationId` and `storageLocationId` optional
query params on the listing/filter endpoints used by the frontend putaway UI.

### B-10 — Replenishment policy filtering — EXPAND

`GET /v1/inventory/replenishment/policies`:

- Add optional `locationId` query param.
- Confirm paged response (`Page<ReplenishmentPolicyDto>`).

### B-11 — Returns workflow — ADD

```text
GET  /v1/inventory/returns/returnable-items?workorderId={uuid}     -> ReturnableItemDto[]
GET  /v1/inventory/returns/reason-codes                            -> ReasonCodeDto[]
POST /v1/inventory/returns/submit-to-stock                         -> ReturnSubmissionResultDto
```

- POST request `ReturnSubmitRequest { workorderId, lines: [{ itemId, quantity, reasonCode, locationId, storageLocationId? }] }`.
- `@EmitEvent(id = "INVENTORY_RETURN_SUBMIT_TO_STOCK", apiVersion = "1")` on POST.
- Permission scope: `inventory:return:write` for POST; `inventory:return:view` for GETs.

### B-12 — Shortage workflow — ADD + ALIGN

```text
GET  /v1/inventory/shortage/options?allocationId={uuid}            -> ShortageOptionDto[]
POST /v1/inventory/shortage/resolve                                -> ShortageResolutionResultDto
```

- Reconcile naming: standardize on `allocationId` (parent allocation) with optional
  `allocationLineId` for line-level operations. Document both in `@Parameter`.
- POST request `ShortageResolveRequest { allocationId, allocationLineId?, resolution: "BACKORDER"\|"SUBSTITUTE"\|"CANCEL", substituteSku?, notes? }`.
- `@EmitEvent(id = "INVENTORY_SHORTAGE_RESOLVE", apiVersion = "1")` on POST.
- Permission scope: `inventory:shortage:resolve` (POST) / `inventory:shortage:view` (GET).

### B-14 — Availability sourceType vs locationId — ALIGN

- Settle semantics in `@Parameter` descriptions on `/availability/by-sku` and
  `/availability/lead-time`:
  - `sourceType`: enum (`WAREHOUSE`, `SUPPLIER`, `TRANSIT`); selects the lookup strategy.
  - `locationId`: optional concrete location filter; valid only when
    `sourceType == WAREHOUSE`.
- Validate combination at controller boundary; return 400 with structured error
  envelope on conflict.

### B-15 — Location inventory schema — ALIGN

`GET /v1/inventory/locations/{locationId}/inventory`:

- Rename response fields:
  - `onHand` → `onHandQuantity`
  - `atp` → `availableToPromiseQuantity`
- Add optional `sku` query param to filter the response.
- Update `@Schema` and any consumer references in the same module.
- Backwards-incompatible — coordinate frontend wave PR.

---

## Acceptance Criteria

For every blocker listed above:

1. Module builds and tests pass:
   `./mvnw -pl pos-<module> -am test`
2. ArchUnit tests pass (no internal package leakage; no controller→repository direct
   dependency).
3. Module OpenAPI is regenerated with no manual edits:
   `scripts/generate-openapi.sh pos-<module>`
4. Every new/changed operation in `pos-<module>/openapi.yaml` shows:
   - `tags`, `summary`, `description`
   - all `parameters` (path + query) with `description` and `required`
   - `requestBody` with `application/json` + schema reference (where applicable)
   - `responses` for every documented status code
   - `security: [{ bearerAuth: [] }]`
5. SDK regeneration succeeds:
   - `cd ../durion-positivity-sdk-angular && scripts/generate-openapi.sh --module <name>`
   - `npm run build`
   - The corresponding generated Angular service exposes a typed method (no
     `Observable<any>` leakage).
6. The frontend PRD blocker row can be marked closed because the blocker is now a
   pure frontend adoption task.

---

## Risks and Open Questions

| Risk                                                                                     | Mitigation                                                                                 |
| ---------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| B-17 and B-15 are backwards-incompatible response schema changes                         | Coordinate single PR per change with frontend wave PR; ship both at once.                  |
| Inventory ledger (B-8) requires a Flyway migration and a new entity                      | Standard Flyway-first workflow; covered by `scripts/check-flyway-hygiene.sh`.              |
| Inventory path-prefix reconciliation (B-0) may invalidate frontend code already migrated | Resolve before any inventory wave PR is merged; do not double-publish under both prefixes. |
| Bulk loader correction contract (B-5) currently has no canonical answer                  | Decision required from product before any change. Block B-5 until decided.                 |
| B-2 may already work — the gap may be SDK packaging, not backend                         | Verify SDK output first; only edit backend if `@Tag`/`operationId` rerouting is required.  |

---

## Out of Scope

- Frontend service migration (covered by `../durion-positivity-frontend/docs/PRD-sdk-migration-completion.md`).
- SDK template or generator-script changes (covered by `../durion-positivity-sdk-angular/AGENTS.md`).
- New product capabilities not already represented as a B-class blocker.
- Changes to `pos-api-gateway` routing beyond automatic aggregation of regenerated
  module specs.

---

## Regeneration Pipeline (per module, in order)

```bash
# 1. Backend module spec
cd durion-positivity-backend
scripts/generate-openapi.sh pos-<module>

# 2. SDK package
cd ../durion-positivity-sdk-angular
scripts/generate-openapi.sh --module <module>
npm run build

# 3. Frontend wave PR (separate PR; this PRD does not cover it)
```
