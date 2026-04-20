# pos-bulk-loader

Bulk import orchestrator service for Durion POS.

## API Surface (Wave 1)

Base path: `/v1/bulk-jobs`

- `POST /v1/bulk-jobs` - create bulk load job (`201`)
- `GET /v1/bulk-jobs/{jobId}` - get job (`200`)
- `GET /v1/bulk-jobs` - list operator jobs (`200`)
- `POST /v1/bulk-jobs/{jobId}/cancel` - cancel job (`200`)
- `POST /v1/bulk-jobs/{jobId}/upload` - upload source file (`200`)
- `POST /v1/bulk-jobs/{jobId}/process` - start processing (`200`)
- `GET /v1/bulk-jobs/{jobId}/mappings` - read proposed mappings (`200`)
- `PUT /v1/bulk-jobs/{jobId}/mappings` - approve mappings (`200`)
- `GET /v1/bulk-jobs/{jobId}/audit` - list row audit records (`200`)
- `GET /v1/bulk-jobs/{jobId}/error-report` - download CSV error report (`200`)

## Security

- Uses gateway header-based security via `pos-security-common` (`GatewaySecurityConfig`).
- State-changing endpoints require `BULK_IMPORT_EXECUTE`.
- Read endpoints require `BULK_IMPORT_READ`.

## Event Logging

State-changing routes emit `pos-events` events with apiVersion `1`:

- `BULK_LOADER_JOB_CREATE`
- `BULK_LOADER_JOB_CANCEL`
- `BULK_LOADER_FILE_UPLOAD`
- `BULK_LOADER_JOB_START`
- `BULK_LOADER_MAPPING_APPROVE`

Registered event types are defined in:

- `src/main/java/com/positivity/bulkloader/internal/config/BulkLoaderEventTypes.java`
- `src/main/java/com/positivity/bulkloader/internal/config/BulkLoaderEventTypeInitializer.java`
