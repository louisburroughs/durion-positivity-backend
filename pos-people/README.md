# pos-people

## Bulk Ingest

### Bulk Import Employees

```http
POST /v1/people/bulk-ingest
Authorization: Bearer {token}
Content-Type: application/json

{
  "items": [
    {
      "legalName": "John Q Public",
      "employeeNumber": "EMP-123",
      "hireDate": "2024-10-01",
      "preferredName": "John",
      "primaryEmail": "john.public@example.com",
      "primaryPhone": "+1-555-222-3333"
    }
  ],
  "idempotencyKey": "bulk-req-002"
}
```

- **Auth:** `hasAuthority('people:employee:create')`
- **Request:** `BulkIngestRequest<PersonBulkIngestRecord>` (fields: `legalName*`, `employeeNumber*`, `hireDate*` (ISO YYYY-MM-DD), `preferredName`, `primaryEmail`, `primaryPhone`)
- **Response:** `BulkIngestResponse`
- **Event:** `PEOPLE_BULK_INGEST`
