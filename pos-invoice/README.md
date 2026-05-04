# pos-invoice

Invoice and payment service for the Durion Positivity ETSMS platform. Creates invoices from workorder completion, collects payments via the configured payment gateway (Stripe), manages receipt generation, handles payment reversals and voids, and enforces billing rules.

## Responsibilities

- Create and finalize invoices from workorder line items
- Collect payments with idempotency enforcement
- Capture, void, and refund payment intents via `PaymentGatewayPort`
- Generate, reprint, and email receipts
- Apply invoice adjustments
- Enforce billing rules per customer or location
- Revert finalised invoices (credit reversal flow)

## Key Classes

- `InvoiceService` — invoice lifecycle (create, retrieve, revert)
- `InvoiceFinalizationService` — transitions invoice to FINALIZED state and triggers downstream events
- `PaymentService` — initiates payment intents with idempotency; delegates to gateway adapter
- `PaymentReversalService` — voids and refunds settled or unsettled payments
- `ReceiptService` — generates PDF receipts and dispatches print/email actions
- `BillingRulesService` — reads and enforces per-customer billing rule configurations

## API Endpoints

- `POST /v1/invoices` — create an invoice
- `GET /v1/invoices/{invoiceId}` — retrieve an invoice
- `POST /v1/invoices/{invoiceId}/finalize` — finalize an invoice
- `POST /v1/invoices/{invoiceId}/revert` — revert a finalized invoice
- `POST /v1/invoices/{invoiceId}/adjustments` — apply an adjustment
- `POST /v1/invoices/{invoiceId}/payments` — initiate payment (idempotent)
- `POST /v1/invoices/{invoiceId}/payments/{paymentId}/capture` — capture an authorized payment
- `POST /v1/invoices/{invoiceId}/payments/{paymentId}/void` — void a payment
- `POST /v1/invoices/{invoiceId}/payments/{paymentId}/refunds` — refund a payment
- `POST /v1/invoices/{invoiceId}/receipts` — generate a receipt
- `POST /v1/invoices/{invoiceId}/receipts/{receiptId}/email` — email a receipt
- `POST /v1/invoices/{invoiceId}/receipts/{receiptId}/print` — print a receipt
- `GET /v1/billing/rules/{partyId}` — retrieve billing rules for a party

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-shared-dtos` — shared invoice creation DTOs
- `pos-tax-common` — tax request/response types

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-invoice -am spring-boot:run
```
