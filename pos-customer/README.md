# pos-customer

CRM service for the Durion Positivity ETSMS platform. Manages the customer party model (persons, commercial parties, organisations), contact roles, communication preferences, party relationships, account tiers, promotion redemptions, and customer-linked vehicles.

## Responsibilities

- Create and manage customers as parties (person and commercial entities)
- Track contact roles and communication preferences per party
- Manage party relationships (e.g., fleet owner to vehicle)
- Assign and query account tiers for loyalty programs
- Record and validate promotion redemptions
- Maintain customer-linked vehicle associations (CRM vehicles); the vehicle records themselves are a read-only `ext_vehicle` replica fed by `vehicle.events.v1` from pos-vehicle-inventory (ADR-0044 §6, #843) — vehicle registry writes go directly to pos-vehicle-inventory through the gateway
- Handle workorder event-driven customer updates via `WorkorderEventHandler`
- Consume `vehicle.events.v1` (`VehicleEventsListener`, idempotent via `processed_events`) and reconcile the replica against `vehicle.manifest.v1` manifests (`VehicleManifestListener`)
- Support bulk customer import via `POST /v1/customer/bulk-ingest`

## Key Classes

- `CustomerService` — core customer lifecycle (create, read, update, deactivate)
- `PartyService` — generic party model shared by person and commercial entities
- `PersonService` — person-specific attributes linked to party
- `AccountTierService` — evaluates and assigns customer loyalty tiers
- `PromotionRedemptionService` — validates and records promotion code redemptions
- `CrmVehicleService` — read-side vehicle queries from the `ext_vehicle` replica; associations stay customer-owned per ADR-0012

## API Endpoints

- `GET /v1/customers/{id}` — retrieve a customer
- `POST /v1/customers` — create a customer
- `DELETE /v1/customers/{id}` — deactivate a customer
- `GET /v1/customers/{accountId}/tier` — get account tier
- `GET /v1/parties/{partyId}` — retrieve a party
- `GET /v1/crm/accounts/parties/duplicate-check?legalName=...` — check potential duplicate commercial parties
- `POST /v1/crm/accounts/parties:resolve` — batch-resolve party ids to display names (auth: `crm:party:view`); for sibling-service finder enrichment
- `GET /v1/parties/{partyId}/contacts` — list contact roles
- `GET /v1/parties/{partyId}/communicationPreferences` — communication preferences
- `PUT /v1/crm/accounts/parties/{partyId}/billing-rules` — upsert billing rules for a commercial party
- `GET /v1/crm/{customerId}/vehicles` — list vehicle summaries for a customer (replica-backed)
- `GET /v1/crm/{customerId}/vehicles/{vehicleId}` — get a linked vehicle (replica-backed; create/update/transfer/deactivate moved to pos-vehicle-inventory per ADR-0044)
- `POST /v1/customer/bulk-ingest` — bulk import customers (auth: `crm:party:create`)

### Unified Party Detail Behavior

- The browse endpoint `GET /v1/crm/accounts/parties` may include both `COMMERCIAL` and `PERSON` party types.
- Party-scoped detail reads now accept either party type for the same `partyId` domain.
- Endpoints that are commercial-account oriented (for example `GET /v1/crm/commercial-accounts/{partyId}/contacts`) return an empty contact list for person parties instead of `404`.

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-shared-dtos` — shared vehicle DTOs
- `pos-domain-events` — ADR-0044 envelope, topics, and versioned payload contracts
- `pos-bulk-ingest-lib` — bulk-ingest base controller

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-customer -am spring-boot:run
```
