# pos-accounting

General-ledger accounting service for the Durion Positivity ETSMS platform. Manages chart of accounts, journal entries, GL posting rules, payment application, AP payments, vendor bills, credit memos, and financial reporting. Consumes payment-cleared events from Kafka and produces posted accounting entries through a transactional outbox pattern.

## Responsibilities

- Manage GL accounts, posting categories, and mapping keys
- Create and post journal entries with idempotency guarantees
- Evaluate posting rule sets to drive automated GL posting
- Apply payments to invoices and record AP payments
- Issue credit memos with configurable GL account targets
- Manage monthly accounting periods (list, close, reopen)
- Produce financial reports (income statement, balance sheet)
- Ingest domain events from Kafka via the event ingestion pipeline
- Maintain an immutable audit trail for all accounting transactions

## Key Classes

- `JournalEntryService` — creates and retrieves journal entries; primary accounting write path
- `GLPostingService` — applies posting rules to produce GL entries from business events
- `PaymentApplicationService` — matches and applies payments to outstanding invoices
- `AccountingPeriodService` — accounting period lifecycle (open/close/reopen) and open-period checks
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
- `GET /v1/accounting/periods` — list accounting periods (permission `accounting:period:view`, event `ACCOUNTING_PERIOD_LIST`)
- `POST /v1/accounting/periods/{periodCode}/close` — close a period (permission `accounting:period:close`, event `ACCOUNTING_PERIOD_CLOSE`)
- `POST /v1/accounting/periods/{periodCode}/reopen` — reopen a closed period with mandatory justification (permission `accounting:period:reopen`, event `ACCOUNTING_PERIOD_REOPEN`)
- `POST /v1/accounting/export` — request a timekeeping export job
- `GET /v1/accounting/export/status/{jobId}` — get export job status
- `GET /v1/accounting/export/history` — list export job history

## Chart of Accounts

GL accounts carry two metadata fields (V11):

- `reconcilable` (boolean, default `false`) — marks accounts whose lines participate in reconciliation flows
- `accountSubtype` (optional enum, 12 values) — `RECEIVABLE`, `PAYABLE`, `BANK_CASH`, `UNDEPOSITED_FUNDS`, `TAX_PAYABLE`, `CURRENT_ASSET`, `FIXED_ASSET`, `CURRENT_LIABILITY`, `SALES`, `COST_OF_SALES`, `OPERATING_EXPENSE`, `OTHER`

Both fields are exposed on the COA DTOs/OpenAPI. GL mapping creation runs a non-blocking subtype
plausibility check: an implausible posting-category/subtype pairing logs a warning but never fails the request.

The repeatable seed (`R__seed_reference_accounting.sql`) provisions a 9-account small-business COA via
idempotent upserts: 1000 Cash, 1090 Undeposited Funds, 1200 Accounts Receivable, 2000 Accounts Payable,
2200 Sales Tax Payable, 2300 Customer Credit Liability, 4000 Service Revenue, 5000 Cost of Goods Sold,
6000 Payment Processor Fees.

## Accounting Periods

Monthly periods keyed by `YYYY-MM` code with a two-state OPEN → CLOSED lifecycle (plan decision D-7):

- Missing period row means OPEN; periods are auto-provisioned on first posting into a new month (`ensurePeriodExists`)
- Close is rejected with `422 PERIOD_HAS_DRAFT_ENTRIES` listing the DRAFT journal-entry IDs still in the period
- Reopen requires a mandatory justification, recorded on the period and in the audit trail
- Errors: `PERIOD_NOT_FOUND` (404), `PERIOD_ALREADY_CLOSED` / `PERIOD_ALREADY_OPEN` (409)
- Close and reopen are audit-logged with the acting user

`isPeriodOpen` reads the period table; full enforcement across every journal-entry posting path lands in
Wave-2 story B2 (#944).

## Payment Application Concurrency

`ReceivablePayment` uses JPA optimistic locking (`@Version`, V10). `RetryingPaymentApplicationService`
(`@Primary` decorator outside the transaction boundary) retries an application exactly once on an
optimistic-lock conflict, re-reading fresh state and re-running all validations (AD-010 idempotency
preserved); a second conflict returns `409 Conflict` and the client should retry.

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
- `V8__je_balance_constraint.sql` — DB-level balance enforcement for POSTED journal entries (per-line CHECKs + deferrable constraint triggers, ±0.0001 tolerance; DRAFT exempt)
- `V9__accounting_period.sql` — `accounting_period` table with backfill from existing journal-entry months
- `V10__receivable_payment_version.sql` — optimistic-locking `version` column on `receivable_payment`
- `V11__gl_account_metadata.sql` — `reconcilable` flag and `account_subtype` column (+ check constraint) on `gl_account`
- `R__seed_reference_accounting.sql` — repeatable seed for reference data, including the 9-account COA

## Development

```bash
./mvnw -pl pos-accounting -am spring-boot:run
```
