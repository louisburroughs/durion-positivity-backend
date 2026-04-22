# pos-bulk-ingest-lib

Shared library providing the `AbstractBulkIngestController` base class and common request/response types for domain bulk-ingest endpoints. This is a library dependency, not a deployable service.

## Responsibilities

- Define a standardised `POST /bulk-ingest` contract for domain services
- Provide `BulkIngestRequest<T>`, `BulkIngestResult`, and `BulkIngestResponse` DTOs
- Give domain services a typed base controller to extend with minimal boilerplate

## Key Classes

- `AbstractBulkIngestController<T>` — base controller exposing `POST /bulk-ingest`; subclasses implement `processRecords(BulkIngestRequest<T>)`
- `BulkIngestRequest<T>` — payload: `jobId`, `locationId`, `operatorId`, `records` list
- `BulkIngestResult` — per-row result: `rowIndex`, `entityId`, `success`, `errorCode`, `errorMessage`
- `BulkIngestResponse` — aggregated response: `totalSubmitted`, `successCount`, `failureCount`, `results`

## Usage

Add to the domain module's `pom.xml`:

```xml
<dependency>
    <groupId>com.positivity</groupId>
    <artifactId>pos-bulk-ingest-lib</artifactId>
</dependency>
```

Extend `AbstractBulkIngestController<YourDto>` and add a `@RequestMapping` prefix.

Current implementations: `pos-catalog`, `pos-inventory`, `pos-location`, `pos-vehicle-inventory`, `pos-vehicle-fitment`, `pos-people`, `pos-customer`, `pos-price`.

## Dependencies

No internal `pos-*` module dependencies. Depends only on Spring Web and Jakarta Validation.

This module is a library dependency — there is no runnable service to start.
