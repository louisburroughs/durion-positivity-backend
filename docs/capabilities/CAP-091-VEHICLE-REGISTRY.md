# CAP:091 - Vehicle Registry Implementation Status

## Overview

**Capability ID:** CAP:091  
**Domain:** CRM  
**Module:** pos-vehicle-inventory  
**Status:** Architecture Complete, Implementation In Progress

## Stories Covered

| Story | Description | Backend Issue | Status |
|-------|-------------|---------------|--------|
| #102 | Create Vehicle Record with VIN and Description | [#105](https://github.com/louisburroughs/durion-positivity-backend/issues/105) | 🚧 Partial |
| #103 | Associate Vehicles to Account and/or Individual | [#104](https://github.com/louisburroughs/durion-positivity-backend/issues/104) | ⏳ Planned |
| #104 | Vehicle Lookup by VIN/Unit/Plate | [#103](https://github.com/louisburroughs/durion-positivity-backend/issues/103) | ⏳ Planned |
| #105 | Store Vehicle Care Preferences | [#102](https://github.com/louisburroughs/durion-positivity-backend/issues/102) | ⏳ Planned |
| #106 | Ingest Vehicle Updates from Workorder Execution | [#101](https://github.com/louisburroughs/durion-positivity-backend/issues/101) | ⏳ Planned |

## Current State

### What Exists

The `pos-vehicle-inventory` module has a foundation:

- ✅ Basic Vehicle entity (using JPA inheritance)
- ✅ CRUD endpoints (by ID and VIN)
- ✅ VehicleRepository with findByVIN
- ✅ REST controller with OpenAPI annotations
- ✅ Service discovery integration

### What's Required

To fully implement CAP:091 per the stories:

1. **Entity Refactoring** (Story #105):
   - UUID-based IDs (currently Long)
   - VIN normalization (`vinNormalized` field)
   - Global VIN uniqueness constraint
   - Additional fields: `accountId`, `unitNumber`, `licensePlate`, `isActive`
   - Audit fields: `createdAt`, `updatedAt`, `version`
   - Remove inheritance model, use composition

2. **Vehicle-Party Associations** (Story #104):
   - New `VehiclePartyAssociation` entity
   - Association types: OWNER, PRIMARY_DRIVER
   - Temporal validity (effectiveStartDate, effectiveEndDate)
   - Implicit owner reassignment logic
   - Service layer with transaction management

3. **Vehicle Search** (Story #103):
   - Search endpoint with ranking (exact → prefix → contains)
   - Query normalization and minimum length validation
   - Pagination support (default 25, max 50)
   - Vehicle+Owner snapshot response
   - Search result ranking logic

4. **Vehicle Care Preferences** (Story #102):
   - New `VehicleCarePreference` entity
   - JSONB column for flexible preferences
   - Common keys + free-form notes
   - Optimistic locking
   - Validation for known keys

5. **Event Ingestion** (Story #101):
   - New `EventProcessingLog` entity
   - Message listener for VehicleUpdated events
   - Idempotency via eventId tracking
   - Conflict resolution policies (mileage, VIN)
   - Dead-letter queue handling

6. **Cross-Cutting Concerns**:
   - pos-events integration for event emission
   - Event type registration on startup
   - @EmitEvent annotations on all state changes
   - ContractBehaviorIT tests for each story
   - ArchUnit tests for architecture compliance
   - Enhanced OpenAPI documentation

## Architecture

### Module Structure

```
pos-vehicle-inventory/
├── src/main/java/com/positivity/vehicle/
│   ├── PosVehicleApplication.java
│   ├── service/                          # PUBLIC API
│   │   ├── VehicleService.java
│   │   ├── VehicleAssociationService.java
│   │   ├── VehicleSearchService.java
│   │   ├── VehiclePreferencesService.java
│   │   └── VehicleEventIngestionService.java
│   └── internal/                         # PRIVATE
│       ├── controller/
│       ├── entity/
│       ├── repository/
│       ├── dto/
│       ├── config/
│       └── listener/
```

### Database Schema (Planned)

**vehicles**
- vehicle_id (UUID, PK)
- account_id (UUID, FK)
- vin (VARCHAR(17))
- vin_normalized (VARCHAR(17), UNIQUE)
- unit_number (VARCHAR(255))
- description (TEXT)
- license_plate (VARCHAR(50))
- is_active (BOOLEAN)
- created_at, updated_at, version

**vehicle_party_associations**
- association_id (UUID, PK)
- vehicle_id (UUID, FK)
- party_id (UUID)
- association_type (ENUM: OWNER, PRIMARY_DRIVER)
- effective_start_date, effective_end_date
- created_at, updated_at, version

**vehicle_care_preferences**
- id (UUID, PK)
- vehicle_id (UUID, FK, UNIQUE)
- preferences (JSONB)
- service_notes (TEXT)
- created_by_user_id, updated_by_user_id
- created_at, updated_at, version

**event_processing_log**
- log_id (UUID, PK)
- event_id (VARCHAR, UNIQUE)
- workorder_id (VARCHAR)
- vehicle_id (UUID, FK)
- status (ENUM: SUCCESS, DUPLICATE, ERROR_*, PENDING_REVIEW)
- details (JSONB)
- received_timestamp, processed_timestamp

### API Endpoints (Planned)

**Vehicle CRUD**
- POST /v1/vehicles
- GET /v1/vehicles/{id}
- PUT /v1/vehicles/{id}
- DELETE /v1/vehicles/{id}
- GET /v1/vehicles/vin/{vin}
- PUT /v1/vehicles/vin/{vin}

**Associations**
- POST /v1/vehicles/{vehicleId}/associations
- GET /v1/vehicles/{vehicleId}/associations
- PUT /v1/vehicles/{vehicleId}/associations/owner
- PUT /v1/vehicles/{vehicleId}/associations/driver

**Search**
- POST /v1/vehicles/search
- GET /v1/vehicles/{vehicleId}/snapshot

**Preferences**
- GET /v1/vehicles/{vehicleId}/preferences
- PUT /v1/vehicles/{vehicleId}/preferences

## Dependencies

### Required Additions

```xml
<dependency>
    <groupId>com.positivity</groupId>
    <artifactId>pos-events</artifactId>
</dependency>

<dependency>
    <groupId>io.hypersistence</groupId>
    <artifactId>hypersistence-utils-hibernate-63</artifactId>
    <version>3.7.0</version>
</dependency>
```

## Testing Strategy

### Contract Behavior Tests

Each story requires ContractBehaviorIT covering:

- Happy path scenarios
- Validation error cases
- Business rule violations
- Concurrency/idempotency
- Event emission verification

### ArchUnit Tests

Verify:
- Internal package encapsulation
- Service layer as public API
- No cross-module repository access
- @EmitEvent presence on state changes

## Configuration

New application properties required:

```yaml
pos:
  vehicle:
    search:
      default-limit: 25
      max-limit: 50
      min-query-length:
        vin: 6
        plate: 3
        general: 3
    preferences:
      max-payload-size: 65536
    event-ingestion:
      mileage-decrease-policy: ACCEPT_AND_FLAG
      vin-change-requires-approval: true
```

## Implementation Plan

### Phase 1: Foundation (Story #105)
1. Refactor Vehicle entity to match requirements
2. Add VIN validation and normalization utility
3. Update VehicleService with business logic
4. Add event emission
5. Create ContractBehaviorIT tests
6. Update OpenAPI documentation

### Phase 2: Associations (Story #104)
1. Create VehiclePartyAssociation entity
2. Implement VehicleAssociationService
3. Add association endpoints
4. Create ContractBehaviorIT tests
5. Emit association events

### Phase 3: Search (Story #103)
1. Create search DTOs
2. Implement VehicleSearchService with ranking
3. Add search endpoint
4. Create ContractBehaviorIT tests
5. Emit search events

### Phase 4: Preferences (Story #102)
1. Create VehicleCarePreference entity
2. Implement VehiclePreferencesService
3. Add preferences endpoints
4. Create ContractBehaviorIT tests
5. Emit preference events

### Phase 5: Event Ingestion (Story #101)
1. Create EventProcessingLog entity
2. Implement VehicleEventIngestionService
3. Add message listener
4. Implement conflict policies
5. Create ContractBehaviorIT tests

### Phase 6: Integration
1. End-to-end testing
2. Performance testing
3. Documentation finalization
4. OpenAPI spec generation

## Observability

### Events to Emit

- VEHICLE_CREATE
- VEHICLE_UPDATE
- VEHICLE_OWNER_ASSOCIATED
- VEHICLE_OWNER_REASSIGNED
- VEHICLE_PRIMARY_DRIVER_ASSIGNED
- VEHICLE_SEARCH
- VEHICLE_SELECTED_FOR_SERVICE
- VEHICLE_PREFERENCE_CREATED
- VEHICLE_PREFERENCE_UPDATED

### Metrics

- Vehicle creation/update rates
- Search latency (p50, p95, p99)
- Association operation rates
- Event ingestion status distribution
- DLQ message count

## Security

All endpoints require appropriate permissions:

- VEHICLE_CREATE
- VEHICLE_VIEW
- VEHICLE_EDIT
- VEHICLE_PARTY_ASSOC_EDIT
- VEHICLE_DEACTIVATE

## Next Steps

1. Review and approve this architecture
2. Create feature branch per story or all-in-one
3. Implement in phases as outlined
4. Create PRs with tests and documentation
5. Deploy to development environment
6. Coordinate with frontend team for integration

## References

- [Comprehensive Implementation Guide](https://github.com/louisburroughs/durion/blob/master/docs/capabilities/CAP-091/CAP-091-backend-implementation.md)
- [Backend Contract Guide](https://github.com/louisburroughs/durion/blob/master/domains/crm/.business-rules/BACKEND_CONTRACT_GUIDE.md)
- [CAP:091 Parent Issue](https://github.com/louisburroughs/durion/issues/91)

---

**Last Updated:** 2026-02-03  
**Author:** GitHub Copilot Agent  
**Status:** Ready for Implementation
