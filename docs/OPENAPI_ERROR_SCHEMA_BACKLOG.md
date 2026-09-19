# ADR-0017 §3 error-envelope backlog (`errorSchema` gaps)

Generated snapshot of every 4xx/5xx response in a committed `openapi.yaml` whose body is a
success DTO rather than the `ApiError` envelope — the findings
`OpenApiErrorResponseSchemaValidator` raises and `module-inventory.yaml` currently keeps at
`REPORT_ONLY` for all but one module.

## Why this list exists

`scripts/generate-openapi.sh --validation-mode strict` fails the build today, because
`OpenApiRepositoryValidator` promotes every `REPORT_ONLY` finding to blocking in strict mode.
The whole gap is this backlog: **642 findings across 17 modules and 363 operations**, and
*zero* findings of any other kind. Closing it is what lets the fleet move to
`errorSchema: STRICT` module by module, and lets the `API Artifacts Sync` workflow run in strict
mode without failing.

Each finding means a `@ApiResponse` for an error status that carries no explicit
`@Schema`/`content`: springdoc then infers the body, and infers it from the endpoint's success
type (or from a `@ControllerAdvice` return type), so the published spec — and every generated
SDK — tells clients an error returns the 200 DTO. See ADR-0017 §3, `docs/ERROR_ENVELOPE.md`,
and issue #1720.

## How to regenerate this list

```bash
# The authoritative run (fails in strict mode; the failure text is the same list)
./mvnw -pl pos-openapi-validation -DskipTests=false \
  -Dopenapi.validation.mode=strict -Dtest=OpenApiRepositoryValidationTest test
```

The tables below were derived from the committed `pos-*/openapi.yaml` files with the same rule
the validator applies (`OpenApiErrorResponseSchemaValidator#check`): a 4xx/5xx response whose
media-type schema `$ref`s a component other than `ApiError`. Responses with no `content` are
not findings — a bodiless error is a legitimate contract.

## Fix pattern

```java
@ApiResponse(
        responseCode = "404",
        description = "Order not found",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
```

Annotate every error response on the operation, regenerate the module's spec
(`scripts/generate-openapi.sh <module>`), then flip the module to `errorSchema: STRICT` in
`pos-openapi-validation/src/test/resources/openapi/module-inventory.yaml` so it cannot
regress. `pos-vehicle-inventory` is the worked reference conversion.

## Where the gap sits

| Module | Findings | Operations | Distinct wrong schemas | `errorSchema` policy |
|---|---:|---:|---:|---|
| `pos-accounting` | 117 | 73 | 40 | REPORT_ONLY (default) |
| `pos-workorder` | 95 | 53 | 27 | REPORT_ONLY (default) |
| `pos-catalog` | 92 | 52 | 21 | REPORT_ONLY (default) |
| `pos-location` | 62 | 33 | 15 | REPORT_ONLY (default) |
| `pos-inventory` | 59 | 34 | 17 | REPORT_ONLY (default) |
| `pos-price` | 54 | 23 | 11 | REPORT_ONLY (default) |
| `pos-warranty` | 53 | 28 | 7 | REPORT_ONLY (default) |
| `pos-customer` | 24 | 11 | 8 | REPORT_ONLY (default) |
| `pos-invoice` | 21 | 12 | 8 | REPORT_ONLY (default) |
| `pos-tenant` | 18 | 12 | 4 | REPORT_ONLY (default) |
| `pos-order` | 17 | 7 | 3 | REPORT_ONLY (default) |
| `pos-shop-manager` | 7 | 6 | 5 | REPORT_ONLY (default) |
| `pos-vehicle-fitment` | 7 | 5 | 3 | REPORT_ONLY (default) |
| `pos-event-receiver` | 5 | 5 | 1 | REPORT_ONLY (default) |
| `pos-mcp-server` | 5 | 4 | 2 | REPORT_ONLY (default) |
| `pos-tax` | 5 | 4 | 2 | REPORT_ONLY (default) |
| `pos-people` | 1 | 1 | 1 | REPORT_ONLY (default) |
| **Total** | **642** | **363** | | |

By status code: `400` ×177, `401` ×36, `403` ×97, `404` ×219, `409` ×72, `422` ×24, `500` ×9, `501` ×3, `502` ×1, `503` ×4.

### Ready to ratchet now (no findings, still `REPORT_ONLY`)

These modules publish a spec with no error-envelope gap at all, so they can be moved to
`errorSchema: STRICT` in `module-inventory.yaml` in a single commit — no endpoint work — which
locks in what they already do:

- `pos-bulk-loader`
- `pos-documents`
- `pos-image`
- `pos-marketing`
- `pos-people-contact`
- `pos-security-service`
- `pos-supplier`

Already `STRICT`: `pos-vehicle-inventory`.

## Suggested order

1. **Ratchet the clean modules** above to `errorSchema: STRICT` (policy-only change).
2. **Small modules** — `pos-people` (1), `pos-event-receiver` (5), `pos-mcp-server` (5),
   `pos-tax` (5), `pos-shop-manager` (7), `pos-vehicle-fitment` (7): one PR each, small enough
   to also flip the policy in the same change.
3. **Mid-size** — `pos-order` (17), `pos-tenant` (18), `pos-invoice` (21), `pos-customer` (24).
4. **Large** — `pos-warranty` (53), `pos-price` (54), `pos-inventory` (59), `pos-location` (62),
   `pos-catalog` (92), `pos-workorder` (95), `pos-accounting` (117): split per controller;
   the per-operation tables below are grouped so one controller's paths sit together.

Until the list is empty, run `API Artifacts Sync` with `validation_mode: report` (its default).

## Per-module detail

### `pos-accounting` — 117 findings

| Operation | Wrong error bodies |
|---|---|
| `GET /v1/accounting/ap/bills` | `400` → `PageVendorBillSummaryResponse` |
| `POST /v1/accounting/ap/payments` | `400, 409, 500` → `APPaymentResponse` |
| `GET /v1/accounting/ap/payments/by-ref/{paymentRef}` | `404` → `APPaymentResponse` |
| `GET /v1/accounting/ap/payments/{paymentId}` | `404` → `APPaymentResponse` |
| `GET /v1/accounting/audit/actor/{actorId}` | `400, 404, 500` → `AuditTrailResponse` |
| `GET /v1/accounting/audit/by-order/{orderId}` | `404, 500` → `AuditTrailResponse` |
| `GET /v1/accounting/audit/invoice/{invoiceId}` | `404, 500` → `AuditTrailResponse` |
| `GET /v1/accounting/audit/order/{orderId}` | `404, 500` → `AuditTrailResponse` |
| `GET /v1/accounting/audit/range` | `400, 500` → `AuditTrailResponse` |
| `GET /v1/accounting/audit/type/{type}` | `400, 500` → `AuditTrailResponse` |
| `GET /v1/accounting/credit-memos` | `400` → `PageCreditMemoResponse` |
| `POST /v1/accounting/credit-memos` | `400, 404, 409` → `CreditMemoResponse` |
| `GET /v1/accounting/credit-memos/{creditMemoId}` | `404` → `CreditMemoResponse` |
| `GET /v1/accounting/default-mappings` | `403` → `DefaultGLMappingListResponse` |
| `POST /v1/accounting/default-mappings` | `400, 403` → `DefaultGLMappingResponse` |
| `GET /v1/accounting/default-mappings/global` | `403` → `DefaultGLMappingResponse` |
| `GET /v1/accounting/default-mappings/resolve` | `403, 404` → `DefaultGLMappingResponse` |
| `GET /v1/accounting/default-mappings/search` | `403` → `DefaultGLMappingResponse` |
| `GET /v1/accounting/default-mappings/{id}` | `403` → `DefaultGLMappingResponse` |
| `PUT /v1/accounting/default-mappings/{id}` | `403` → `DefaultGLMappingResponse` |
| `GET /v1/accounting/events` | `403` → `PageAccountingEventResponse` |
| `POST /v1/accounting/events` | `400` → `AccountingEventResponse` |
| `GET /v1/accounting/events/contract` | `403` → `EventEnvelopeContract` |
| `GET /v1/accounting/events/{eventId}` | `404` → `AccountingEventResponse` |
| `POST /v1/accounting/events/{eventId}/reprocess` | `400, 404, 409` → `AccountingEventResponse` |
| `POST /v1/accounting/events/{eventId}/retry` | `404` → `AccountingEventResponse` |
| `POST /v1/accounting/export` | `400, 403` → `ExportJobResponse` |
| `GET /v1/accounting/export/history` | `403` → `PageExportJobResponse` |
| `GET /v1/accounting/export/status/{jobId}` | `403, 404` → `ExportJobResponse` |
| `GET /v1/accounting/gl-accounts` | `400, 403` → `GLAccountListResponse` |
| `POST /v1/accounting/gl-accounts` | `400` → `GLAccountResponse` |
| `GET /v1/accounting/gl-accounts/{glAccountId}` | `404` → `GLAccountResponse` |
| `PUT /v1/accounting/gl-accounts/{glAccountId}` | `404` → `GLAccountResponse` |
| `POST /v1/accounting/gl-accounts/{glAccountId}/activate` | `404` → `GLAccountResponse` |
| `POST /v1/accounting/gl-accounts/{glAccountId}/archive` | `404` → `GLAccountResponse` |
| `GET /v1/accounting/gl-accounts/{glAccountId}/balance` | `404` → `GLAccountBalanceResponse` |
| `POST /v1/accounting/gl-accounts/{glAccountId}/deactivate` | `404` → `GLAccountResponse` |
| `POST /v1/accounting/invoice-revenue/reconcile` | `400, 403` → `InvoiceRevenueReconcileResponse` |
| `POST /v1/accounting/invoice/invoices` | `404, 409, 503` → `InvoiceGenerationResponse` |
| `GET /v1/accounting/invoice/rules/{customerId}` | `404, 503` → `BillingRuleRefResponse` |
| `GET /v1/accounting/journal-entries` | `400, 403` → `PagedResponseJournalEntryResponse` |
| `POST /v1/accounting/journal-entries` | `400` → `JournalEntryResponse` |
| `POST /v1/accounting/mapping-keys` | `400` → `MappingKeyResponse` |
| `GET /v1/accounting/mapping-keys/{mappingKeyId}` | `404` → `MappingKeyResponse` |
| `PUT /v1/accounting/mapping-keys/{mappingKeyId}` | `400, 404` → `MappingKeyResponse` |
| `POST /v1/accounting/mappings` | `400` → `GLMappingCreateResponse` |
| `POST /v1/accounting/mappings/resolve` | `400` → `GLMappingResolveResponse` |
| `GET /v1/accounting/payment-applications` | `400` → `PagePaymentApplicationListRow` |
| `POST /v1/accounting/payments/{paymentId}/applications` | `400, 404, 409` → `PaymentApplicationResponse` |
| `GET /v1/accounting/posting-categories` | `403` → `PostingCategoryListResponse` |
| `POST /v1/accounting/posting-categories` | `400` → `PostingCategoryResponse` |
| `GET /v1/accounting/posting-categories/{postingCategoryId}` | `404` → `PostingCategoryResponse` |
| `PUT /v1/accounting/posting-categories/{postingCategoryId}` | `400, 404` → `PostingCategoryResponse` |
| `GET /v1/accounting/posting-categories/{postingCategoryId}/mapping-keys` | `403, 404` → `MappingKeyListResponse` |
| `GET /v1/accounting/posting-rules` | `400, 403` → `PostingRuleSetListResponse` |
| `POST /v1/accounting/posting-rules` | `400` → `PostingRuleSetResponse` |
| `PUT /v1/accounting/posting-rules/{postingRuleSetId}` | `409` → `PostingRuleSetResponse` |
| `GET /v1/accounting/reports/export` | `401, 403` → `PageReportExportResponse` |
| `POST /v1/accounting/reports/export` | `400, 401, 403` → `ReportExportResponse` |
| `GET /v1/accounting/reports/export/{exportId}` | `401, 403, 404` → `ReportExportResponse` |
| `GET /v1/accounting/reports/financial/balance-sheet` | `400, 401, 403` → `BalanceSheetReport` |
| `GET /v1/accounting/reports/financial/drilldown/accounts/{statementLineCode}` | `400, 401, 403` → `AccountDrilldownResponse` |
| `GET /v1/accounting/reports/financial/drilldown/journal-lines/{accountId}` | `400, 401, 403` → `JournalLineDrilldownResponse` |
| `GET /v1/accounting/reports/financial/income-statement` | `400, 401, 403` → `IncomeStatementReport` |
| `GET /v1/accounting/reports/location/labor-overhead` | `400, 401, 403` → `LaborOverheadCostReport` |
| `GET /v1/accounting/vendor-bills` | `400` → `PageVendorBillListRow` |
| `POST /v1/accounting/vendor-bills` | `400` → `VendorBillResponse` |
| `GET /v1/accounting/vendor-bills/event/{eventId}` | `404` → `VendorBillResponse` |
| `POST /v1/accounting/vendor-bills/match` | `400` → `VendorBillResponse` |
| `POST /v1/accounting/vendor-bills/match-candidates/{candidateId}/select` | `404` → `VendorBillResponse` |
| `GET /v1/accounting/vendor-bills/{billId}` | `404` → `VendorBillResponse` |
| `POST /v1/accounting/vendor-bills/{billId}/resolve-exception` | `404` → `VendorBillResponse` |
| `GET /v1/accounting/vendors/{vendorId}` | `404` → `VendorResponse` |

### `pos-workorder` — 95 findings

| Operation | Wrong error bodies |
|---|---|
| `GET /v1/workexec/approvalConfigurations/applicable` | `404` → `ApprovalConfigurationResponse` |
| `GET /v1/workexec/approvalConfigurations/{approvalId}` | `404` → `ApprovalConfigurationResponse` |
| `PUT /v1/workexec/approvalConfigurations/{approvalId}` | `404` → `ApprovalConfigurationResponse` |
| `GET /v1/workorders/analytics/open-by-customer` | `401, 403` → `OpenWorkordersByCustomerResponse` |
| `GET /v1/workorders/analytics/reopened` | `401, 403` → `ReopenedWorkorderAnalyticsResponse` |
| `GET /v1/workorders/analytics/technician-labor` | `401, 403` → `TechnicianLaborAnalyticsResponse` |
| `GET /v1/workorders/changeRequests/{changeId}` | `404` → `ChangeRequestResponse` |
| `POST /v1/workorders/changeRequests/{changeId}/approve` | `400, 404` → `ChangeRequestResponse` |
| `POST /v1/workorders/changeRequests/{changeId}/decline` | `400, 404` → `ChangeRequestResponse` |
| `POST /v1/workorders/changeRequests/{changeId}/emergency-override` | `400, 403, 404` → `ChangeRequestResponse` |
| `POST /v1/workorders/estimates/from-appointment` | `400, 401` → `CreateEstimateFromAppointmentResponse` |
| `GET /v1/workorders/estimates/{estimateId}` | `404` → `EstimateResponse` |
| `POST /v1/workorders/estimates/{estimateId}/decline` | `400, 404` → `EstimateResponse` |
| `POST /v1/workorders/estimates/{estimateId}/items` | `400, 409, 422` → `EstimateItemResponse` |
| `PATCH /v1/workorders/estimates/{estimateId}/items/{itemId}` | `400, 409, 422` → `EstimateItemResponse` |
| `POST /v1/workorders/estimates/{estimateId}/reopen` | `400, 404` → `EstimateResponse` |
| `GET /v1/workorders/estimates/{estimateId}/summary` | `404` → `EstimateSummaryResponse` |
| `GET /v1/workorders/search` | `400` → `PageWorkorderSearchResult` |
| `GET /v1/workorders/status-transitions` | `401, 403` → `WorkorderStatusTransitionsResponse` |
| `POST /v1/workorders/workSessions/start` | `400` → `WorkSessionResponse` |
| `POST /v1/workorders/workSessions/{workSessionId}/breaks` | `400, 404` → `BreakSegmentResponse` |
| `POST /v1/workorders/workSessions/{workSessionId}/breaks/{breakSegmentId}/stop` | `400, 404` → `BreakSegmentResponse` |
| `POST /v1/workorders/workSessions/{workSessionId}/stop` | `400, 404` → `WorkSessionResponse` |
| `GET /v1/workorders/{workorderId}` | `404` → `WorkorderResponse` |
| `POST /v1/workorders/{workorderId}/changeRequests` | `400, 404` → `ChangeRequestResponse` |
| `POST /v1/workorders/{workorderId}/complete` | `400, 404` → `CompleteWorkorderResponse` |
| `GET /v1/workorders/{workorderId}/completion-preconditions` | `404` → `CompletionPreconditionsResponse` |
| `GET /v1/workorders/{workorderId}/detail` | `404` → `WorkorderDetailResponse` |
| `POST /v1/workorders/{workorderId}/generate-invoice` | `404, 409` → `InvoiceGenerationResponse` |
| `GET /v1/workorders/{workorderId}/labor` | `403` → `WorkorderLaborEntryResponse` |
| `PUT /v1/workorders/{workorderId}/labor/{entryId}/adjust` | `400, 403` → `WorkorderLaborEntryResponse` |
| `POST /v1/workorders/{workorderId}/labor/{entryId}/stop` | `400, 403, 404` → `WorkorderLaborEntryResponse` |
| `GET /v1/workorders/{workorderId}/operationalContext` | `404` → `OperationalContextResponse` |
| `POST /v1/workorders/{workorderId}/operationalContext/override` | `404, 409` → `OperationalContextResponse` |
| `GET /v1/workorders/{workorderId}/parts/adjustments` | `404` → `WorkorderPartAdjustmentEventResponse` |
| `POST /v1/workorders/{workorderId}/parts/consume` | `404, 422` → `WorkorderPartUsageEventResponse` |
| `POST /v1/workorders/{workorderId}/parts/correct` | `404, 422` → `WorkorderPartAdjustmentEventResponse` |
| `POST /v1/workorders/{workorderId}/parts/issue` | `400, 404, 409, 422` → `WorkorderPartUsageEventResponse` |
| `POST /v1/workorders/{workorderId}/parts/return` | `404, 422` → `WorkorderPartUsageEventResponse` |
| `POST /v1/workorders/{workorderId}/parts/returnUnused` | `404` → `WorkorderPartAdjustmentEventResponse` |
| `POST /v1/workorders/{workorderId}/parts/substitute` | `404` → `WorkorderPartAdjustmentEventResponse` |
| `GET /v1/workorders/{workorderId}/parts/usageHistory` | `404` → `WorkorderPartUsageEventResponse` |
| `POST /v1/workorders/{workorderId}/parts/{partId}/complete` | `400, 404` → `WorkorderItemCompletionResponse` |
| `DELETE /v1/workorders/{workorderId}/position` | `404` → `ServicePositionResponse` |
| `GET /v1/workorders/{workorderId}/position` | `404` → `ServicePositionResponse` |
| `PUT /v1/workorders/{workorderId}/position` | `404` → `ServicePositionResponse` |
| `POST /v1/workorders/{workorderId}/reopen` | `400, 404` → `ReopenWorkorderResponse` |
| `POST /v1/workorders/{workorderId}/services/{serviceId}/labor/start` | `400, 403, 404` → `WorkorderLaborEntryResponse` |
| `POST /v1/workorders/{workorderId}/services/{serviceLineId}/complete` | `400, 404` → `WorkorderItemCompletionResponse` |
| `POST /v1/workorders/{workorderId}/start` | `400, 404, 409` → `WorkorderStartResponse` |
| `GET /v1/workorders/{workorderId}/technician` | `404` → `TechnicianAssignmentResponse` |
| `POST /v1/workorders/{workorderId}/technician` | `400, 403, 404` → `TechnicianAssignmentResponse` |
| `PUT /v1/workorders/{workorderId}/technician` | `400, 403, 404` → `TechnicianAssignmentResponse` |

### `pos-catalog` — 92 findings

| Operation | Wrong error bodies |
|---|---|
| `POST /v1/catalog-items/{type}` | `400` → `CatalogItemResponseDto` |
| `PUT /v1/catalog-items/{type}/{catalogId}` | `400, 404` → `CatalogItemResponseDto` |
| `POST /v1/catalog/bulk-ingest` | `400` → `BulkIngestResponse` |
| `POST /v1/catalog/labor-standards/bulk-ingest` | `400` → `BulkIngestResponse` |
| `GET /v1/catalog/labor-standards/conflicts` | `401, 403` → `LaborStandardConflictDto` |
| `POST /v1/catalog/services/bulk-ingest` | `400` → `BulkIngestResponse` |
| `POST /v1/catalogs` | `400` → `CatalogDto` |
| `GET /v1/catalogs/{catalogId}` | `404` → `CatalogDto` |
| `PUT /v1/catalogs/{catalogId}` | `400, 404` → `CatalogDto` |
| `POST /v1/products` | `400, 409` → `ProductDto` |
| `PUT /v1/products/items/{itemId}/standard-cost` | `400` → `ItemCostsDto` |
| `GET /v1/products/noninventory/{productId}` | `404` → `NonInventoryProductDto` |
| `POST /v1/products/price-books` | `400` → `PriceBookDto` |
| `GET /v1/products/price-books/{priceBookId}` | `404` → `PriceBookDto` |
| `PUT /v1/products/price-books/{priceBookId}` | `404` → `PriceBookDto` |
| `POST /v1/products/price-books/{priceBookId}/rules` | `409` → `PriceBookRuleDto` |
| `PUT /v1/products/price-books/{priceBookId}/rules/{ruleId}` | `409` → `PriceBookRuleDto` |
| `GET /v1/products/pricing/effective-price/{locationId}/{productId}` | `404` → `EffectiveLocationPriceResponseDto` |
| `POST /v1/products/pricing/guardrail-policies` | `400` → `LocationPriceOverrideResponseDto` |
| `POST /v1/products/pricing/location-overrides` | `400, 403` → `LocationPriceOverrideResponseDto` |
| `POST /v1/products/pricing/location-overrides/{overrideId}/approve` | `400, 404, 409` → `LocationPriceOverrideResponseDto` |
| `POST /v1/products/pricing/location-overrides/{overrideId}/reject` | `400, 404, 409` → `LocationPriceOverrideResponseDto` |
| `GET /v1/products/search` | `400` → `CatalogSearchResultDto` |
| `GET /v1/products/services/{serviceId}` | `404` → `ServiceDto` |
| `POST /v1/products/substitution-groups` | `400` → `SubstitutionGroupDto` |
| `GET /v1/products/substitution-groups/{groupId}` | `404` → `SubstitutionGroupDto` |
| `POST /v1/products/substitution-groups/{groupId}/members` | `400, 404, 409` → `SubstitutionGroupDto` |
| `DELETE /v1/products/substitution-groups/{groupId}/members/{productId}` | `404` → `SubstitutionGroupDto` |
| `POST /v1/products/uom-conversions` | `400` → `UomConversionDto` |
| `GET /v1/products/uom-conversions/{id}` | `404` → `UomConversionDto` |
| `PUT /v1/products/uom-conversions/{id}` | `400, 404` → `UomConversionDto` |
| `GET /v1/products/{productId}` | `404` → `ProductDto` |
| `PUT /v1/products/{productId}` | `400, 404, 409` → `ProductDto` |
| `GET /v1/products/{productId}/detail` | `400, 404, 500` → `ProductDetailView` |
| `GET /v1/products/{productId}/lifecycle` | `404` → `ProductLifecycleResponse` |
| `PUT /v1/products/{productId}/lifecycle` | `400, 403, 404, 409` → `ProductLifecycleResponse` |
| `POST /v1/products/{productId}/msrp` | `400, 403, 409` → `ProductMsrpDto` |
| `GET /v1/products/{productId}/msrp/active` | `404` → `ProductMsrpDto` |
| `PUT /v1/products/{productId}/msrp/{msrpId}` | `400, 403, 404, 409` → `ProductMsrpDto` |
| `POST /v1/products/{productId}/replacements` | `400, 404` → `ReplacementOption` |
| `GET /v1/products/{productId}/substitutes` | `404` → `ProductDto` |
| `PUT /v1/products/{productId}/tracking-level` | `400, 404` → `ProductDto` |
| `GET /v1/products/{productId}/uoms` | `404` → `ProductUomDto` |
| `POST /v1/products/{productId}/uoms` | `400, 404, 409` → `ProductUomDto` |
| `PUT /v1/products/{productId}/uoms/{uomId}` | `400, 404` → `ProductUomDto` |
| `POST /v1/service-package-members/bulk-ingest` | `400` → `BulkIngestResponse` |
| `GET /v1/service-packages` | `401, 403` → `ServicePackageResponseDto` |
| `POST /v1/service-packages` | `401, 403, 409, 422` → `ServicePackageResponseDto` |
| `POST /v1/service-packages/bulk-ingest` | `400` → `BulkIngestResponse` |
| `GET /v1/service-packages/{packageId}` | `401, 403, 404` → `ServicePackageResponseDto` |
| `POST /v1/service-packages/{packageId}/members` | `401, 403, 404, 409` → `ServicePackageResponseDto` |
| `DELETE /v1/service-packages/{packageId}/members/{memberId}` | `401, 403, 404` → `ServicePackageResponseDto` |

### `pos-location` — 62 findings

| Operation | Wrong error bodies |
|---|---|
| `POST /v1/locations` | `409, 422` → `LocationResponseDTO` |
| `POST /v1/locations/bays/bulk-ingest` | `400, 403` → `BulkIngestResponse` |
| `POST /v1/locations/bulk-ingest` | `400` → `BulkIngestResponse` |
| `POST /v1/locations/storage-locations/bulk-ingest` | `400, 403` → `BulkIngestResponse` |
| `GET /v1/locations/top-level` | `404` → `LocationResponseDTO` |
| `POST /v1/locations/{childId}/parents/{parentId}` | `400, 409` → `ProblemDetail` |
| `GET /v1/locations/{locationId}` | `404` → `LocationResponseDTO` |
| `PATCH /v1/locations/{locationId}` | `404, 409, 422` → `LocationResponseDTO` |
| `PUT /v1/locations/{locationId}` | `404, 409, 422` → `LocationResponseDTO` |
| `GET /v1/locations/{locationId}/bays` | `400, 404` → `PageBayResponse` |
| `POST /v1/locations/{locationId}/bays` | `400, 404, 409` → `BayResponse` |
| `GET /v1/locations/{locationId}/bays/{bayId}` | `400, 404` → `BayResponse` |
| `PATCH /v1/locations/{locationId}/bays/{bayId}` | `400, 404, 409` → `BayResponse` |
| `GET /v1/locations/{locationId}/children` | `400` → `LocationResponseDTO` |
| `GET /v1/locations/{locationId}/defaults` | `404` → `SiteDefaultsResponse` |
| `PUT /v1/locations/{locationId}/defaults` | `400, 404, 422` → `SiteDefaultsResponse` |
| `GET /v1/locations/{locationId}/descendants` | `400, 404` → `LocationDescendantResponseDTO` |
| `GET /v1/locations/{locationId}/responsible-person` | `404` → `PersonDTO` |
| `POST /v1/locations/{siteId}/storage-locations` | `400, 404, 409` → `StorageLocationResponse` |
| `GET /v1/locations/{siteId}/storage-locations/topology` | `404` → `StorageLocationTopologyResponse` |
| `GET /v1/locations/{siteId}/storage-locations/{storageLocationId}` | `404` → `StorageLocationResponse` |
| `PATCH /v1/locations/{siteId}/storage-locations/{storageLocationId}` | `400, 404, 409, 422` → `StorageLocationResponse` |
| `POST /v1/mobile-units` | `409` → `MobileUnitResponse` |
| `POST /v1/mobile-units/bulk-ingest` | `400, 403` → `BulkIngestResponse` |
| `GET /v1/mobile-units/{id}` | `404` → `MobileUnitResponse` |
| `PATCH /v1/mobile-units/{id}` | `403, 409` → `MobileUnitResponse` |
| `PUT /v1/mobile-units/{id}/coverage-rules` | `404` → `CoverageRuleResponse` |
| `POST /v1/service-areas` | `400, 409` → `ServiceAreaResponse` |
| `PATCH /v1/service-areas/{id}` | `400, 404` → `ServiceAreaResponse` |
| `PUT /v1/service-areas/{id}/postal-codes` | `400, 404` → `ServiceAreaResponse` |
| `GET /v1/storage-locations/{storageLocationId}/validation` | `400, 403` → `StorageLocationValidationResponseDTO` |
| `POST /v1/travel-buffer-policies` | `409` → `TravelBufferPolicyResponse` |
| `PATCH /v1/travel-buffer-policies/{id}` | `400, 404` → `TravelBufferPolicyResponse` |

### `pos-inventory` — 59 findings

| Operation | Wrong error bodies |
|---|---|
| `GET /v1/inventory/availability/{productId}` | `400` → `LocationAvailabilityDto` |
| `POST /v1/inventory/availability/{productId}` | `501` → `InventoryAvailabilityResponse` |
| `POST /v1/inventory/bulk-ingest` | `400` → `BulkIngestResponse` |
| `POST /v1/inventory/cycleCount/recount` | `400, 403, 404` → `CountResponse` |
| `POST /v1/inventory/cycleCount/submit` | `400, 404` → `CountResponse` |
| `GET /v1/inventory/cycleCount/task/{taskId}` | `404` → `CycleCountTaskResponse` |
| `POST /v1/inventory/cycleCountAdjustments` | `400` → `AdjustmentResponse` |
| `GET /v1/inventory/cycleCountAdjustments/{adjustmentId}` | `404` → `AdjustmentResponse` |
| `POST /v1/inventory/cycleCountAdjustments/{adjustmentId}/approve` | `400, 403` → `AdjustmentResponse` |
| `POST /v1/inventory/cycleCountAdjustments/{adjustmentId}/reject` | `400, 403` → `AdjustmentResponse` |
| `POST /v1/inventory/cycleCountPlans` | `400` → `CycleCountPlanResponse` |
| `POST /v1/inventory/cycleCountPlans/bulk-ingest` | `400, 403` → `BulkIngestResponse` |
| `GET /v1/inventory/cycleCountPlans/{planId}` | `404` → `CycleCountPlanResponse` |
| `PUT /v1/inventory/cycleCountPlans/{planId}/status` | `400, 403, 404, 409` → `CycleCountPlanResponse` |
| `GET /v1/inventory/cycleCountPlans/{planId}/tasks` | `403, 404` → `CycleCountTaskResponse` |
| `POST /v1/inventory/cycleCountPlans/{planId}/tasks` | `400, 403, 404, 409` → `CycleCountTaskGenerationResponse` |
| `POST /v1/inventory/cycleCountSchedules` | `400` → `CycleCountScheduleResponse` |
| `DELETE /v1/inventory/cycleCountSchedules/{scheduleId}` | `403, 404` → `CycleCountScheduleResponse` |
| `GET /v1/inventory/cycleCountSchedules/{scheduleId}` | `404` → `CycleCountScheduleResponse` |
| `PUT /v1/inventory/cycleCountSchedules/{scheduleId}` | `400, 403, 404` → `CycleCountScheduleResponse` |
| `POST /v1/inventory/cycleCountTolerances` | `400` → `CycleCountToleranceResponse` |
| `PUT /v1/inventory/cycleCountTolerances/{toleranceId}` | `400` → `CycleCountToleranceResponse` |
| `POST /v1/inventory/locations/{locationId}/deactivate` | `400, 404, 409` → `DeactivateLocationResponse` |
| `GET /v1/inventory/locations/{locationId}/inventory-inquiry` | `400` → `LocationInventoryInquiryResponse` |
| `GET /v1/inventory/locations/{locationId}/inventory-rollup` | `400` → `LocationInventoryRollupResponse` |
| `POST /v1/inventory/opening-stock/bulk-ingest` | `400, 403` → `BulkIngestResponse` |
| `POST /v1/inventory/putaway/bulk-ingest` | `400, 403` → `BulkIngestResponse` |
| `POST /v1/inventory/putaway/tasks/generate` | `400` → `PutawayTaskResponse` |
| `POST /v1/inventory/putaway/tasks/{taskId}/claim` | `404` → `PutawayTaskResponse` |
| `POST /v1/inventory/putaway/tasks/{taskId}/execute` | `400, 403, 404, 422` → `PutawayExecutionResponse` |
| `POST /v1/inventory/replenishment/policies` | `400` → `ReplenishmentPolicyResponse` |
| `PUT /v1/inventory/replenishment/policies/{policyId}` | `400, 404` → `ReplenishmentPolicyResponse` |
| `POST /v1/inventory/replenishment/policies/{policyId}/snooze` | `404, 422` → `ReplenishmentPolicyResponse` |
| `GET /v1/inventory/sites/{siteId}/inventory-rollup` | `400` → `SiteInventoryRollupResponse` |

### `pos-price` — 54 findings

| Operation | Wrong error bodies |
|---|---|
| `POST /v1/labor-rate-adjustments/bulk-ingest` | `400` → `BulkIngestResponse` |
| `GET /v1/labor-rates` | `401, 403` → `LaborRateResponse` |
| `POST /v1/labor-rates` | `401, 403, 422` → `LaborRateResponse` |
| `GET /v1/labor-rates/adjustments` | `401, 403` → `LaborRateAdjustmentResponse` |
| `POST /v1/labor-rates/adjustments` | `401, 403, 422` → `LaborRateAdjustmentResponse` |
| `POST /v1/labor-rates/bulk-ingest` | `400` → `BulkIngestResponse` |
| `POST /v1/price/bulk-ingest` | `400, 403` → `BulkIngestResponse` |
| `GET /v1/price/restrictions/rules` | `401` → `RestrictionRuleResponse` |
| `POST /v1/price/restrictions/rules` | `400, 401, 403` → `RestrictionRuleResponse` |
| `DELETE /v1/price/restrictions/rules/{ruleId}` | `401, 403, 404` → `RestrictionRuleResponse` |
| `GET /v1/price/restrictions/rules/{ruleId}` | `401, 404` → `RestrictionRuleResponse` |
| `POST /v1/price/restrictions:evaluate` | `400, 401, 503` → `RestrictionEvaluationResponse` |
| `POST /v1/price/restrictions:override` | `400, 401, 403, 404` → `RestrictionOverrideResponse` |
| `GET /v1/price/snapshots/{snapshotId}` | `403, 404` → `PricingSnapshotResponse` |
| `POST /v1/promotions/offers` | `400, 403, 409, 422` → `PromotionOfferResponse` |
| `POST /v1/promotions/offers/apply` | `400, 403` → `ApplyPromotionResponse` |
| `GET /v1/promotions/offers/by-code/{promoCode}` | `403, 404` → `PromotionOfferResponse` |
| `GET /v1/promotions/offers/{id}` | `403, 404` → `PromotionOfferResponse` |
| `PATCH /v1/promotions/offers/{id}/activate` | `403, 404, 422` → `PromotionOfferResponse` |
| `PATCH /v1/promotions/offers/{id}/deactivate` | `403, 404, 422` → `PromotionOfferResponse` |
| `GET /v1/promotions/offers/{promotionId}/rules` | `403` → `EligibilityRuleResponse` |
| `POST /v1/promotions/offers/{promotionId}/rules` | `400, 403, 404` → `EligibilityRuleResponse` |
| `POST /v1/promotions/offers/{promotionId}/rules/evaluate` | `400, 403` → `EligibilityDecisionResponse` |

### `pos-warranty` — 53 findings

| Operation | Wrong error bodies |
|---|---|
| `POST /v1/warranty/claims` | `400` → `ClaimResponse` |
| `GET /v1/warranty/claims/{id}` | `404` → `ClaimResponse` |
| `PUT /v1/warranty/claims/{id}` | `404, 409` → `ClaimResponse` |
| `POST /v1/warranty/claims/{id}/cancel` | `404, 409` → `ClaimResponse` |
| `POST /v1/warranty/claims/{id}/close` | `404, 409` → `ClaimResponse` |
| `POST /v1/warranty/claims/{id}/decision` | `400, 404, 409` → `ClaimResponse` |
| `POST /v1/warranty/claims/{id}/eligibility` | `404, 409` → `ClaimResponse` |
| `POST /v1/warranty/claims/{id}/lines` | `404, 409` → `ClaimResponse` |
| `DELETE /v1/warranty/claims/{id}/lines/{lineId}` | `404, 409` → `ClaimResponse` |
| `POST /v1/warranty/claims/{id}/notes` | `404` → `ClaimResponse` |
| `POST /v1/warranty/claims/{id}/part-returns` | `404, 409` → `PartReturnResponse` |
| `DELETE /v1/warranty/claims/{id}/photos` | `404, 409` → `ClaimResponse` |
| `POST /v1/warranty/claims/{id}/photos` | `404, 409` → `ClaimResponse` |
| `PUT /v1/warranty/claims/{id}/reimbursement` | `400, 404, 409` → `ReimbursementResponse` |
| `POST /v1/warranty/claims/{id}/reimbursement/submit` | `404, 409` → `ReimbursementResponse` |
| `POST /v1/warranty/claims/{id}/settlements` | `404, 409, 422, 502` → `SettlementResponse` |
| `POST /v1/warranty/claims/{id}/submit` | `404, 409, 422` → `ClaimResponse` |
| `PUT /v1/warranty/part-returns/{id}` | `404, 409` → `PartReturnResponse` |
| `POST /v1/warranty/policies` | `400, 404` → `PolicyResponse` |
| `GET /v1/warranty/policies/applicable` | `400` → `PolicyResponse` |
| `GET /v1/warranty/policies/{id}` | `404` → `PolicyResponse` |
| `PUT /v1/warranty/policies/{id}` | `400, 404` → `PolicyResponse` |
| `POST /v1/warranty/providers` | `400` → `ProviderResponse` |
| `GET /v1/warranty/providers/{id}` | `404` → `ProviderResponse` |
| `PUT /v1/warranty/providers/{id}` | `400, 404` → `ProviderResponse` |
| `POST /v1/warranty/registrations` | `400, 404` → `RegistrationResponse` |
| `GET /v1/warranty/registrations/{id}` | `404` → `RegistrationResponse` |
| `PUT /v1/warranty/registrations/{id}` | `400, 404` → `RegistrationResponse` |

### `pos-customer` — 24 findings

| Operation | Wrong error bodies |
|---|---|
| `GET /v1/crm/billing-terms` | `401, 403` → `BillingTermsRef` |
| `GET /v1/crm/commercial-accounts/{partyId}/contacts` | `401, 403, 404` → `GetCommercialAccountContactsResponse` |
| `POST /v1/crm/commercial-accounts/{partyId}/relationships` | `400, 401, 403, 404, 409` → `CreatePartyRelationshipResponse` |
| `GET /v1/crm/persons` | `401, 403` → `GetPersonResponse` |
| `POST /v1/crm/persons` | `400, 401, 403` → `CreatePersonResponse` |
| `GET /v1/crm/persons/{personId}` | `401, 403, 404` → `GetPersonResponse` |
| `GET /v1/crm/snapshot/party/{partyId}/billing-rules` | `403, 404` → `BillingRuleRef` |
| `GET /v1/crm/{id}` | `404` → `CustomerDTO` |
| `PUT /v1/crm/{id}` | `404` → `CustomerDTO` |
| `POST /v1/customer/bulk-ingest` | `400` → `BulkIngestResponse` |
| `POST /v1/customer/commercial/bulk-ingest` | `400` → `BulkIngestResponse` |

### `pos-invoice` — 21 findings

| Operation | Wrong error bodies |
|---|---|
| `POST /v1/billing/auth/elevate` | `400, 401` → `ElevateResponse` |
| `GET /v1/invoices/{invoiceId}/artifacts` | `404` → `InvoiceArtifact` |
| `POST /v1/invoices/{invoiceId}/artifacts/{artifactRefId}/download-token` | `404` → `ArtifactDownloadToken` |
| `POST /v1/invoices/{invoiceId}/cancel` | `409` → `OrderInvoiceResponse` |
| `POST /v1/invoices/{invoiceId}/payments` | `400, 403, 422, 503` → `InitiatePaymentResponse` |
| `POST /v1/invoices/{invoiceId}/payments/{paymentId}/capture` | `403, 404` → `InitiatePaymentResponse` |
| `POST /v1/invoices/{invoiceId}/payments/{paymentId}/refunds` | `404, 409, 422` → `RefundPaymentResponse` |
| `POST /v1/invoices/{invoiceId}/receipts` | `404` → `ReceiptResponse` |
| `POST /v1/invoices/{invoiceId}/receipts/{receiptId}/reprint` | `404, 409` → `ReceiptResponse` |
| `GET /v1/invoices/{invoiceId}/refunds` | `404` → `InvoiceRefundResponse` |
| `POST /v1/invoices/{invoiceId}/refunds` | `404, 422` → `RefundPaymentResponse` |
| `POST /v1/refunds` | `400` → `RefundPaymentResponse` |

### `pos-tenant` — 18 findings

| Operation | Wrong error bodies |
|---|---|
| `POST /v1/platform/accounts` | `409` → `AccountResponse` |
| `GET /v1/platform/accounts/{id}` | `404` → `AccountResponse` |
| `PATCH /v1/platform/accounts/{id}` | `404, 409` → `AccountResponse` |
| `PUT /v1/platform/accounts/{id}/billing-profile` | `404` → `BillingProfileResponse` |
| `POST /v1/platform/accounts/{id}/contacts` | `404` → `AccountContactResponse` |
| `PUT /v1/platform/accounts/{id}/contacts/{contactId}` | `404` → `AccountContactResponse` |
| `POST /v1/platform/tenants` | `404, 409` → `TenantResponse` |
| `GET /v1/platform/tenants/{id}` | `404` → `TenantResponse` |
| `PATCH /v1/platform/tenants/{id}` | `404, 409` → `TenantResponse` |
| `POST /v1/platform/tenants/{id}/decommission` | `404, 409` → `TenantResponse` |
| `POST /v1/platform/tenants/{id}/reactivate` | `404, 409` → `TenantResponse` |
| `POST /v1/platform/tenants/{id}/suspend` | `404, 409` → `TenantResponse` |

### `pos-order` — 17 findings

| Operation | Wrong error bodies |
|---|---|
| `POST /v1/orders/carts/{orderId}/cancel` | `400, 403, 404, 409` → `CancellationResponse` |
| `POST /v1/orders/carts/{orderId}/cancel/retry` | `403, 404, 409` → `CancellationResponse` |
| `GET /v1/orders/price-overrides` | `400` → `PriceOverrideDetail` |
| `POST /v1/orders/price-overrides` | `400, 403` → `PriceOverrideResult` |
| `GET /v1/orders/price-overrides/{overrideId}` | `404` → `PriceOverrideDetail` |
| `POST /v1/orders/price-overrides/{overrideId}/approve` | `400, 403, 404` → `PriceOverrideDetail` |
| `POST /v1/orders/price-overrides/{overrideId}/reject` | `400, 403, 404` → `PriceOverrideDetail` |

### `pos-shop-manager` — 7 findings

| Operation | Wrong error bodies |
|---|---|
| `POST /v1/appointments` | `501` → `AppointmentResponse` |
| `GET /v1/appointments/{appointmentId}` | `501` → `AppointmentResponse` |
| `GET /v1/appointments/{appointmentId}/assignments` | `403` → `AssignmentResponse` |
| `POST /v1/appointments/{appointmentId}/conflict-override` | `409` → `ConflictResponse` |
| `GET /v1/schedules/view` | `400, 404` → `ScheduleViewResponse` |
| `GET /v1/shop-manager/{locationId}/technicians/{personId}/person` | `404` → `PersonDTO` |

### `pos-vehicle-fitment` — 7 findings

| Operation | Wrong error bodies |
|---|---|
| `POST /v1/fitments/bulk-ingest` | `400` → `BulkIngestResponse` |
| `POST /v1/vehicle-fitment/hints` | `400, 404` → `HintResponse` |
| `POST /v1/vehicle-fitment/hints/filter-products` | `400` → `FilterProductsResponse` |
| `GET /v1/vehicle-fitment/hints/{hintId}` | `404` → `HintResponse` |
| `PUT /v1/vehicle-fitment/hints/{hintId}` | `400, 404` → `HintResponse` |

### `pos-event-receiver` — 5 findings

| Operation | Wrong error bodies |
|---|---|
| `POST /v1/eventTypes` | `400` → `EventTypeResponse` |
| `GET /v1/eventTypes/code/{typeCode}` | `404` → `EventTypeResponse` |
| `PUT /v1/eventTypes/code/{typeCode}` | `400` → `EventTypeResponse` |
| `GET /v1/eventTypes/{id}` | `404` → `EventTypeResponse` |
| `PUT /v1/eventTypes/{id}` | `404` → `EventTypeResponse` |

### `pos-mcp-server` — 5 findings

| Operation | Wrong error bodies |
|---|---|
| `POST /v1/mcp/documents` | `400` → `DocumentIngestionJobResponse` |
| `POST /v1/prompts` | `409` → `SystemPromptResponse` |
| `GET /v1/prompts/{id}` | `404` → `SystemPromptResponse` |
| `PUT /v1/prompts/{id}` | `404, 409` → `SystemPromptResponse` |

### `pos-tax` — 5 findings

| Operation | Wrong error bodies |
|---|---|
| `POST /v1/tax/calculate` | `400, 500` → `TaxCalculationResponse` |
| `POST /v1/tax/exemption-certificates` | `400` → `ExemptionCertificateResponse` |
| `GET /v1/tax/exemption-certificates/{id}` | `404` → `ExemptionCertificateResponse` |
| `PUT /v1/tax/exemption-certificates/{id}` | `404` → `ExemptionCertificateResponse` |

### `pos-people` — 1 findings

| Operation | Wrong error bodies |
|---|---|
| `POST /v1/people/bulk-ingest` | `400` → `BulkIngestResponse` |

