# pos-bulk-loader

Bulk data import orchestrator for Durion Positivity ETSMS. Accepts CSV file uploads, proposes column mappings, drives Spring Batch processing against domain service bulk-ingest endpoints, and queues failed records for operator review.

## Responsibilities

- Create and manage bulk load jobs (catalog, inventory, location, vehicle, vehicle fitment)
- Accept file uploads via standard HTTP or tus resumable upload protocol
- Auto-propose CSV column-to-field mappings with confidence scores
- Launch Spring Batch jobs that chunk records and POST to domain service `/bulk-ingest` endpoints
- Maintain a per-row audit trail; surface an error report CSV for failed rows
- Authenticate outbound bulk-ingest calls using the caller's forwarded bearer token

## Key Classes

- `BulkLoadJobService` — job lifecycle (create, cancel, status)
- `BulkLoadBatchLauncher` / `SpringBatchBulkLoadLauncher` — triggers Spring Batch jobs via API
- `TusUploadService` — manages tus resumable upload sessions and local file storage
- `ColumnMappingService` — proposes and persists CSV column mappings
- `ReviewQueueService` — surfaces failed rows with structured error codes
- `ContentDetectionService` — detects domain type from uploaded file content

## API Endpoints

Base path: `/v1/bulk-jobs`

- `POST /v1/bulk-jobs` — create a bulk load job
- `GET /v1/bulk-jobs` — list jobs for the operator
- `GET /v1/bulk-jobs/{jobId}` — get job status
- `POST /v1/bulk-jobs/{jobId}/cancel` — cancel a job
- `POST /v1/bulk-jobs/{jobId}/upload` — upload source file
- `POST /v1/bulk-jobs/{jobId}/process` — launch Spring Batch processing
- `GET /v1/bulk-jobs/{jobId}/mappings` — read proposed column mappings
- `PUT /v1/bulk-jobs/{jobId}/mappings` — approve/override column mappings
- `GET /v1/bulk-jobs/{jobId}/audit` — list per-row audit records
- `GET /v1/bulk-jobs/{jobId}/error-report` — download CSV error report
- `POST /v1/bulk-jobs/bulk-jobs/{jobId}/tus` — initiate tus upload
- `DELETE /v1/tus/{uploadId}` — cancel tus upload

## Configuration

| Property                          | Default                 | Description                            |
| --------------------------------- | ----------------------- | -------------------------------------- |
| `bulk-loader.storage.local-root`  | `/tmp/bulk-loader`      | Local directory for uploaded files     |
| `bulk-loader.tus.max-upload-size` | `536870912` (512 MB)    | Maximum tus upload size                |
| `bulk-loader.tus.expiry-hours`    | `24`                    | Hours before incomplete uploads expire |
| `pos.catalog.base-url`            | `http://localhost:8082` | Catalog bulk-ingest target             |
| `pos.vehicle-inventory.base-url`  | `http://localhost:8091` | Vehicle inventory target               |
| `pos.vehicle-fitment.base-url`    | `http://localhost:8092` | Vehicle fitment target                 |

## Dependencies

- `pos-security-common` — gateway header-based security
- `pos-events` — audit event emission
- `pos-shared-dtos` — shared DTOs
- `pos-bulk-ingest-lib` — bulk-ingest request/response contract

## Database

Uses Flyway with PostgreSQL. Key tables:

- `bulk_load_job` — job metadata
- `bulk_load_record_audit` — per-row processing results
- `bulk_load_column_mapping` — CSV column mappings
- Spring Batch metadata tables (via `V2__init_spring_batch_schema.sql`)

## Development

```bash
./mvnw -pl pos-bulk-loader -am spring-boot:run --spring.profiles.active=dev
```

Requires PostgreSQL (`pos_bulk_loader_db`) and Eureka (`pos-service-discovery`) running.
