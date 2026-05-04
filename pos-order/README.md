# pos-order

Order management service for the Durion Positivity ETSMS platform. Manages sales order carts, price override requests with approval workflows, and order cancellations.

## Responsibilities

- Create and manage sales order carts and line items
- Accept and validate price override requests from service advisors
- Auto-approve small discounts; queue large discounts for manager approval
- Record approval and rejection decisions with full audit trail
- Cancel orders with inventory release coordination
- Enforce idempotency on price override creation to prevent duplicate submissions

## Key Classes

- `SalesOrderService` — cart lifecycle (create, add/update/remove lines, retrieve)
- `PriceOverrideService` — override request, auto-approval evaluation, approval/rejection
- `OrderCancellationService` — cancellation workflow with pre/post state snapshots
- `PriceOverrideServiceImpl` — auto-approval thresholds (≤10% or ≤$50), idempotency key check, audit record creation

## API Endpoints

- `POST /v1/orders/carts` — create a sales order cart
- `GET /v1/orders/carts/{orderId}` — retrieve a cart
- `POST /v1/orders/carts/{orderId}/items` — add a line item
- `PUT /v1/orders/carts/{orderId}/items/{lineId}` — update a line item
- `DELETE /v1/orders/carts/{orderId}/items/{lineId}` — remove a line item
- `POST /v1/orders/{orderId}/cancel` — cancel an order
- `POST /v1/orders/price-overrides` — request a price override (idempotent via `idempotencyKey`)
- `GET /v1/orders/price-overrides/{overrideId}` — retrieve an override
- `GET /v1/orders/price-overrides/pending` — list pending approvals
- `POST /v1/orders/price-overrides/{overrideId}/approve` — approve an override
- `POST /v1/orders/price-overrides/{overrideId}/reject` — reject an override

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-shared-dtos` — shared DTOs

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-order -am spring-boot:run
```
