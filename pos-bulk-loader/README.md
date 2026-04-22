# pos-bulk-loader

Bulk import orchestrator service for Durion POS. Provides CSV file upload, column mapping, Spring Batch processing, and review queue for failed records.

## Module Overview

- **Service Name**: `POS-BULK-LOADER`
- **Port**: `8090` (default, overridable via `SERVER_PORT`)
- **Database**: `pos_bulk_loader_db` (PostgreSQL)
- **Eureka**: Registers with service discovery
- **Gateway Route**: `/bulk-loader/**` → `lb://POS-BULK-LOADER` (StripPrefix=1)

## Architecture

This module orchestrates bulk data imports across multiple domain services:

1. **Job Creation**: Operator creates a bulk load job specifying target domain (catalog, inventory, location)
2. **File Upload**: CSV file uploaded and parsed
3. **Column Mapping**: Automatic mapping proposal with manual review/override capability
4. **Spring Batch Processing**: Chunk-based processing (500 records/chunk) with domain-specific validation
5. **Review Queue**: Failed records captured with error details for operator review

## API Surface (Wave 1)

Base path: `/v1/bulk-jobs`

- `POST /v1/bulk-jobs` - create bulk load job (`201`)
- `GET /v1/bulk-jobs/{jobId}` - get job (`200`)
- `GET /v1/bulk-jobs` - list operator jobs (`200`)
- `POST /v1/bulk-jobs/{jobId}/cancel` - cancel job (`200`)
- `POST /v1/bulk-jobs/{jobId}/upload` - upload source file (`200`)
- `POST /v1/bulk-jobs/{jobId}/process` - launch Spring Batch processing (`200`)
- `GET /v1/bulk-jobs/{jobId}/mappings` - read proposed mappings (`200`)
- `PUT /v1/bulk-jobs/{jobId}/mappings` - approve mappings (`200`)
- `GET /v1/bulk-jobs/{jobId}/audit` - list row audit records (`200`)
- `GET /v1/bulk-jobs/{jobId}/error-report` - download CSV error report (`200`)

## Security

- Uses gateway header-based security via `pos-security-common` (`GatewaySecurityConfig`).
- State-changing endpoints require `BULK_IMPORT_EXECUTE`.
- Read endpoints require `BULK_IMPORT_READ`.
- When `/v1/bulk-jobs/{jobId}/process` runs inside an authenticated HTTP request, outbound bulk-ingest calls relay the caller bearer token in addition to gateway headers so downstream services can resolve stable user identity from JWT claims.

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

## Spring Batch Configuration

### Jobs

- **Job Name**: `catalogBulkLoadJob` (Wave 1 - catalog domain)
- **Step Name**: `catalogBulkLoadStep`
- **Chunk Size**: 500 records
- **Auto-start**: Disabled in production (`spring.batch.job.enabled: false`)
- **Trigger**: Jobs started via API endpoint (`POST /v1/bulk-jobs/{jobId}/process`), which launches the matching Spring Batch job using the persisted upload path, locationId, and operatorId

Job execution updates `bulk_load_job` with terminal status (`COMPLETED` or `FAILED`), row counts, success and failure counts, and `completedAt` when the batch run finishes.

### Spring Batch Tables

Spring Batch metadata tables are created via Flyway migration `V2__init_spring_batch_schema.sql`:

- `BATCH_JOB_INSTANCE`
- `BATCH_JOB_EXECUTION`
- `BATCH_JOB_EXECUTION_PARAMS`
- `BATCH_JOB_EXECUTION_CONTEXT`
- `BATCH_STEP_EXECUTION`
- `BATCH_STEP_EXECUTION_CONTEXT`
- `BATCH_JOB_SEQ`, `BATCH_JOB_EXECUTION_SEQ`, `BATCH_STEP_EXECUTION_SEQ`

**Note**: `spring.batch.jdbc.initialize-schema: never` — schema managed by Flyway only.

## Database Schema

### Flyway Migrations

- **V1__init_bulk_loader_schema.sql**: Domain tables (`bulk_load_job`, `bulk_load_record_audit`, `bulk_load_column_mapping`)
- **V2__init_spring_batch_schema.sql**: Spring Batch metadata tables

### Domain Tables

| Table | Purpose |
| ----- | ------- |
| `bulk_load_job` | Job metadata (domain, status, operator_id, location_id, file statistics) |
| `bulk_load_record_audit` | Per-row processing audit trail (review status, reason codes, original/corrected values) |
| `bulk_load_column_mapping` | CSV column → domain field mappings with confidence scores |

## Running Locally

### Prerequisites

- PostgreSQL 14+ running on `localhost:5432`
- Database `pos_bulk_loader_db` created
- Eureka server running on `localhost:8761` (pos-service-discovery)
- Target domain services running (pos-catalog, pos-inventory, pos-location)

### Quick Start

```bash
# From backend root
cd durion-positivity-backend

# Build module
./mvnw -pl pos-bulk-loader -am clean package

# Run with dev profile
java -jar pos-bulk-loader/target/pos-bulk-loader-*.jar --spring.profiles.active=dev

# Service registers at http://localhost:8090
```

### Configuration Properties

| Property | Default | Description |
| -------- | ------- | ----------- |
| `server.port` | `8090` | Default service port (overridable via `SERVER_PORT`) |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/pos_bulk_loader_db` | Database URL |
| `spring.batch.job.enabled` | `false` | Auto-start jobs (disabled for API-driven execution) |
| `spring.flyway.enabled` | `true` | Flyway migrations enabled |
| `eureka.client.service-url.defaultZone` | `http://localhost:8761/eureka/` | Eureka server URL |

## Integration with Domain Services

This service depends on domain-specific bulk ingest endpoints provided by:

- **pos-catalog**: `/v1/catalog/bulk-ingest` (via `pos-bulk-ingest-lib`)
- **pos-inventory**: `/v1/inventory/bulk-ingest` (via `pos-bulk-ingest-lib`)
- **pos-location**: `/v1/locations/bulk-ingest` (via `pos-bulk-ingest-lib`)

See [pos-bulk-ingest-lib/README.md](../pos-bulk-ingest-lib/README.md) for contract details.

## Supported Domain Types (Wave 1..3)

- `CATALOG`
- `INVENTORY`
- `LOCATION`
- `VEHICLE`  ← added in Wave 3 (writes to pos-vehicle-inventory `/v1/vehicles/bulk-ingest`)
- `VEHICLE_FITMENT`  ← added in Wave 3 (writes to pos-vehicle-fitment `/v1/fitments/bulk-ingest`)

## New Wave 3: Vehicle bulk-ingest

- Job: `vehicleBulkLoadJob` — implemented and wired; validates `VehicleBulkRecord` rows and POSTs bulk-ingest payloads to `pos-vehicle-inventory` at `/v1/vehicles/bulk-ingest`.
- Job: `vehicleFitmentBulkLoadJob` — implemented and wired; validates `VehicleFitmentRecord` rows and POSTs bulk-ingest payloads to `pos-vehicle-fitment` at `/v1/fitments/bulk-ingest`.
- Records are validated by `VehicleLoaderStrategy` and `VehicleFitmentLoaderStrategy` before the outbound bulk-ingest call.

Permissions: service calls target endpoints that require domain-specific create permissions (see target module README for exact scopes).
