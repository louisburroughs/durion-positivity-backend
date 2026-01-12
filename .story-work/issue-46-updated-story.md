# [BACKEND] [STORY] Availability: Normalize Manufacturer Inventory Feeds (Stub via Positivity)

> **Story Status:** Ready for Development  
> **Domain:** Inventory Control  
> **Clarification Issue:** #239 (Resolved)

---

## 1. Story Intent

Enable the POS system to receive and normalize manufacturer inventory availability data delivered via the Positivity integration service. This story focuses on consuming a standardized feed format, resolving manufacturer part numbers to internal product IDs, and storing normalized availability data for downstream consumption by availability query and allocation services.

**Key Objective:** Establish a reliable pipeline for manufacturer inventory data that maintains referential integrity with the internal product catalog while handling unmapped parts gracefully.

---

## 2. Actors & Stakeholders

### Primary Actors
- **Positivity Integration Service**: External service that delivers standardized manufacturer inventory feeds
- **pos-inventory Service**: Consumes and normalizes the feed data
- **pos-product Service**: Authoritative source for manufacturer part number mapping

### Stakeholders
- **Inventory Management Team**: Requires accurate manufacturer availability data
- **Operations Team**: Monitors unmapped parts and maintains part mapping
- **Shop Staff**: Depends on accurate availability for ordering decisions

---

## 3. Preconditions

1. **Manufacturer Part Map Exists**: The `pos-product` service maintains a `ManufacturerPartMap` that resolves manufacturer part numbers to internal product IDs.
   - API endpoint available: `GET /product/v1/manufacturer-part-map:resolve`
   - Batch API available: `POST /product/v1/manufacturer-part-map:resolve`
   - Map maintenance is handled by a separate story (not in scope here)

2. **Positivity Feed Available**: The Positivity integration service delivers manufacturer inventory feeds in a standardized JSON format.

3. **Target Database**: `pos-inventory` service has database schema for storing normalized availability records.

---

## 4. Functional Behavior

### 4.1 Feed Consumption

**Input**: Positivity delivers a standardized JSON feed with the following schema:

```json
{
  "schemaVersion": "1",
  "manufacturerId": "UUIDv7",
  "asOf": "2026-01-12T00:00:00Z",
  "items": [
    {
      "manufacturerPartNumber": "string",
      "availableQty": 12,
      "uom": "EA",
      "unitPrice": { "amount": 100.00, "currency": "USD" },
      "leadTimeDays": { "min": 1, "max": 3 },
      "minOrderQty": 4,
      "packSize": 1,
      "sourceLocationCode": "string"
    }
  ]
}
```

**Processing Steps**:
1. Receive feed from Positivity (REST pull or event stream - implementation choice)
2. Validate schema version compatibility
3. Extract manufacturerId and asOf timestamp
4. For each item in the feed:
   - Extract manufacturerPartNumber
   - Call pos-product API to resolve to internal productId
   - If mapped: prepare normalized record with productId
   - If unmapped: record in unmapped parts backlog for operations follow-up

### 4.2 Part Number Resolution

**API Call to pos-product**:
```
GET /product/v1/manufacturer-part-map:resolve?manufacturerId={manufacturerId}&manufacturerPartNumber={mpn}

Response:
{
  "productId": "UUIDv7",
  "confidence": "HIGH",
  "metadata": {...}
}
```

**Batch Resolution** (preferred for performance):
```
POST /product/v1/manufacturer-part-map:resolve
Body:
{
  "manufacturerId": "UUIDv7",
  "parts": [
    { "mpn": "ABC-123" },
    { "mpn": "XYZ-789" }
  ]
}

Response:
{
  "results": [
    { "mpn": "ABC-123", "productId": "...", "confidence": "HIGH" },
    { "mpn": "XYZ-789", "productId": null, "reason": "UNMAPPED" }
  ]
}
```

### 4.3 Normalized Record Storage

For successfully mapped items, store a normalized availability record:

**NormalizedAvailability Entity**:
- `id`: UUIDv7 (primary key)
- `productId`: UUIDv7 (foreign key to pos-product)
- `manufacturerId`: UUIDv7
- `manufacturerPartNumber`: string
- `availableQty`: decimal
- `uom`: string
- `unitPrice`: money (amount + currency)
- `leadTimeDaysMin`: integer
- `leadTimeDaysMax`: integer
- `minOrderQty`: integer (nullable - store if present, null otherwise)
- `packSize`: integer
- `sourceLocationCode`: string
- `asOf`: timestamp
- `receivedAt`: timestamp (system-generated)
- `schemaVersion`: string

### 4.4 Unmapped Parts Handling

For items that cannot be resolved to a productId:
- Insert into `UnmappedManufacturerParts` table/queue:
  - `manufacturerId`
  - `manufacturerPartNumber`
  - `firstSeen`: timestamp
  - `lastSeen`: timestamp
  - `occurrenceCount`: integer (increment on duplicate)
  - `status`: PENDING_REVIEW

**Note**: Operations team will use a separate tool/story to review and create mappings for unmapped parts.

---

## 5. Alternate / Error Flows

### 5.1 Invalid Schema Version
- **Trigger**: Incoming feed has unsupported schemaVersion
- **Action**: Log error, reject feed, alert operations
- **Outcome**: Feed is not processed; system remains stable

### 5.2 pos-product API Unavailable
- **Trigger**: Batch resolution API call fails or times out
- **Action**: Log error, optionally retry with exponential backoff
- **Outcome**: Feed processing is delayed; no data is partially stored

### 5.3 Partial Mapping Failure
- **Trigger**: Some items resolve successfully, others fail
- **Action**: 
  - Store successfully mapped items
  - Add unmapped items to UnmappedManufacturerParts
  - Log summary (e.g., "Processed 95/100 items, 5 unmapped")
- **Outcome**: Partial success; unmapped items tracked for follow-up

### 5.4 Duplicate Feed Delivery
- **Trigger**: Positivity re-sends the same feed (same manufacturerId + asOf)
- **Action**: Detect duplicate using upsert logic on (productId, manufacturerId, asOf)
- **Outcome**: Idempotent processing; no duplicate records created

### 5.5 Missing Optional Fields
- **Trigger**: `minOrderQty` is absent from feed item
- **Action**: Store null in database field
- **Outcome**: Record is saved; enforcement logic (out of scope) handles null gracefully

---

## 6. Business Rules

### BR-1: System of Record for Manufacturer Part Mapping
- **Authority**: `pos-product` service is the authoritative source
- **Access**: Via API only (no direct database access)
- **Scope**: Mapping maintenance is a precondition; this story consumes the map

### BR-2: Feed Format
- **Format**: Single standardized JSON format with versioned schema
- **Manufacturer-Specific Parsing**: NOT supported in pos-inventory; handled by Positivity
- **Transport**: REST pull or event stream (implementation choice)
- **Idempotency**: Required; use (manufacturerId, asOf, productId) as natural key

### BR-3: Minimum Order Quantity Handling
- **Presence**: Field may be absent in feed
- **Storage**: Store if present; otherwise store null
- **Enforcement**: Out of scope for this story (not enforced in ordering/cart logic)
- **Complex Rules**: Out of scope (tiered min orders, mixed-case constraints)

### BR-4: Unmapped Parts Policy
- **Action**: Surface for operations follow-up
- **Storage**: Optional backlog table/queue
- **Resolution**: Separate story for mapping maintenance

### BR-5: Data Freshness
- **Timestamp**: Use `asOf` from feed as authoritative time
- **Retention**: Define TTL or archival policy (implementation decision)

---

## 7. Data Requirements

### 7.1 Input Schema (from Positivity)

```json
{
  "schemaVersion": "1",
  "manufacturerId": "UUIDv7",
  "asOf": "ISO8601 timestamp",
  "items": [
    {
      "manufacturerPartNumber": "string",
      "availableQty": "number (decimal)",
      "uom": "string (e.g., EA, CASE)",
      "unitPrice": {
        "amount": "number (decimal)",
        "currency": "string (ISO 4217)"
      },
      "leadTimeDays": {
        "min": "integer",
        "max": "integer"
      },
      "minOrderQty": "integer (optional)",
      "packSize": "integer",
      "sourceLocationCode": "string"
    }
  ]
}
```

### 7.2 Output Schema (NormalizedAvailability)

**Database Table**: `normalized_availability`

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| id | UUID | No | Primary key |
| productId | UUID | No | FK to pos-product |
| manufacturerId | UUID | No | Source manufacturer |
| manufacturerPartNumber | VARCHAR | No | Original MPN |
| availableQty | DECIMAL | No | Quantity available |
| uom | VARCHAR | No | Unit of measure |
| unitPriceAmount | DECIMAL | No | Price amount |
| unitPriceCurrency | VARCHAR | No | Price currency |
| leadTimeDaysMin | INTEGER | No | Minimum lead time |
| leadTimeDaysMax | INTEGER | No | Maximum lead time |
| minOrderQty | INTEGER | Yes | Minimum order quantity |
| packSize | INTEGER | No | Pack size |
| sourceLocationCode | VARCHAR | No | Manufacturer location |
| asOf | TIMESTAMP | No | Feed timestamp |
| receivedAt | TIMESTAMP | No | System receipt time |
| schemaVersion | VARCHAR | No | Feed schema version |

**Indexes**:
- Primary key on `id`
- Unique index on `(productId, manufacturerId, asOf)`
- Index on `asOf` for time-based queries
- Index on `productId` for availability lookups

### 7.3 Unmapped Parts Schema

**Database Table**: `unmapped_manufacturer_parts`

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| id | UUID | No | Primary key |
| manufacturerId | UUID | No | Source manufacturer |
| manufacturerPartNumber | VARCHAR | No | Unmapped MPN |
| firstSeen | TIMESTAMP | No | First occurrence |
| lastSeen | TIMESTAMP | No | Most recent occurrence |
| occurrenceCount | INTEGER | No | Number of times seen |
| status | ENUM | No | PENDING_REVIEW, RESOLVED, IGNORED |

**Indexes**:
- Primary key on `id`
- Unique index on `(manufacturerId, manufacturerPartNumber)`
- Index on `status` for operations queries

---

## 8. Acceptance Criteria

### AC-1: Feed Consumption
- [ ] System successfully receives feed from Positivity service
- [ ] Schema version is validated before processing
- [ ] Invalid schema versions are rejected with appropriate error

### AC-2: Part Number Resolution
- [ ] System calls pos-product API to resolve manufacturer part numbers
- [ ] Batch resolution API is used for performance (preferred)
- [ ] Successfully mapped items are processed and stored
- [ ] Unmapped items are recorded in unmapped parts backlog

### AC-3: Normalized Record Storage
- [ ] Mapped items are stored in `normalized_availability` table
- [ ] All required fields are populated correctly
- [ ] Optional `minOrderQty` field is stored as null when absent from feed
- [ ] Duplicate feeds are handled idempotently (no duplicate records)

### AC-4: Unmapped Parts Handling
- [ ] Unmapped parts are recorded in `unmapped_manufacturer_parts` table
- [ ] First occurrence timestamp is captured
- [ ] Occurrence count is incremented for repeated unmapped parts
- [ ] Operations team can query unmapped parts for review

### AC-5: Error Handling
- [ ] Invalid schema versions are logged and rejected
- [ ] pos-product API unavailability is handled gracefully (retry with backoff)
- [ ] Partial mapping failures result in partial success (mapped items stored, unmapped tracked)
- [ ] All error conditions are logged with appropriate detail

### AC-6: Data Integrity
- [ ] Foreign key to pos-product is enforced (productId must exist)
- [ ] Unique constraint on (productId, manufacturerId, asOf) prevents duplicates
- [ ] All timestamps are stored in UTC
- [ ] Currency codes follow ISO 4217

### AC-7: API Contract Compliance
- [ ] pos-product resolution API is called correctly (not direct DB access)
- [ ] Batch API payload format matches contract
- [ ] Response parsing handles all documented response fields

### AC-8: Performance
- [ ] Batch resolution is used instead of individual API calls
- [ ] Feed processing completes within reasonable time (e.g., < 60s for 1000 items)
- [ ] Database indexes support efficient availability queries

---

## 9. Audit & Observability

### 9.1 Logging Requirements
- **Feed Receipt**: Log `manufacturerId`, `asOf`, item count, `receivedAt`
- **Mapping Results**: Log successful mappings count, unmapped count
- **Errors**: Log schema validation failures, API errors, database errors
- **Performance**: Log processing duration, batch sizes

### 9.2 Metrics
- **Feed Processing Rate**: Items/second
- **Mapping Success Rate**: Percentage of items successfully mapped
- **Unmapped Parts Count**: Trending count of unmapped parts
- **API Latency**: p50, p95, p99 for pos-product API calls
- **Processing Duration**: End-to-end feed processing time

### 9.3 Alerts
- **Schema Version Mismatch**: Immediate alert on unsupported version
- **High Unmapped Rate**: Alert if > 10% of items are unmapped
- **API Unavailability**: Alert if pos-product API is unreachable
- **Processing Failures**: Alert on feed processing errors

### 9.4 Audit Trail
- All feed processing events should be auditable:
  - Feed received timestamp
  - Processing outcome (success/partial/failure)
  - Items processed count
  - Items unmapped count
  - Error details (if any)

---

## 10. Original Story (Preserved for Traceability)

> *This section contains the original story text before clarification resolution.*
> *It is preserved for audit and traceability purposes only.*

**Original Request**: Normalize Manufacturer Inventory Feeds (Stub via Positivity)

**Original Open Questions** (Now Resolved):
1. Mapping Authority - **Resolved**: pos-product is SoR, accessed via API
2. Feed Specification - **Resolved**: Single standardized JSON format
3. Minimum Order Rules - **Resolved**: Optional presence in feed, store if present

**Clarification Issue**: #239  
**Resolution Date**: 2026-01-12

---

## Story Metadata

- **Story ID**: #46
- **Domain**: domain:inventory
- **Type**: type:story
- **Status**: status:ready-for-dev
- **Priority**: TBD
- **Clarification Issue**: #239 (Resolved)
- **Labels**: domain:inventory, type:story, status:ready-for-dev
- **Dependencies**: 
  - pos-product service with manufacturer part map API
  - Positivity integration service with standardized feed format

---

**Story Finalized**: 2026-01-12  
**Updated By**: Story Authoring Agent (Clarification Resolution)
