# pos-customer

CRM service for the Durion POS platform. Manages the customer party model (persons, commercial parties, organisations), contact roles, communication preferences, party relationships, account tiers, promotion redemptions, and customer-linked vehicles.

## Responsibilities

- Create and manage customers as parties (person and commercial entities)
- Track contact roles and communication preferences per party
- Manage party relationships (e.g., fleet owner to vehicle)
- Assign and query account tiers for loyalty programs
- Record and validate promotion redemptions
- Maintain customer-linked vehicle associations (CRM vehicles)
- Handle workorder event-driven customer updates via `WorkorderEventHandler`
- Support bulk customer import via `POST /v1/customer/bulk-ingest`

## Key Classes

- `CustomerService` — core customer lifecycle (create, read, update, deactivate)
- `PartyService` — generic party model shared by person and commercial entities
- `PersonService` — person-specific attributes linked to party
- `AccountTierService` — evaluates and assigns customer loyalty tiers
- `PromotionRedemptionService` — validates and records promotion code redemptions
- `CrmVehicleService` — links vehicles to customer accounts

## API Endpoints

- `GET /v1/customers/{id}` — retrieve a customer
- `POST /v1/customers` — create a customer
- `DELETE /v1/customers/{id}` — deactivate a customer
- `GET /v1/customers/{accountId}/tier` — get account tier
- `GET /v1/parties/{partyId}` — retrieve a party
- `GET /v1/parties/{partyId}/contacts` — list contact roles
- `GET /v1/parties/{partyId}/communicationPreferences` — communication preferences
- `GET /v1/customers/{customerId}/vehicles/{vehicleId}` — get a linked vehicle
- `DELETE /v1/customers/{customerId}/vehicles/{vehicleId}` — unlink a vehicle
- `POST /v1/customer/bulk-ingest` — bulk import customers (auth: `crm:party:create`)

## Configuration

| Property | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL |
| `EUREKA_SERVER_URL` | required | Eureka service discovery URL |

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-shared-dtos` — shared vehicle DTOs
- `pos-bulk-ingest-lib` — bulk-ingest base controller

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-customer -am spring-boot:run
```
