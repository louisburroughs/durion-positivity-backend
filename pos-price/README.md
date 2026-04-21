# pos-price

## Bulk Ingest

### Bulk Import Base Prices

```http
POST /v1/price/bulk-ingest
Authorization: Bearer {token}
Content-Type: application/json

{
  "jobId": "00000000-0000-0000-0000-000000000001",
  "locationId": "00000000-0000-0000-0000-000000000002",
  "operatorId": "user-123",
  "records": [
    {
      "productId": "00000000-0000-0000-0000-000000000000",
      "msrp": "199.99",
      "currency": "USD",
      "effectiveFrom": "2026-04-20T00:00:00Z"
    }
  ]
}
```

- **Auth:** `hasAuthority('pricing:base_price:create')`
- **Request:** `BulkIngestRequest<BasePriceBulkIngestRecord>` (fields: `productId*` [UUID], `msrp*` [decimal], `currency*` [ISO-4217 3-char], `effectiveFrom*` [ISO-8601 instant])
- **Response:** `BulkIngestResponse`
- **Event:** `PRICE_BULK_INGEST`
