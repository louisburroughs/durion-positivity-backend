# pos-workorder

Core workorder service for the Durion Positivity ETSMS platform. Manages the full workorder lifecycle from estimate creation through WIP execution, technician time tracking, parts usage, invoice generation, and completion. Integrates with the customer, vehicle, tax, and invoice services.

## Responsibilities

- Create and manage workorders with state machine transitions (estimate, WIP, complete, cancelled)
- Build estimates from appointments with line item services and parts
- Track WIP status and dashboard summaries per shop
- Assign and reassign technicians to workorder service lines
- Record labor time entries and work sessions for payroll and billing
- Manage part usage, part substitutions, and part pick coordination
- Apply and validate promotional offers on workorder lines
- Generate invoices at completion by calling `pos-invoice`
- Calculate tax on estimate totals via `pos-tax`, resolving the estimate's shop-location address from `pos-location` as the tax jurisdiction
- Emit Kafka events for cross-service consumption (configurable; off by default)

## Key Classes

- `WorkorderService` — workorder lifecycle: create, update status, retrieve, cancel
- `EstimateService` — estimate creation, item management, and appointment conversion
- `WipService` — WIP board state and job-time totals
- `WorkorderLaborService` — labor line management on workorder service lines
- `WorkorderPartUsageService` — part consumption recording and adjustments
- `WorkorderInvoiceService` — invokes `pos-invoice` to generate an invoice at close
- `TechnicianAssignmentService` — assign and reassign technicians
- `DashboardService` — aggregated shop dashboard data
- `TaxClient` — outbound client for `pos-tax`; forwards `X-User: pos-workorder` and `X-Authorities: tax:calculate` on the tax-calculate call so the request satisfies `tax:calculate` enforcement (matching `pos-invoice`'s `TaxServiceClient`)

## API Endpoints

- `POST /v1/workorders` — create a workorder
- `GET /v1/workorders/{workorderId}` — retrieve a workorder
- `DELETE /v1/workorders/{workorderId}` — cancel a workorder
- `GET /v1/workorders/customer/{customerId}` — workorders for a customer
- `GET /v1/workorders/location/{locationId}` — workorders for a location
- `POST /v1/estimates` — create an estimate
- `GET /v1/estimates/{estimateId}` — retrieve an estimate
- `GET /v1/estimates/{estimateId}/summary` — estimate summary
- `DELETE /v1/estimates/{estimateId}/items/{itemId}` — remove an estimate line
- `GET /v1/workorders/wip` — WIP board summary
- `GET /v1/workorders/job-time-totals` — job time totals for WIP
- `GET /v1/workorders/pick-list` — pick list for parts
- `GET /v1/workorders/picked-items` — picked item status
- `POST /v1/workexec/time-tracking` — submit labor time entry
- `GET /v1/workexec/adjustments` — time entry adjustments

## Configuration

| Property                       | Default                    | Description                      |
| ------------------------------ | -------------------------- | -------------------------------- |
| `SPRING_DATASOURCE_URL`        | required                   | PostgreSQL connection URL        |
| `EUREKA_SERVER_URL`            | required                   | Eureka service discovery URL     |
| `pos.customer.base-url`        | `http://pos-customer:8084` | Customer service URL             |
| `pos.vehicle.base-url`         | `http://pos-vehicle:8088`  | Vehicle service URL              |
| `pos.tax.base-url`             | `http://pos-tax:8091`      | Tax service URL                  |
| `pos.location.base-url`        | `http://pos-location:8080` | Location service URL             |
| `workorder.kafka.enabled`      | `false`                    | Enable Kafka event emission      |
| `workorder.kafka.events-topic` | `workorder.events.v1`      | Kafka topic for workorder events |

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-shared-dtos` — invoice generation request DTOs
- `pos-tax-common` — tax calculation request/response types

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-workorder -am spring-boot:run
```
