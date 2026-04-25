# PRD: Missing Backend Endpoints Blocking SDK Migration

**Status:** Ready for Development
**Date:** 2026-04-25
**Owner:** Platform API
**Related:** `../durion-positivity-frontend/docs/sdk-migration-analysis.md`

---

## Problem

The Angular frontend (`durion-positivity-frontend`) is mid-migration from direct
`HttpClient` calls to using the typed Angular SDK (`durion-positivity-sdk-angular`).
That migration is blocked in five specific areas where the frontend is calling endpoints
that do not exist in `durion-positivity-backend`.

These are not SDK generation failures. The SDK generator reads `pos-*/openapi.yaml`
files, which are produced by `scripts/generate-openapi.sh` from the live SpringDoc spec.
If a controller endpoint is not in the backend, it will not appear in the OpenAPI file,
and therefore will not appear in the SDK. The correct fix is to implement the missing
backend endpoints — the SDK and frontend can then catch up in subsequent steps.

**Current symptom in the frontend:** five families of direct `ApiBaseService` calls
remain in `accounting.service.ts`, `crm.service.ts`, `product-catalog.service.ts`, and
`bulk-import.service.ts` because their corresponding SDK methods do not exist. Those
calls will return HTTP 404 at runtime.

---

## Scope

This PRD covers implementation of five missing backend endpoint groups across four
modules. Each group has a dedicated section with acceptance criteria, contract shape,
and OpenAPI annotation requirements.

Modules affected:

| Module            | Work                                               |
| ----------------- | -------------------------------------------------- |
| `pos-accounting`  | Async report export: request, status poll, history |
| `pos-customer`    | Billing terms reference list                       |
| `pos-catalog`     | Supplier cost structure collection GET             |
| `pos-bulk-loader` | Job retry; correction record submission            |

---

## Non-Goals

- Frontend migration work — that follows from these endpoints existing.
- SDK generation — runs as a defined step after backend delivery (see Pipeline section).
- Changes to existing, working endpoints.
- `pos-security-service` changes — security `createRole`/`createUser`/`getAllPermissions`
  are already in the SDK; the frontend migration for those is a Category A fix (no
  backend work required).

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

## Endpoint Specifications

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

Implement as four independent PRs (one per module). There are no cross-module
dependencies. Run the full generation pipeline after each PR merges.

| PR  | Module            | Endpoints                         | SDK package       |
| --- | ----------------- | --------------------------------- | ----------------- |
| 1   | `pos-catalog`     | Supplier cost list                | `sdk-catalog`     |
| 2   | `pos-customer`    | Billing terms list                | `sdk-customer`    |
| 3   | `pos-bulk-loader` | Job retry + correction submission | `sdk-bulk-loader` |
| 4   | `pos-accounting`  | Export request, status, history   | `sdk-accounting`  |

Start with `pos-catalog` (smallest scope, single read endpoint) and `pos-customer`
(single read endpoint) to validate the annotation → generation → SDK pipeline before
tackling the more complex async export and stateful bulk loader operations.

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
