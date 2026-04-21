# pos-customer

## Bulk Ingest

### Bulk Import Customers

```http
POST /v1/customer/bulk-ingest
Authorization: Bearer {token}
Content-Type: application/json

{
  "jobId": "00000000-0000-0000-0000-000000000001",
  "locationId": "00000000-0000-0000-0000-000000000002",
  "operatorId": "user-123",
  "records": [
    {
      "firstName": "Jane",
      "lastName": "Doe",
      "email": "jane.doe@example.com",
      "phoneNumber": "+1-555-111-2222",
      "primaryAddress": "123 Main St, Springfield, IL 62701",
      "customerNumber": "CUST-001"
    }
  ]
}
```

- **Auth:** `hasAuthority('crm:party:create')`
- **Request:** `BulkIngestRequest<CustomerBulkIngestRecord>` (fields: `firstName*`, `lastName*`, `email`, `phoneNumber`, `primaryAddress`, `customerNumber`)
- **Response:** `BulkIngestResponse` (fields: `totalSubmitted`, `successCount`, `failureCount`, `results[]`)
- **Event:** `CUSTOMER_BULK_INGEST`
