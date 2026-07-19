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
- `SettlementSourcePort` — outbound port for a processor settlement (payout) feed; default binding is the `UnavailableSettlementSourceAdapter` placeholder until a real adapter is provided (story F1b, #962)
- `SettlementEventPublisher` — emits the normalized `SettlementReportedV1` fact (`payment.events.v1`, keyed by settlement id) and the compacted `SettlementProviderConfigV1` (`payment.settlement-config.v1`, keyed per provider) to pos-accounting via the invoice transactional outbox

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

| Property                          | Default  | Description                                                              |
| --------------------------------- | -------- | ----------------------------------------------------------------------- |
| `SPRING_DATASOURCE_URL`           | required | PostgreSQL connection URL                                               |
| `EUREKA_SERVER_URL`               | required | Eureka service discovery URL                                           |
| `invoice.elevation.token-secret`  | required | HMAC secret for manager-approval elevation tokens (≥32 bytes; service fails fast if unset/short) |
| `invoice.elevation.token-ttl-seconds` | `300` | Elevation token lifetime in seconds                                  |

### Manager-approval elevation — operational setup

Finalizing/reverting an invoice above the service-advisor cap requires override
capability. There are two ways to obtain it:

- **Logged-in manager/admin — auto-approved.** A caller holding the
  `ROLE_SHOP_MANAGER`, `ROLE_LOCATION_MANAGER`, or `ROLE_ADMIN` role (always present in
  the JWT) finalizes/reverts directly, no approval code. This needs **no** extra setup.
- **Service advisor naming a manager (employee-number approval).** The named manager
  must hold the `invoice:finalize:override` **authority**. The permission is registered
  at startup from `permissions.yaml`, but **grants are runtime data** (this platform
  never SQL-seeds `role_permissions`). For this path, an admin must, after deploy:
  1. Grant `invoice:finalize:override` to the manager/admin roles via the pos-security
     role-permission admin API.
  2. Ensure managers are assigned those roles via the **user-role** admin API (the
     `person-decision` check resolves authorities through `User.roles`).

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
