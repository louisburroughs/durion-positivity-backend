# PRD: Backend Contract Parity for Angular SDK Migration

**Status:** Execution-ready for issue 320
**Date:** 2026-04-26
**Owner:** Platform API
**Related:** `louisburroughs/durion#320`, `../durion-positivity-frontend/docs/PRD-sdk-migration-completion.md`

---

## Problem Statement

Issue 320 is broader than the original missing-endpoints backlog. The remaining B-class
blockers in the Angular SDK migration include:

- backend endpoints that do not exist yet
- backend endpoints that exist but are not represented correctly in OpenAPI
- backend endpoints whose parameters or schemas are underspecified relative to real
  frontend behavior
- backend ownership gaps where a cross-domain operation exists but is not modeled in the
  correct module spec

When any of those conditions exist, the generated Angular SDK cannot become the
authoritative transport boundary for the frontend. The result is persistent direct
`ApiBaseService` usage, cast-based adaptation in frontend services, and delayed Wave 3
and Wave 4 migration work.

The backend PRD therefore covers the full backend-owned parity surface for issue 320,
not only the original five missing endpoint groups.

---

## Solution

Deliver backend contract parity for every backend-owned blocker in issue 320.

For each affected module, the backend work is complete only when:

- the real operation exists and is owned by the correct module
- the OpenAPI contract captures the full supported parameter and schema surface
- the regenerated Angular SDK exposes the correct method signatures and types
- the frontend can migrate away from the corresponding direct transport call without
  inventing compatibility shims

This keeps backend work aligned to contract truth instead of treating the SDK generator
or frontend casts as the place where contract mismatches should be hidden.

---

## Scope

This PRD covers backend-owned contract parity work across the blocker groups identified
in issue 320. That includes both new endpoint implementation and OpenAPI/schema
correction for existing operations.

Modules affected:

| Module                 | Workstream                                                                 |
| ---------------------- | -------------------------------------------------------------------------- |
| `pos-security-service` | permissions, audit event filters, audit exports                            |
| `pos-shop-manager`     | ownership and OpenAPI coverage for shop audit endpoints                    |
| `pos-bulk-loader`      | correction contract parity and any missing correction or retry surfaces    |
| `pos-inventory`        | availability, locations, ledger, putaway, replenishment, returns, shortage |
| `pos-catalog`          | supplier cost list                                                         |
| `pos-accounting`       | event list filters, processing-log schema, AP bill list, export paths      |
| `pos-customer`         | billing terms, duplicate-check, billing-rules update                       |

---

## User Stories

1. As a backend developer, I want every issue-320 blocker classified as missing
   endpoint, incomplete OpenAPI, wrong parameter surface, or wrong schema, so that I can
   fix the correct layer first.
2. As an OpenAPI maintainer, I want existing operations to expose the real query and
   body contracts, so that generated SDKs stop dropping supported frontend behavior.
3. As an SDK maintainer, I want each regenerated package to reflect the actual backend
   module ownership, so that the correct service class is generated for each business
   flow.
4. As a frontend consumer, I want backend contracts to preserve current supported
   behavior unless an explicit product decision changes it, so that migration is not a
   hidden feature rewrite.
5. As a security engineer, I want permissions, audit search, and export flows modeled
   explicitly, so that security pages can migrate to generated clients safely.
6. As an inventory engineer, I want lookup, ledger, putaway, replenishment, returns,
   and shortage endpoints represented correctly in OpenAPI, so that the largest
   remaining transport surface can move to generated clients.
7. As an accounting engineer, I want timekeeping export and event-management contracts
   separated clearly from financial reporting, so that the generated SDK reflects the
   correct business domain.
8. As a CRM engineer, I want dedicated duplicate-check and billing-rules operations, so
   that incompatible response shapes are not overloaded into general-purpose endpoints.
9. As a release engineer, I want backend module tests, OpenAPI regeneration, SDK
   regeneration, and frontend migration to operate as one delivery chain, so that stale
   generated packages do not block downstream work.
10. As a platform architect, I want issue 320 closure measured by contract parity rather
    than just endpoint count, so that the SDK becomes the authoritative frontend
    boundary.

---

## Implementation Decisions

- The unit of delivery is the blocker group, not the repository. A blocker is not closed
  when only backend code lands; it closes only after OpenAPI regeneration, SDK
  regeneration, and frontend adoption succeed.
- Existing endpoints should be corrected in OpenAPI before the frontend is asked to
  adapt around missing parameters or wrong schemas.
- New endpoint implementation is required only when the business flow truly lacks a
  backend operation. Do not create duplicate endpoints to preserve frontend drift.
- Cross-domain ownership must be explicit. If an operation belongs in shop-manager,
  accounting, or another bounded context, the contract must be generated from that
  module rather than stranded in an unrelated SDK package.
- The first detailed endpoint specifications retained in this document are the original
  missing-endpoint groups. Additional blocker groups below are tracked as contract
  correction workstreams that must be implemented with the same annotation and
  regeneration rigor.
- OpenAPI is the source of truth for generated clients. Frontend casts or local adapter
  logic are not acceptable substitutes for backend schema fidelity.

---

## Testing Decisions

- Good backend tests verify external behavior, contract exposure, and OpenAPI fidelity,
  not internal implementation details.
- Every backend blocker closure must have module-level test coverage for the operation
  and a regenerated `openapi.yaml` that exposes the corrected contract.
- SDK regeneration is part of verification, not a follow-up courtesy step. The backend
  work is incomplete if regeneration produces the wrong types or missing methods.
- Prior art should come from existing controller tests, OpenAPI generation workflows,
  and generated SDK build checks already used by other modules.
- Where a blocker resolves a schema mismatch rather than adding a new endpoint, tests
  must prove the corrected parameter or response shape, not just endpoint reachability.

---

## Out of Scope

- Frontend migration work — that follows from these endpoints existing.
- Published SDK registry and package-distribution work covered by the publication
  transition PRD.
- Changes to existing, working endpoints that are unrelated to issue 320 blocker
  closure.
- Frontend-only model deletion and final `ApiBaseService` retirement.

---

## Backend Workstream Matrix

Use this matrix to decide whether a blocker requires new controller code, OpenAPI
correction, or ownership clarification.

| Blocker Group     | Primary module                   | Backend action                                                                                          |
| ----------------- | -------------------------------- | ------------------------------------------------------------------------------------------------------- |
| B-1, B-3, B-4     | `pos-security-service`           | Expand permission and audit-event parameter coverage; add audit export operations                       |
| B-2               | `pos-shop-manager`               | Confirm ownership and expose `/shop/audit` through the correct module spec                              |
| B-5               | `pos-bulk-loader`                | Decide whether bulk correction contract supersedes single-record correction or add a distinct operation |
| B-6 through B-12  | `pos-inventory`                  | Add or correct availability, locations, ledger, putaway, replenishment, returns, and shortage contracts |
| B-13              | `pos-catalog`                    | Add supplier cost list operation                                                                        |
| B-14, B-15        | `pos-inventory` or `pos-catalog` | Resolve parameter semantics and location inventory schema parity                                        |
| B-16 through B-20 | `pos-accounting`                 | Correct event filters, processing-log schema, AP bill list, export paths, and event-envelope contract   |
| B-21, B-22        | `pos-customer`                   | Add duplicate-check and billing-rules update operations                                                 |

Any workstream that changes request or response shape must update the spec and survive
SDK regeneration before it is considered closed.

---

## OpenAPI and SDK Generation Pipeline

**This is the critical path for SDK delivery.** Every endpoint added in this PRD must
be fully documented with Springdoc/Swagger annotations before the module's `openapi.yaml`
is regenerated. The SDK is generated directly from `openapi.yaml` — incomplete or
missing annotations produce incomplete or missing SDK client methods.

### Required annotation checklist (per endpoint)

Every new controller method must carry:

- `@Tag` on the class (if the controller is new) or the existing tag value if adding to
  an existing controller.
- `@Operation(summary = "...", description = "...")` — summary is 10 words or fewer;
  description explains business intent and any state machine context.
- `@ApiResponse` for every HTTP status code the method can return (200/201, 400, 403,
  404, 409, 500 where applicable). Include `content = @Content(...)` with the response
  DTO schema for 2xx responses.
- `@Parameter` on every `@PathVariable` and `@RequestParam` with `description` and
  `required`.
- `@io.swagger.v3.oas.annotations.security.SecurityRequirement` with the `bearerAuth`
  scheme and the specific permission scope string(s).
- `@Schema` on every new DTO class and its fields (`description`, `example` where
  possible).

Failure to annotate completely will produce SDK methods with `any` return types,
missing parameters, or undocumented error codes. **Review the generated `openapi.yaml`
diff before merging a PR.**

### Generation pipeline (run in order after each module's backend PR merges)

**Step 1 — Regenerate the backend OpenAPI spec for affected module(s):**

```bash
# From durion-positivity-backend root
# Run for each affected module, e.g.:
scripts/generate-openapi.sh pos-accounting
scripts/generate-openapi.sh pos-customer
scripts/generate-openapi.sh pos-catalog
scripts/generate-openapi.sh pos-bulk-loader

# Or regenerate all modules at once:
scripts/generate-openapi.sh
```

This runs the `springdoc-openapi-maven-plugin` under the `openapi` Maven profile,
boots Spring Boot, captures the live spec, writes `pos-<module>/openapi.yaml`, and
updates `pos-api-gateway/docs/openapi-aggregate.yaml`.

**Step 2 — Verify the new `openapi.yaml` before committing:**

- Confirm new paths are present in the file.
- Confirm all request/response schemas are fully resolved (no `{}` or missing `$ref`).
- Confirm `operationId` values are unique within the file.

**Step 3 — Regenerate the Angular SDK:**

```bash
# From durion-positivity-sdk-angular root
# Regenerate only the affected SDK package, e.g.:
scripts/generate-openapi.sh --module accounting
scripts/generate-openapi.sh --module customer
scripts/generate-openapi.sh --module catalog
scripts/generate-openapi.sh --module bulk-loader

# Or regenerate all SDK packages:
scripts/generate-openapi.sh
```

The SDK generator reads `../durion-positivity-backend/pos-<module>/openapi.yaml` as its
input spec (configured in `openapitools.json`). New endpoints appear as typed service
methods in `packages/sdk-<module>/src/apis/`.

**Step 4 — Verify SDK output:**

- Confirm the new service method(s) appear in the generated `.service.ts` file.
- Confirm return types are strongly typed (no `Observable<any>`).
- Confirm parameter types match the DTOs.
- Run `npm run build` in `durion-positivity-sdk-angular` and confirm zero TypeScript
  errors.

**Step 5 — Frontend migration (separate PR):**

Replace the direct `ApiBaseService` calls in the frontend with the newly generated SDK
methods. Remove the corresponding local type definitions if they duplicate SDK models.

---

## Detailed Endpoint Specifications

---

### 1. Accounting Report Export

**Module:** `pos-accounting`
**Frontend service:** `accounting.service.ts` — methods `requestExport`, `getExportStatus`, `getExportHistory`
**Current state:** `ReportExportRequest.java` and `ExportFormat.java` DTOs exist; no controller.

#### Context

The accounting module produces event and journal-line data that operators need to
export for reconciliation and external accounting systems. The export must be async
because generating a large export can take seconds to minutes. The frontend polls for
completion.

#### Endpoints

| Method | Path                                       | Description                     |
| ------ | ------------------------------------------ | ------------------------------- |
| `POST` | `/v1/accounting/reports/export`            | Request a new async export      |
| `GET`  | `/v1/accounting/reports/export/{exportId}` | Poll export status              |
| `GET`  | `/v1/accounting/reports/export`            | List export history (paginated) |

#### Request / Response contract

**POST `/v1/accounting/reports/export`**

Request body — reuse or extend `ReportExportRequest`:

```json
{
  "format": "CSV",
  "reportType": "JOURNAL_LINES",
  "startDate": "2026-01-01",
  "endDate": "2026-03-31",
  "organizationId": "<uuid>"
}
```

Response `201` — new `ReportExportResponse`:

```json
{
  "exportId": "<uuid>",
  "status": "PENDING",
  "requestedAt": "2026-04-25T10:00:00Z",
  "format": "CSV",
  "reportType": "JOURNAL_LINES"
}
```

**GET `/v1/accounting/reports/export/{exportId}`**

Response `200`:

```json
{
  "exportId": "<uuid>",
  "status": "COMPLETED",
  "requestedAt": "2026-04-25T10:00:00Z",
  "completedAt": "2026-04-25T10:00:05Z",
  "downloadUrl": "<presigned or endpoint URL>",
  "format": "CSV"
}
```

Status values: `PENDING`, `IN_PROGRESS`, `COMPLETED`, `FAILED`.

**GET `/v1/accounting/reports/export`**

Response `200` — `Page<ReportExportResponse>` with standard `Pageable` query params.

#### Permissions

Use or introduce a `accounting:report:export` permission scope. Apply
`@PreAuthorize("hasAuthority('accounting:report:export')")` and the
`@SecurityRequirement` annotation.

#### OpenAPI requirement

The controller must carry `@Tag(name = "Financial Reporting", ...)` so the generated
SDK service class is named `FinancialReportingService` and groups with the existing
income-statement/balance-sheet operations in `financialReporting.service.ts`.

---

### 2. CRM Billing Terms

**Module:** `pos-customer`
**Frontend service:** `crm.service.ts` — method `getBillingTerms`
**Current state:** `BillingTerm` is a field on `CommercialParty` and appears in
`GetPartyResponse`. No dedicated endpoint for listing available billing term options.

#### Context

The frontend needs to populate a billing terms dropdown when creating or editing a
commercial account. The backend should expose the reference list of valid billing
terms so the frontend does not hardcode them.

#### Endpoints

| Method | Path                    | Description                      |
| ------ | ----------------------- | -------------------------------- |
| `GET`  | `/v1/crm/billing-terms` | List all available billing terms |

#### Response contract

Response `200` — `List<BillingTermsRef>` (new DTO or reuse if it already exists in
the billing/invoice module):

```json
[
  { "code": "NET_30", "label": "Net 30", "netDays": 30 },
  { "code": "NET_60", "label": "Net 60", "netDays": 60 },
  { "code": "COD", "label": "Cash on Delivery", "netDays": 0 }
]
```

This is a reference/lookup endpoint. It is read-only and returns the same data for all
callers. No pagination required; the list is short and stable.

#### Permissions

Use existing `crm:party:view` or a new `crm:billing-terms:read` permission. Evaluate
whether this should be open to all authenticated users or restricted.

#### OpenAPI requirement

Add to `CrmAccountsController` under the existing `@Tag(name = "CRM Accounts", ...)`
so it groups with the party and account operations in the generated SDK. The method
must have a `description` explaining that this is a reference list, not a per-party
query.

---

### 3. Catalog Supplier Cost Structure — Collection GET

**Module:** `pos-catalog`
**Frontend service:** `product-catalog.service.ts` — method `listCostStructures`
**Current state:** `SupplierItemCostController` has `POST`, `GET /{id}`, `PUT /{id}`,
`DELETE /{id}`. No collection GET.

#### Context

The product catalog management UI shows a grid of all supplier cost structures
associated with a product. The frontend needs to list them by item (product) ID or by
supplier ID.

#### Endpoints

| Method | Path                          | Description                                         |
| ------ | ----------------------------- | --------------------------------------------------- |
| `GET`  | `/v1/products/supplier-costs` | List supplier cost structures with optional filters |

#### Request (query params)

| Param        | Type | Required | Description             |
| ------------ | ---- | -------- | ----------------------- |
| `itemId`     | UUID | optional | Filter by catalog item  |
| `supplierId` | UUID | optional | Filter by supplier      |
| `page`       | int  | optional | Page number (default 0) |
| `size`       | int  | optional | Page size (default 20)  |

At least one of `itemId` or `supplierId` should be provided; the controller may return
`400` if neither is given (to prevent unbounded scans).

#### Response contract

Response `200` — `Page<SupplierItemCostDto>` using the existing DTO.

#### OpenAPI requirement

Add to the existing `SupplierItemCostController` under
`@Tag(name = "Supplier Item Cost API", ...)`. The new method must document all query
parameters with `@Parameter` annotations including their defaults and whether they are
required.

---

### 4. Bulk Loader — Job Retry

**Module:** `pos-bulk-loader`
**Frontend service:** `bulk-import.service.ts` — method `retryJob`
**Current state:** `BulkLoadJobController` has cancel/create/get/list. No retry.

#### Context

Bulk import jobs can fail due to transient errors (file parsing issues, downstream
service unavailability). Operators need to retry a failed job without creating a new
one and re-uploading the file.

#### Endpoints

| Method | Path                          | Description                  |
| ------ | ----------------------------- | ---------------------------- |
| `POST` | `/v1/bulk-jobs/{jobId}/retry` | Retry a failed bulk load job |

#### Request

No request body required. The job ID in the path identifies the target.

#### Response contract

Response `200` — `BulkLoadJobResponse` (reuse existing DTO):

```json
{
  "jobId": "<uuid>",
  "status": "PENDING",
  "operatorId": "...",
  ...
}
```

Status transitions: `FAILED → PENDING` (retry resets the job to pending and re-queues
it). Return `409` if the job is not in `FAILED` state. Return `403` if the job does
not belong to the authenticated operator.

#### Permissions

Use existing `bulkImport:upload:execute` permission (consistent with `createJob` and
`cancelJob`).

#### OpenAPI requirement

Add to `BulkLoadJobController` under the existing `@Tag(name = "Bulk Load Jobs API",
...)`. Document the state-machine constraint (`FAILED → PENDING`) in the `description`
so it appears in the SDK JSDoc and generated method description. Include `@ApiResponse`
for 409 with a clear description.

Add `@EmitEvent(id = "BULK_LOADER_JOB_RETRY", apiVersion = "1")`.

---

### 5. Bulk Loader — Correction Submission

**Module:** `pos-bulk-loader`
**Frontend service:** `bulk-import.service.ts` — method `submitCorrection`
**Current state:** `ReviewQueueController` has `getAuditRecords` and
`downloadErrorReport`. No correction submission.

#### Context

After reviewing audit records for a failed bulk import, an operator may submit
corrected data for individual error rows rather than retrying the full job. This allows
targeted fixes for records that failed validation.

#### Endpoints

| Method | Path                                | Description                                         |
| ------ | ----------------------------------- | --------------------------------------------------- |
| `POST` | `/v1/bulk-jobs/{jobId}/corrections` | Submit corrected data for one or more error records |

#### Request body — new `BulkCorrectionRequest` DTO

```json
{
  "corrections": [
    {
      "auditRecordId": "<uuid>",
      "correctedData": { "<field>": "<value>", ... }
    }
  ]
}
```

#### Response contract

Response `202` — `BulkCorrectionResponse`:

```json
{
  "jobId": "<uuid>",
  "submittedCount": 3,
  "acceptedCount": 3,
  "rejectedCount": 0,
  "rejections": []
}
```

Return `404` if the job does not exist. Return `403` if the job does not belong to the
authenticated operator. Return `409` if the job is not in a state that accepts
corrections (e.g., still running or already completed).

#### Permissions

Use existing `bulkImport:upload:execute` permission.

#### OpenAPI requirement

Add to `ReviewQueueController` under the existing `@Tag(name = "Review Queue API",
...)`. Document the correction lifecycle in the description. Add `@Schema` annotations
to `BulkCorrectionRequest`, `BulkCorrectionItem`, and `BulkCorrectionResponse` with
field-level descriptions and examples.

Add `@EmitEvent(id = "BULK_LOADER_CORRECTION_SUBMIT", apiVersion = "1")`.

---

## Build Plan

Execute backend-owned blocker work in the same sequence for every module.

1. Resolve the business decision first when the blocker reveals a real semantic mismatch.
2. Implement or correct the backend controller and DTO contract.
3. Run the affected module tests.
4. Regenerate the module OpenAPI spec and inspect the diff.
5. Regenerate the affected Angular SDK package and build the SDK workspace.
6. Hand off the regenerated contract to the frontend migration PR for adoption.

Recommended commands:

```bash
# durion-positivity-backend
./mvnw -pl <module> -am test --no-transfer-progress
scripts/generate-openapi.sh <module>

# durion-positivity-sdk-angular
scripts/generate-openapi.sh --module <sdk-module>
npm run build
```

For blockers that touch multiple modules, keep PRs module-scoped where possible so each
OpenAPI diff and SDK regeneration remains reviewable.

---

## Acceptance Criteria

### Per endpoint

- [ ] Controller method exists and is mapped to the correct HTTP method and path.
- [ ] `@Operation`, `@ApiResponse`, `@Tag`, `@Parameter`, and `@SecurityRequirement`
      annotations are present and complete (no missing status codes, no empty descriptions).
- [ ] All new DTO classes have `@Schema` annotations at class and field level.
- [ ] `@PreAuthorize` is applied with the correct permission string.
- [ ] Unit or integration test covers the happy path and key error cases (4xx).
- [ ] `@EmitEvent` annotation present on all write operations.

### OpenAPI file

- [ ] `scripts/generate-openapi.sh` runs without error for each affected module.
- [ ] The generated `pos-<module>/openapi.yaml` contains the new paths.
- [ ] All new response schemas are fully inline or `$ref`-resolved in the spec (no
      empty `{}` schemas).
- [ ] `operationId` values in the spec are unique.

### Angular SDK

- [ ] `scripts/generate-openapi.sh --module <name>` runs without error in
      `durion-positivity-sdk-angular`.
- [ ] New service method(s) appear in the corresponding `*.service.ts` file.
- [ ] All new method return types are strongly typed (e.g.,
      `Observable<ReportExportResponse>`, not `Observable<any>`).
- [ ] `npm run build` passes with zero TypeScript errors in the SDK workspace.
- [ ] New SDK method names, parameter names, and types match the OpenAPI spec.

### Frontend (follow-on, separate PR)

- [ ] Direct `ApiBaseService` calls replaced with generated SDK methods in each
      affected service file.
- [ ] `as never` and `as unknown` casts removed for these methods.
- [ ] Local type definitions that duplicate the SDK models removed.

---

## Delivery Order

Implement as small module-scoped PRs grouped by blocker family. There are a few
cross-module dependencies, so prefer the order that validates the closure workflow and
unlocks the most frontend migration surface.

| Order | Module(s)                                  | Focus                                                                     | SDK package(s)                     |
| ----- | ------------------------------------------ | ------------------------------------------------------------------------- | ---------------------------------- |
| 1     | `pos-security-service`, `pos-shop-manager` | permissions, audit ownership, audit filters, audit exports                | `sdk-security`, `sdk-shop-manager` |
| 2     | `pos-bulk-loader`                          | correction and retry parity                                               | `sdk-bulk-loader`                  |
| 3     | `pos-catalog`, `pos-customer`              | supplier cost list, billing terms, duplicate-check, billing-rules update  | `sdk-catalog`, `sdk-customer`      |
| 4     | `pos-accounting`                           | event filters, processing log, AP bill list, export paths, event contract | `sdk-accounting`                   |
| 5     | `pos-inventory`                            | availability, lookups, ledger, putaway, replenishment, returns, shortage  | `sdk-inventory`                    |

Start with security/shop-manager and bulk-loader to validate the end-to-end closure
workflow on smaller slices, then land catalog/customer, then accounting, and finally the
inventory workstream with the widest contract surface.

---

## Reference

- Backend OpenAPI generation script:
  `durion-positivity-backend/scripts/generate-openapi.sh`
- SDK generation script:
  `durion-positivity-sdk-angular/scripts/generate-openapi.sh`
- SDK generator configuration:
  `durion-positivity-sdk-angular/openapitools.json`
- Frontend migration analysis:
  `durion-positivity-frontend/docs/sdk-migration-analysis.md`
- Existing controller patterns to follow:
  - `pos-accounting/…/CreditMemoController.java` — paginated list with filters
  - `pos-bulk-loader/…/BulkLoadJobController.java` — stateful job operations
  - `pos-bulk-loader/…/ReviewQueueController.java` — audit/download pattern
