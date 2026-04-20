# pos-bulk-ingest-lib

Shared API contract library for domain bulk-ingest endpoints.

## Provided Types

- `BulkIngestRequest<T>`
- `BulkIngestResult`
- `BulkIngestResponse`
- `AbstractBulkIngestController<T>`

## Controller Contract

`AbstractBulkIngestController<T>` exposes:

- `POST /bulk-ingest` (`200`)

Subclasses implement `processRecords(BulkIngestRequest<T>)` and provide module-specific routing with `@RequestMapping`.
