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

- `POST /v1/bulk-jobs` — create a bulk load job (`tenantId` names the tenant it loads into, see Multitenancy)
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
| `pos.tenancy.default-tenant-id`   | alpha default tenant    | ADR-0062 §9 transitional binding of unbound requests and of jobs created without a `tenantId` (WARN); unset for strict |
| `pos.tenancy.registry.*`          | `STATIC`                | Which tenants a job may target: `pos.tenancy.tenants`, else the default; `REMOTE` asks pos-tenant |

## Multitenancy (ADR-0062, plan WS8)

Every bulk-load job targets one tenant. `POST /v1/bulk-jobs` takes `tenantId`; `BulkLoadTenantBinding`
accepts an active tenant of the module's `TenantRegistry` (`pos.tenancy.tenants`, or pos-tenant's list in
`REMOTE` mode) or the platform tenant (platform data: the role template's `roles.csv`), refuses any tenant the
caller is not itself bound to (403 `BULK_JOB_TENANT_FORBIDDEN`) — the platform operator included, so it loads
platform data and nothing else —
and refuses a request with no `tenantId` (400 `BULK_JOB_TENANT_REQUIRED`) unless the transitional default
tenant is configured, in which case the default is used and logged at WARN. An unknown tenant is 400
`BULK_JOB_TENANT_UNKNOWN`.

`BulkLoadJobServiceImpl` binds the target (`TenantContext.runAs`) around the job's create, so the job row lands
in that tenant, and around the whole batch run (`startProcessing`), so every audit and mapping row and every
call to a sibling service carries it: `AuthorizationHeaderRelay` puts the bound tenant on each outbound call as
`X-Tenant-Id` beside the operator's bearer token and the caller's own gateway authority headers
(`GatewayCallerHeaders` — a sibling is called directly, so those headers are the only thing it authenticates
with; a header the call site already set, such as an ingest writer's per-target `X-Authorities`, is left
alone), and the launch records the tenant as a non-identifying `tenantId`
batch parameter. Both open their transaction inside the binding through a `TransactionTemplate` (the
connection binds `app.current_tenant` at checkout and the Hibernate session fixes its tenant when it opens).
Every other endpoint — status, listing, upload, mappings, review queue, cancel, retry — reads and writes under
the request's own binding, so a job is visible only to callers bound to its tenant, and is continued by the
operator who created it. That is why a cross-tenant target is refused at create rather than allowed and left
stranded: the creator could not see the job afterwards, and a caller who could would fail its ownership check.
Loading into a tenant on its behalf needs a credential genuinely bound to that tenant, which is out of scope
for WS8.

This module depends on `pos-tenancy-common`: every entity (`bulk_load_job`, `bulk_load_record_audit`,
`bulk_load_column_mapping`, `tus_upload`) extends `TenantScopedEntity`; the Spring Batch metadata tables are
the module's only global tables (`src/main/resources/db/tenancy-global-tables.txt`). The request tenant is
bound by `TenantContextFilter` from `X-Tenant-Id`, and every connection checkout binds `app.current_tenant`
for row-level security. The application pool connects as the non-owner `pos_app` role (Compose:
`SPRING_DATASOURCE_USERNAME` / `POS_APP_PASSWORD`); Flyway alone uses the owner credential
(`SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD`).

Scheduled work: `TusUploadServiceImpl.cleanupExpiredUploads` is per-tenant (`TenantIterator`; `tus_upload`
is tenant-scoped, so each tenant's expired uploads are visible only under its binding). There is no
platform-scoped scheduler.

Proof: `TenantIsolationIT` (a job written as tenant A is invisible to tenant B and to an unbound connection,
through the repository and through raw SQL; the one-active-job-per-operator index is per tenant) and
`TenancySchemaConformanceIT` (every non-whitelisted table has `tenant_id`, RLS enabled and forced, and the
`tenant_isolation` policy; the pool is `pos_app` with no bypass), both on Testcontainers Postgres
(`./mvnw -pl pos-bulk-loader verify`, Docker required).

## Dependencies

- `pos-security-common` — gateway header-based security
- `pos-tenancy-common` — ADR-0062 tenant context, connection binding, Hibernate resolver
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
