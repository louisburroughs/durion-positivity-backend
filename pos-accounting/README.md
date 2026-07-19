# pos-accounting

General-ledger accounting service for the Durion Positivity ETSMS platform. Manages chart of accounts, journal entries, GL posting rules, payment application, AP payments, vendor bills, credit memos, and financial reporting. Consumes payment-cleared events from Kafka and produces posted accounting entries through a transactional outbox pattern.

## Responsibilities

- Manage GL accounts, posting categories, and mapping keys
- Create and post journal entries with idempotency guarantees and POST-time entry numbering
- Reverse posted journal entries with a full REVERSED lifecycle and bidirectional linkage
- Evaluate posting rule sets to drive automated GL posting
- Enforce accounting-period state (closed periods, hard lock, override) on every posting path
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
- `GET /v1/accounting/journal-entries` — list journal entries (supports `entryNumber` filter)
- `GET /v1/accounting/journal-entries/{journalEntryId}` — retrieve a journal entry
- `GET /v1/accounting/journal-entries/{journalEntryId}/traceability` — trace posting lineage
- `POST /v1/accounting/journal-entries/{journalEntryId}/post` — post a DRAFT entry (assigns `entryNumber`, runs the period gate; permission `accounting:je:post`, event `ACCOUNTING_JOURNAL_ENTRY_POST`)
- `POST /v1/accounting/journal-entries/{journalEntryId}/reverse` — reverse a POSTED entry (permission `accounting:je:reverse`, event `ACCOUNTING_JOURNAL_ENTRY_REVERSE`)
- `POST /v1/payment-applications` — apply a payment to an invoice
- `POST /v1/ap-payments` — record an accounts-payable payment
- `POST /v1/credit-memos` — create a credit memo
- `GET /v1/reporting/income-statement` — income statement report
- `GET /v1/reporting/balance-sheet` — balance sheet report
- `GET /v1/reporting/drilldown/journal-lines/{accountId}` — drill into GL lines
- `GET /v1/accounting/periods` — list accounting periods (permission `accounting:period:view`, event `ACCOUNTING_PERIOD_LIST`)
- `POST /v1/accounting/periods/{periodCode}/close` — close a period (permission `accounting:period:close`, event `ACCOUNTING_PERIOD_CLOSE`)
- `POST /v1/accounting/periods/{periodCode}/reopen` — reopen a closed period with mandatory justification (permission `accounting:period:reopen`, event `ACCOUNTING_PERIOD_REOPEN`)
- `GET /v1/accounting/periods/hard-lock` — read the org-level hard-lock date (permission `accounting:period:view`, event `ACCOUNTING_PERIOD_HARD_LOCK_VIEW`)
- `PUT /v1/accounting/periods/hard-lock` — set/advance the hard-lock date with mandatory justification (permission `accounting:period:hard_lock`, event `ACCOUNTING_PERIOD_HARD_LOCK_SET`)
- `POST /v1/accounting/export` — request a timekeeping export job
- `GET /v1/accounting/export/status/{jobId}` — get export job status
- `GET /v1/accounting/export/history` — list export job history
- `GET /v1/accounting/reports/financial/general-ledger` — general ledger report (story G2)
- `GET /v1/accounting/reports/financial/aged-receivables` — aged receivables buckets (story G2)
- `GET /v1/accounting/reports/financial/aged-payables` — aged payables buckets (story G2)
- `GET /v1/accounting/settlements/{settlementId}/lines` — list settlement lines, optional `unmatchedOnly` filter (permission `accounting:reconciliation:view`, event `ACCOUNTING_SETTLEMENT_LINES_LIST`, story F1c)
- `POST /v1/accounting/settlements/lines/{lineId}/match` — manually match an unmatched line to a receivable payment (permission `accounting:reconciliation:adjust`, event `ACCOUNTING_SETTLEMENT_LINE_MATCH`, story F1c)
- `POST /v1/accounting/settlements/lines/{lineId}/write-off` — write off a small unmatched line with mandatory reason (permission `accounting:reconciliation:adjust`, event `ACCOUNTING_SETTLEMENT_LINE_WRITE_OFF`, story F1c)

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

## Journal Entry Numbering

Posted entries carry a sequential `entryNumber` in the format `JE-{YYYYMM}-{seq}` (V13, plan decision D-1):

- Numbers are assigned at POST time only, inside the posting transaction, from a per-month
  `accounting_sequence` row locked with `PESSIMISTIC_WRITE` — concurrent posts serialize per month
- The month comes from the entry's `transactionDate`; sequences start at 1 per month, no backfill —
  DRAFT/PENDING and pre-migration entries stay unnumbered (`entry_number` nullable, unique on non-null)
- Numbering is gapless as a side effect of post-time assignment (a rollback returns the number);
  **no statutory gapless guarantee is claimed** (D-1)
- `entryNumber` is exposed on journal-entry DTOs and as a list filter on `GET /v1/accounting/journal-entries`;
  a gap query exists for operational verification
- Reversal entries are numbered through the same sequence seam

## Journal Entry Reversal

`POST /v1/accounting/journal-entries/{journalEntryId}/reverse` implements the full reversal lifecycle:

- Creates and immediately posts an inverse entry (debits/credits swapped, own `entryNumber`) and
  transitions the original POSTED → REVERSED via a race-safe conditional UPDATE (a lost race returns 409)
- Bidirectional linkage between original and reversal; `reversedAt` and the acting user are stamped
  and the operation is audit-logged; a non-blank reason is required
- Optional `reversalDate` is validated against open periods; when omitted it defaults to the original
  entry's transaction date if that period is OPEN, otherwise to today
- Errors: `JE_ALREADY_REVERSED` (409, includes double-reversal races), `JE_NOT_POSTED` (409),
  `PERIOD_CLOSED` / `PERIOD_HARD_LOCKED` (422, see the period gate below)
- Emits a `JournalEntryReversed` outbox domain event in the same transaction (MANDATORY propagation)
  for downstream read models

## Accounting Periods

Monthly periods keyed by `YYYY-MM` code with a two-state OPEN → CLOSED lifecycle (plan decision D-7):

- Missing period row means OPEN; periods are auto-provisioned on first posting into a new month (`ensurePeriodExists`)
- Close is rejected with `422 PERIOD_HAS_DRAFT_ENTRIES` listing the DRAFT journal-entry IDs still in the period
- Reopen requires a mandatory justification, recorded on the period and in the audit trail
- Errors: `PERIOD_NOT_FOUND` (404), `PERIOD_ALREADY_CLOSED` / `PERIOD_ALREADY_OPEN` (409)
- Close and reopen are audit-logged with the acting user
- Concurrent close/reopen of the same period is serialized by optimistic locking (`@Version`, V15)

### Period Enforcement (B2, #944)

`AccountingPeriodGate` is the single choke point wired into `postJournalEntry` and `reverseJournalEntry`,
covering every posting path (manual, posting engine, credit memo, payment application, and AP transitively).
Check order: **hard lock > closed period > override**.

- **Hard lock** — a transaction/reversal date strictly before the org-level hard-lock date is rejected
  with `422 PERIOD_HARD_LOCKED`; never overridable
- **Closed period** — a date in a CLOSED period is rejected with `422 PERIOD_CLOSED` unless the caller
  holds `accounting:period:override` **and** supplies a non-blank `overrideJustification`, in which case
  the posting proceeds and the override is audit-logged (`PERIOD_OVERRIDE_POST`)
- **Posting engine** — closed-period autoPost events land in SUSPENDED with
  `failureReasonCode=PERIOD_CLOSED`; the auto-retry loop skips them, and they become reprocessable
  after the period is reopened

### Hard Lock

The org-level hard-lock date lives in `accounting_configuration` (V14, key `HARD_LOCK_DATE`); the table
ships empty, so no hard lock exists until an operator sets one via
`PUT /v1/accounting/periods/hard-lock` (permission `accounting:period:hard_lock`, mandatory justification,
event `ACCOUNTING_PERIOD_HARD_LOCK_SET`; read via GET with `accounting:period:view`). The date is
monotonic-forward-only — moving it backward is rejected with `422 HARD_LOCK_DATE_REGRESSION` — which makes
the lock effectively irreversible.

Permission catalog note: catalog v23 adds bits 382 (`accounting:period:hard_lock`) and 383
(`accounting:period:override`); the `CATALOG_VERSION` 22 → 23 bump requires a fleet-coordinated deploy.

## Posting Rules

Full schema reference: `durion/domains/accounting/.business-rules/POSTING_RULES_SCHEMA.md`.

- **Proportional split lines (E1, #945)** — rule lines may carry `factorPercent` (0–100, 4dp) and a
  `splitGroup` per condition sharing one `amountField`; factors must sum to 100 and mixed DEBIT/CREDIT
  groups are forbidden. Shares round HALF_UP to 2dp with the residual assigned to the largest raw share
  (first-in-order tie-break), so each group always sums exactly to the source amount. Non-split lines
  are byte-identical to pre-E1 behavior.
- **Condition predicates (E2, #946)** — conditions use a whitelist predicate grammar
  (`eventType` / `payload.<path>` clauses; `== != > >= < <=`; `&&` conjunction; string/number literals;
  no expression engine or scripting). A missing or non-scalar path makes the clause a non-match, never
  an error. See POSTING_RULES_SCHEMA.md §2.1.
- **Publish-time validation** — split invariants and predicate parse errors are aggregated and rejected
  at publish with `422 UNBALANCED_RULES` carrying per-violation `fieldErrors` locators; a defensive
  eval-time recheck fails loudly rather than silently rebalancing. Pre-E2 unparseable conditions on
  already-published versions stay WARN + non-match at evaluation.

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
- `V12__je_balance_assert_lock.sql` — row-locking rewrite of the V8 balance-assert function, closing a concurrent-writer race on POSTED entries
- `V13__je_entry_number_sequence.sql` — `accounting_sequence` per-month counter table + nullable unique `entry_number` on `journal_entry` (no backfill)
- `V14__accounting_configuration_hard_lock.sql` — `accounting_configuration` key/value table backing the org-level `HARD_LOCK_DATE`
- `V15__accounting_period_version.sql` — optimistic-locking `version` column on `accounting_period`
- `R__seed_reference_accounting.sql` — repeatable seed for reference data, including the 9-account COA

## Development

```bash
./mvnw -pl pos-accounting -am spring-boot:run
```
