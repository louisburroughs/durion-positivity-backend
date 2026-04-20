# pos-customer

## Bulk Ingest

### Bulk Import Customers

```http
POST /v1/customer/bulk-ingest
Authorization: Bearer {token}
Content-Type: application/json

{
  "items": [
    {
      "firstName": "Jane",
      "lastName": "Doe",
      "email": "jane.doe@example.com",
      "phoneNumber": "+1-555-111-2222",
      "primaryAddress": { /* address object */ },
      "customerNumber": "CUST-001"
    }
  ],
  "idempotencyKey": "bulk-req-001"
}
```

- **Auth:** `hasAuthority('crm:party:create')`
- **Request:** `BulkIngestRequest<CustomerBulkIngestRecord>` (fields: `firstName*`, `lastName*`, `email`, `phoneNumber`, `primaryAddress`, `customerNumber`)
- **Response:** `BulkIngestResponse` (fields: `totalSubmitted`, `successCount`, `failureCount`, `results[]`)
- **Event:** `CUSTOMER_BULK_INGEST`
