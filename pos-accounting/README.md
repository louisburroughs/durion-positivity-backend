# pos-accounting

General-ledger accounting service for the Durion POS platform. Manages chart of accounts, journal entries, GL posting rules, payment application, AP payments, vendor bills, credit memos, and financial reporting. Consumes payment-cleared events from Kafka and produces posted accounting entries through a transactional outbox pattern.

## Responsibilities

- Manage GL accounts, posting categories, and mapping keys
- Create and post journal entries with idempotency guarantees
- Evaluate posting rule sets to drive automated GL posting
- Apply payments to invoices and record AP payments
- Issue credit memos with configurable GL account targets
- Produce financial reports (income statement, balance sheet)
- Ingest domain events from Kafka via the event ingestion pipeline
- Maintain an immutable audit trail for all accounting transactions

## Key Classes

- `JournalEntryService` — creates and retrieves journal entries; primary accounting write path
- `GLPostingService` — applies posting rules to produce GL entries from business events
- `PaymentApplicationService` — matches and applies payments to outstanding invoices
- `PostingRuleEvaluator` — evaluates posting rule sets to determine which GL accounts to debit/credit
- `EventIngestionService` — receives domain events and triggers the accounting pipeline
- `OutboxService` — transactional outbox for reliable downstream event publication

## API Endpoints

- `GET /v1/gl-accounts/{glAccountId}` — retrieve a GL account
- `GET /v1/gl-accounts/{glAccountId}/balance` — get account balance
- `GET /v1/journal-entries/{journalEntryId}` — retrieve a journal entry
- `GET /v1/journal-entries/{journalEntryId}/traceability` — trace posting lineage
- `POST /v1/payment-applications` — apply a payment to an invoice
- `POST /v1/ap-payments` — record an accounts-payable payment
- `POST /v1/credit-memos` — create a credit memo
- `GET /v1/reporting/income-statement` — income statement report
- `GET /v1/reporting/balance-sheet` — balance sheet report
- `GET /v1/reporting/drilldown/journal-lines/{accountId}` — drill into GL lines
- `POST /v1/accounting/export` — request a timekeeping export job
- `GET /v1/accounting/export/status/{jobId}` — get export job status
- `GET /v1/accounting/export/history` — list export job history

## Configuration

| Property                                            | Default              | Description                              |
| --------------------------------------------------- | -------------------- | ---------------------------------------- |
| `pos.accounting.credit-memo.revenue-account-id`     | required             | GL account for revenue reversals         |
| `pos.accounting.credit-memo.tax-payable-account-id` | required             | GL account for tax payable reversals     |
| `pos.accounting.credit-memo.ar-account-id`          | required             | GL account for AR reductions             |
| `pos.accounting.kafka.enabled`                      | `false`              | Enable Kafka consumer for payment events |
| `pos.accounting.kafka.payments-topic`               | `payment.cleared.v1` | Kafka topic for cleared payments         |
| `stripe.api-key`                                    | required             | Stripe API key for payment processing    |

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` AOP annotation and event registration
- `pos-shared-dtos` — shared invoice and vehicle DTOs

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`:

- `V1__baseline_accounting_schema.sql` — full schema baseline
- `R__seed_reference_accounting.sql` — repeatable seed for reference data

## Development

```bash
./mvnw -pl pos-accounting -am spring-boot:run
```
