# pos-accounting

Canonical accounting documentation lives in [durion](https://github.com/louisburroughs/durion/blob/master/domains/accounting/index.md).
See the [reconciled implementation reference](https://github.com/louisburroughs/durion/blob/master/domains/accounting/implementation-reference.md)
for bill matching, GL event ingestion, outboxes and schema history. The local `docs/` files are compatibility pointers.

General-ledger accounting service for the Durion Positivity ETSMS platform. Manages chart of accounts, journal entries, GL posting rules, payment application, AP payments, vendor bills, credit memos, and financial reporting. Consumes settled-payment facts from `payment.events.v1` and produces posted accounting entries through a transactional outbox pattern.

## Responsibilities

- Manage GL accounts, posting categories, and mapping keys
- Create and post journal entries with idempotency guarantees and POST-time entry numbering
- Reverse posted journal entries with a full REVERSED lifecycle and bidirectional linkage
- Evaluate posting rule sets to drive automated GL posting
- Enforce accounting-period state (closed periods, hard lock, override) on every posting path
- Apply payments to invoices and record AP payments
- Issue credit memos with configurable GL account targets, freezing their per-jurisdiction tax reversal
- Apply or refund AR customer credits, relieving the customer-credit liability recognized at issuance
- Post inventory shrinkage (Dr Inventory Shrinkage 5100 / Cr Inventory 1300) from `inventory.scrap.posted` facts on `inventory.events.v1`, exactly once per scrap; uncosted scraps (ADR-0048 interim `costSource=NONE`) are logged and skipped, never posted
- Post inventory adjustments (cycle-count variances and manual adjustments) from `inventory.adjustment.posted` facts on `inventory.events.v1`, exactly once per adjustment: a loss posts Dr 5100 / Cr 1300 and a gain Dr 1300 / Cr 5100 for `abs(quantityDelta) × unitCost` through the `INVENTORY_ADJUSTMENT` posting category; uncosted facts are counted and recorded `SKIPPED`, never posted (see Inventory Posting Facts below)
- Post a goods receipt's accrual from `goodsreceipt.recorded` facts on `inventory.events.v1` (CAP:550 S41, #2602; AW38), exactly once per receipt: Dr 1300 at inventory's value / Cr 2100 Goods Received Not Yet Billed at the accrued value / Dr or Cr 5050 the difference, through the `GOODS_RECEIPT` posting category; a fact with no currency or a foreign one is held `SUSPENDED / CURRENCY_NOT_SUPPORTED`, a malformed one `SUSPENDED / VALIDATION_ERROR`, an uncosted one `SKIPPED / UNCOSTED_FACT` (see Inventory Posting Facts below)
- Post manual cost revaluations from `inventory.product-value.changed` facts on `inventory.events.v1`, exactly once per revaluation: a write-up posts Dr 1300 / Cr 5000 and a write-down Dr 5000 / Cr 1300 for `abs(totalValueDelta)` through the `INVENTORY_REVALUATION` posting category; a zero delta posts no entry but is still recorded `PROCESSED` (see Inventory Posting Facts below)
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
- `POST /v1/accounting/ap/payments` — pay a vendor from a `BANK_CASH` account through the gateway (permission `accounting:ap:pay`, event `AP_PAYMENT_EXECUTE`; see [AP payments](#ap-payments-post-through-ap_payment-cap550-s42-2603-2627))
- `POST /v1/accounting/ap/payments/{paymentId}/gl-posting-retry` — post again a payment whose posting was refused (permission `accounting:je:post`, event `ACCOUNTING_AP_PAYMENT_GL_POSTING_RETRY`)
- `POST /v1/credit-memos` — create a credit memo
- `GET /v1/accounting/tenant-template/status` — where the caller's tenant stands against the accounting template (permission `accounting:coa:view`, event `ACCOUNTING_TENANT_TEMPLATE_STATUS_VIEW`; see [Tenant provisioning](#tenant-provisioning-the-accounting-template-2526))
- `PUT /v1/accounting/tenant-template/add-ons/retread-plant` — turn the retread-plant add-on on for the caller's tenant (permission `accounting:coa:create`, event `ACCOUNTING_TENANT_TEMPLATE_ADD_ON_ENABLE`)
- `POST /v1/accounting/bank-accounts/{glAccountId}/opening-balance` — a bank account's opening balance at cutover, with its outstanding items, through 3900 (permissions `accounting:je:create` and `accounting:je:post`, event `ACCOUNTING_BANK_OPENING_BALANCE_ESTABLISH`; see [Bank opening balance](#bank-opening-balance-2572-oi-10))
- `GET /v1/accounting/undeposited-sessions[?sessionId=…&bankGlAccountId=…]` — the closed register sessions whose drawer cash has not reached the bank, oldest first, and for a selection the deposit it makes (permission `accounting:deposit:create`, event `ACCOUNTING_UNDEPOSITED_SESSIONS_VIEW`; see [Bank deposits of drawer cash](#bank-deposits-of-drawer-cash-cap550-s18-2514))
- `POST /v1/accounting/deposits` — Record bank deposit of whole sessions (permission `accounting:deposit:create`, event `ACCOUNTING_DEPOSIT_CREATE`)
- `GET /v1/accounting/deposits/{depositId}` — a deposit as it stands (permission `accounting:deposit:create`, event `ACCOUNTING_DEPOSIT_VIEW`)
- `POST /v1/accounting/deposits/{depositId}/reversal` — Reverse deposit (permission `accounting:deposit:reverse`, event `ACCOUNTING_DEPOSIT_REVERSE`)

### Display references on responses

Responses that carry an entity UUID also carry a display-ready value for it, so screens never have to render a raw identifier (issues #1778, #1779):

- Credit memo list and detail add `creditMemoReference`, `originalInvoiceReference`, `customerDisplayName` and `customerReference` beside the existing ids.
- Accounting event **detail** adds `payloadReferences`: a typed projection of the reference values recognized inside the raw payload (invoice, customer, organization, location, journal entry, vendor, vendor bill), each with a `path` locating it in the payload and a `rawValue` carrying the value as written. `id` is that value parsed as a UUID and is null when it is not one. Location is the one code-keyed type (issue #1797): accounting's location dimension carries a code such as `LOC-107`, so `locationId` / `location_id` values are matched case-insensitively against `accounting_location_profile.location_code`, with the canonical stored code as `displayReference`. The raw payload itself is returned unchanged; list responses omit the projection.

Every display value is resolved from data accounting already holds — its own records and its event-fed `ext_invoice` / `ext_customer_party` replicas (ADR-0044) — so nothing is fetched across a domain wall. **A display value is null when accounting cannot resolve it; an identifier is never copied into a display field as fallback text.** Identifiers stay in the contract for commands, links and audit traceability.
- `GET /v1/reporting/income-statement` — income statement report
- `GET /v1/reporting/balance-sheet` — balance sheet report
- `GET /v1/reporting/drilldown/journal-lines/{accountId}` — drill into GL lines
- `GET /v1/accounting/periods` — list accounting periods (permission `accounting:period:view`, event `ACCOUNTING_PERIOD_LIST`)
- `POST /v1/accounting/periods/{periodCode}/close` — close a period (permission `accounting:period:close`, event `ACCOUNTING_PERIOD_CLOSE`)
- `POST /v1/accounting/periods/{periodCode}/reopen` — reopen a closed period with mandatory justification (permission `accounting:period:reopen`, event `ACCOUNTING_PERIOD_REOPEN`)
- `GET /v1/accounting/periods/hard-lock` — read the org-level hard-lock date (permission `accounting:period:view`, event `ACCOUNTING_PERIOD_HARD_LOCK_VIEW`)
- `PUT /v1/accounting/periods/hard-lock` — set/advance the hard-lock date with mandatory justification (permission `accounting:period:hard_lock`, event `ACCOUNTING_PERIOD_HARD_LOCK_SET`)
- `PUT /v1/accounting/configuration/time-zone` — set the tenant's accounting-calendar time zone before its first close (permission `accounting:period:hard_lock`, event `ACCOUNTING_CONFIGURATION_TIME_ZONE_SET`; see [Accounting time zone](#accounting-time-zone-2558))
- `POST /v1/accounting/export` — request a timekeeping export job
- `GET /v1/accounting/export/status/{jobId}` — get export job status
- `GET /v1/accounting/export/history` — list export job history
- `GET /v1/accounting/reports/financial/general-ledger` — general ledger report (story G2)
- `GET /v1/accounting/reports/financial/aged-receivables` — aged receivables by due state: `notYetDue`, `days1To30`, `days31To60`, `days61To90`, `days90Plus`, `overdue`, `totalOutstanding`; rows carry `customerName` / `customerReference` from the customer replica, ordered by name then id; the CASH walk-in house account is left out (its open sales are the unpaid walk-in sales read's, so aged receivables plus that read's `balance` is the AR subledger, apart from legacy party-less invoices) (story G2; CAP:550 S35, #2524; S11, #2508)
- `GET /v1/accounting/reports/financial/aged-payables` — aged payables with the same buckets over APPROVED bills only; bills not yet approved (`PENDING_RECEIPT_MATCH`, `MATCH_EXCEPTION`) are reported unaged as `unapproved`, `unapprovedBillCount`, `totalIncludingUnapproved` per row and beside `totals` (AW11; CAP:550 S35, #2524)
- `POST /v1/accounting/payments/{paymentId}/remainder-credit` — keep a payment's whole unapplied remainder as a customer credit (AD-003): one transaction creates the credit, enqueues Dr 1090 / Cr 2300, and the payment becomes `FULLY_APPLIED`; idempotent on `requestId`, guarded by `expectedAmount`; refused with 422 `CASH_CUSTOMER_CREDIT_NOT_ALLOWED` for a payment of the CASH walk-in account (#2508) (permission `accounting:payment:apply`, event `ACCOUNTING_PAYMENT_REMAINDER_CREDIT`, CAP:550 S35, #2524)
- `GET /v1/accounting/receivable-payments?status=AVAILABLE&customerId=&page=&size=` — customer payments still waiting to be matched, oldest cleared first (`size` 1-100, default 25; any other `status` → 400 `VALIDATION_ERROR`). Each row carries the customer's name and number from the replica (null when unknown), `paymentMethod`, `sourceInvoiceId` / `sourceInvoiceNumber` (the invoice it was taken against) and a `suggestion` of the open invoices it most likely pays: `reasons` (`REMITTANCE_REFERENCE`, `SAME_CUSTOMER`, `EXACT_TOTAL`), `invoices` with `suggestedAmount`, `suggestedTotal` and `leftOver` (the would-be credit, AD-003; null for a payment of the CASH walk-in account, which never keeps a credit, #2508). `summary` totals every matching payment, not only the page (permission `accounting:payment:apply`, event `ACCOUNTING_RECEIVABLE_PAYMENT_LIST_VIEW`, CAP:550 S1, #2502)
- `GET /v1/accounting/payment-applications/automatic?since=&page=&size=` — "Matched automatically": the payment applications nobody made by hand (`source` `PAYMENT_SETTLED` or `INVOICE_PAYMENT`) at or after `since` (required ISO-8601 instant, at most 31 days back), newest first (`size` 1-100, default 50). Each row carries `paymentApplicationId` / `paymentId` / `invoiceId` for commands and links, `appliedAt`, `appliedAmount`, `invoiceNumber`, `customerDisplayName`, `customerReference` (null when unresolved, never a UUID), `creditCreatedAmount` (the credit the same request kept, else null), `reversed` / `reversedAt`, and `actions` = `["UNDO"]` while it stands and the caller holds `accounting:payment:reverse` (undo is `reversePaymentApplication`). `totalElements` counts reversed ones too (permission `accounting:payment:apply`, event `ACCOUNTING_PAYMENT_APPLICATION_AUTOMATIC_LIST_VIEW`, CAP:550 S2, #2503)
- `GET /v1/accounting/customers/{customerId}/open-invoices?page=&size=` — the customer's open invoices (`FINALIZED` / `POSTED` with a derived balance above 0.00) in `OLDEST_FIRST` order, with `balanceDue` net of applications, customer credits, posted credit memos and deposits, `arStatus`, `overdue` / `daysOverdue` (due date, else document date) and `workorderId` for a link only; `size` 1-200, default 100; an unknown customer gets 200 with no rows. `summary` totals every open invoice and equals the customer's aged-receivables total today (except for the CASH walk-in account, which aged receivables leaves out; its open sales are in the unpaid walk-in sales read, #2508) (permission `accounting:payment:apply`, event `ACCOUNTING_CUSTOMER_OPEN_INVOICES_VIEW`, #2502)
- `GET /v1/accounting/unpaid-walk-in-sales` — unpaid walk-in sales (see [Unpaid walk-in sales](#unpaid-walk-in-sales-cap550-s11-2508)): the CASH house account's `balance`, its `openInvoices` oldest sale first, the day-end `needsAttention` item and `unappliedPayments` (permission `reporting:view:financial-statements`, event `ACCOUNTING_UNPAID_WALK_IN_SALES_VIEW`, CAP:550 S11, #2508)
- `GET /v1/accounting/settlements/{settlementId}/lines` — list settlement lines, optional `unmatchedOnly` filter (permission `accounting:reconciliation:view`, event `ACCOUNTING_SETTLEMENT_LINES_LIST`, story F1c)
- `POST /v1/accounting/settlements/lines/{lineId}/match` — manually match an unmatched line to a receivable payment (permission `accounting:reconciliation:adjust`, event `ACCOUNTING_SETTLEMENT_LINE_MATCH`, story F1c)
- `POST /v1/accounting/settlements/lines/{lineId}/write-off` — write off a small unmatched line with mandatory reason (permission `accounting:reconciliation:adjust`, event `ACCOUNTING_SETTLEMENT_LINE_WRITE_OFF`, story F1c)
- `POST /v1/accounting/reconciliations/import` — start a CSV bank reconciliation for a reconcilable GL account (permission `accounting:reconciliation:adjust`, story F2)
- `GET /v1/accounting/reconciliations` / `GET /v1/accounting/reconciliations/{id}` — list / get reconciliations (permission `accounting:reconciliation:view`)
- `POST /v1/accounting/reconciliations/{id}/match` · `/unmatch` — match (1-to-1 / N-to-1, ±0.01) or unmatch statement lines to posted GL lines (permission `accounting:reconciliation:adjust`)
- `GET /v1/accounting/reconciliations/adjustment-types` — served adjustment-type enum (`BANK_FEE, NSF_FEE, INTEREST_EARNED, FLOAT_ADJUSTMENT, OTHER`; frontend never hardcodes it)
- `POST /v1/accounting/reconciliations/{id}/adjustments` — record an adjustment; posts a real balanced JE via posting categories, respecting period locks (permission `accounting:reconciliation:adjust`)
- `POST /v1/accounting/reconciliations/{id}/submit` — submit for approval behind the gate E4: the live difference within ±0.01, then no unexplained bank transaction or ledger line from the baseline (permission `accounting:reconciliation:adjust`, event `ACCOUNTING_RECONCILIATION_SUBMIT`, fact `accounting.bankreconciliation.submitted`, #2304)
- `POST /v1/accounting/reconciliations/{id}/finalize` — approve a SUBMITTED reconciliation: row lock, live E4, `approvedGlEndingBalance` and `baselineDate` snapshots; the submitter is refused unless `BANK_REC_ALLOW_SELF_APPROVAL` is true (permission `accounting:reconciliation:approve`, fact `accounting.bankreconciliation.approved`, #2304)
- `POST /v1/accounting/reconciliations/{id}/return` · `/cancel` · `/supersede` — return to the preparer, cancel (matches unmatched, registered items released), or start a successor of a FINALIZED / INVALIDATED one (permission `accounting:reconciliation:approve`, #2304)
- `GET /v1/accounting/reconciliations/{id}/report` · `/audit` — reconciliation report / the stored `AccountingAuditLog` trail of the reconciliation, its matches and its items, paged (permission `accounting:reconciliation:view`)
- A posting or reversal that touches an approved window invalidates it in the same transaction (`INVALIDATED`, fact `accounting.bankreconciliation.invalidated`); a corrected statement (`supersedesStatementId` on the manual statement or the import) supersedes the old one and invalidates its approval (#2304)
- `GET /v1/accounting/reports/financial/tax-liability` — sales-tax liability by jurisdiction with GL drift (permission `reporting:view:financial-statements`, story T8)
- `GET /v1/accounting/customer-credits` · `/{creditId}` — list / get AR customer credits with their remaining open amount (permission `accounting:customer-credit:view`, issue #992)
- `POST /v1/accounting/customer-credits/{creditId}/applications` — apply an open credit to an invoice; posts Dr Customer Credit Liability (2300) / Cr AR, idempotent on `requestId` (permission `accounting:customer-credit:apply`, issue #992)
- `POST /v1/accounting/customer-credits/{creditId}/refunds` — refund an open credit as cash; posts Dr 2300 / Cr Undeposited Funds (1090), idempotent on `requestId` (permission `accounting:customer-credit:refund`, issue #992)

### Customer-credit lifecycle (issue #992)

Overpayment issuance (#975) recognizes the customer-credit liability but never relieves it, so the
2300 control account only ever grew. Applying or refunding a credit now posts the mirror entry, and
across the full lifecycle a fully-consumed credit nets 2300 back to zero. `customer_credit` carries
the running `applied_amount` / `refunded_amount` and a derived status
(`AVAILABLE → PARTIALLY_CONSUMED → CONSUMED`); each draw-down is a `customer_credit_transaction` row
holding its own posting lifecycle (`gl_journal_entry_id` / `gl_posted_at`), because one credit can be
relieved many times and each relief must be independently idempotent and traceable. The invoice
balance derivation subtracts applied credits, so a credit-settled invoice is no longer shown as
outstanding.

**Request ids on credits (CAP:550 S35, #2524).** `customer_credit.request_id` records the command that
issued the credit, namespaced: `APPLY:<applicationRequestId>` from `applyPayment`,
`REMAINDER:<requestId>` from `creditPaymentRemainder`, and the invoice-payment event's own prefix for
credits issued from settled payments. It is unique per tenant (`uq_customer_credit_request_id`, V6), the
same namespaced value keys the credit-issuance GL posting (so an apply key equal to a remainder key never
collides), and a replay of the issuing command returns the credit: an `applyPayment` replay carries the
`customerCredit` it issued, a remainder-credit replay the same `creditId`. The remainder path refuses a
payment that is not `AVAILABLE` (409 `PAYMENT_NOT_AVAILABLE`), in another currency (422
`CURRENCY_NOT_SUPPORTED`) or whose unapplied amount no longer equals `expectedAmount` (422
`PAYMENT_REMAINDER_CHANGED`), writing nothing; the same `requestId` on another payment is 409
`IDEMPOTENCY_CONFLICT`. It runs through `RetryingPaymentApplicationService` with apply's one retry, which also covers a lost race on `uq_customer_credit_request_id` (two
simultaneous requests with one key: the retry replays the winner's credit); a second conflict is 409 `OPTIMISTIC_LOCK`. The report-export request carries no `organizationId`: the tenant
comes from the caller's context (ADR-0062).

## Chart of Accounts

GL accounts carry two metadata fields (V11):

- `reconcilable` (boolean, default `false`) — marks accounts whose lines participate in reconciliation flows
- `accountSubtype` (optional enum, 12 values) — `RECEIVABLE`, `PAYABLE`, `BANK_CASH`, `UNDEPOSITED_FUNDS`, `TAX_PAYABLE`, `CURRENT_ASSET`, `FIXED_ASSET`, `CURRENT_LIABILITY`, `SALES`, `COST_OF_SALES`, `OPERATING_EXPENSE`, `OTHER`

Both fields are exposed on the COA DTOs/OpenAPI. GL mapping creation runs a non-blocking subtype
plausibility check: an implausible posting-category/subtype pairing logs a warning but never fails the request.

Every tenant receives the reference chart from the accounting template: the working small-business chart
(1000 Cash, 1090 Undeposited Funds, 1095 Register Cash Clearing, 1200 Accounts Receivable, 1300 Inventory,
2000 Accounts Payable, 2200 Sales Tax Payable, 2300 Customer Credit Liability, 4000 Service Revenue, 5000 Cost
of Goods Sold, 6000 Payment Processor Fees and the settlement, bank reconciliation, shrinkage and over/short
accounts) and the labour and overhead accounts. The template is data in the platform tenant, written by the
repeatable seed (`R__seed_reference_accounting.sql`); the seed writes no tenant's rows. See
[Tenant provisioning](#tenant-provisioning-the-accounting-template-2526).

## Bank opening balance (#2572, OI-10)

`POST /v1/accounting/bank-accounts/{glAccountId}/opening-balance` puts a bank account's balance at cutover on
the books, once per account, following the go-live float pattern. The body is `asOfDate` (the cutover, not after
today in the tenant's accounting calendar), `statementBalance`, `currencyCode` (ISO 4217, checked against the
JDK list, `IsoCurrencyCodes`; it must be the account's currency, else 422 `CURRENCY_NOT_SUPPORTED`),
`outstandingItems[]` (`OUTSTANDING_CHECK` or `DEPOSIT_IN_TRANSIT`, `reference`, `itemDate` on or before
`asOfDate`, `amount` more than zero), `justification` and `requestId`. A malformed body is 400 `VALIDATION_ERROR`
naming the field in `fieldErrors`; amounts, and their sum, stay below 10^14 (the entry's `numeric(19,4)`
totals). An amount finer than the currency's minor unit is 422 `AMOUNT_PRECISION_EXCEEDS_CURRENCY` naming every
offending field (ADR-0067 PC-6), checked after the currency. An account the caller cannot see is 404
`GL_ACCOUNT_NOT_FOUND`.

- **The entry**, dated `asOfDate` and posted with no override (the period must be OPEN): one bank line for the
  statement balance (a credit when overdrawn), one bank line per item carrying the dimensions
  `outstandingItemType`, `reference` and `itemDate` (a check credits the bank, a deposit in transit debits it),
  and one line for the net on `OPENING_BALANCE` / `OPENING_BALANCE_EQUITY` (3900 in the template). The book
  balance is statement + deposits in transit − outstanding checks. Statement 10,000.00, check #1043 450.00 and
  a deposit in transit 1,200.00 post Dr 1000 10,000.00 / Cr 1000 450.00 / Dr 1000 1,200.00 / Cr 3900 10,750.00.
- **Refusals**: 422 `BANK_OPENING_BALANCE_ACCOUNT_NOT_ELIGIBLE` (not an active `BANK_CASH` account in functional
  currency), 409 `BANK_OPENING_BALANCE_ALREADY_ESTABLISHED` (a standing opening: reverse its entry and run the
  command again; **date the correcting reversal on or before `asOfDate`**, or the reversed opening still counts
  in the balance at the cutover and the re-run is not first), 422 `BANK_OPENING_BALANCE_NOT_FIRST` (a line the
  balance at the end of `asOfDate` holds, counted as the bank reconciliation counts it: POSTED or REVERSED
  entries at their own dates, a reversal pair dated wholly on or before `asOfDate` netting out; or a committed
  statement starting on or before it; later lines are allowed), 422 `BANK_OPENING_BALANCE_EMPTY` (a zero balance
  with no items). Idempotent on `requestId` (`bank_opening_balance`, V14): a replay returns the stored first
  result (account code and entry number included); another body is 409 `IDEMPOTENCY_CONFLICT`. Two openings of
  one account serialize on the account row; an ordinary posting does not take that lock, so one committed in
  the same instant is not seen (the first reconciliation's opening difference shows it).
- **Bank reconciliation**: the response lists each item's `glLineId`. The account's first statement starts on
  `asOfDate` + 1 with opening balance = `statementBalance` and a `gapAcknowledgement`; registering each
  `glLineId` as an outstanding item there leaves `openingDifference` = 0.00, and the items match 1:1 when they
  clear. A registered opening item keeps its own `itemDate` (from the line's dimension), so its aging is real;
  the dimension is believed only on a line of an opening entry itself (a `bank_opening_balance` row owns it and
  it is not a reversal), so a forged or copied dimension never backdates an item.
- 3900 is cleared to 3000 by a manual entry; the `OPENING_BALANCE_EQUITY_NOT_CLEARED` readiness warning covers it.

## Tenant provisioning: the accounting template (#2526)

A tenant created through pos-tenant starts with nothing in this module. Provisioning gives it the chart of
accounts, posting categories, mapping keys, GL mappings, default GL mappings, statement lines and policy
defaults it needs for its first invoice to post (ADR-0062 §6–§7; SPEC-accounting-workspace §7.1 "Seed", AW30).

**The template is data in the platform tenant.** `R__seed_reference_accounting.sql` binds the platform tenant
(`01900000-0000-7000-8000-000000000000`) and writes into no other. It keeps `ON CONFLICT … DO UPDATE`, so the
template follows the file. A template row's id is
`md5('accounting-template:<platform tenant>:<KIND>:<natural key>')::uuid`, so it can never equal a tenant row's
id, and every reference inside the file goes through the same expression: no literal id, and no look-up by
account code (Flyway runs as the owner, which sees every tenant's rows).

**Never overwrite.** `AccountingTemplateApplier` creates what a tenant lacks and otherwise leaves the tenant's
rows as they are. It never renames, retypes, repoints, reactivates or deletes one, and it writes no journal
entry. What it did with each entry is recorded in `accounting_template_entry` (one row per entry per tenant),
and the tenant's `accounting_template_state` row holds the fingerprint of the template last applied.

| Kind | Entry key | Matched in a tenant by | Adopted when |
| --- | --- | --- | --- |
| Account | `ACCOUNT:<code>` | account code | same type, same name (trimmed, case-insensitive), active |
| Posting category | `CATEGORY:<name>` | category name | it exists |
| Mapping key | `MAPPING_KEY:<category>/<key>` | category and key name | it exists |
| GL mapping | `GL_MAPPING:<category>/<key>` | any mapping without dimensions for that key | it exists, whatever account it names |
| Default GL mapping | `DEFAULT_GL_MAPPING:<event type>` | event type | it exists |
| Statement line | `STATEMENT_LINE:<statement type>:<account code>` | the account's line on that statement | it exists |

| Outcome | Meaning | Applied again? |
| --- | --- | --- |
| `CREATED` | The tenant lacked the row; it was created (UUIDv7 id, actor `tenant-template`, the template's dates) | Never |
| `ADOPTED` | The tenant already held a match; nothing was written to it | Never |
| `REFRESHED` | A statement line the tenant had not touched took the template's new line code, parent line code, description and display order | Never, except a further refresh while untouched |
| `CONFLICT` | The tenant holds an account under the template's code that is another account (`ACCOUNT_DIFFERS`) or is inactive (`ACCOUNT_INACTIVE`); it is left alone | Every run, until resolved |
| `WITHHELD` | The entry refers to an account in conflict (`DEPENDS_ON_CONFLICT`) or missing (`ACCOUNT_MISSING`); it is not created | Every run, until resolved |

A settled entry (`CREATED`, `ADOPTED`, `REFRESHED`) is never applied again: later template changes do not reach
it, and a tenant that renamed the account, deactivated the key, remapped it or deleted the row keeps its
decision. A posting through a withheld key fails with the existing `GL_MAPPING_NOT_CONFIGURED` rather than
reaching an account the template did not mean. An entry removed from the template leaves tenant rows alone.

**Three paths, one transaction each**, all through `AccountingTenantProvisioner` and all idempotent on the
tenant (the applier holds the tenant's state row `FOR UPDATE` for the length of a run):

| Path | When | Notes |
| --- | --- | --- |
| `TenantEventsListener` | `tenant.created` on `tenant.events.v1` (group `pos-accounting-tenant-events`, `auto.offset.reset=earliest`) | Reads the template under the platform binding, then provisions under the new tenant's. Idempotent on eventId (`processed_events`, owner `tenant`). An empty template or a failure propagates: retry, then `tenant.events.v1.dlq`. `tenant.created` for the platform tenant is recorded and skipped |
| `AccountingTemplateStartupSweep` | every start, per `TenantIterator` tenant | The alpha default tenant has no `tenant.created` fact, so this is the path that provisions it; it also carries template additions to tenants provisioned earlier. Logs and continues past a failing tenant; never blocks startup; an empty template (Flyway off) is one ERROR line |
| Add-on choice | `PUT /v1/accounting/tenant-template/add-ons/retread-plant` | Records the tenant's choice, audits it with the caller, reconciles the tenant |

The provisioner also seeds the policy defaults (three override thresholds, one refund policy, the `UTC`
accounting time zone of #2558, each only when
the tenant has none): `DataInitializationService` is a provisioning step, no longer a startup runner, so a
tenant receives them only through one of the three paths above. Statement lines the template creates are
global lines (`location_id` null) that name the account by its name; a per-location override a tenant holds
(#731) is never adopted in the global line's place and never rewritten by a refresh.

**Sources.** The template is one body of rows; an `AccountingTemplateSource` says which entries are its own and
whether a tenant receives them. `AccountingTemplateReader` is the generic source every tenant receives.
`RetreadPlantAddOnSource` (AW30) owns accounts 6350, 6450, 6470, 6510, 6520, 6530 and 6900 (4940 after S15's
renumbering) and their `LABOR_OVERHEAD` lines; a tenant receives them only when its `RETREAD_PLANT_ADD_ON` row
in `accounting_configuration` is `true`. Absent means off, there is no switching off, and the alpha default
tenant's choice is recorded on by `V5`, so its `V2` rows are adopted.

**Status read.** `GET /v1/accounting/tenant-template/status` answers `state`, `lastAppliedAt`, counts by
outcome, `retreadPlantAddOn` and `attention[]` of `{entryKey, kind, reason, templateValue, tenantValue}` in
business text ("6295 Staff Meals & Refreshments, expense"), never ids. It takes no tenant from the request.

| `state` | Meaning |
| --- | --- |
| `NOT_PROVISIONED` | The template has never been applied to this tenant |
| `UP_TO_DATE` | Everything is created or adopted |
| `PENDING` | The template is newer than the last apply; the next start brings it in |
| `NEEDS_ATTENTION` | At least one entry is `CONFLICT` or `WITHHELD` |

A request thread never switches tenant, so the status and the add-on endpoint work from the template the sweep
or the listener already read in this process. With the sweep off and no `tenant.created` consumed since the
start, the status cannot report `PENDING`, and a newly chosen add-on is recorded and reaches the tenant at the
next start.

**Observability.** INFO per tenant run (tenant, path `event` | `startup` | `add-on`, counts); one WARN per
`CONFLICT` / `WITHHELD` entry key; counter `accounting.tenant_template.entries` (tags `outcome`, `path`); gauge
`accounting.tenant_template.tenants_needing_attention`, set by the sweep; one `AccountingAuditLog` row per run
that changed anything (operation `TENANT_TEMPLATE_APPLY`, user `tenant-template`, fingerprint and counts) and
one per add-on choice (`TENANT_TEMPLATE_ADD_ON_ENABLE`, the caller, the justification).

**Adding to the template.** Add rows to `R__seed_reference_accounting.sql` only: platform tenant, the id
expression, references by natural key. Never seed a tenant's rows from Flyway. A new table in the template
needs its kind, natural key and adoption rule in `TemplateEntryKind`, `AccountingTemplate` and the applier.
Existing tenants receive an addition at their next start.

**Rolling this back is not a plain revert.** The repeatable seed before #2526 looked accounts up by code with
sub-selects that, run as the Flyway owner, see every tenant's rows; with the template in the platform tenant
they return more than one row and the service does not start. To roll back: revert the change, delete the
platform tenant's rows (`WHERE tenant_id = '01900000-0000-7000-8000-000000000000'`) from
`statement_line_mappings`, `default_gl_mapping`, `gl_mapping`, `mapping_key`, `posting_category` and
`gl_account`, in that order, and `flyway repair` (or reset the database) so Flyway forgets `V5`, which the
reverted code does not know. `V5`'s two tables and the configuration row can stay; rows provisioned into
tenants stay, they are ordinary tenant rows. `TenantTemplateAdoptionIT` runs the old seed in exactly that
template-free state.

**Before a second tenant is created on a cell,** the module must use the remote registry
(`pos.tenancy.registry.mode=REMOTE`) or list its tenants in `pos.tenancy.tenants`: the sweep reaches only the
tenants `TenantIterator` knows, so template additions would miss a tenant the registry does not list. Its own
`tenant.created` still provisions it.

Tests: `AccountingTemplateApplierTest` (each outcome, healing, never re-applied, refresh only when untouched),
`TenantTemplateProvisioningIT` and `TenantTemplateAdoptionIT` (Testcontainers Postgres: two tenants, isolation,
concurrency, a database that ran the old seeds).

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
- Register float entries (#2571, AW32): a relocation entry is never reversed (409
  `FLOAT_RELOCATION_NOT_REVERSIBLE`); a go-live or Change float entry of a register that has moved may
  not be reversed before its latest move (422 `FLOAT_REVERSAL_BEFORE_RELOCATION`), and when its 1080
  line sits at a location the register has left, the reversal also posts, on its own date, the
  reclass that brings the reversed amount to the register's current location (422
  `GL_MAPPING_NOT_CONFIGURED` if `REGISTER_FLOAT` has no mapping on that date)
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

### Accounting time zone (#2558)

Every posting date and every period boundary is cut in the **tenant's accounting-calendar zone**, the
`ACCOUNTING_TIME_ZONE` row of `accounting_configuration` (an IANA region id). The `Clock` bean stays UTC and
the JVM zone is never read: `AccountingCalendarZoneResolver` is the only place an instant becomes a business
date (period service `getPeriodIdForDate` / `getCurrentPeriodId`, the settlement listener, automatic
application, the payment-application and customer-credit GL handlers, customer-credit draw-downs, invoice
revenue recognition and reversal, inventory adjustment / revaluation / shrinkage, register over/short, the
ingestion-record dates of the invoice, inventory, order and warranty listeners, the vendor-bill duplicate-date
compare and bill-number month, the default date of a journal-entry reversal, a credit-memo reversal or void, and
an API-submitted event without a transaction date). An ArchUnit rule
(`posting_dates_use_the_accounting_calendar_zone`) fails anywhere under `com.positivity.accounting` (the resolver
excepted) on anything that reads the clock's or the JVM's zone: `Clock.getZone()`, `Clock.systemDefaultZone()`,
`ZoneId`/`ZoneOffset.systemDefault()`, `TimeZone.getDefault()`, and `now()` / `now(Clock)` on `LocalDate`,
`LocalDateTime`, `LocalTime`, `YearMonth`, `Year`, `MonthDay`, `ZonedDateTime`, `OffsetDateTime` and `OffsetTime`.
Bank reconciliation dates follow the accounting calendar too (Accounting Domain ruling on #2558): the
"statement end date must not be in the future" check, the close-readiness aging cap and outstanding-item aging
all read today through the resolver (`BankRecCalendar` for the intake port, `ReconciliationSupport.today()`), and
answer `422 ACCOUNTING_TIME_ZONE_UNSET` without a zone. Bank-import file retention stays intentionally UTC
(retention, not accounting; the stamp and the purge use the same zone). The Kafka posting
listeners treat an unset zone as they treat a closed period: the posting fails and the record is retried by the
container (DLQ after the retries; Accounting Domain ruling on #2558); only the settled-payment path holds a row
`SUSPENDED / ACCOUNTING_TIME_ZONE_UNSET`. Each failed attempt is logged with `reason=ACCOUNTING_TIME_ZONE_UNSET`
and counted in `accounting.kafka.record.failed{reason,topic}`; a record sent to its `.dlq` is counted in
`accounting.kafka.record.dead_lettered{reason,topic}` (every other failure is `reason=OTHER`).
**Runbook:** set the tenant zone (`PUT /v1/accounting/configuration/time-zone`), then replay the DLQ records —
never post manual journal entries. A replay is idempotent on the event id (`processed_events`): a failed record
was never marked processed, and a second replay posts nothing.

A tenant without the row cannot close a period or set a hard-lock date either (`422 ACCOUNTING_TIME_ZONE_UNSET`).
GL account activation and deactivation stamps, the default-mapping validation instant, the bank-cash "active now"
reads, the settlement reclass date fallback and the receivables worklist's "today" (days past due, as aged
receivables and the walk-in business day) are all in the tenant's calendar, so they compare correctly with
posting dates.

- **Seed.** V10 gives every existing tenant `UTC` (what the UTC clock dated everything in, so nothing is
  re-cut); tenant provisioning (`DataInitializationServiceImpl`) gives every new tenant `UTC`. An
  administrator sets the legal entity's zone before the first close.
- **No default.** A tenant without the row posts nothing: a settled payment is held
  `SUSPENDED / ACCOUNTING_TIME_ZONE_UNSET`, which the failed-event retry job releases by itself once the row
  exists (sharing the module's attempt cap; after it, `POST /v1/accounting/events/{eventId}/reprocess`), a GL
  work item fails and retries, and a request answers `422 ACCOUNTING_TIME_ZONE_UNSET`.
- **No cache.** Every date reads the row by its unique index, so a change is seen by every instance at once.
- **Changing it.** `PUT /v1/accounting/configuration/time-zone` `{"timeZone":"America/Chicago"}` (permission
  `accounting:period:hard_lock`, the authority over the hard-lock date). Only IANA region ids:
  `400 INVALID_ACCOUNTING_TIME_ZONE` for an unknown id, a fixed offset (`+05:00`, `UTC+05:00`, `Etc/GMT+5`, and
  the UTC aliases `GMT`, `Etc/UTC`, `Etc/GMT`) or a `SystemV/*` id; only `UTC` itself, the seed, is
  accepted. Once the tenant has closed a period (even one reopened since) or set a hard-lock date the zone is
  fixed: `409 ACCOUNTING_TIME_ZONE_LOCKED`. Each change is audited
  (`ACCOUNTING_TIME_ZONE_SET`, old and new zone, actor). A change never re-cuts history: posted entries keep
  their dates and periods.
- **Calendar lock.** A zone change, a period close and a hard-lock change each take the tenant's
  `ACCOUNTING_TIME_ZONE` row `FOR UPDATE` first, so a close or hard lock that commits first is seen by the
  zone change's checks (409), and a zone change that commits first is what the close then cuts in.

### Bank reconciliation close readiness and policy (#2305)

- `GET /v1/accounting/periods/{periodCode}/close-readiness` — the derived readiness read model: per in-scope
  bank account the baseline that applies at the period end, the coverage and reconciled frontiers, the OPEN
  outstanding items with their sum and the checks that fired; tenant-wide `DRAFT_JOURNAL_ENTRIES` and
  `CLEARING_BALANCE_AGING` in the top-level `checks[]` (permission `accounting:period:view`, event
  `ACCOUNTING_PERIOD_CLOSE_READINESS`)
- `GET|PUT /v1/accounting/periods/bank-reconciliation-policy` — `closePolicy` (`ADVISORY` /
  `REQUIRED_WITH_EXCEPTION` / `REQUIRED`), `closeScope` (`BANK_CASH_SUBTYPE` / `ALL_RECONCILABLE`),
  `closeCoverageLagDays`, `allowSelfApproval`, `otherApprovalThreshold` (null = unset); PUT replaces all five
  with a justification of at least 10 characters and writes one `BANK_REC_POLICY_SET` audit row per changed
  setting (GET `accounting:period:view`, PUT `accounting:period:hard_lock`; events
  `ACCOUNTING_PERIOD_BANK_REC_POLICY_VIEW` / `_SET`). Defaults with no row: `REQUIRED_WITH_EXCEPTION`,
  `BANK_CASH_SUBTYPE`, `0`, `false`, unset
- Close evaluates readiness under the period row lock after the DRAFT check. Under `REQUIRED*` a BLOCKING
  check refuses the close (`422 PERIOD_BANK_RECONCILIATION_INCOMPLETE`) unless, under
  `REQUIRED_WITH_EXCEPTION`, the body carries `bankReconciliationException.justification` and the caller holds
  `accounting:period:close` and `accounting:period:override` (else `403 PERIOD_CLOSE_EXCEPTION_NOT_PERMITTED`);
  a granted exception is audited as `PERIOD_CLOSE_BANKREC_EXCEPTION` with the readiness snapshot. The
  `PERIOD_CLOSE` audit row carries a readiness summary; the response carries `bankReconciliationReady` and
  `bankReconciliationException`. Because the seeded `1000 Cash` is in scope, an unreconciled tenant cannot close
  under the default policy until it reconciles, takes the exception, or sets `ADVISORY`

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
  `failureReasonCode=PERIOD_CLOSED`; the auto-retry loop skips them (as it skips currency holds,
  `CURRENCY_NOT_SUPPORTED`), and they become reprocessable after the period is reopened

### Hard Lock

The org-level hard-lock date lives in `accounting_configuration` (V14, key `HARD_LOCK_DATE`); the table
ships empty, so no hard lock exists until an operator sets one via
`PUT /v1/accounting/periods/hard-lock` (permission `accounting:period:hard_lock`, mandatory justification,
event `ACCOUNTING_PERIOD_HARD_LOCK_SET`; read via GET with `accounting:period:view`). The date is
monotonic-forward-only — moving it backward is rejected with `422 HARD_LOCK_DATE_REGRESSION` — which makes
the lock effectively irreversible.

Permission catalog note: catalog v23 adds bits 382 (`accounting:period:hard_lock`) and 383
(`accounting:period:override`); the `CATALOG_VERSION` 22 → 23 bump requires a fleet-coordinated deploy.
The override permission is the constant `AccountingPermissions.PERIOD_OVERRIDE` (CAP:550 S3 moved it off
the former `AccountingPeriodGate.OVERRIDE_AUTHORITY`; the code and bit are unchanged).

## Permissions and the accounting roles (CAP:550 S3, #2504)

Every permission this module enforces is a constant in `internal/security/AccountingPermissions.java` and
registered in `src/main/resources/permissions.yaml` (ADR-0025). Who holds what is decided in
`pos-security-service` (`R__seed_role_permissions.sql`, the platform role template); applications gate on
permission codes, never on role names (`SPEC-accounting-workspace` §8.2). After S3 the accounting-workspace
holder sets are:

| Permission | Held by exactly |
| --- | --- |
| `accounting:payment:apply` | `ACCOUNT_MANAGER`, `ACCOUNTING_CLERK`, `ADMIN`, `CONTROLLER`, `GENERAL_MANAGER` |
| `accounting:ap:pay` | `ADMIN`, `CONTROLLER`, `GENERAL_MANAGER` — clerks never pay bills (AW6) |
| `accounting:ap:view` | `ACCOUNTING_CLERK`, `ADMIN`, `CONTROLLER`, `GENERAL_MANAGER`, `SUPPORT` |
| `accounting:reconciliation:adjust` | `ACCOUNTING_CLERK`, `ADMIN`, `CONTROLLER` (the preparer; `CONTROLLER` alone approves) |
| `accounting:payment:assign-customer` | no role yet |
| `accounting:ap:approve` | `ACCOUNTING_CLERK`, `ADMIN`, `CONTROLLER`, `GENERAL_MANAGER` — send a bill for approval, correct a match exception, select a candidate, enter the real due date, and approve or `ACCEPT` a bill within the clerk limit (S12, #2509; S13, #2510; reinstated, bit 262) |
| `accounting:ap:reject` | `ACCOUNTING_CLERK`, `ADMIN`, `CONTROLLER`, `GENERAL_MANAGER` — reject, void a match exception; with the approval tier, void an approved bill (S12; reinstated, bit 263) |
| `accounting:ap:approve_over_limit` | `ADMIN`, `CONTROLLER`, `GENERAL_MANAGER` — approve, `ACCEPT` or void an approved bill over the clerk limit (default 0: every bill) (S12, catalog v102, bit 558) |
| `accounting:ap_approval_policy:manage` | `ADMIN`, `CONTROLLER`, `GENERAL_MANAGER` — read and change the AP approval policy (S13, #2510; catalog v103, bit 559) |
| `accounting:tax_registration:view` | `ADMIN`, `CONTROLLER`, `SUPPORT` (the `accounting:mapping-key:view` holders) — read the tenant's tax registrations (S32c, #2638; catalog v105, bit 562) |
| `accounting:tax_registration:manage` | `ADMIN`, `CONTROLLER` (the `accounting:mapping-key:edit` holders) — record and change them through the front door to pos-tax (S32c; catalog v105, bit 561) |

`accounting:payment:assign-customer` (catalog v97, bit 548; `AccountingPermissions.PAYMENT_ASSIGN_CUSTOMER`)
is registered ahead of its endpoint — assigning a customer, once and with a justification, to a payment
received without one (AD-004, depends on OI-8). No `@PreAuthorize` names it and no role holds it until that
endpoint lands; `scripts/audit-rbac.py` reports it as `registered_unrequired` / `catalog_dead`, both
informational.

Side effect of the S3 grants: `POST /v1/accounting/payments/{paymentId}/void` and `/reverse` are gated by
`accounting:ap:pay`, so an `ACCOUNTING_CLERK` cannot void or reverse a receivable payment. S3 does not
regate them.

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

## Invoice Revenue Recognition (issue #1843)

Accounting is event-only inbound and outbound (ADR-0044 §6). Invoice revenue reaches the ledger through
`InvoiceEventsListener` → `InvoiceRevenuePostingService`, not through any REST call from pos-invoice.

- **Trigger** — an applied (non-stale) `invoice.invoice.updated` fact on `invoice.events.v1` with status
  `FINALIZED` (or `POSTED`, so manifest replays of invoices the old simulated posting path had already marked
  posted are backfilled). Deposit-take invoices (`depositSourceType` set — they fund a contract liability, #1623),
  zero/null totals, and facts without `finalizedAt` are skipped.
- **Entry** — `Dr 1200 Accounts Receivable (total) / Cr 4000 Service Revenue (total − tax) / Cr 2200 Sales Tax
  Payable (tax)`, dated at the invoice's `finalizedAt` (business time, so it lands in the invoice's month). The
  tax leg is omitted when zero; the entry balances by construction because pos-invoice computes
  `total = subtotal + adjustments + tax`. Accounts resolve through the `INVOICE_REVENUE` posting category and its
  `ACCOUNTS_RECEIVABLE` / `SERVICE_REVENUE` / `SALES_TAX_PAYABLE` mapping keys (seeded in
  `R__seed_reference_accounting.sql`) — never hardcoded. The period gate applies; a CLOSED period, missing
  mapping, or transient DB error propagates unwrapped so the Kafka container retries / DLQs the record
  (ADR-0044 §4) instead of marking it processed.
- **Idempotency** — one `invoice_gl_posting` row per `(invoice_id, finalized_at)` cycle, written in the same
  transaction as the journal entry; at most one open (un-reversed) posting per invoice. Redeliveries, the
  follow-up `POSTED` fact, and replays of an already-reversed cycle post nothing. The journal entry's
  `sourceEventId` is `nameUUIDFromBytes("INVOICE_REVENUE:" + invoiceId + ":" + finalizedAt)`.
- **Reversal** — a `DRAFT` or `CANCELLED` fact for an invoice with an open posting posts the mirror
  (`Dr Service Revenue / Dr Sales Tax Payable / Cr Accounts Receivable`) for the amounts actually posted, dated
  at the fact's `occurredAtUtc` (current open period, like the credit-memo void mirror), and closes the row
  (`reversal_journal_entry_id`, `reversed_at`). Re-finalizing afterwards carries a new `finalizedAt` and posts a
  fresh cycle. Without an open posting the fact is a no-op.
- **Outbound fact** — each posting and reversal enqueues `accounting.invoice.gl-posted` v1
  (`InvoiceGlPostedV1`: `invoiceId`, `journalEntryId`, `postingKind` `POSTED|REVERSED`, `finalizedAt`, `postedAt`,
  `reversedJournalEntryId`) on `accounting.events.v1` through the transactional outbox
  (`kafka_event_outbox`, `OutboxEventWriter` / `OutboxPublisher`, at-least-once, keyed by invoice id).
  pos-invoice consumes it to move the invoice `FINALIZED → POSTED` with the real journal entry id. The outbox
  writer is always active in deployed profiles (ADR-0044 §4, #2195); in the broker-less `dev`/test profiles it is
  absent, the posting still happens and the publish is a no-op. (`event_outbox` / `OutboxProcessor` is the unrelated in-process Spring-event outbox.)

## Statement Lines: Signs and Totals (issue #2394)

The income statement and balance sheet are built from `statement_line_mappings` (GL account → statement line,
with an operation). `FinancialReportingServiceImpl` reads the account's type (`gl_account.account_type`) for both
the sign and the totals; the statement line code is a label only and carries no meaning.

- **Line amounts** — each mapped account contributes its posted balance on its normal side: assets and expenses
  as debits minus credits; liabilities, equity and revenue as credits minus debits. So a revenue or liability
  account mapped with `SUM` reads as a positive amount. `SUBTRACT` takes that amount off the line. `NEGATE`
  keeps its older meaning, credits minus debits whatever the type: on a credit-normal account it equals `SUM`
  (the sign is not flipped twice), on a debit-normal account it reverses the balance. The account drill-down
  returns the same per-account amounts, so its rows add up to the line.
- **Totals** — taken from the account types, each mapped account once, independent of line code and operation.
  Income statement: `totalRevenue` is credits minus debits over the mapped `REVENUE` accounts, `totalExpenses`
  debits minus credits over the mapped `EXPENSE` accounts, `netIncome` the difference. Balance sheet:
  `totalAssets` from `ASSET` accounts, `totalLiabilities` from `LIABILITY` accounts, `totalEquity` from `EQUITY`
  accounts plus any mapped `REVENUE` / `EXPENSE` accounts (earnings not yet closed), the last three as credits
  minus debits.
- **Mixed lines** — a line may aggregate accounts of different types (a gross-profit line of sales less cost of
  sales). It is not rejected: the line shows the combined amount and each account still goes to its own total.
  An asset, liability or equity account mapped onto the income statement shows on its line and joins neither
  total (logged at WARN).
- **Every posted balance counts (CAP:550 S35, #2524).** An account with a non-zero balance (as of the date on the
  balance sheet, for the period on the income statement) and no mapping for that statement lands on a computed
  line, so the totals cover every account and `balanced` means something: on the balance sheet `BS_IN_THE_BANK`
  for a `BANK_CASH` asset (AW9), else `BS_OTHER_ASSETS`, `BS_OTHER_LIABILITIES`, `BS_OTHER_EQUITY`, and revenue
  less expenses on `BS_PROFIT_NOT_YET_CLOSED`; on the income statement `IS_OTHER_INCOME` and `IS_OTHER_EXPENSES`.
  Computed lines appear only when non-empty; named lines appear even at zero. Balances come from one grouped
  query per statement; the report logs at INFO which account codes fell on computed lines. A mapping moves an
  account off a computed line, never duplicates it.
- **Named lines.** The template (`R__seed_reference_accounting.sql`, applied per tenant, see "Tenant
  provisioning") carries the balance sheet lines `BS_IN_THE_BANK` (1000), `BS_WAITING_TO_BE_DEPOSITED` (1090,
  1095), `BS_CUSTOMERS_OWE_YOU` (1200), `BS_INVENTORY` (1300), `BS_BILLS_FROM_VENDORS` (2000),
  `BS_SALES_TAX_COLLECTED` (2200), `BS_CUSTOMER_CREDITS` (2300) and the income statement lines `IS_SALES` (4000,
  formerly `REVENUE`), `IS_COST_OF_PARTS_SOLD` (5000, and 5100 Inventory Shrinkage: cost of inventory consumed sits above gross margin), `IS_CARD_PROCESSING_FEES` (6000). `BS_KEPT_IN_DRAWERS`
  (1080), `BS_OWNER_EQUITY` (3000) and `BS_OPENING_BALANCE_EQUITY` (3900) are reserved for S15; 1250 and 1260
  for S32. Until then those accounts fall on computed lines.
- **Drill-down.** `GET /reports/financial/drilldown/accounts/{statementLineCode}` lists every account on the
  line, mapped or collected by a computed code, with `accountCode`, `accountType` and the GL account's own name.
  A balance-sheet line reports as-of balances at `endDate`; an income-statement line reports period movement; in
  both cases the rows add up to the line.
- **Normal side on ledger reads.** General-ledger sections carry `accountType`, `normalSide` (`DEBIT` for
  assets and expenses, `CREDIT` for liabilities, equity and revenue), `normalOpeningBalance` and
  `normalClosingBalance`; lines carry `direction` (`INCREASE` / `DECREASE`) and `normalRunningBalance`; the GL
  account balance carries `accountType`, `normalSide` and `normalBalance`. Signed (debit-positive) fields are
  unchanged. `NormalSide` is the one rule.

## Payment Application

Every `payment_application` records the path that created it in `application_source` (V8, #2503):
`MANUAL` (`POST .../payments/{paymentId}/applications`, dated now, by the caller), `INVOICE_PAYMENT` (the
`INVOICE_PAYMENT` event processor, #2435) or `PAYMENT_SETTLED`. Rows created before V8 were backfilled from
the request id (`INVOICE_PAYMENT:` prefix → `INVOICE_PAYMENT`, else `MANUAL`).

**Settled payments apply automatically** (CAP:550 S2, #2503; AW14). After `SettlementEventsListener`
records a `payment.payment.settled` payment, `AutomaticPaymentApplicationService` decides in the same
handler transaction — the first rule that matches wins. Before the rules, the payment's history decides:
if a person undid an automatic application of it (by this path or the `INVOICE_PAYMENT` processor) nothing
is applied again, and if an application already exists under this settlement's request id that is the
result; neither writes a row.

| Case | Outcome | `accounting_event` row |
|---|---|---|
| a. `methodType` not `CASH` / `CARD` | not applied | `SKIPPED / NOT_POSTABLE` |
| b. invoice not in `ext_invoice` | not applied; the retry job tries again, sharing the module retry cap (`pos.accounting.failed-event-retry.max-retries`, default 3 passes, about 45 minutes at the default 15-minute poll). After that the row stays `SUSPENDED` and needs a manual `POST /v1/accounting/events/{eventId}/reprocess` once the invoice arrives | `SUSPENDED / INVOICE_NOT_FOUND` |
| c. invoice not `FINALIZED` / `POSTED` | not applied; retried up to the attempt cap | `FAILED / INVOICE_NOT_ELIGIBLE` |
| d. invoice party (UUID) missing or not the payment's customer | not applied | `SKIPPED / NOT_POSTABLE` "customer differs from invoice INV-…" |
| e0. the tenant has no accounting time zone (#2558) | not applied; the retry job applies it once the zone exists (shared attempt cap) | `SUSPENDED / ACCOUNTING_TIME_ZONE_UNSET` |
| e. settlement date (in the tenant's accounting-calendar zone) in a closed or hard-locked period | not applied; reprocess by hand after reopening (a hard-locked date cannot be reopened: the detail says to match or credit the payment by hand) | `SUSPENDED / PERIOD_CLOSED` |
| f. payment has nothing unapplied (another path applied it) | nothing | none |
| g. invoice has no open balance | not applied, not credited, left for a person (most likely a duplicate charge; confirmed by the Accounting Domain, 2026-10-06) | `SKIPPED / NOT_POSTABLE` |
| h. otherwise | the whole unapplied amount applied to **that invoice only**, capped at its balance, excess kept as a customer credit (AD-003) — except on the CASH walk-in account, where the excess stays unapplied (see below) | none: the application is the evidence |

The application, any credit and both GL work items (Dr 1090 / Cr 1200, and Dr 1090 / Cr 2300 for an excess)
are dated `settledAt`, created by `SYSTEM`, keyed `PAYMENT_SETTLED:<paymentIntentId>`. That key makes it
once per settlement: a redelivery, a re-publish under a new event id or a reprocess returns the recorded
application. An automatic application undone through `reversePaymentApplication` is never repeated by
either automatic path: a later settled fact or reprocess applies nothing, and a later `INVOICE_PAYMENT`
event for the payment is `PROCESSED / DUPLICATE_IGNORED` (the payment stays `AVAILABLE` for a person). The
application's and credit's `created_at` is when they were written (ADR-0024); the business date is
`application_timestamp` and the journal entries' date. A payment left unapplied stays `AVAILABLE` in
`GET /v1/accounting/receivable-payments`. Counter `accounting.payment.settled.auto_apply`, tag `outcome`
(`applied`, `skipped_method`, `skipped_party`, `skipped_paid`, `suspended_invoice`, `suspended_period`,
`failed_ineligible`, `already_applied`). Reprocessing a held row (`POST /v1/accounting/events/{eventId}/reprocess`,
or the retry job for `INVOICE_NOT_FOUND` / `INVOICE_NOT_ELIGIBLE`) re-runs this decision from the stored fact,
never the posting engine and never recording the payment again: applied (or already settled) → `PROCESSED /
NEW`; a skip → `SKIPPED`; still held → the new hold. Payments settled before this shipped are not
re-processed (AW13).

### Concurrency

`ReceivablePayment` uses JPA optimistic locking (`@Version`, V10). `RetryingPaymentApplicationService`
(`@Primary` decorator outside the transaction boundary) retries an application exactly once on an
optimistic-lock conflict, re-reading fresh state and re-running all validations (AD-010 idempotency
preserved); a second conflict returns `409 Conflict` and the client should retry.

## Unpaid walk-in sales (CAP:550 S11, #2508)

Walk-in sales go to the tenant's CASH house account (AW12), which must net to zero every business day. The
account is the party the `ext_customer_party` replica flags `house_account = CASH_SALE`
(`CustomerPartyUpdatedV1.houseAccount`, V9) — never the customer number `CASH` or the name. One shared rule,
`InvoiceBalanceCalculator.walkInPartyIds()` / `isWalkIn`, serves every view below.

**The read.** `GET /v1/accounting/unpaid-walk-in-sales` (`reporting:view:financial-statements`, event
`ACCOUNTING_UNPAID_WALK_IN_SALES_VIEW`) returns `asOf`, `houseAccountKnown`, `customerNumber`,
`currencyCode` (functional, ADR-0067), `balance` (the CASH party's open AR-eligible balances, the same open
rule and currency scale as aged receivables), `openInvoices[]` (`invoiceNumber`, `locationCode`, `saleDate`,
`total`, `balanceDue`, `businessDayEnded`, `timezoneFallback`, `resolutions`, and `invoiceId` / `locationId`
for links), `needsAttention {count, amount}` and `unappliedPayments[]` (`paymentReference` = the number of
the invoice the payment was taken against, `receivedAt`, `unappliedAmount`, `paymentId`). Computed on read;
no job and nothing stored. It never posts and never changes an invoice.

- **Business day.** A sale's date is the local date of `finalizedAt` (else `invoiceCreatedAt`) in its
  location's time zone (`ext_location.timezone`, V9, from `LocationUpdatedV1.timezone`). Its business day has
  ended once that date is before the location's current local date; such an invoice is the day-end
  needs-attention item (§9.5a). A location without a usable time zone, or an invoice without a location,
  uses UTC with `timezoneFallback = true`, logged once per location (WARN).
- **Resolutions** are `COLLECT` (payment capture in pos-invoice) and `CREDIT_MEMO`. **Reassign** to the real
  customer is not offered until Invoicing & Payments decides reassignment of a finalised invoice (§12 OI-5).
- **`houseAccountKnown = false`** means the replica has no CASH party yet: the read answers zero but cannot
  vouch for it; a consumer must not show "all clear" on that basis.

**No customer credit on the CASH account** (§4.4 item 4). `PaymentApplicationServiceImpl` never creates a
`CustomerCredit` for a CASH payment:

- automatic paths (`PAYMENT_SETTLED`, case h above, and `INVOICE_PAYMENT`, including a payment on an invoice
  already paid): applied up to the open balance; the excess stays unapplied on the `ReceivablePayment`, which
  stays `AVAILABLE` and is listed in `unappliedPayments`; WARN and counter `accounting.walk_in.overpayment`.
  The event is still processed, so no retry loop starts;
- a person (`POST .../payments/{paymentId}/applications` with an overpayment, or
  `POST .../payments/{paymentId}/remainder-credit`): 422 `CASH_CUSTOMER_CREDIT_NOT_ALLOWED` ("Walk-in
  overpayments are refunded, not kept as credit"), nothing written. The excess is returned through
  pos-invoice's payment refund (`POST /v1/invoices/{invoiceId}/payments/{paymentId}/refunds`).

A completed refund (`payment.payment.reversed`, `REFUND`) takes the refunded amount off the payment's
unapplied remainder — up to the remainder, never touching its applications — in the same transaction as the
refund replica row and once per `refundId` (a replay is skipped). A payment left with nothing is
`FULLY_APPLIED`, leaves `unappliedPayments` and cannot be applied again. This holds for every customer's
payment, not only CASH.

A refund processed before the fact that records its payment (#2556) finds no payment yet: its row is
stored, and whichever fact records the payment releases it in the same transaction — a
`payment.payment.settled` fact (record the payment, apply it automatically (S2), then release) or an
`INVOICE_PAYMENT` event (record, apply or credit, then release). The stored refunds for that
`paymentIntentId` come off what the application left, which is the outcome the record-then-refund order
gives, so either arrival order ends the same on both paths. Only the event that recorded the payment
releases (a fact re-published under a new event id changes nothing; a redelivery is skipped by its processed
mark, which commits with the payment). The settlement handler, the refund handler and the `INVOICE_PAYMENT`
processor each take a Postgres transaction-scoped advisory lock on the `paymentIntentId` first
(`PaymentIntentLock`, `pg_advisory_xact_lock` on the transaction's own connection; skipped on H2), so a
refund and the fact recording its payment that commit at the same time cannot miss each other.

The counter `accounting.refund.unreleased` counts refunds that released less than they refunded, by
`reason`:

| `reason` | Log | Meaning |
| --- | --- | --- |
| `payment_not_recorded` | WARN | The refund came first; the fact that records the payment releases it. A payment that never arrives (held for its currency, party-less) is for a person. |
| `exceeds_remainder` | WARN | The payment had something unapplied and the refund is more than that: the applied part stays applied and the invoice still shows paid until a person reverses the application. |
| `fully_applied` | INFO | The payment had nothing unapplied (the ordinary refund of a paid invoice). Counted, not raised. |

**Excluded from customer views and measures** (§4.4 item 2; ADR-0057): aged receivables; collections (E2)
`invoiced`, and applications to CASH invoices and their reversals out of `collected` /
`applicationReversals` (the deposit-take exclusion pattern); payment-lag cohorts. Both analytics reads run in
one REPEATABLE READ snapshot, so the gross and the exclusion sums see the same commits. The ledger is unchanged
(account 1200, trial balance, balance sheet; ADR-0047). No customer statement, dunning or collection-case
feature exists yet; when one is built it must leave the CASH account out by the same rule.

**Settled payments without a party** (§4.4 item 1). Never given an invented customer; the envelope's
`schemaVersion` decides the severity: `1` (legacy, before go-live) keeps the WARN and
`payment.settled.unmappable`; `≥ 2` is a defect (S9 guarantees the party): ERROR with the payment intent id
and invoice number, counter `payment.settled.party_missing_defect`.

**Alert rules** for the operations dashboard: `payment.settled.party_missing_defect` > 0 (any rate is a
defect); and any CASH `needsAttention.amount` carried over more than one business day.

**Post-deploy step.** Existing CASH rows in this module's replica get the flag only from a pos-customer
party-fact replay: run `POST /v1/crm/accounts/facts/replay` once after deploying V9 (equal versions apply,
`ReplicaVersionGuard`). Location time zones fill on pos-location's next fact or replay the same way. Until
then the read answers `houseAccountKnown = false`, and the UTC fallback applies.

## Tax registrations: the front door to pos-tax (CAP:550 S32c, #2638)

pos-tax owns the tenant's indirect-tax registrations (ADR-0071 §7); pos-accounting is their only front door (AW59,
ADR-0071 §5). Nothing here names a country or a regime: they come from pos-tax's configured profiles.

| Method · path | Permission | Codes |
| --- | --- | --- |
| `GET /v1/accounting/tax-registrations[?asOf=]` | `accounting:tax_registration:view` | 200; 400 (malformed `asOf`); 403 |
| `POST /v1/accounting/tax-registrations` | `accounting:tax_registration:manage` | 201; 200 (replayed `requestId`); 400; 403; 409 and 422 relayed; 503 `SERVICE_UNAVAILABLE` + `Retry-After` |
| `PUT /v1/accounting/tax-registrations/{registrationId}` | `accounting:tax_registration:manage` | 200; 400; 403; 404, 409 and 422 relayed; 503 `SERVICE_UNAVAILABLE` + `Retry-After` |
| `GET /v1/accounting/tax-regimes[?countryCode=]` (#2659) | `accounting:tax_registration:view` | 200; 400 (malformed `countryCode`); 403; 503 `SERVICE_UNAVAILABLE` + `Retry-After` |

- **Writes.** The front door checks the justification (10 to 1000 characters) and the `requestId` (400
  `VALIDATION_ERROR`), then calls pos-tax (`TaxRegistrationClient`, ADR-0044 R2) with its per-caller secret
  (`pos.accounting.tax.front-door-secret`, env `POS_TAX_ACCOUNTING_SECRET`, never in code), the actor and the bound
  tenant. The actor is the authenticated principal's user id (ADR-0018), forwarded to pos-tax as `X-User-Id`; a body
  field or a raw header never names it, and a caller without a person's user id is 403. The inbound
  `X-Correlation-Id` is forwarded too. pos-tax's 400, 404, 409 (`TAX_REGISTRATION_OVERLAP`, `OPTIMISTIC_LOCK`,
  `IDEMPOTENCY_CONFLICT`) and 422 (`TAX_JURISDICTION_NOT_CONFIGURED`, `TAX_REGIME_NOT_DECLARED`) are relayed with their
  code, message and field errors (`fieldErrors[registrationNumber]` for a number that does not match its regime's
  shape); the correlation id is this request's. pos-tax unreachable, slower than the timeouts
  (`pos.accounting.tax.connect-timeout` 2s, `read-timeout` 5s), failing, a blank secret here or a 401 there is 503
  `SERVICE_UNAVAILABLE` with `Retry-After`; a 401 also counts `accounting.tax_registration.front_door_secret_refused`.
  Nothing is stored here either way. pos-tax is never reached from a screen.
- **Replica.** `ext_tax_registration` (V21) is written only by pos-tax's `tax.registration.changed` on
  `tax.events.v1` (`TaxRegistrationEventsListener`), keyed by the registration id and guarded by its version
  (`ReplicaVersionGuard`), so a redelivery or the manifest's re-send applies once. `TaxManifestListener` compares
  each `tax.manifest.v1` window with `processed_events` (owner `tax`) and asks pos-tax to replay a drifted window on
  `tax.commands.v1`. The GET reads it, every row or those in effect on `asOf` (both ends inclusive, AW49); a status
  is derived on `asOf`, else today in UTC. A write appears in the GET once its fact arrives. A fact whose payload
  names another tenant than the one its record header bound is skipped and logged.
- **Data.** The copy keeps the shape-checked, normalised number: INTERNAL under ADR-0072 Decision 1. Nothing logs it.
- **Regime choices (#2659).** `GET /v1/accounting/tax-regimes` lists the regimes pos-tax configures for `countryCode`
  (two upper-case letters, else 400 `VALIDATION_ERROR`), or for the tax country (`accounting.tax.country`) when it is
  omitted: each regime in configured order with the region codes it covers (empty = the whole country) and the tax
  types registered and recovered under it (code and jurisdiction level). A tax type with no regime cannot be
  registered for and is not listed. It is read on every call from pos-tax's `GET /v1/tax/tax-types` through
  `TaxReferenceClient` (`tax:rates:view`, tenant and correlation id forwarded, the timeouts above) and not cached. A
  country without a profile answers an empty list. Anything that is not an answer (pos-tax unreachable, any 4xx or
  5xx, or an answer missing a required list or field or naming an undeclared regime) is 503 `SERVICE_UNAVAILABLE`
  with `Retry-After`, never an empty list; nothing is relayed, since the country is validated here first.

## Input-tax recovery and typed output tax (CAP:550 S32d, #2639)

Multi-national first: recovery is keyed by *regime*, output tax by *tax type*, and accounts come from mapping keys a
currency's template data provisions. Countries, regimes and tax types come from pos-tax's profiles (S32a); no code
names one, nor an account number. A tenant with no registration and no typed keys (every USD tenant today) books
exactly as before.

| Method · path / listener | Permission | Codes |
| --- | --- | --- |
| `GET /v1/accounting/input-tax-recovery` | `accounting:mapping-key:view` | 200; 403; 422 `ACCOUNTING_TIME_ZONE_UNSET` |
| `PUT /v1/accounting/petty-expense-categories/{categoryCode}/tax-recovery` | `accounting:mapping-key:edit` | 200 (a replayed `requestId` returns the first result); 400; 403; 404; 409 `OPTIMISTIC_LOCK` / `IDEMPOTENCY_CONFLICT`; 422 `INPUT_TAX_RECOVERY_NOT_ENABLED` |
| Close fact v2 (`order.session.closed`) | — | one expense line, one line per recovered regime, one cash line |

- **Flags (AW49).** `InputTaxRecoveryFlags`: recovery under regime `R` is on at a date only while a registration for
  `R` is in effect in `ext_tax_registration` (S32c) **and** the currency pos-tax configures for its country
  (`GET /v1/tax/tax-types`) is the functional currency (today the ledger currency; ADR-0067 A2 replaces the source).
  Postings ask at their business date, so back-dating a registration never changes a posted entry. A profile that
  cannot be read (`TaxProfileClient`: the gateway authority header `tax:rates:view`, the bound tenant, the inbound
  `X-Correlation-Id`, `pos.accounting.tax.connect-timeout` / `read-timeout`) throws: the posting rolls back for retry
  and recovery is never read as off.
- **Settings read.** One row per registered regime (country, regime, `enabled`, the registration in effect with its
  number and start, and the `INPUT_TAX_<regime>` account), the evidence rules of each registered country, every
  category's `taxRecoverable` / `recoverablePercent` / `version`, and the change history (date, actor and role, old to
  new, reason). It never fails because of pos-tax: `enabled` is then null (unknown, never off) and `evidenceRules`
  null.
- **Category recovery (item 4).** A category without a setting is not recoverable. The PUT writes
  `petty_expense_category_tax_setting`, a history row in force from now (`petty_expense_category_tax_setting_change`,
  never updated) and queues `accounting.petty-expense-category.changed`, which gains `taxRecoverable` and
  `recoverablePercent` (additive; a message without them reads as not recoverable). Refused with 422
  `INPUT_TAX_RECOVERY_NOT_ENABLED` while no regime's recovery is on today. Emits
  `ACCOUNTING_PETTY_CATEGORY_TAX_RECOVERY_UPDATE`.
- **Currency template data (item 3).** `CurrencyTemplateSource` owns the template entries the platform tenant's
  `accounting_template_currency_entry` rows reserve for a functional currency; the generic source owns none of them.
  The CAD data set in `R__seed_reference_accounting.sql` (placeholders held for expert advice, OI-4: recovery must
  not reach a production CAD tenant before it is answered): 1250 / 1260 (`TAX_RECOVERABLE`), 2210 / 2220 / 2230
  (`TAX_PAYABLE`), 6050 Cash Rounding; `INPUT_TAX_<regime>` under `REGISTER_CASH_MOVEMENT`, `TAX_RECOVERABLE_<regime>`
  under `VENDOR_BILL`, `SALES_TAX_PAYABLE_<taxType>` under `INVOICE_REVENUE`, `CASH_ROUNDING_DIFFERENCE` under
  `CASH_ROUNDING`; every category recoverable at 100 %, Staff meals at 50 % (template entry kind
  `PETTY_EXPENSE_TAX_RECOVERY`, in force from the template's date). Accounts stay remappable.
- **Petty expenses at close (item 9, AW52).** `PettyExpenseRecoveryDecider`: a stated regime is recovered only when,
  on the movement's date (its `occurredAt` in the accounting zone), its flag is on, the category was recoverable when
  the movement was **recorded** (the share then in force, from accounting's own history), pos-tax answered
  `PLAUSIBLE`, a supplier name and receipt reference are present, and a required supplier number is present.
  `rec = stated × share / 100`, HALF_UP at the currency exponent. The entry is Dr `PETTY_EXPENSE_<code>` (gross −
  Σ rec), Dr `INPUT_TAX_<regime>` per recovered regime, Cr `CASH_CLEARING` (gross), with the movement's idempotency
  key. Every stated regime's outcome is kept in `register_cash_movement_tax_recovery` (`recovery_withheld_reason`
  `NOT_REGISTERED`, `CATEGORY_NOT_RECOVERABLE`, `RATE_UNAVAILABLE`, `EVIDENCE_MISSING` or
  `SUPPLIER_REGISTRATION_MISSING`), with the supplier's number as the claim's evidence (INTERNAL, ADR-0072 Decision 1;
  never logged).
- **Vendor bills (item 10; AW37-AW43, AW51, AW53).** Every bill stores the tax its document states by type in
  `vendor_bill_tax` (`VendorBillStatedTax`): the EDI fact's taxes (S23), or the optional `taxByType[]` on approve and
  resolve-exception `ACCEPT`, copied from the document; it must sum to the stated tax, else 422
  `AP_BILL_TAX_SPLIT_MISMATCH` (`fieldErrors[taxByType]`). At approval (`VendorBillTaxSplit`), for a tenant with a
  regime enabled, each typed amount's regime and recoverability come from pos-tax's tax types; a recoverable type
  whose regime's flag is on at the bill date debits `TAX_RECOVERABLE_<regime>` (a credit note credits it), and
  anything else goes into its line's class, never into inventory cost. A bill with tax but no split recovers nothing
  (`TAX_SPLIT_MISSING`) and automatic approval leaves it `AWAITING_APPROVAL`. Evidence (AW53): when the gross reaches
  the `VENDOR_BILL` evidence rule and the vendor copy holds no registration of the country's supplier regime (pos-tax's
  `supplierRegistrationRegime`, compared on scheme and `last4` only), nothing is recovered
  (`SUPPLIER_REGISTRATION_MISSING`) and automatic approval is held. Each type's outcome is kept in
  `vendor_bill_tax_recovery`; the bill read gains `taxByType[]` and `inputTaxRecovery[]`, and the approval audit
  records both. A tenant without recovery (every USD tenant) books the gross as before and never calls pos-tax. Tax by
  type from the bill-intake channel (`BillIntakePort`, the S25 draft) arrives with S25 (#2518).
- **Output tax by type (item 11, AW50).** `ext_invoice_tax.tax_type` is filled from S32a's
  `TaxBreakdownLine.taxType`. A tenant that has any `SALES_TAX_PAYABLE_<taxType>` key mapped under `INVOICE_REVENUE`
  (`TypedOutputTax`) posts one tax leg per type to that key, HALF_UP per leg with any cent on the largest, and its
  reversals mirror the original lines. An invoice whose typed rows do not account for its whole tax, or whose type
  has no mapped key, posts nothing (AR, revenue and tax wait together): it is held `SUSPENDED` / `TAX_TYPE_MISSING`
  and posted by the audited reprocess (`InvoiceRevenueReprocessor`). No default account is used and no type is
  inferred. A credit memo follows how the invoice's own entry posted its tax (`invoice_gl_posting.tax_posted_by_type`),
  never the tenant's keys today: an invoice posted untyped is credited untyped, one posted by type is split across
  its types pro rata, and a memo against a held invoice is refused with 422 `TAX_TYPE_MISSING` before anything is
  stored (it succeeds once the invoice's reprocess posts it). A memo's void always mirrors the memo's own reversal
  lines. A tenant without typed keys (USD) posts exactly
  as before. The tax-liability reconciliation compares against every account the `SALES_TAX_PAYABLE` and
  `SALES_TAX_PAYABLE_*` keys map to in the period, never a literal code (`taxPayableAccountCode` lists them,
  comma-joined; still `2200` for a USD tenant).
- **Cash rounding (item 12).** The CAD data provisions 6050 and `CASH_ROUNDING_DIFFERENCE`; the posting waits for
  ADR-0067 step A8 (its own story), which adds the settled amount and the signed rounding to the cash
  `PaymentSettledV1`. Release gate: no production tenant whose currency has a cash-rounding increment (CAD first)
  takes cash until A8 merges.
- **Flyway.** `V23__input_tax_recovery.sql`.

## Location scope (ADR-0061, #1885)

Location-scoped permissions are enforced on top of `@PreAuthorize` using the caller's
`LocationScope` (decoded from the gateway's `X-Loc-Fin-Bits`, `X-Loc-Oth-Bits` and `X-Loc-Scope`
headers). Tokens without those claims are unscoped and behave exactly as before. Ancestor sets
come from this module's own `ext_location` replica via `LocationHierarchyService`, which is the
module's `LocationAncestorResolver`; there is no per-request call to pos-location. A denial is a
403 `ApiError` with code `LOCATION_SCOPE_DENIED`. The full per-operation record CI checks is
`location-scope.yaml` in this module's root.

Accounting names a location by its GL `locationId` dimension value (e.g. `LOC-107`) rather than by
pos-location's UUID, so the replica also carries the owner's `code` and the gate resolves the code
to the id the ancestor sets are keyed on before checking. A code the replica cannot place is
denied for a location-scoped caller — fail closed — and ignored for a global one.

**Gate** — the location names the site the report is derived for; outside the caller's reach is a 403:

| Operation | Permission | Where |
| --- | --- | --- |
| `generateLaborOverheadReport` | `reporting:view:financial-statements` | controller, after `fiscalYear`/`asOfMonth` validation |

This is the platform's clearest `FINANCIAL`-dimension case: an `ACCOUNTANT` assigned to a region
sees that region's shops and no others.

The register float commands (`/v1/accounting/registers/{registerId}/float`, #2511) gate the body's
`locationId` on `accounting:float:manage`. The relocation
(`POST /v1/accounting/registers/{registerId}/float/relocation`, #2571, AW32) gates **both**
`fromLocationId` and `toLocationId`, then requires the register's stored location to equal
`fromLocationId` under the row lock (422 `FLOAT_REGISTER_LOCATION_MISMATCH`; the stored location is
logged, never returned). It posts Dr 1080 {register, to} / Cr 1080 {register, from} for the float,
dated the move; a zero float moves without an entry. New codes: 404 `FLOAT_REGISTER_NOT_FOUND`, 422
`FLOAT_RELOCATION_SAME_LOCATION`, `FLOAT_RELOCATION_DATE_INVALID`, `FLOAT_AMOUNT_NEGATIVE`,
`FLOAT_DATE_BEFORE_RELOCATION` (a later go-live or Change float dated before the move),
`FLOAT_REGISTER_SESSION_OPEN`, 409 `FLOAT_RELOCATION_NOT_REVERSIBLE`, 422
`FLOAT_REVERSAL_BEFORE_RELOCATION`.

A move may not be dated before any float entry of the register — every go-live, change, relocation
and reversal, **reversed or not**, since a reversed entry's 1080 line stays on its date and a reversal
may be dated before the entry it reverses (`FLOAT_RELOCATION_DATE_INVALID`).

A register does not move while it has an open pos-order session (#2573): `OrderEventsListener` keeps
the `ext_order_register_session` replica from `order.session.opened` and `order.session.closed`
(version-guarded; a session never reopens). The closed fact closes the replica in a transaction of its
own, *before* the over/short posting transaction that holds the processed mark: a close whose posting
fails still closes the session, and the redelivery re-applies the state-based write harmlessly. The
relocation refuses, under the float row lock, a register whose latest-opened session is OPEN (422 `FLOAT_REGISTER_SESSION_OPEN`, `referenceId` = the
session id; its location is logged, not returned). Accepted race: a session whose opened fact has not
arrived does not block. The guard takes effect once pos-order publishes `order.session.opened` (S40,
#2578); until then the replica holds only sessions seen through their close facts. The fact
`accounting.float.changed` is schema version 3: version 2 added kind `RELOCATION` and a nullable
`previousLocationId`, version 3 adds `currencyCode` (#2577, below).

The order feed is reconciled (ADR-0044 §4, #2579): `OrderEventsListener` records every `order.events.v1`
eventId it reads under the `order` owner tag, the fact types it ignores included, and `OrderManifestListener`
compares each per-tenant `order.manifest.v1` window with those rows. On drift it counts
`replica.drift{owner="order"}` and sends `order.outbox.replay-requested` for the window on `order.commands.v1`;
pos-order re-sends the window's facts with their original event ids, which `processed_events` dedupes.

## Drawer movement posting (CAP:550 S17, #2513)

A closed register session posts its drawer movements at close, from the `movements` of
`order.session.closed` schema version 2 (S16, #2512). `OrderEventsListener` hands the fact to
`RegisterCashMovementPostingService` and then to the over/short posting in one handler transaction, so a
session posts all or nothing; the replica close still runs first, in its own transaction (#2573). A
schema-1 fact has no movements and posts its over/short alone, as before.

| Reason | At close | Accounts (posting category `REGISTER_CASH_MOVEMENT`) |
|---|---|---|
| `PETTY_EXPENSE` | one entry | Dr `PETTY_EXPENSE_<categoryCode>` / Cr `CASH_CLEARING` (1095), the receipt's gross; 2200 is never debited |
| `VENDOR_COD` | **not posted yet** (below) | — |
| `BANK_DROP`, `FLOAT_INCREASE`, `FLOAT_DECREASE` | nothing | the deposit (S18), Change float (S15) |
| none (recorded before S16) or unknown | nothing, counted `UNCLASSIFIED` | — |

- **Entry** — one two-line entry per movement, source type `REGISTER_CASH_MOVEMENT`, `sourceEventId` =
  `nameUUIDFromBytes("REGISTER_CASH_MOVEMENT:" + movementId)`, dated at the session's `closedAt`, both lines
  dimensioned `registerId` (the terminal), `sessionId` and the session's `locationId` — the session's, never the
  float row's (AW36). The description names the category, amount, receipt, register and close time. A category
  deactivated after its movement was recorded still resolves: its mapping key stays.
- **Idempotency** — `REGISTER_CASH_MOVEMENT_GL_POSTING:<movementId>`, registered with the entry and scoped to the
  tenant: a fact redelivered under a new envelope id posts nothing twice and its record is `PROCESSED /
  DUPLICATE_IGNORED`. The key expires after 24 hours (`IdempotencyService`); past that, the entry's deterministic
  `sourceEventId` is the durable backstop, as for inventory revaluations. The over/short has no such backstop: its
  "posts nothing twice" holds within the posting-key window, pending non-expiring posting keys (#2595). The checks run
  in the over/short's order — idempotency, then currency, then the fact's contract — so a redelivered session already
  posted never writes a hold, and a foreign session with a malformed movement is held rather than sent to the DLQ.
- **Failures** — a closed period or a missing mapping rolls the whole session back (over/short included) and
  propagates for retry and the DLQ, like the over/short; so does a petty expense that breaks the fact's contract
  (no `movementId`, not `OUT`, no category, no positive amount). A missing mapping writes no ingestion row; a
  reprocess route for `order.session.closed` (which would allow one) is a follow-up (#2594).
- **Currency** — a session whose fact or any movement to post is not in the ledger currency posts nothing, over/short
  included, and is held once (Ledger currency below).
- **Vendor cash on delivery** — its posting (Dr `ACCOUNTS_PAYABLE` / Cr `CASH_CLEARING` plus an AP payment of method
  `CASH`) is the second half of #2513, waiting on the vendor copy (S24, #2517) and the pay guard (S13, #2510); pos-order
  refuses the reason until #2576, which is blocked by that half. A `VENDOR_COD` movement that arrives meanwhile is
  **skipped**: logged at `ERROR`, counted on `accounting.cash_movement.unposted{reason=VENDOR_COD}` — which must alert,
  since the cash left the drawer and 1095 does not show it — and not posted. **Recovery is a backfill, never a
  redelivery**: every consumed close fact is stored whole as its `accounting_event` row's `payload`, so the COD half
  selects the `VENDOR_COD` ids in `payload.movements` that have no registered posting key and posts them. (A redelivery
  cannot: pos-order's manifest replay (#2579, above) re-sends a close fact under its original envelope id, which
  `processed_events` drops, and pos-order has no other re-emit of it.)
- **Metrics** — `accounting.cash_movement.posted{reason}` and `accounting.cash_movement.unposted{reason}`, counted
  after commit. Any `unposted` increment must alert (operations configuration).

## Bank deposits of drawer cash (CAP:550 S18, #2514)

Drawer cash leaves "Waiting to be deposited" (1090 + 1095, AW9) exactly when it reaches the bank, one entry per
deposit, and a wrong deposit is reversed, never edited (AW10, ADR-0047).

- **Undeposited sessions** — `OrderEventsListener` writes an `undeposited_session` row (and one
  `undeposited_session_drop` per `BANK_DROP` movement) for every `order.session.closed` fact at schema version 2 in
  the ledger currency, in the handler transaction that posts the session's movements and over/short, after they
  posted (`UndepositedSessionProjection`). It holds the drops (bag numbers, amounts) and their total, the
  **expected cash** (the `CASH` tender total, what the session's sales put in 1090) and the **clearing net** (the
  signed sum, debit positive, of the 1095 lines the session's over/short and petty expenses posted:
  `overShort − Σ petty`). A schema-1 fact, a session closed in another currency or with a drop in one, and a session
  S17 held for its currency write no row (ADR-0067 PC-9: never deposited at par); a redelivery writes nothing; a
  schema-2 fact without its `movements` list goes to retry / DLQ. A session with no drops, no expected cash and a zero
  clearing net (card tenders only, no over/short) is written `NOTHING_TO_DEPOSIT`, terminal: never listed, counted in
  the gauges or taken by a deposit. No call to pos-order (ADR-0044).
- **Read** — `GET /v1/accounting/undeposited-sessions` lists the `UNDEPOSITED` sessions the caller's
  `accounting:deposit:create` reaches, oldest first, with register, location, close date, age in days, bag numbers
  and amounts. With `sessionId` parameters it adds `selection`: `depositAmount`, `expectedCash`, `clearingNet`,
  `difference = depositAmount − expectedCash − clearingNet`, `balanced` and the entry's lines, so the dialog (S20)
  never sums amounts (P7). A `sessionId` it does not list is 400; a `bankGlAccountId` Record would refuse is 422
  `DEPOSIT_BANK_ACCOUNT_NOT_ELIGIBLE`. Nothing posts.
- **Record bank deposit** — `POST /v1/accounting/deposits` `{bankGlAccountId, depositDate, currencyCode, sessionIds,
  requestId, depositSlipReference?, overrideJustification?}`. One entry, source type `BANK_DEPOSIT`, dated
  `depositDate` through the period gate, its `sourceEventId` = `nameUUIDFromBytes("BANK_DEPOSIT:" + requestId)`: Dr the
  bank account by the drops (one line, the line bank reconciliation matches), Cr `UNDEPOSITED_FUNDS` (1090) by the expected cash, Dr `CASH_CLEARING` (1095) when the clearing net is a
  credit, Cr when a debit (a zero line is left out); accounts through the `BANK_DEPOSIT` posting category. Sessions
  are taken whole and become `DEPOSITED`; their rows are locked in session-id order, so two clerks serialize.
  Refusals: 422 `CURRENCY_NOT_SUPPORTED`, 422 `DEPOSIT_BANK_ACCOUNT_NOT_ELIGIBLE` (not an active, reconcilable
  `BANK_CASH` account in functional currency), 400 for an unknown session or one with nothing to deposit, 403
  `LOCATION_SCOPE_DENIED`, 409 `DEPOSIT_SESSION_ALREADY_DEPOSITED`, 422 `AMOUNT_PRECISION_EXCEEDS_CURRENCY`, 422
  `DEPOSIT_UNBALANCED` naming the difference (missing drops included; no plug line is ever written), then 400 when
  the selection balances at zero drops (no bank line to post), 422 `PERIOD_CLOSED` / `PERIOD_HARD_LOCKED`. Idempotent on `requestId`: a replay returns the first result (`replayed: true`, 200), another
  body is 409 `IDEMPOTENCY_CONFLICT`.
- **Reverse deposit** — `POST /v1/accounting/deposits/{depositId}/reversal` `{reason (10-400), reversalDate?,
  overrideJustification?, requestId}` reverses the entry through the journal-entry reversal (its default date, period
  gate and override); `DepositReversalReaction` marks the deposit `REVERSED` and returns its sessions to
  `UNDEPOSITED`. The same reaction runs when the deposit's entry is reversed through
  `POST /v1/accounting/journal-entries/{id}/reverse`, so the deposit and the ledger never disagree. The command takes
  no lock before the reversal, so both routes lock in the reversal's order (entry-number sequence, entry, deposit,
  sessions) and serialize; the loser of a race is 409 `DEPOSIT_ALREADY_REVERSED`. The deposit's reversal entry is
  never reversed itself (409 `DEPOSIT_REVERSAL_NOT_REVERSIBLE`: record the deposit again). 404 `DEPOSIT_NOT_FOUND`,
  409 `DEPOSIT_ALREADY_REVERSED`, 409 `IDEMPOTENCY_CONFLICT`.
- **Location scope** — every session and deposit is gated on its stored location (location-scope.yaml); a session
  with no location is reachable only by an unscoped caller.
- **Fact** — `accounting.deposit.recorded` v1 on `accounting.events.v1` (`DepositRecordedV1`, aggregate = the
  deposit): `{depositId, bankGlAccountId, depositDate, amount, currencyCode, sessionIds, status, journalEntryId}`,
  queued through the outbox when recorded and again with `status = REVERSED`.
- **Audit and metrics** — `BANK_DEPOSIT_RECORD` / `BANK_DEPOSIT_REVERSE` audit rows (actor, bank account, sessions,
  bag numbers, amounts, slip reference, `requestId`, override justification); counters `accounting.deposit.recorded`
  and `accounting.deposit.unbalanced`; gauges `accounting.deposit.undeposited.amount` and
  `accounting.deposit.undeposited.oldest_age_days` across tenants (`pos.accounting.deposit-gauge.*`).
- **Not here** — checks (no `CHECK` tender exists; spec discrepancy 1); the dialog (S20); the to-do items and the
  1090-age warning (S19). The clearing net is taken from the close fact when the session closes: a later reversal of
  one of the session's over/short or petty-expense entries does not change it.

## Ledger currency (ADR-0067)

The ledger books one currency, `accounting.ledger.base-currency` (`USD` in `application.yml`), read
through `LedgerCurrency` so ADR-0067 step A5 can replace it with the tenant's functional currency in
one place. A Stage A ledger never books another currency at par (ADR-0067 PC-9); an absent currency
on an inbound fact means the ledger currency until producers stamp one (E-3).

- **Register over/short and drawer movements** (`order.session.closed`, #2312, #2513) — a session closed in
  another currency, or with a drawer movement to post in another currency, posts nothing: neither the over/short
  nor any movement. It is held as one `AccountingEvent` row, `sourceSystem = pos-order`, `status = SUSPENDED`,
  `failureReasonCode = CURRENCY_NOT_SUPPORTED`, `domainKeyId` = `sessionId`, the currency in
  `errorMessage`; a redelivery writes no second row. Find one with
  `GET /v1/accounting/events?eventType=order.session.closed&domainKeyId=<sessionId>`.
- **Settled payments** (`payment.payment.settled`, #2310) — one in another currency never becomes an
  `AVAILABLE` `ReceivablePayment` and is never applied automatically (#2503). It is held the same way:
  `sourceSystem = pos-invoice`, `SUSPENDED`, `CURRENCY_NOT_SUPPORTED`, `domainKeyId` = `paymentIntentId`.
- **Releasing a hold** (#2334) — a held fact is `SUSPENDED`, not terminal, so it stays visible until a
  booking rate (ADR-0067 B1) or manual handling releases it. The scheduled auto-retry skips it, as it skips
  `PERIOD_CLOSED`; release goes through the audited `POST /v1/accounting/events/{eventId}/reprocess`. While
  the fact's `currencyCode`, or any of a session fact's `movements[].currencyCode` (#2513), is still not the
  ledger currency, a reprocess records a `FAILURE` attempt, re-suspends it with `CURRENCY_NOT_SUPPORTED` and posts
  nothing.
- **Vendor bills from supplier invoices** (`supplier.invoice.received`, #2309) — the bill records the
  invoice's `currency` (`vendor_bill.currency`, on `VendorBillResponse`). A bill in another currency gets status
  `CURRENCY_HOLD` with the reason in `rejectionReason`: it is not matched, cannot be approved through
  match resolution, is never paid (AP payment takes `APPROVED` bills only) and is left out of Aged
  Payables. A re-issue under the same number in a different currency is flagged `MATCH_EXCEPTION`, like a
  different amount; a re-issue of a held bill keeps it held.
- **Payment application** (`POST /v1/accounting/payments/{paymentId}/applications`, #2310) — a payment
  applies only to invoices in its own currency. The invoice replica carries no currency, so an invoice is
  in the ledger currency; a payment in another currency is refused with 422 `CURRENCY_NOT_SUPPORTED`
  (ADR-0067 PC-9 (a), ADR-0017 §2; #2334) before any application, credit or journal entry is written.
- **Register float** (`/v1/accounting/registers/{registerId}/float[/go-live]`, #2577; R-1) — a go-live or
  Change float states `currencyCode`: missing or not on the ISO 4217 list (`IsoCurrencyCodes`, never
  normalised) is 400 `VALIDATION_ERROR` naming the field; a code other than the ledger currency, or than
  the currency the register's float is held in, is 422 `CURRENCY_NOT_SUPPORTED` before any entry posts (the
  float-currency check runs under the row lock, and the refusal rolls the command back). A replay is checked first and answers with the first result; the code is part of the
  replayed body. `register_float` and `register_float_change` hold `currency_code NOT NULL` (V15; rows
  that predate it take the ledger currency from the `ledger_currency` Flyway placeholder that
  `FlywayConfig` binds from `LedgerCurrency`, never a literal). The response (`RegisterFloatResponse`)
  and `accounting.float.changed` (schema version 3, additive) carry it, from every go-live, change,
  relocation, reversal and start-up republish; a relocation request carries no amount and so no code.

## Vendor-bill approval and posting at approval (CAP:550 S12, #2509; AW8, AW37-AW47)

Every vendor bill passes an explicit approval. The actor of every decision is the caller in the security
context (ADR-0018), and it must be a named caller (403 otherwise); no body carries one (`operatorId` is gone
and ignored if sent). Every transition locks the bill row and re-reads its status under the lock, so of two
concurrent decisions one wins and the other is 409 `AP_BILL_NOT_APPROVABLE` naming the status it found.

**Replays.** The commands take no idempotency key (`requestId`): a transition is its own guard. A command sent
again after it succeeded finds the bill moved on and is refused with 409 (`AP_BILL_NOT_APPROVABLE`,
`AP_BILL_NOT_VOIDABLE`, `AP_MATCH_CANDIDATE_ALREADY_RESOLVED`); the client reads the bill to see the outcome.
Nothing posts twice: the posting's durable key backs the status guard.

```
PENDING_RECEIPT_MATCH ─submit (EDI only)─► AWAITING_APPROVAL ─approve (posts)─► APPROVED ─void (reverses)─► VOIDED
MATCH_EXCEPTION ───────submit─►         │             └─reject─► REJECTED (terminal, nothing posted)
MATCH_EXCEPTION ─ACCEPT (posts)─► APPROVED   ─VOID─► VOIDED   ─CORRECT─► PENDING_RECEIPT_MATCH (as received)
PENDING_RECEIPT_MATCH ─/match HIGH─► AWAITING_APPROVAL (submittedBy SYSTEM) ─/match MEDIUM or discrepancy─► MATCH_EXCEPTION
ambiguous match ─select candidate─► AWAITING_APPROVAL      CURRENCY_HOLD: never submitted, approved or posted (AW43)
PENDING_RECEIPT_MATCH (goods receipt) ─void, posts nothing (AW45)─► VOIDED
```

| Endpoint (`/v1/accounting/vendor-bills`) | Permission | Refusals |
| --- | --- | --- |
| `POST /{billId}/submit-for-approval` `{justification, classification?, difference?}` | `ap:approve` or `ap:approve_over_limit` | 400 `JUSTIFICATION_REQUIRED`, `VALIDATION_ERROR`, `ARGUMENT_NOT_VALID`; 404 `VENDOR_BILL_NOT_FOUND`; 409 `AP_BILL_NOT_APPROVABLE`, `AP_BILL_AWAITING_INVOICE`; 422 `AP_BILL_ZERO_TOTAL`, `AP_BILL_TOTALS_UNRECONCILED` |
| `POST /{billId}/approve` `{justification?, classification?, difference?, overrideJustification?}` | `ap:approve` or `ap:approve_over_limit`, then the tier (S13) | 403 `AP_APPROVAL_LIMIT_EXCEEDED`, `AP_BILL_SELF_APPROVAL`; 400 `JUSTIFICATION_REQUIRED` (a creator's exception use without one); 409 `AP_BILL_NOT_APPROVABLE`, `AP_BILL_AWAITING_INVOICE`; 422 `AP_BILL_UNCLASSIFIED`, `AP_BILL_TOTALS_UNRECONCILED`, `AP_BILL_ZERO_TOTAL`, `PERIOD_CLOSED`, `PERIOD_HARD_LOCKED`, `GL_MAPPING_NOT_CONFIGURED` |
| `POST /{billId}/reject` `{reason}` | `ap:reject` | 400 `JUSTIFICATION_REQUIRED`, 409 `AP_BILL_NOT_APPROVABLE` |
| `POST /{billId}/resolve-exception` `{resolutionAction, reason, classification?, difference?, overrideJustification?}` | any of the three; per action: `ACCEPT` and `CORRECT` `ap:approve` or `ap:approve_over_limit` (`ACCEPT` then the tier, S13), `VOID` `ap:reject` | 400 `VALIDATION_ERROR` (unknown action), `JUSTIFICATION_REQUIRED`; 409; `ACCEPT` as approve |
| `POST /match-candidates/{candidateId}/select` (no body) | `ap:approve` or `ap:approve_over_limit` | 404 `AP_MATCH_CANDIDATE_NOT_FOUND`, 409 `AP_MATCH_CANDIDATE_ALREADY_RESOLVED`, `AP_BILL_NOT_APPROVABLE`, `AP_BILL_AWAITING_INVOICE` (a candidate that kept no invoice), `AP_BILL_DUPLICATE` |
| `POST /{billId}/void` `{reason, overrideJustification?}` (`voidVendorBill`) | `ap:reject`; an `APPROVED` bill's also either approve permission and its tier against the current clerk limit (S13), checked by the service | 403 (`FORBIDDEN`, `AP_APPROVAL_LIMIT_EXCEEDED`); 409 `AP_BILL_NOT_VOIDABLE` (neither `APPROVED` nor a goods-receipt bill in `PENDING_RECEIPT_MATCH`, or anything allocated); 422 `PERIOD_CLOSED`, `PERIOD_HARD_LOCKED` |
| `PUT /{billId}/due-date` `{dueDate, justification?}` (S13) | `ap:approve` | 400 `VALIDATION_ERROR`, `JUSTIFICATION_REQUIRED`; 404; 409 `AP_BILL_NOT_APPROVABLE` outside review |
| `GET /{billId}` · `GET /stages` · `GET /by-stage?stage=&page=&size=` | `ap:view` | 404; 400 (unknown stage) |

Every command also answers 401 without a valid token and 403 `FORBIDDEN` without the permission. Every
justification and reason needs at least 10 characters (400 `JUSTIFICATION_REQUIRED` otherwise); a field over
1000 characters is 400 `ARGUMENT_NOT_VALID`.

**Before a bill is sent, approved or accepted** (409 or 422, nothing written):

- no ambiguous match naming it may be open: 409 `AP_BILL_NOT_APPROVABLE`, pick the match first (select a
  candidate); while candidates are open the read offers only the selection, `CORRECT` and the voids;
- a goods-receipt bill needs a vendor invoice matched to it (AW45): its latest evidence is a match or a selection
  (not an ambiguous match's scoring) and its lines carry what was billed, else 409 `AP_BILL_AWAITING_INVOICE`.
  "Send without match" stays for EDI bills only. A receipt no invoice will match is closed by
  `POST /{billId}/void` from `PENDING_RECEIPT_MATCH` (`VOID_UNMATCHED`, `ap:reject`, reason): nothing posts, the
  receipt's accrual stays in 2100 until the vendor's EDI bill classified `GOODS` clears it at its approval;
- a bill of 0.00 has nothing to post: 422 `AP_BILL_ZERO_TOTAL`;
- the vendor's totals add up (AW47), or the command says where the gap posts (below).

**Posting at approval (AW37-AW42, AW47).** Approve, `ACCEPT` (and S13's system approval) post the bill in the same
transaction through `VendorBillPostingService`: a bill is approved if and only if it posted, and creation, match,
selection, submit and reject post nothing. Accounts resolve through the `VENDOR_BILL` posting category:

| Class | Debit |
| --- | --- |
| `RECEIPT_MATCHED` (stocked line matched to its receipt) | `GOODS_RECEIVED_NOT_BILLED` (2100) = billed qty x received price; `PURCHASE_PRICE_DIFFERENCE` (5050) = the rest, debit or credit |
| `GOODS` (stock with no receipt; an EDI bill's header) | 2100 at the stated net; the stated US tax to 5050 |
| `EXPENSE` (non-stock lines; an EDI bill's header) | `EXPENSE_<CODE>` (the nine AW18 codes on their AW30 accounts), tax included |

The credit is `ACCOUNTS_PAYABLE` (2000), always the billed gross; a credit note posts the mirror (`EXPENSE` or
`PRICE_ALLOWANCE` to 5050). A bill without receipt-matched lines (EDI) and any non-stock line need a
`classification {debitClass, expenseMappingKey}` from the approver or the submitter's proposal, merged field by
field (each field the approver gives wins; the vendor default arrives with S24), else 422 `AP_BILL_UNCLASSIFIED`.
A header tax on a bill with lines is prorated by line net, the residual cent on the largest line.

**The vendor's own totals (AW47).** An EDI bill keeps the vendor's `net_amount` and `tax_amount` as stated, signed
like the gross. A missing tax is 0; a missing net is gross - tax, so a derived net never leaves a gap; neither stated,
net = gross and tax 0. A stated net and gross without a tax are checked as net + 0 (ruling #2509 comment 6059252089).
A gap `gross - (net + tax)` within 0.01 per stated line, at most 0.05 per bill (a header-only bill counts as one
line), goes on the largest debit and is kept as `posting.roundingAdjustment`. A larger one creates the bill in
`MATCH_EXCEPTION` with `statusExplanation` "The vendor's totals don't add up: net N + tax T ≠ total G" and
`checks[]` `TOTALS_ADD_UP` = FAIL `{difference}`; submit, approve and `ACCEPT` then need `difference {class, expenseMappingKey?, justification}`: `FREIGHT` posts to
`FREIGHT_IN` (5060), `GOODS` to 2100, `EXPENSE` to the `EXPENSE_<CODE>` key given, `PRICE_DIFFERENCE` to 5050, a
negative gap as a credit. Without it: 422 `AP_BILL_TOTALS_UNRECONCILED`, nothing written; `CORRECT` and `VOID`
remain. A difference given at submission is kept on the bill (`approval.proposedDifference`) and posts at approval
unless the approver gives another; the posting records `differenceClass` and `differenceAmount`.

The entry is dated on the bill date when it is on or before today and its period is open, otherwise today
(tenant calendar); the read serves `posting {journalEntryReference, postingDate, postingDateRule
(BILL_DATE | APPROVAL_DATE_BILL_PERIOD_NOT_OPEN | APPROVAL_DATE_BILL_DATE_FUTURE), roundingAdjustment,
differenceClass, differenceAmount, reversalReference}`. The period gate then applies (CLOSED: 422 `PERIOD_CLOSED`
unless `accounting:period:override` and `overrideJustification`, audited `PERIOD_OVERRIDE_POST`; HARD_LOCKED: 422
`PERIOD_HARD_LOCKED`). A key with no mapping effective on that date, or one whose account is not active then, is
one refusal (#2601): 422 `GL_MAPPING_NOT_CONFIGURED`, its message naming the category, the key and the posting date,
guided with `referenceId` `VENDOR_BILL/<KEY>` and a `nextAction` naming the mapping to set up. Any refusal rolls the
approval back (no approval field, no entry) and writes one `VENDOR_BILL_APPROVE_REFUSED` (or
`..._MATCH_EXCEPTION_RESOLVE_REFUSED`) audit row in a transaction of its own. One `vendor_bill_gl_posting` row per
bill holds the entry and the durable keys `VENDOR_BILL:<billId>` and `VENDOR_BILL_VOID:<billId>`; the entry's
source is `VENDOR_BILL` / `nameUUIDFromBytes("VENDOR_BILL:" + billId)`.

**The void (AW42).** A void of an approved bill with nothing allocated reverses the entry through the
journal-entry reversal, dated today in today's period, never back in the original one. Only the void reverses a
bill's entry: `POST /v1/accounting/journal-entries/{id}/reverse` on a bill's entry, or on its void's reversal, is
409 `AP_BILL_ENTRY_NOT_REVERSIBLE` and rolls back (`VendorBillReversalReaction`), so the ledger always follows the
bill's status. Payments lock the bills they allocate to in id order and read the status under the lock, so a void
and a payment of the same bill never both succeed.

`VendorBillGLPostingEvent`, its handler and the `VENDOR_BILL_GL_POSTING` event type are retired; V17 closed every
such event not already `PROCESSED` as `SKIPPED / RETIRED_EVENT_TYPE`, which no retry selects, and
`POST /v1/accounting/events` refuses the type with 400 `VALIDATION_ERROR`.

**Matching keeps what was billed (AW39, AW46).** `/match` considers goods-receipt bills only (never an EDI bill) and
compares the invoice with what was received: the lines with a received quantity above 0 and the sum of their line
totals, for the tolerance check and the amount points alike. A single match (HIGH, MEDIUM or a discrepancy) and a
candidate selection set the bill's number to the invoice reference, its date to the `invoiceDate` (required on
`/match`) and its total to the billed total, and keep each receipt line's billed quantity and price (a received line
the invoice did not bill is billed 0; an invoice line with no receipt becomes a line of its own with nothing
received; a second match replaces what the first kept). The duplicate rule is checked first on the invoice number
and date, so a refusal (409 `AP_BILL_DUPLICATE`) leaves the receipt bill untouched. Each status-changing match and
each selection writes one append-only `vendor_bill_match_evidence` row: the score and points per criterion (amount
40, products 30, date 20, purchase order 5), the confidence, the invoice and its date, the receipt date
(`receivedDate`), the received and billed totals and the line comparison. Candidate rows keep their points and the
invoice. The matched bill is locked and re-read before routing: a bill decided meanwhile is 409 `OPTIMISTIC_LOCK`
(send the invoice again). Selecting a candidate returns the bill the ambiguous match had held in `MATCH_EXCEPTION`
for that invoice to `PENDING_RECEIPT_MATCH` (`VENDOR_BILL_MATCH_CANDIDATE_RELEASE`). `CORRECT` puts a goods-receipt
bill back to its receipt: the added lines removed, the billed values cleared, the total the received total, the
bill date the evidence's `receivedDate`, and the submission, proposal and difference cleared.

A re-issue of an `APPROVED` or `PAID` bill never reopens it: the bill keeps its status and approval, and a
`vendor_bill_reissue` row linked to it records both amounts; the bill is locked and re-read first, and one voided
or rejected meanwhile retries the event, which then becomes a bill of its own.

**Reads.** `GET /{billId}` adds `channel`, `netAmount`, `taxAmount`, `approval` (submission, `requiredTier`
from the current clerk limit (S13), `clerkLimit` and its `currencyCode`, the proposed classification and difference,
and the approval only once approved, with `approvedByKind` `PERSON` | `SYSTEM`),
`rejection` (`REJECTED`, `VOIDED`), `statusExplanation` (`MATCH_EXCEPTION`, `CURRENCY_HOLD`), `openAmount`, `match`
(latest evidence), `openCandidates[]` (each with `candidateId` and `invoiceEventId`), `reissues[]`, `lines[]`,
`checks[]`, `availableActions[]` (the decisions valid now whose permission the caller holds; `VOID_APPROVED`
only with a posting and no allocation; one the tier or the creator rule blocks is listed with `allowed = false` and
its `blockedReason`, S13) and `posting`. The checks:

| Code | Outcome |
| --- | --- |
| `MATCHED_TO_DELIVERY` | PASS once an invoice is matched (HIGH, MEDIUM, a selection; MEDIUM passes, its confidence in `args.confidence`); FAIL `reason` `PICK_A_MATCH` (open candidates), `INVOICE_NOT_MATCHED` (goods receipt) or `NO_DELIVERY_RECORDED` (EDI) |
| `WITHIN_PRICE_TOLERANCE` | the matched invoice against the receipt; NOT_APPLICABLE before a match |
| `TOTALS_ADD_UP` | bills with the vendor's header totals only; FAIL with `difference`, `netAmount`, `taxAmount`, `totalAmount`, `tolerance` |
| `WITHIN_CLERK_LIMIT` | S13: in `PENDING_RECEIPT_MATCH`, `MATCH_EXCEPTION`, `AWAITING_APPROVAL`, PASS when the absolute total is within the clerk limit (above 0), else FAIL, with `totalAmount`, `clerkLimit`, `currencyCode`; NOT_APPLICABLE otherwise |
| `OPEN_DELIVERIES_FROM_VENDOR` | EDI bills classified `GOODS` only; FAIL with `count` and `billNumbers` (up to 10) while the vendor has goods-receipt bills in `PENDING_RECEIPT_MATCH`, `MATCH_EXCEPTION` or `AWAITING_APPROVAL`; informational, blocks nothing |

`GET /stages` counts `CHECK` (`PENDING_RECEIPT_MATCH`, `MATCH_EXCEPTION`, `CURRENCY_HOLD`), `APPROVE`
(`AWAITING_APPROVAL`), `PAY` (`APPROVED`, open > 0) and `DONE` (`APPROVED`, paid in full, last payment this month);
`GET /by-stage` lists one stage in the server's order, page size capped at 100, with no due-date window. Aged
payables report `AWAITING_APPROVAL` bills under `unapproved`, never aged.

**Audit and events.** One `accounting_audit_log` row per decision (entity `VENDOR_BILL`; `VENDOR_BILL_SUBMIT`,
`_APPROVE`, `_REJECT`, `_VOID` (`action=VOID_APPROVED` or `VOID_UNMATCHED`), `_MATCH_EXCEPTION_RESOLVE`,
`_MATCH_CANDIDATE_SELECT`, `_MATCH_CANDIDATE_RELEASE`, `_MATCH_ROUTED` by `SYSTEM`) with the tier, the clerk
(`limit`) and automatic (`autoLimit`) limits in force, `exception` (`CREATOR_APPROVAL` | `NONE`), the total,
currency, match score and evidence id, and on an approval the rounding adjustment and any difference.
`@EmitEvent` ids `ACCOUNTING_VENDOR_BILL_SUBMIT`, `_APPROVE`, `_REJECT`, `_VOID` (approval), `_STAGES_VIEW` (fast
read), `_STAGE_LIST` (search).

## Approval limits and separation of duties (CAP:550 S13, #2510; AW4-AW7, AW33, AW45-AW47)

**The AP approval policy.** Five `accounting_configuration` keys, read in one snapshot by `ApApprovalPolicy`; an
absent key reads as its default and an unreadable value as the stricter default, with a warning:

| Key | Default | Value |
| --- | --- | --- |
| `AP_CLERK_APPROVAL_LIMIT` | 0 (no clerk approves) | amount >= 0, functional currency |
| `AP_AUTO_APPROVAL_LIMIT` | 0 (off) | amount >= 0, never above the clerk limit |
| `AP_ALLOW_CREATOR_APPROVAL` | false | boolean |
| `AP_ALLOW_APPROVER_PAYMENT` | false | boolean |
| `AP_DEFAULT_TERMS` | `NET30` | `DUE_ON_RECEIPT` or `NET1`..`NET120` (`CashAndPayablesSettings.parseTerms` reads the same vocabulary) |

`GET /v1/accounting/ap-approval-policy?historyPage=&historySize=` returns the five effective values,
`currencyCode`, `asOf` and `history[]` newest first (default page 20, at most 100): `changedAt`, `changedBy`,
`changedByRoles`, `setting`, `oldValue`, `newValue`, `justification`. `PUT` takes any of the five (missing =
unchanged), `currencyCode` (required with a limit, the functional currency: 422 `CURRENCY_NOT_SUPPORTED` otherwise;
a limit finer than the minor unit is 422 `AMOUNT_PRECISION_EXCEEDS_CURRENCY`), `justification` (>= 10 characters,
400 `JUSTIFICATION_REQUIRED`) and `requestId` (UUID). A negative limit, an automatic limit above the clerk limit,
terms outside the vocabulary, a limit with more than 13 integer digits or a missing `requestId` is 400
`VALIDATION_ERROR` with `fieldErrors`; the 400s answer before the 422s, and nothing is written on either. A clerk
limit below the stored automatic limit, sent without an automatic limit, lowers the automatic limit to it (a clerk
limit of 0 turns automatic approval off), on its own row tagged `cause=AP_CLERK_APPROVAL_LIMIT`; raising the clerk
limit never raises the automatic limit, and an automatic limit sent above the clerk limit stays 400 (ruling
6063520413 item 2).

The audit log is the policy's history of record (ruling item 5): only a changed setting is written, with one
`AP_APPROVAL_POLICY_SET` row (entity `ACCOUNTING_CONFIGURATION`, `old_value` the effective value before, `new_value`
`setting=<KEY>;value=<NEW>;roles=<ROLES>[;cause=<KEY>];requestId=<UUID>`, every key and value percent-encoded for
`%`, `;`, `=` and `,` so no role name can add or shadow a field; read first occurrence wins, and a row without escapes
reads as it is). Idempotency: every PUT that passes validation also writes one `AP_APPROVAL_POLICY_REQUEST` row whose
entity id is the `requestId`, a no-op PUT included, and `history[]` does not list it. A `requestId` already recorded
writes nothing and returns the current policy, so a retried no-op never overwrites a change made since. The PUT takes a
tenant-scoped transaction advisory lock first (two first PUTs on a fresh tenant serialize), then the rows `FOR
UPDATE`. Both need `accounting:ap_approval_policy:manage`; events
`ACCOUNTING_AP_APPROVAL_POLICY_VIEW` (fast read) and `_SET` (approval).

**The tier.** A bill is `CLERK`-tier when the clerk limit is above 0 and the absolute value of its stored
`totalAmount` (the billed gross: an EDI bill's stated gross, a goods-receipt bill's billed total after `/match`) is at
most the limit; else `OVER_LIMIT`, which needs `accounting:ap:approve_over_limit`. A `difference` never changes it.
It is derived on every read and decision, never stored, so a changed limit re-routes waiting bills at once. A
decision reads the policy rows share-locked (in key order, as the PUT locks them), so a racing PUT applies wholly
before or after it.

**Guard order** on approve, `ACCEPT` and the void of an approved bill; the first failure answers and nothing is
written: (1) the endpoint gate (403 `FORBIDDEN`); (2) the bill's state: status and open candidates (409
`AP_BILL_NOT_APPROVABLE`), a goods-receipt bill's matched invoice (409 `AP_BILL_AWAITING_INVOICE`), a void's
allocation (409 `AP_BILL_NOT_VOIDABLE`); (3) the tier (403 `AP_APPROVAL_LIMIT_EXCEEDED`, `nextAction` naming
`accounting:ap:approve_over_limit`); (4) creator is not approver, approve and `ACCEPT` only (403
`AP_BILL_SELF_APPROVAL`; under `AP_ALLOW_CREATOR_APPROVAL` it goes through with approve's `justification` or
`ACCEPT`'s `reason`, audited `VENDOR_BILL_SOD_EXCEPTION`); (5) the content: 422 `AP_BILL_ZERO_TOTAL`,
`AP_BILL_TOTALS_UNRECONCILED`, then `AP_BILL_UNCLASSIFIED` from a dry run of the entry's legs with the merged
classification (audited `_REFUSED`), then, after it, the hold for tax on goods for resale, 422
`AP_BILL_TAX_ON_RESALE_GOODS` (audited `_REFUSED`; S43, below); (6) the posting, whose first act is the self-assessed
(`USE`) tax quote when the bill accrues (S43), then `PERIOD_CLOSED`, `PERIOD_HARD_LOCKED`, `GL_MAPPING_NOT_CONFIGURED`. Each 403 of (3) and (4) is audited as `<operation>_REFUSED` with `code=` in a transaction of its own. Submit runs (2) and (5) only.

**Automatic approval.** Only on `/match`, a HIGH match within tolerance, and only when the automatic limit is above 0
and the absolute total is at most min(automatic, clerk) limit: the system submits, approves (`approvedBy` and
`submittedBy` `SYSTEM`, `approvedByKind` `SYSTEM`) and posts through `VendorBillPostingService` in the match
transaction, with the lines' own classes and no override, dated on the invoice date when its period is open, else
today (AW42). Whatever would need a person, or would refuse the posting (`AP_BILL_ZERO_TOTAL`,
`AP_BILL_UNCLASSIFIED`, `AP_BILL_TAX_ON_RESALE_GOODS`, `PERIOD_CLOSED`, `PERIOD_HARD_LOCKED`,
`GL_MAPPING_NOT_CONFIGURED`, `SERVICE_UNAVAILABLE` when pos-tax cannot answer; S43), leaves the bill
`AWAITING_APPROVAL` with one `VENDOR_BILL_AUTO_APPROVE_SKIPPED` row naming the code; the match is kept. The posting is
`MANDATORY` and the JPA dialect has no savepoints, so every refusal is asked first without writing (the legs in
memory, then the period and the mappings in a `REQUIRES_NEW` transaction of its own, which takes a second pooled
connection while the match holds its first). In the rare race where a period closes or a mapping changes between the
pre-check and the posting, the posting's exception is never caught: the whole `/match` rolls back and answers 422
`PERIOD_CLOSED`, `PERIOD_HARD_LOCKED` or `GL_MAPPING_NOT_CONFIGURED`. Nothing retries it (the REST controller is the
only caller); resending the invoice is safe, and the resend's pre-check ends matched, `AWAITING_APPROVAL`, with one
`VENDOR_BILL_AUTO_APPROVE_SKIPPED` row (ruling item 3).

**Approver is not payer.** `POST /v1/accounting/ap/payments` builds its allocation plan (explicit, or oldest due
first; an explicit 0.00 line is dropped, so it is neither allocated nor able to block a later void), locks its bills
and validates it before the payment row is saved and before the gateway is called: a missing bill, one not
`APPROVED`, another vendor's or an over-allocation is refused before the gateway, nothing is charged and no
`ap_payment` row is saved, so the same `paymentRef` may be sent again once corrected (ruling item 4). The plan's bill
locks are held across the gateway call (the automatic plan locks every `APPROVED` bill of the vendor), bounded by
the gateway's connect and read timeouts, and every wait for them by `accounting.ap.lock-timeout` (S42, #2627). Then the
pay guard refuses the whole payment when the payer (the security context's username) approved a bill of the plan
(`approvedByKind` `PERSON`): 403 `AP_PAYMENT_SELF_APPROVED_BILL`, `fieldErrors[selfApprovedBillNumbers]` naming each,
one `VENDOR_BILL_PAYMENT_REFUSED` row per bill surviving the rollback. Under `AP_ALLOW_APPROVER_PAYMENT` it pays and
audits each as `VENDOR_BILL_SOD_EXCEPTION`. A system approval never blocks. The pre-gateway block in
`APPaymentServiceImpl.executePayment` is ordered and commented: S42 adds its request and period checks, S24 its vendor
and remit-to checks, at the numbered places. The payer, and so `ap_payment.created_by`, is the security context's
username (`SecurityContextHelper`, ADR-0018), no longer `Authentication.getName()`.

**The real due date.** `PUT /v1/accounting/vendor-bills/{billId}/due-date` `{dueDate, justification?}`
(`ap:approve`; event `ACCOUNTING_VENDOR_BILL_DUE_DATE_SET`) in `PENDING_RECEIPT_MATCH`, `MATCH_EXCEPTION` or
`AWAITING_APPROVAL` only (409 `AP_BILL_NOT_APPROVABLE` otherwise), stored at the start of the day and audited
`VENDOR_BILL_DUE_DATE_SET` old to new. A later `/match` or selection whose invoice states a due date replaces it,
recorded as `dueDate=OLD->NEW` on the match's audit row; one set after the match stays.

**Data.** V18 adds `vendor_bill.approved_by_kind` (`PERSON` | `SYSTEM`, checked), backfilled `SYSTEM` where
`approved_by = 'SYSTEM'` and `PERSON` for any other approver. No pos-tax function is used (AW48).

## AP payments post through AP_PAYMENT (CAP:550 S42, #2603, #2627)

AW40, AW41: an AP payment reaches the books from the bank account it was paid from, with the fee the bank charged,
on the day it executed. No posting-rule version is involved: `AP_PAYMENT_GL_POSTING` is retired (V19 closed its events
SKIPPED / `RETIRED_EVENT_TYPE`, and `POST /v1/accounting/events` refuses it).

**The pay command.** `POST /v1/accounting/ap/payments` takes `bankAccountId` (the GL account id of an eligible
`BANK_CASH` account: active at the start of the execution date, the instant its entry posts at, and not in a foreign
currency per its bank profile; an account activated later that day is eligible from the next day, and an account
deactivated at any point up to the pay command cannot fund it, even though the entry posts at the start of the day:
Accounting ruling of 2026-10-08, #2603 comment 6068272860) and an optional
`overrideJustification` (10-1000 characters). `bankAccountId` may be omitted only when exactly one eligible account
exists; inactive and foreign-currency accounts are not counted. `netAmount` is gone from the request, the response, the
outbox event, the entity and the table: the bank pays gross + fee. An idempotent replay compares the resolved bank
account (an omitted one resolves to the one account active at the start of the stored date; a replay funds nothing,
so a deactivation since does not change it). The response adds `bankAccountId`
and `paymentDate`.

**Guard order** (`APPaymentServiceImpl.executePayment`, `APPaymentPreGatewayChecks`). Every refusal comes before the
gateway is called, charges nothing and saves no payment, so the same `paymentRef` may be sent again; the first refusal
wins. (A refusal's own audit row, such as `VENDOR_BILL_PAYMENT_REFUSED`, commits in a transaction of its own.)

| Slot | Status | Code | When |
| --- | --- | --- | --- |
| 1a | 422 | `AP_PAYMENT_METHOD_NOT_SUPPORTED` | `CREDIT_CARD` or `OTHER` (OI-17) |
| 1b | 400 / 422 | `VALIDATION_ERROR` / `CURRENCY_NOT_SUPPORTED` | not ISO 4217 / not the functional currency (ADR-0067) |
| 1c | 400 | `VALIDATION_ERROR` `fieldErrors[bankAccountId]` | missing and not exactly one eligible, or the supplied one not eligible |
| 2, 3 | 400 / 403 | allocation refusals, `AP_PAYMENT_SELF_APPROVED_BILL` | S13 |
| 2 | 409 | `LOCK_TIMEOUT` | a bill lock waited beyond `accounting.ap.lock-timeout` |
| 5a | 422 | `ACCOUNTING_TIME_ZONE_UNSET` | no accounting time zone (fails closed) |
| 5b | 422 | `PERIOD_HARD_LOCKED` | the execution date is hard-locked |
| 5c | 422 | `PERIOD_CLOSED` | its period is closed, without an `overrideJustification` and `accounting:period:override` |
| 5d | 422 | `GL_MAPPING_NOT_CONFIGURED` | no `AP_PAYMENT/ACCOUNTS_PAYABLE` mapping to an account active on the date, or none for `PAYMENT_FEES` with a fee above 0 |

S24 adds its vendor check at the end of slot 1 and its remit-to check in slot 4. Slot 5c reads the period row
**without a lock**: a lock held across the gateway call would hold every other posting of the month (each takes the row
`FOR UPDATE` in the period gate) behind it. A period closed during the gateway call refuses the outbox posting
(`PERIOD_CLOSED`) and the payment goes `GL_POST_FAILED`; whether a close should wait for unposted AP payments is close
readiness (S19, #2515). Likewise a hard lock moved past the payment's date during the gateway call leaves the payment
`GL_POST_FAILED` for good (`PERIOD_HARD_LOCKED`, never re-dated); that race is out of scope here.

**Execution date.** The tenant's business date (accounting time zone) is read once and fixed in slot 5 on
`ap_payment.payment_date` (`DATE`): the date the period check used, the date the entry posts on and the date a retry
posts on. It is never re-dated; a payment whose date is later hard-locked stays `GL_POST_FAILED` (S19 reports it).

**Posting** (`APPaymentPostingService`, from the outbox). It reads the `ap_payment` row, locked, never the event:
Dr `AP_PAYMENT/ACCOUNTS_PAYABLE` (2000) the gross, Dr `AP_PAYMENT/PAYMENT_FEES` (6030) the fee when above 0, Cr the
payment's own bank account the gross + fee, dated `payment_date`, source `AP_PAYMENT` with source event id
`nameUUIDFromBytes("AP_PAYMENT:" + paymentId)`. The payment stores its entry and moves to `GL_POSTED`; a second
delivery or a retry posts nothing. Allocations post nothing; an unapplied amount stays a debit in 2000 for the vendor.
A gateway `PENDING` charge (an ACH charge in flight) posts on the day it was initiated, like a mailed check; a charge
that later fails is not yet detected (follow-up #2642: settlement and returns).
The `AP_PAYMENT` category, its keys and mappings are template rows (`R__seed_reference_accounting.sql`) that S37's
applier and startup sweep give every tenant. No pos-tax function is used: a payment has no tax leg (AW48, OI-4).

**Refused after execution.** The money has moved, so the payment stands. A refusal at the outbox
(`GL_MAPPING_NOT_CONFIGURED`, `GL_ACCOUNT_NOT_ACTIVE`, `PERIOD_CLOSED`, `PERIOD_HARD_LOCKED`,
`ACCOUNTING_TIME_ZONE_UNSET`) is not transient: the payment goes `GL_POST_FAILED` with the code in `glPostError`,
recorded in its own transaction, and the outbox row completes without spending its retries. Any other exception
retries through the outbox; when the last retry fails, the payment goes `GL_POST_FAILED` with
`GL_POST_RETRIES_EXHAUSTED`, so `gl-posting-retry` can post it.

**The period override travels with the payment.** A closed-period override accepted in slot 5c is stored
(`period_override_justification`, `period_override_by`); the outbox posting applies it as the payer
(`AccountingPeriodGate.assertPostingAllowedWithRecordedOverride`), and the `PERIOD_OVERRIDE_POST` audit row names the
payer. Only the outbox delivery applies it (an ArchUnit rule limits the callers); a retry never does.

**Retry.** `POST /v1/accounting/ap/payments/{paymentId}/gl-posting-retry` `{overrideJustification?}`
(`accounting:je:post`; `accounting:period:override` for the override) locks the payment and posts a `GL_POST_FAILED`
one on its stored date: 200 with the payment `GL_POSTED`; 404 `NOT_FOUND`; 409 `AP_PAYMENT_NOT_RETRYABLE` for any
other status; 409 `LOCK_TIMEOUT`; 422 `GL_MAPPING_NOT_CONFIGURED`, `GL_ACCOUNT_NOT_ACTIVE`, `PERIOD_CLOSED`,
`PERIOD_HARD_LOCKED` or `ACCOUNTING_TIME_ZONE_UNSET` with the payment left `GL_POST_FAILED` and the new code in
`glPostError`. A retry is a new act (Accounting ruling 1 of 2026-10-08 on #2603): into a closed period it needs the
caller's own `overrideJustification` and `accounting:period:override`, applied and audited for the caller; the override
the payer gave on the pay command never applies to it, and the stored override fields are never changed.

**Bounded waits (#2627).** Every gateway call carries `payment.gateway.connect-timeout` (default 5 s) and
`payment.gateway.read-timeout` (default 20 s) on its Stripe `RequestOptions`. The pay command, the retry and the
vendor-bill decisions that lock a bill (approve, reject, void, due-date change, send, resolve) run `SET LOCAL
lock_timeout` from `accounting.ap.lock-timeout` (default 5 s) first; a wait beyond it answers 409 `LOCK_TIMEOUT`
("Another request is working on these bills; retry" on the AP paths, a neutral message elsewhere), and the transaction
rolls back. Work a REQUIRES_NEW transaction committed (a provisioned period row, a refusal's audit row) is not undone,
and such a transaction does not inherit the limit. A gateway timeout means the
outcome is unknown: the transaction rolls back (no `ap_payment` row, no allocation, no posting) and the caller sends
the same `paymentRef` again within the gateway idempotency window (`STRIPE_IDEMPOTENCY_WINDOW_HOURS`), whose key
replays the original outcome. If `idle_in_transaction_session_timeout` is ever set for pos-accounting, it must exceed
connect + read. The automatic plan still locks every `APPROVED` bill of the vendor.

**Data.** V19 makes `ap_payment.payment_date` a `DATE`, adds the foreign key `(tenant_id, bank_account_id)` →
`gl_account` and the two override columns, drops `net_amount`, and retires the `AP_PAYMENT_GL_POSTING` events. Same
table, same row-level security policy.

## Vendor bill duplicate rule (#2501, ADR-0070 Decision 4)

One rule decides whether a vendor bill already exists, for every path that writes a bill number. Two bills
are duplicates when they have the same tenant, the same `vendor_id` as stored, the same normalised bill
number and the same calendar date of `bill_date`, and neither is `VOIDED` or `REJECTED`. Every other status
counts, `PAID` and `CURRENCY_HOLD` included.

- **Normalisation** (`VendorBillNumbers.normalise`): Unicode NFKC, upper case, letters and digits only,
  leading zeros removed while more than one character remains, at most 255 characters. `INV-00123`,
  `inv 00123`, `INV/00123` and the full-width `ＩＮＶ－００１２３` share the key `INV00123`; `INV-123` does not
  (zeros inside the key are kept); `000123` and `12-3` share `123`. A number with no letter or digit has the
  empty key, which is legal. The key is stored in `vendor_bill.bill_number_key` by `VendorBill.setBillNumber`,
  so no writer can leave it stale; `bill_number` itself is unchanged.
- **Enforcement**: the partial unique index `uq_vendor_bill_duplicate_rule` on
  `(tenant_id, vendor_id, bill_number_key, (bill_date)::date)` where the status is not `VOIDED` or `REJECTED`
  (`V4__vendor_bill_duplicate_rule.sql`). The database is the authority; `VendorBillDuplicateGuard` reads the
  same key first so the answer can name the original. Voiding or rejecting a bill releases its key, so a
  re-issue goes through. The same number on another date is a different bill.
- **REST** (`POST /v1/accounting/vendor-bills`, `POST /v1/accounting/vendor-bills/match`): a duplicate is
  refused with 409 `AP_BILL_DUPLICATE`. `message` names the original by number, vendor and date and carries
  no id; `referenceId` is the original's `vendorBillId`; `nextAction` is `Open the existing bill.` Nothing is
  written: no bill, no vendor-directory entry, and a refused match leaves the
  goods-receipt bill as it was. A replayed goods-received `eventId` still returns the existing bill with 201.
  A match that loses a concurrent race for the same number between the rule's check and its commit is
  stopped by the index and answers the generic 409 `DUPLICATE_RESOURCE` instead, with no `referenceId`:
  the index violation is translated to `AP_BILL_DUPLICATE` on the create and listener paths only. The
  match is rolled back, so no second debt is recorded either way.
- **EDI** (`supplier.invoice.received`): the listener asks the same rule with the invoice date. A live
  original not yet approved is flagged `MATCH_EXCEPTION` when the amount or currency differs (unchanged from
  #2309; an `APPROVED` one keeps its status and gets a `vendor_bill_reissue` exception item, #2509) or recorded
  `PROCESSED / DUPLICATE_IGNORED` when identical; nothing is thrown and no second bill is created. With no
  live original the invoice becomes a bill. If the insert loses a race under the index, the handler runs once
  more in a new transaction and takes the duplicate path; a second collision propagates for retry, unmarked.
- **Migration guard**: V4 backfills the key, then stops with an error naming the count and the first ten
  groups if existing rows break the rule. It never edits a bill; void the extra bill (or reset a
  pre-production database) and run it again.
- **Goods-receipt bill numbers** (`BILL_<vendor prefix>_<yyyyMMdd>_<7-digit sequence>`): the sequence is
  the tenant's own, never a shared database sequence (ADR-0062 §9; platform-owner ruling of 2026-10-05). It
  is the `accounting_sequence` counter under scope `BILL-<yyyyMM>` (the month the bill is recorded in),
  drawn through `AccountingSequenceLocker` exactly as journal-entry numbers (`JE-<yyyyMM>`) and credit memo
  references (`CM-<yyyyMM>`) are. Each tenant starts every month at 1. The counter row is locked and
  incremented in the bill's own transaction, so concurrent creates in a tenant take consecutive, distinct
  numbers, and a create that rolls back (a refused duplicate included) does not consume its number. A
  tenant's row is created on first use; nothing provisions it. Before this, the number came from a
  database sequence `bill_number_seq` that no migration created, so the create failed on every Postgres
  database.
- **Connections under the counter lock**: the counter row stays locked from the draw to the end of the
  bill's transaction, so creates in one tenant queue on it, each holding a pooled connection. The create
  therefore asks for no second connection while it holds the lock: the vendor is read from the copy before
  the number is drawn (S24), and the former vendor-directory write is retired with `ap_vendor`. Before, on a
  pool as small as Compose's (`maximum-pool-size 3`), three concurrent creates left the lock holder waiting
  for a fourth connection until the pool's timeout (`VendorBillGoodsReceiptSmallPoolIT`). Nothing else between
  the number and the commit leaves the bill's connection: the GL posting hook and event ingestion join the
  transaction. One bounded exception: a create that loses the race under the unique index *inside a caller's
  transaction* reads the original on one extra connection while the aborted transaction still holds the lock;
  if the pool has none, that read fails after the pool's connection timeout and the create fails with that
  error instead of 409. No bill is created either way.
- **Observability**: one `accounting.vendor_bill.duplicate` increment per event, tagged `channel`
  (`goods_receipt`, `match`, `edi`) and `outcome` (`refused`, `flagged`, `ignored`, `retried`), and one log
  line: WARN for a refusal or a flag, DEBUG for an ignored duplicate (overlapping fetch windows republish by
  design), INFO for a retry. The counter is not tied to the transaction: a run that rolls back and is
  redelivered counts again.
- **Limits**: the rule compares `vendor_id` as stored. Since S24 every channel stores the pos-supplier
  vendor id, so one vendor has one key (EDI no longer keys on the vendor profile). The SQL backfill and the Java normaliser agree on every
  example above; a number with letters outside ASCII depends on the database's character classification
  in the backfill only, since Java is the key's sole producer afterwards.

## Vendor copies and one vendor key (CAP:550 S24, #2517; AW23, AW39, AW48; ADR-0072)

Bills, AP payments and the vendor reads name a vendor by its **pos-supplier vendor id**, read from
`ext_supplier_vendor`, accounting's copy of the vendor master. `ap_vendor` and its first-sight sync are retired
(V20). Neither this module nor pos-order calls pos-supplier (ADR-0044 R1); only the consumer writes the copy (R3).

- **The copy.** `SupplierEventsListener` (formerly `SupplierInvoiceEventsListener`, still the module's one
  `supplier.events.v1` consumer, group `pos-accounting-supplier-events`) upserts the copy on
  `supplier.vendor.updated` under `ReplicaVersionGuard`: an equal `aggregateVersion` re-applies (a replay
  repairs), an older one changes nothing. It holds every vendor, either status, with the remit-to and its
  version and requester, the default terms and currency, `createdBy` and `tax_registrations`
  (`[{scheme, region, last4}]`); no remit-to address or approver (ADR-0044 R3 minimum).
  Every fact the consumer reads is marked `owner = "supplier"`.
- **Schema version (Security ruling #2617, ADR-0072).** Only `schemaVersion` 2 or later is applied; the version
  is read from the envelope before the payload is mapped. An older vendor fact is marked, counted as
  `accounting.supplier_vendor.skipped{eventType, schemaVersion}` (those two tags only) and skipped; its payload
  is never logged. No full registration number is received, stored, returned or logged, `last4` is never
  logged or tagged, `ExtSupplierVendor.toString` leaves the registrations out, and a constraint refusal of the
  copy propagates with the constraint's name only (Postgres would quote the failing row), and the datasource sets
  pgjdbc's `logServerErrorDetail: false` so no driver message (which Hibernate logs at ERROR) carries a
  `Failing row contains (…)` detail. (On the copy's row-level-security tables Postgres never sends the application role the failing
  row anyway; the flag covers every other table and connection.) Hibernate's DEBUG entity listing
  (`org.hibernate.orm.core`, `EntityPrinter`) is pinned to INFO, since it prints every flushed value, `last4` included. Nothing here reads
  or validates the registrations (AW48; pos-tax is not called).
- **Seeding.** On first deployment the operator calls pos-supplier's `POST /v1/supplier/vendors/facts/replay`
  per tenant until it reports `complete` (`docs/OPERATIONS_RUNBOOK.md`). Until then bills and payments for
  unseeded vendors are refused or held as below.
- **EDI vendor key.** An invoice fact's vendor is its `vendorId`, never `vendorProfileId`. No `vendorId`: the fact
  is held, reason `VENDOR_ID_MISSING` (S25 turns these into drafts). A vendor not in the copy: held,
  `VENDOR_NOT_IN_COPY`, and released through the same bill creation, with its ingestion record, when the
  vendor is copied (`accounting.supplier_invoice.hold_released`). An `INACTIVE` vendor: the bill is created
  `MATCH_EXCEPTION`, `statusExplanation` "Vendor V-000123 is inactive"; an AW47 totals gap still reports
  `TOTALS_ADD_UP` FAIL and needs its `difference`. A hold (`supplier_invoice_hold`: event id, reason, the whole
  envelope) is written with the processed mark, so a fact is never dropped. `SupplierInvoiceHoldSweep`
  retries releases and refreshes the gauge `accounting.supplier_invoice.held{reason}` hourly, and WARN-logs
  holds older than 24 hours daily.
- **New business.** `POST /v1/accounting/vendor-bills` (goods receipt) and `POST /v1/accounting/ap/payments`
  (slot 1d) answer a vendor missing from the copy with 503 `VENDOR_REPLICATION_PENDING` (`Retry-After`; absence in an
  event-fed copy is "not yet", ADR-0017 §1) and refuse an `INACTIVE` one (422 `VENDOR_INACTIVE`); both copy the vendor's display name into `vendorName`. `/match` is not guarded. An
  inactive vendor's existing bill may still be approved or accepted, but is not paid.
- **Remit-to at approval and payment.** Every approval (approve, `ACCEPT`, S13's automatic approval) stamps
  `vendor_bill.approved_remit_to_version` with the copy's current `remitToVersion` (0 without a remit-to);
  a void never clears it. Payment slot 4 compares each planned, locked bill's version with the current one:
  a bill passes when they are equal, or when `ap_vendor_settings` holds a confirmation of the current version
  by someone other than the payer. Otherwise the whole payment is refused, 409
  `VENDOR_PAYMENT_DETAILS_CHANGED`, naming each bill and the vendor number, before any row is saved or the
  gateway is called; oldest-first never skips such a bill. A bill approved before S24 has no version and is
  refused until confirmed.
- **Vendor creator's first bill (rule 9).** Approve and `ACCEPT` refuse the vendor's creator (the copy's
  `createdBy`) while no bill or credit note of the vendor was ever approved, voided ones included: 403
  `AP_BILL_SELF_APPROVAL`, reason `VENDOR_CREATOR_FIRST_BILL` in the message and the refusal audit, in S13's
  guard position and under S13's `AP_ALLOW_CREATOR_APPROVAL` switch. Automatic approval and the void are exempt;
  the available actions show `APPROVE` / `ACCEPT_EXCEPTION` blocked for that approver.
- **Vendor AP defaults (rule 10, AW39).** `ap_vendor_settings.default_debit_class` (`GOODS` | `EXPENSE`) and
  `default_expense_mapping_key` (an active `VENDOR_BILL` key `EXPENSE_<CODE>`). An approval's classification is
  taken field by field: the approver's, then the proposal at submission, then the vendor default, then 422
  `AP_BILL_UNCLASSIFIED`. The class applies to a bill whose lines are not stored, the key to `EXPENSE` and
  non-stock lines; `RECEIPT_MATCHED` lines are never reclassified and a `GOODS` default never classes a credit
  note. Automatic approval uses the same fallback.
- **Reconciliation.** `SupplierManifestListener` compares each per-tenant `supplier.manifest.v1` manifest with
  the `supplier`-owned marks; on drift it counts `replica.drift{owner="supplier"}` and sends
  `supplier.outbox.replay-requested` on `supplier.commands.v1`. A tenantless manifest is skipped and counted.

| Method | Path | Permission | Codes |
| --- | --- | --- | --- |
| GET | `/v1/accounting/vendors?name=&status=&limit=` | `accounting:ap:view` | 200, 400 `VALIDATION_ERROR` (status) |
| GET | `/v1/accounting/vendors/{vendorId}` (with `apSettings`) | `accounting:ap:view` | 200, 503 `VENDOR_REPLICATION_PENDING` |
| POST | `/v1/accounting/vendors/{vendorId}/remit-to-confirmation` `{remitToVersion, justification}` | `accounting:ap:approve` | 200, 400 `VALIDATION_ERROR` / `JUSTIFICATION_REQUIRED`, 403 `VENDOR_REMIT_TO_SELF_CONFIRMATION`, 409 `VENDOR_PAYMENT_DETAILS_CHANGED`, 503 `VENDOR_REPLICATION_PENDING` |
| PUT | `/v1/accounting/vendors/{vendorId}/ap-settings` `{defaultDebitClass?, defaultExpenseMappingKey?, justification, requestId}` | `accounting:ap_approval_policy:manage` | 200, 400 `VALIDATION_ERROR` (`fieldErrors`) / `JUSTIFICATION_REQUIRED`, 403, 409 `IDEMPOTENCY_CONFLICT`, 503 `VENDOR_REPLICATION_PENDING` |

The PUT's missing field is unchanged and an explicit null clears it; unknown body fields are refused. It is
idempotent on `requestId`: the request row records the vendor and the normalised body, and a reused `requestId`
with another vendor or body is 409 `IDEMPOTENCY_CONFLICT`. The remit-to confirmation refuses the remit-to's
requester (`remitToRequestedBy`, 403 `VENDOR_REMIT_TO_SELF_CONFIRMATION`) before anything else is checked or written. Audit:
entity `VENDOR`, operations `REMIT_TO_CONFIRM` and `AP_VENDOR_SETTINGS_SET` (old → new, actor, justification,
`requestId`). The vendor read's `paymentDetailsChanged` is true while an approved, open bill was approved at
another remit-to version and nobody confirmed the current one; S19's work item reads it.

## Vendor AP hold and information-return flag (CAP:550 #2615; ADR-0072)

Two more settings on `ap_vendor_settings` (V22), set through the same `PUT …/ap-settings` under
`accounting:ap_approval_policy:manage`. No new permission bit.

- **AP hold.** `apHold {onHold, reason?}`: `onHold: true` needs a reason of 10-500 characters (code points, once
  stripped, as V22's `char_length(btrim(...))` counts them; text of only whitespace or no-break spaces is none)
  (shorter, blank or missing: 400 `JUSTIFICATION_REQUIRED` naming `apHold.reason`; longer: 400 `VALIDATION_ERROR`);
  `onHold: false` releases it, the PUT's `justification` recording why (a reason sent with it is ignored).
  `apHold: null`, or `onHold` missing or null, is 400 `VALIDATION_ERROR`. Audit: `AP_VENDOR_HOLD_SET` (old → new
  hold and reason) and `AP_VENDOR_HOLD_CLEARED` (the old reason), each with the actor from the security context,
  the justification and the `requestId`; the same reason again, or releasing a vendor not held, writes none.
- **What the hold stops.** Only `POST /v1/accounting/ap/payments`: 422 `VENDOR_ON_AP_HOLD` at **slot 1e** of the
  pre-gateway block, right after 1d (the vendor in the copy and active) and before the plan, so nothing is saved
  and the gateway is not called; an inactive vendor that is also held answers `VENDOR_INACTIVE`, and a payment
  replayed by `paymentRef` still returns its first result. The refusal is logged at INFO (payment reference,
  vendor number, code) and counted as `accounting.ap_payment.refused{code="VENDOR_ON_AP_HOLD"}`; the counter counts
  this refusal only (the pay command's other refusals are not counted there). Submit, approve,
  `ACCEPT`, automatic approval at `/match`, EDI and goods-receipt bill creation, reject, void and the due-date PUT
  all go ahead: approval records a debt that exists (AW37). The hold is independent of the vendor's status, takes
  no lock (a payment past its check completes), posts nothing and changes no aged payables.
- **Reads.** `VendorResponse.apHold` on the list and the detail read; `apSettings.apHold {onHold, reason, setBy,
  setAt}` on the detail read; `vendorApHold` on stage rows; `vendorApHold` and `vendorApHoldReason` on
  `GET /v1/accounting/ap/bills` rows; the informational bill check `VENDOR_AP_HOLD` (FAIL `{vendorNumber, reason,
  since}` while held, PASS otherwise, NOT_APPLICABLE on `REJECTED`, `VOIDED`, `PAID` and an `APPROVED` bill with nothing
  open). It blocks no action and sets no `blockedReason`. Each list page reads its vendors' settings in one query.
- **Information return.** `informationReturn {reportable, form?, box?, payeeTaxRegistrationScheme?}`:
  `reportable: true` needs a `form` and `box` configured for the tax country and an optional scheme among the
  form's `payeeIdSchemes`; `reportable: false` needs the three absent or null and clears them. The object replaces
  the stored flag as a whole: a form, box or scheme left out of it is stored as null (there is no partial update). Each violation is
  400 `VALIDATION_ERROR` with `fieldErrors[informationReturn.*]`; a country that configures no form refuses
  `reportable: true`. pos-tax is called only when the information return changes to a reportable value; when it
  cannot answer (unreachable, slow, or any 4xx or 5xx), the PUT is 503 `SERVICE_UNAVAILABLE` with `Retry-After` and
  nothing is written. That call is made while the PUT holds the vendor copy's row lock (it needs the stored flag to
  know whether anything changed), so it is bounded by the client's 2 s connect and 5 s read timeouts. Each changed
  field writes an `AP_VENDOR_SETTINGS_SET` row (`informationReturnReportable`, `informationReturnForm`,
  `informationReturnBox`, `informationReturnPayeeScheme`).
- **Taxpayer numbers.** pos-accounting has no field, column or parameter for one: an unknown key such as `tin`
  is 400 `VALIDATION_ERROR` naming the key, never its value (top level and inside both objects). The detail read's
  `apSettings.informationReturn` adds `payeeTinOnFile` (the copy holds a registration of the chosen scheme) and
  `payeeTinLast4`, relayed unchanged from the copy's `last4` when exactly one registration of the scheme exists,
  else null. `last4` and the hold reason are CONFIDENTIAL (ADR-0072): never in a log line, an error message, a
  metric tag or (for `last4`) an audit row; the request fingerprint carries the reason's SHA-256.
- **The tax country.** `accounting.tax.country` (`TaxCountry`, shipped `US`, a placeholder) names the ISO 3166-1
  alpha-2 country whose pos-tax configuration applies. Startup fails unless it is upper-case alpha-2 and, when the
  JDK maps the country to a currency, that currency is `accounting.ledger.base-currency`'s. Deployment-wide in
  Stage A; ADR-0067 A5's tenant replica replaces both. No code branches on its value.
- **The forms front door.** `GET /v1/accounting/information-return-forms` relays pos-tax's stub for the tax
  country, `{countryCode, source, forms: [{form, label, boxes: [{box, label}], payeeIdSchemes}]}` (ADR-0071,
  AW59). `TaxReferenceClient` calls pos-tax on `pos.accounting.tax.base-url` as `X-User: pos-accounting`,
  `X-Authorities: tax:rates:view`, with the tenant and `X-Correlation-Id` forwarded and bounded timeouts
  (`pos.accounting.tax.connect-timeout` 2 s, `read-timeout` 5 s). Any pos-tax 4xx or 5xx, an unreachable pos-tax
  or an unreadable answer is 503 `SERVICE_UNAVAILABLE` with `Retry-After`, never relayed: the country is the server's
  own setting, so a refusal is never the caller's fault (ADR-0017); a 4xx is logged at WARN with pos-tax's status
  and code only. The forms are placeholders held for expert advice (OI-4).

| Method | Path | Permission | Codes |
| --- | --- | --- | --- |
| PUT | `/v1/accounting/vendors/{vendorId}/ap-settings` `{…, apHold?, informationReturn?}` | `accounting:ap_approval_policy:manage` | adds 400 `JUSTIFICATION_REQUIRED` (`apHold.reason`), 400 `VALIDATION_ERROR` (`fieldErrors[apHold*]`, `fieldErrors[informationReturn.*]`, unknown keys), 503 `SERVICE_UNAVAILABLE` |
| GET | `/v1/accounting/information-return-forms` | `accounting:ap:view` | 200, 503 `SERVICE_UNAVAILABLE` |
| POST | `/v1/accounting/ap/payments` | `accounting:ap:pay` | adds 422 `VENDOR_ON_AP_HOLD` (slot 1e) |

### Purchase-tax rules by country (CAP:550 S43, #2604, AW44)

Two purchase-tax behaviours, each switched on per country by pos-tax's purchase-tax rules stub (`GET
/v1/tax/purchase-rules?countryCode=&asOf=`, `{configured, taxOnResaleGoods: HOLD|ALLOW, selfAssessUntaxedExpenses}`)
for the tax country `accounting.tax.country`. The shipped US rule (`HOLD`, `true`) is a placeholder held for expert
advice (OI-4); a country without rules answers `configured: false`, and neither behaviour applies. No code names a
country.

- **Which bills qualify** (bill level, never a credit note). The hold: stated tax above 0.00 and at least one
  `RECEIPT_MATCHED` or `GOODS` line with an AW39 prorated tax share above 0.00 (a header-only `GOODS` bill with tax
  qualifies). The accrual: no stated tax (null or 0.00); each `EXPENSE` line accrues, or the header net of a
  header-only `EXPENSE` bill. Goods lines never accrue.
- **The hold for tax on goods for resale.** Rule `HOLD`: approve and `ACCEPT` refuse a qualifying bill with 422
  `AP_BILL_TAX_ON_RESALE_GOODS` (the message names the bill and the tax), the last content check after
  `requireClassified`, audited `<operation>_REFUSED`. It goes through with the vendor's AP setting
  `acceptTaxOnResaleGoods` (override `VENDOR_SETTING`) or, for the one bill, `taxOnResaleOverrideJustification`
  (10-1000 characters after trimming, else 400 `JUSTIFICATION_REQUIRED` / `VALIDATION_ERROR`; looked at only when the
  hold applies, otherwise ignored and not stored; override `BILL`). `vendor_bill.tax_on_resale_override` (`BILL` |
  `VENDOR_SETTING`) and `tax_on_resale_override_justification` (V24, with a check constraint) record it; the bill read
  serves `taxOnResaleOverride {source, justification}` and the approval audit row adds `taxOnResaleOverride=<source>`
  (never the justification, which is CONFIDENTIAL, ADR-0072). The overridden bill posts as AW39 says: the tax into
  5050 for goods. Automatic approval honours the vendor setting and otherwise skips with `AP_BILL_TAX_ON_RESALE_GOODS`.
- **The self-assessed (use) tax accrual.** `selfAssessUntaxedExpenses = true`: the posting's first act asks pos-tax
  once per decision, `POST /v1/tax/calculate` with `calculationType = USE`, `committable = false`, the ledger currency
  (ADR-0067 PC-11 (a)), the AW42 posting date, the bill id and `destinationAddress {country: accounting.tax.country,
  accounting.tax.purchase-place.region-code, postal-code}` (`X-Authorities: tax:calculate`). The returned line taxes
  above 0.00 post as returned in the approval entry: Dr the expense key, Cr `USE_TAX_PAYABLE` (2240 Use Tax Payable,
  seeded in the generic chart), so accounts payable stays the gross; the audit row adds `useTaxAmount=…`. The void's
  reversal mirrors it. A missing `USE_TAX_PAYABLE` mapping is 422 `GL_MAPPING_NOT_CONFIGURED`. Automatic approval
  quotes once inside its pre-check, before the legs, and posts that same answer.
- **pos-tax refusing the quote for a configuration state** (#2604 ruling 4 as amended in comment 6076230360): a pos-tax
  422 `TAX_JURISDICTION_NOT_CONFIGURED`, `CURRENCY_NOT_SUPPORTED` or `TAX_CAPABILITY_UNSUPPORTED` on the `USE` quote is
  answered 422 with the same code (`TaxQuoteRefusedException`, ADR-0017 §2: a configuration to fix, retrying cannot).
  The message is accounting's own, naming the bill and the setting to check; pos-tax's message is never echoed or
  logged. Nothing is written (no posting, no `_REFUSED` row); automatic approval skips with that code.
- **pos-tax giving no answer** (unreachable, any other 4xx including an unlisted 422, a 501, a 5xx, an unreadable or
  empty answer, or a line tax finer than the ledger currency's minor unit), for the rules or the quote, is 503
  `SERVICE_UNAVAILABLE` with `Retry-After` and writes nothing (AW49: never read as "off"); automatic approval skips
  with `SERVICE_UNAVAILABLE`. The purchase-rules read never relays. Decisions never use a cache.
- **With input-tax recovery (S32d).** Recovery works on the tax a bill states; the accrual applies only to a bill that
  states none, so one bill never carries both, and the accrual's two legs are always equal. The hold is the last content
  check, after S32d's `AP_BILL_TAX_SPLIT_MISMATCH`.
- **Amounts as returned** (ADR-0067 PC-6): each line tax is taken at the ledger currency's exponent
  (`Currency#getDefaultFractionDigits`), never rounded; one that cannot be represented there is an unusable answer.
- **Rollout.** With the shipped settings (`accounting.tax.country: US`, pos-tax's `US {HOLD, true}`) every tenant of
  the deployment holds taxed goods-for-resale bills and accrues use tax on untaxed expense bills, at pos-tax's
  placeholder rates for the placeholder purchase place. **The 2240 balances accrued at these placeholder rates must
  not be filed or paid.** A US deployment whose pos-tax routes calculation to `EXTERNAL` or `AVALARA` cannot quote use
  tax, so every untaxed expense approval there answers 503 (422 `TAX_CAPABILITY_UNSUPPORTED` once #2629 lands) until
  the pos-tax `pos.tax.purchase-rules.US` row is removed. Removing that row is the off switch (`configured: false`).
- **The bill check `TAX_ON_RESALE_GOODS`.** FAIL `{taxAmount, currencyCode}` in review when the rule holds a qualifying
  bill and the vendor setting is off; PASS `{acceptedBy: VENDOR_SETTING}` with it on, or `{acceptedBy}` of an approved
  override; NOT_APPLICABLE otherwise, with `{rulesUnavailable: true}` when pos-tax gives no rules (the read still
  succeeds). Reads cache the rules per (country, date) for `accounting.tax.purchase-rules.cache-ttl` (default
  `PT5M`). `APPROVE` and `ACCEPT_EXCEPTION` stay allowed: the approver can override.
- **Settings.** `accounting.tax.purchase-place` (`region-code` optional, 1-3 letters or digits; `postal-code` required,
  at most 20 characters; env `ACCOUNTING_TAX_PURCHASE_REGION`, `ACCOUNTING_TAX_PURCHASE_POSTAL_CODE`) is a Stage A,
  deployment-wide placeholder until bills carry a location (ADR-0044 R1); startup fails naming the property.

| Method | Path | Permission | Change and codes |
| --- | --- | --- | --- |
| POST | `/v1/accounting/vendor-bills/{billId}/approve` `{…, taxOnResaleOverrideJustification?}` | unchanged | adds 422 `AP_BILL_TAX_ON_RESALE_GOODS`, the relayed 422 `TAX_JURISDICTION_NOT_CONFIGURED` / `CURRENCY_NOT_SUPPORTED` / `TAX_CAPABILITY_UNSUPPORTED`, 400 `JUSTIFICATION_REQUIRED` / `VALIDATION_ERROR` (`fieldErrors[taxOnResaleOverrideJustification]`, over 1000 characters) for the field, 503 `SERVICE_UNAVAILABLE` |
| POST | `/v1/accounting/vendor-bills/{billId}/resolve-exception` (`ACCEPT`) `{…, taxOnResaleOverrideJustification?}` | unchanged | as approve |
| GET | `/v1/accounting/vendor-bills/{billId}` (every bill read) | unchanged | check `TAX_ON_RESALE_GOODS`; `taxOnResaleOverride` |
| PUT | `/v1/accounting/vendors/{vendorId}/ap-settings` `{…, acceptTaxOnResaleGoods?}` | `accounting:ap_approval_policy:manage` | a boolean: absent unchanged, null 400 `VALIDATION_ERROR` (`fieldErrors[acceptTaxOnResaleGoods]`); in the fingerprint (409 `IDEMPOTENCY_CONFLICT`); each change one `AP_VENDOR_SETTINGS_SET` row |
| GET | `/v1/accounting/vendors/{vendorId}` | `accounting:ap:view` | `apSettings.acceptTaxOnResaleGoods` |

## Error codes

Every non-2xx response carries the platform `ApiError` envelope. Field semantics, payload examples,
and the platform-wide fallback codes emitted by `pos-web-common` and `pos-security-common` are in
[`durion/docs/architecture/api/ERROR_ENVELOPE.md`](../../durion/docs/architecture/api/ERROR_ENVELOPE.md).
The table below is this module's own codes; any endpoint here may additionally return a platform
fallback code. Add a row in the same pull request as the controller or advice that mints the code.

| Code | Status | Description |
|------|--------|-------------|
| `VALIDATION_ERROR` | 400 | Request-level validation failure this module raises itself: an invalid date range or request parameter, an unparseable bank statement, an invalid inbound event, a posting-rule publish that fails validation, an invalid bill allocation or vendor-bill operator action, or a constraint violation on a parameter |
| `ARGUMENT_NOT_VALID` | 400 | Bean-validation rejection of a request body (`MethodArgumentNotValidException`) |
| `UNSUPPORTED_SORT_PROPERTY` | 400 | A `sort` parameter names a property the endpoint does not sort on |
| `NO_MATCHING_VENDOR_BILL` | 400 | An inbound vendor invoice matched no pending receipt/bill for the vendor (a failed match, not a missing addressed resource) |
| `JUSTIFICATION_REQUIRED` | 400 | A vendor-bill justification, reason or override justification absent or under 10 characters (#2509) |
| `VENDOR_REPLICATION_PENDING` | 503 | A vendor read or command, a goods-receipt bill or an AP payment names a vendor not in the pos-supplier vendor copy yet; `Retry-After` set. Not-yet, never "no" (ADR-0017 §1; S24, #2517) |
| `VENDOR_REMIT_TO_SELF_CONFIRMATION` | 403 | The caller requested the vendor's current remit-to in pos-supplier and may not confirm it (S24, Accounting ruling on PR #2648) |
| `VENDOR_INACTIVE` | 422 | A new goods-receipt bill or AP payment names an `INACTIVE` vendor; an inactive vendor's existing bills are not paid either (S24, #2517) |
| `VENDOR_ON_AP_HOLD` | 422 | An AP payment names a vendor on AP hold (slot 1e); the message names the vendor number only, the reason is on the vendor read (#2615, ADR-0072) |
| `SERVICE_UNAVAILABLE` | 503 | pos-tax cannot answer the information-return forms read, check an information-return change, or give a vendor-bill decision its purchase-tax rules or use-tax quote; `Retry-After` set, nothing written (#2615, S43) |
| `VENDOR_PAYMENT_DETAILS_CHANGED` | 409 | A bill the payment would pay was approved at another remit-to version and no one but the payer confirmed the current one; or a confirmation names a version that is not the current one (S24, #2517) |
| `VENDOR_BILL_NOT_FOUND` | 404 | No vendor bill with that id is visible to the caller (#2509) |
| `AP_MATCH_CANDIDATE_NOT_FOUND` | 404 | No match candidate with that id is visible to the caller (#2509) |
| `AP_BILL_NOT_APPROVABLE` | 409 | The bill's own status does not allow the decision (the message names it), a replayed command, an open ambiguous match naming the bill (pick the match first), or a `CURRENCY_HOLD` bill (#2509) |
| `AP_BILL_NOT_VOIDABLE` | 409 | Void of a bill that is neither `APPROVED` nor a goods-receipt bill in `PENDING_RECEIPT_MATCH`, or has an allocation; correct it with a vendor credit note (AW42, AW45, #2509) |
| `AP_BILL_AWAITING_INVOICE` | 409 | A goods-receipt bill no vendor invoice has been matched to is sent, approved or accepted, or a candidate that kept no invoice is selected; match the invoice, select a candidate, or void the bill (AW45, #2509) |
| `AP_BILL_ENTRY_NOT_REVERSIBLE` | 409 | A vendor bill's entry, or its void's reversal, reversed through the journal-entry endpoint; void the bill instead (AW42, #2509) |
| `AP_MATCH_CANDIDATE_ALREADY_RESOLVED` | 409 | Someone else already resolved the ambiguous match (#2509) |
| `AP_BILL_UNCLASSIFIED` | 422 | The bill (or a non-stock line) has no class and its vendor no default; the approval needs a `classification` (AW39, #2509) |
| `AP_BILL_TAX_ON_RESALE_GOODS` | 422 | The bill charges tax on goods for resale, the tax country's purchase-tax rule holds such bills, its vendor does not accept the tax and no `taxOnResaleOverrideJustification` was given (approve, `ACCEPT`); audited `_REFUSED` (AW44, S43) |
| `TAX_JURISDICTION_NOT_CONFIGURED`, `CURRENCY_NOT_SUPPORTED`, `TAX_CAPABILITY_UNSUPPORTED` | 422 | Relayed from pos-tax for a vendor bill's use-tax quote (approve, `ACCEPT`): a configuration state of the tax country, purchase place, ledger currency or provider; accounting's own message names the bill and the setting; nothing written (S43, #2604 ruling 4 amended) |
| `AP_BILL_TOTALS_UNRECONCILED` | 422 | The vendor's gross differs from its net + tax beyond the rounding tolerance and the send, approval or acceptance gives no `difference`; nothing is written (AW47, #2509) |
| `AP_BILL_ZERO_TOTAL` | 422 | A bill totalling 0.00 is sent, approved or accepted; correct it or void it (#2509) |
| `AP_APPROVAL_LIMIT_EXCEEDED` | 403 | The bill's absolute total is over the clerk limit and the caller lacks `accounting:ap:approve_over_limit` (approve, `ACCEPT`, void of an approved bill); `nextAction` names the permission (#2510) |
| `AP_BILL_SELF_APPROVAL` | 403 | The caller created the bill, or created its vendor and no bill of that vendor was ever approved (`VENDOR_CREATOR_FIRST_BILL` in the message, S24), and the policy does not allow a creator to approve it (approve, `ACCEPT`) (#2510) |
| `AP_PAYMENT_SELF_APPROVED_BILL` | 403 | The payer approved a bill the payment would pay; `fieldErrors[selfApprovedBillNumbers]` name them; nothing is paid (#2510) |
| `AP_PAYMENT_METHOD_NOT_SUPPORTED` | 422 | An AP payment by `CREDIT_CARD` or `OTHER`: no funding account is modelled for them (OI-17); refused first, before the gateway (#2603) |
| `AP_PAYMENT_NOT_RETRYABLE` | 409 | `gl-posting-retry` of an AP payment that is not `GL_POST_FAILED` (already posted, pending, or a gateway state) (#2603) |
| `LOCK_TIMEOUT` | 409 | A row lock waited beyond `accounting.ap.lock-timeout` on the AP pay command, its retry or a vendor-bill decision: another request is working on these bills (a neutral message on other paths); the transaction rolled back, retry (#2627) |
| `UNAUTHENTICATED` | 401 | No usable authentication on the request |
| `FORBIDDEN` | 403 | Caller lacks the required permission |
| `AUTHORIZATION_DENIED` | 403 | Audit-trail event creation refused because the caller may not record that event |
| `RECONCILIATION_SELF_APPROVAL` | 403 | The approver submitted the reconciliation and the tenant's `BANK_REC_ALLOW_SELF_APPROVAL` is not true; the refusal is audited (#2304) |
| `RECONCILIATION_ADJUSTMENT_APPROVAL_REQUIRED` | 403 | An OTHER reconciliation adjustment above `BANK_REC_OTHER_APPROVAL_THRESHOLD` (or any non-residual OTHER while it is unset) without `accounting:reconciliation:approve` (#2303) |
| `PERIOD_CLOSE_EXCEPTION_NOT_PERMITTED` | 403 | A bank reconciliation close exception from a caller without both `accounting:period:close` and `accounting:period:override`; the period stays OPEN (#2305) |
| `NOT_FOUND` | 404 | A JPA entity the request addresses does not exist (`EntityNotFoundException`) |
| `JOURNAL_ENTRY_NOT_FOUND` | 404 | Referenced journal entry does not exist |
| `DEFAULT_GL_MAPPING_NOT_FOUND` | 404 | Referenced default GL mapping does not exist |
| `POSTING_RULE_SET_NOT_FOUND` | 404 | Referenced posting rule set does not exist |
| `GL_ACCOUNT_NOT_FOUND` | 404 | Referenced GL account does not exist (a bank opening balance's path account included, #2572) |
| `PERIOD_NOT_FOUND` | 404 | Referenced accounting period does not exist |
| `TAX_SNAPSHOT_NOT_FOUND` | 404 | Referenced tax snapshot does not exist |
| `SETTLEMENT_LINE_NOT_FOUND` | 404 | Referenced settlement line does not exist |
| `RECEIVABLE_PAYMENT_NOT_FOUND` | 404 | Referenced receivable payment does not exist |
| `PAYMENT_NOT_FOUND` | 404 | The payment named by a remainder-credit command does not exist (#2524) |
| `RECONCILIATION_NOT_FOUND` | 404 | Referenced bank reconciliation does not exist |
| `EVENT_NOT_FOUND` | 404 | Referenced AP payment event does not exist |
| `EXPORT_JOB_NOT_FOUND` | 404 | Referenced report export job does not exist |
| `DUPLICATE_EVENT` | 409 | Event with this ID has already been processed |
| `EVENT_NOT_RETRYABLE` | 409 | Retry of an accounting event that is not `FAILED`; the event is left unchanged (#2411) |
| `IDEMPOTENCY_CONFLICT` | 409 | An AP payment idempotency key was reused with a different payload; a remainder-credit `requestId` reused on another payment (#2524) |
| `PAYMENT_NOT_AVAILABLE` | 409 | Remainder credit on a payment that is not `AVAILABLE` (already fully applied or credited); nothing is written (#2524) |
| `PAYMENT_REMAINDER_CHANGED` | 422 | The remainder-credit `expectedAmount` no longer matches the payment's unapplied amount (an application intervened); nothing is written, re-read the payment (#2524) |
| `CASH_CUSTOMER_CREDIT_NOT_ALLOWED` | 422 | A person's application with an overpayment, or a remainder credit, on a payment of the CASH walk-in house account: walk-in overpayments are refunded, never kept as credit; nothing is written (#2508) |
| `GL_POSTING_FAILED` | 409 | General ledger posting failed |
| `DUPLICATE_ACCOUNT_CODE` | 409 | Chart of accounts code already exists |
| `AP_BILL_DUPLICATE` | 409 | A live vendor bill (any status except `VOIDED` or `REJECTED`) already has the same vendor, normalised bill number and bill date; `referenceId` is that bill's `vendorBillId` and `nextAction` is `Open the existing bill.` Raised by vendor-bill create and match (#2501) |
| `ACCOUNT_NOT_ZERO_BALANCE` | 409 | GL account cannot be deactivated because its posted balance is not zero |
| `ACCOUNT_NOT_INACTIVE` | 409 | GL account cannot be archived because it is not currently INACTIVE |
| `ENTRY_ALREADY_POSTED` | 409 | Posting a journal entry that is already POSTED or REVERSED |
| `JE_ALREADY_REVERSED` | 409 | Reversing a journal entry that is already REVERSED |
| `JE_NOT_POSTED` | 409 | Reversing a journal entry that was never POSTED |
| `PERIOD_ALREADY_CLOSED` | 409 | Closing an accounting period that is already closed |
| `PERIOD_ALREADY_OPEN` | 409 | Reopening an accounting period that is already open |
| `TAX_SNAPSHOT_PERIOD_NOT_CLOSED` | 409 | A tax snapshot was requested for a period that is still open |
| `TAX_SNAPSHOT_ALREADY_EXISTS` | 409 | A tax snapshot already exists for the period |
| `SETTLEMENT_LINE_NOT_UNMATCHED` | 409 | The settlement line is no longer in the UNMATCHED state the operation needs |
| `SETTLEMENT_NOT_POSTED` | 409 | The operation needs a POSTED settlement |
| `RECONCILIATION_ALREADY_FINALIZED` | 409 | The bank reconciliation is finalized and no longer editable |
| `RECONCILIATION_LINE_INELIGIBLE` | 409 | A bank transaction or ledger line is not in a matchable state (already matched, excluded, pending, in an OPEN outstanding item, or a ledger line dated after the window) |
| `RECONCILIATION_WINDOW_ALREADY_RECONCILED` | 409 | The statement already has an IN_PROGRESS or SUBMITTED reconciliation, or a FINALIZED / INVALIDATED one without a successor (supersede it instead); supersede of an IN_PROGRESS / SUBMITTED one; a statement supersession over a statement with an IN_PROGRESS or SUBMITTED reconciliation. `fieldErrors[reconciliationId]` names it (#2303, #2304) |
| `RECONCILIATION_NOT_EDITABLE` | 409 | A preparer's change on a SUBMITTED (return it first), INVALIDATED, SUPERSEDED or CANCELLED reconciliation; supersede of a superseded or cancelled one, or of one whose statement was superseded (#2304) |
| `RECONCILIATION_NOT_SUBMITTED` | 409 | Approve or return of a reconciliation that is not SUBMITTED (#2304) |
| `MATCH_STATE_INVALID` | 409 | Accept / reject of a match that is not PROPOSED, or unmatch of one that is not ACCEPTED (#2303) |
| `ADJUSTMENT_ALREADY_REVERSED` | 409 | Reversing a reconciliation adjustment twice (#2303) |
| `ADJUSTMENT_BRIDGE_ALREADY_POSTED` | 409 | A POSTED gap bridge already exists for the statement (#2303) |
| `OPTIMISTIC_LOCK` | 409 | The record was changed by another request since it was read (stale `@Version`); reload and retry |
| `CONFLICT` | 409 | An `IllegalStateException` reporting an item that is `already PROCESSED` |
| `ILLEGAL_STATE` | 409 | Any other `IllegalStateException` raised by this module's services |
| `UNBALANCED_ENTRY` | 422 | Journal entry debits and credits do not balance (or has no lines) |
| `GL_ACCOUNT_NOT_ACTIVE` | 422 | GL account is not active on the transaction date, or was never activated |
| `GL_MAPPING_NOT_CONFIGURED` | 422 | No GL mapping (posting category/key/effective date) is configured for the request; an AP payment names the missing `AP_PAYMENT/<key>` as `referenceId` (#2601, #2603) |
| `PERIOD_CLOSED` | 422 | The transaction date falls in a closed accounting period |
| `PERIOD_HARD_LOCKED` | 422 | The transaction date falls in a hard-locked accounting period |
| `HARD_LOCK_DATE_REGRESSION` | 422 | The requested hard-lock date is earlier than the current one |
| `ACCOUNTING_TIME_ZONE_UNSET` | 422 | The tenant has no accounting time zone, so nothing can be dated or posted (#2558) |
| `INVALID_ACCOUNTING_TIME_ZONE` | 400 | The requested accounting time zone is not an IANA region id (#2558) |
| `ACCOUNTING_TIME_ZONE_LOCKED` | 409 | The tenant closed a period or set a hard-lock date, so its accounting time zone can no longer change (#2558) |
| `PERIOD_HAS_DRAFT_ENTRIES` | 422 | The period cannot close while DRAFT journal entries remain; `fieldErrors` lists each `draftJournalEntryIds` value |
| `PERIOD_BANK_RECONCILIATION_INCOMPLETE` | 422 | The bank reconciliation close policy refuses the close: one `fieldErrors[unreconciledGlAccountIds]` entry per blocked account, message `<glAccountId> <accountCode>: <check codes>`; under `REQUIRED` an exception body adds `fieldErrors[bankReconciliationException]` (#2305) |
| `UNBALANCED_RULES` | 422 | A posting-rule publish violates the split-group/`factorPercent` invariants; `fieldErrors` locates each offending group or line |
| `WRITE_OFF_THRESHOLD_EXCEEDED` | 422 | A settlement write-off exceeds the configured threshold |
| `WHOLE_REQUEST_REVERSAL_REQUIRED` | 422 | A payment application that was applied as one request must be reversed as one request |
| `ACCOUNT_NOT_RECONCILABLE` | 422 | The GL account is not flagged as reconcilable |
| `CURRENCY_NOT_SUPPORTED` | 422 | A document in a currency the ledger does not book (ADR-0067 PC-9): a payment applied to invoices in another currency, refused before anything is written (#2334); an AP payment in another currency, refused before the gateway (#2603); a bank account, statement or import in another currency; a bank opening balance whose `currencyCode` is not the account's currency (#2572) |
| `MATCH_AMOUNT_MISMATCH` | 422 | The matched statement and ledger amounts differ |
| `RECONCILIATION_ADJUSTMENT_SIGN_INVALID` | 422 | A reconciliation adjustment carries the wrong sign for its type |
| `RECONCILIATION_NOT_BALANCED` | 422 | Submit or approve while the live difference is beyond ±0.01; `fieldErrors` carries the `difference` |
| `RECONCILIATION_HAS_UNEXPLAINED_ITEMS` | 422 | Submit or approve while balanced but with unexplained bank transactions or ledger lines from the baseline; `fieldErrors` carries `countUnexplainedBank`, `countUnexplainedLedger` and the first 50 `unexplainedBankTransactionIds[n]` / `unexplainedGlLineIds[n]` (#2304) |
| `STATEMENT_SUPERSESSION_NOT_ELIGIBLE` | 422 | `supersedesStatementId` names a statement unknown in the tenant, of another account, or not COMMITTED (#2304) |
| `MATCH_CARDINALITY_NOT_ALLOWED` | 422 | A match with more than one member on both sides (N:M) (#2303) |
| `MATCH_REQUIRES_REVIEW` | 422 | A non-1:1 match, tolerance use, out-of-window dates or a former possible duplicate without a justification; `fieldErrors[justification]` lists the reasons (#2303) |
| `OUTSTANDING_ITEM_NOT_ELIGIBLE` | 422 | The line, sign, window or state does not allow the outstanding item, reaffirmation, release or clear-in-gap (#2303) |
| `ADJUSTMENT_LINK_REQUIRED` | 422 | An OTHER without exactly one link, a residual/bridge link on another type, or a TRANSFER counter missing or misplaced (#2303) |
| `ADJUSTMENT_LINK_NOT_ELIGIBLE` | 422 | The named match, statement, amount or TRANSFER counter fails its rule; an adjustment linked to a bank transaction must equal its amount exactly, else `fieldErrors[amount]` (#2303) |
| `AMOUNT_PRECISION_EXCEEDS_CURRENCY` | 422 | An amount has more decimal places than its currency's ISO 4217 minor unit allows (e.g. `10.005` in USD; trailing zeros do not count). Refused, never rounded; `fieldErrors` names each amount — `otherApprovalThreshold` on the policy PUT, `amount` on a reconciliation adjustment, `openingBalance` / `closingBalance` / `transactions[n].signedAmount` on a manual statement or import commit, `statement.*Balance` / `splitAt[n].closingBalance` on an import upload or mapping change, `correctedValues.signedAmount` on an import row correction (ADR-0067 PC-6, #2305). An over-precise file row is not refused: upload and mapping change stage it `REJECTED` with `rejectionCode` `AMOUNT_PRECISION_EXCEEDS_CURRENCY` so the preparer can correct it before commit (#2336); a bank opening balance names every offending amount in `fieldErrors` (#2572) |
| `BANK_OPENING_BALANCE_ACCOUNT_NOT_ELIGIBLE` | 422 | The bank opening balance names an account that is not an active `BANK_CASH` account in functional currency (#2572) |
| `BANK_OPENING_BALANCE_ALREADY_ESTABLISHED` | 409 | The bank account already has a standing opening balance; reverse its entry and run the command again (#2572) |
| `BANK_OPENING_BALANCE_NOT_FIRST` | 422 | The bank account has a standing posted line dated on or before `asOfDate`, or a committed statement starting on or before it (#2572) |
| `BANK_OPENING_BALANCE_EMPTY` | 422 | A bank opening balance of zero with no outstanding items (#2572) |
| `BANK_ACCOUNT_FEED_NOT_LINKED` | 422 | A statementless (feed-backed) reconciliation on an account without a feed link — every account in phase 1 (#2303) |
| `PAYMENT_GATEWAY_FAILURE` | 500 | The AP payment gateway call failed |
| `INTERNAL_ERROR` | 500 | Audit-trail event creation failed unexpectedly |
| `REQUEST_FAILED` | varies | A `ResponseStatusException` raised by a service (payment application, credit memos, report exports, mapping keys): the status is the exception's own and the message is its reason |

## Configuration

| Property                                            | Default              | Description                              |
| --------------------------------------------------- | -------------------- | ---------------------------------------- |
| `pos.accounting.credit-memo.revenue-account-id`     | required             | GL account for revenue reversals         |
| `pos.accounting.credit-memo.tax-payable-account-id` | required             | GL account for tax payable reversals     |
| `pos.accounting.credit-memo.ar-account-id`          | required             | GL account for AR reductions             |
| `pos.accounting.kafka.inventory-events-topic`       | `inventory.events.v1` | Inventory scrap and adjustment facts for shrinkage / adjustment GL posting (#1043, #2191) |
| `pos.accounting.kafka.accounting-events-topic`      | `accounting.events.v1` | Accounting's own fact feed (`accounting.invoice.gl-posted`), drained from `kafka_event_outbox` (#1843) |
| `pos.accounting.kafka.accounting-commands-topic`    | `accounting.commands.v1` | Drift repair for accounting's own facts: `accounting.outbox.replay-requested` re-queues the requesting tenant's facts of a window (`AccountingCommandListener`; CAP:550 S16, #2512) |
| `pos.accounting.kafka.order-manifest-topic` / `order-commands-topic` | `order.manifest.v1` / `order.commands.v1` | Reconciliation of the order feed: `OrderManifestListener` compares each per-tenant window with the `order` rows of `processed_events` and sends `order.outbox.replay-requested` on drift (ADR-0044 §4, #2579) |
| `pos.accounting.manifest.topic`                     | `accounting.manifest.v1` | One reconciliation manifest per tenant per closed window of `accounting.events.v1` (`ManifestPublisher`, ADR-0044 §4; first consumer: pos-order's copies of the float and petty-expense category facts, #2512) |
| `pos.accounting.manifest.window` / `.grace`         | `PT1H` / `PT5M`      | Manifest window length, and how long after a window closes its manifest is published |
| `pos.accounting.outbox.poll-interval-ms`            | `1000`               | Kafka outbox drain interval (#1843) |
| `pos.accounting.outbox.send-timeout-ms`             | `10000`              | Broker ack timeout per outbox row (#1843) |
| `stripe.api-key`                                    | required             | Stripe API key for payment processing    |
| `payment.gateway.connect-timeout` / `payment.gateway.read-timeout` | `5s` / `20s` | Connect and read timeouts of every gateway call, on its Stripe `RequestOptions` (`PAYMENT_GATEWAY_CONNECT_TIMEOUT`, `PAYMENT_GATEWAY_READ_TIMEOUT`; #2627) |
| `accounting.ap.lock-timeout`                        | `5s`                 | `SET LOCAL lock_timeout` on the AP pay command, its retry and the vendor-bill decisions; a longer wait is 409 `LOCK_TIMEOUT` (`ACCOUNTING_AP_LOCK_TIMEOUT`; #2627) |
| `pos.accounting.kafka.tenant-events-topic`          | `tenant.events.v1`   | Tenant lifecycle facts; `tenant.created` provisions the new tenant from the accounting template (#2526) |
| `pos.accounting.kafka.tenant-events-consumer-group` | `pos-accounting-tenant-events` | Consumer group of the `tenant.created` listener; reads from the earliest offset (#2526) |
| `pos.accounting.tenant-template.startup-sweep.enabled` | `true`            | Apply the accounting template to every registry tenant at each start (#2526). The default override-policy thresholds and refund policy reach a tenant only through provisioning (this sweep, `tenant.created`, or an add-on choice), no longer from a startup runner of their own |
| `pos.accounting.bankrec.match.date-window-days`     | `7`                  | Bank reconciliation candidate date window W (#2303) |
| `pos.accounting.bankrec.duplicate.date-window-days` | `3`                  | Near-duplicate candidate window (#2303) |
| `pos.accounting.bankrec.outstanding.aging-warning-days` | `90`             | Age past which an outstanding item needs a justification and an OTHER_LEDGER_TIMING item a reaffirmation (#2303) |
| `pos.accounting.bankrec.clearing.aging-warning-days` | `90`               | A clearing account (a counter account of POSTED `OTHER` adjustments, e.g. 2360) away from zero both at the period end and this many days before it raises the `CLEARING_BALANCE_AGING` readiness warning (#2305) |

## Multitenancy (ADR-0062, WS3 wave 2)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`, every scoped entity
extends `TenantScopedEntity`, and the three global tables (`event_outbox`, `kafka_event_outbox`,
`processed_events`, listed in `src/main/resources/db/tenancy-global-tables.txt`) carry `@TenantGlobal`. The
request tenant is bound by `TenantContextFilter` from `X-Tenant-Id` (the gateway injects it from the token's
`tid`), the Kafka tenant by `TenantRecordInterceptor` from the `tenantId` record header on every one of the
module's consumers, and every connection checkout binds `app.current_tenant` for row-level security.
`pos.tenancy.default-tenant-id` still binds the alpha default tenant on every unbound path (tokens issued
before `tid`, records without the header).

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, `FlywayConfig`).

Both outboxes are global tables whose rows carry the producing tenant as data (`tenant_id`, stamped from the
bound tenant by `OutboxEventWriter` and `OutboxServiceImpl`):

| Job | Classification | Why |
| --- | --- | --- |
| `OutboxPublisher.publishPending` | platform-scoped | Drains `kafka_event_outbox`; each row's `tenant_id` becomes the record header |
| `OutboxProcessor.processPendingEvents` | platform-scoped | Polls `event_outbox` and binds each row's `tenant_id` before dispatching its Spring event, so the GL-posting handlers write that tenant's journal entries |
| `OutboxProcessor.cleanupOldEvents` | platform-scoped | Deletes published `event_outbox` rows across tenants |
| `WorkorderEventsListener.reapExpiredRequests` | per-tenant | `invoice_regeneration_request` is scoped; one pass per tenant of the registry |
| `AccountingTemplateStartupSweep` (startup) | per-tenant | Applies the accounting template and the default override-policy thresholds and refund policy to each tenant of the registry, each in a transaction opened inside the binding (#2526) |

Per-tenant passes iterate the static registry (`TenantIterator.forEachActiveTenant`; the default tenant until
the `ext_tenant` replica lands per module). A tenant created after startup is provisioned by its
`tenant.created` fact (`TenantEventsListener`, #2526). The two native queries carry `@TenantAudited`:
`VendorBillRepository.getNextBillNumberSequence` reads a platform-wide sequence, not a table, and
`AccountingSequenceRepository.findMissingEntryNumbers` reads two scoped tables that row-level security binds to
the calling tenant.

Proof: `TenantIsolationIT` (tenant A's `override_policy_threshold` row is invisible to tenant B and to an
unbound connection, through the repository and through raw SQL) and `TenancySchemaConformanceIT` (every
non-whitelisted table has `tenant_id`, RLS enabled and forced, and the `tenant_isolation` policy; the pool is
`pos_app` with no bypass), both on Testcontainers Postgres (`./mvnw -pl pos-accounting verify`).

## Inventory Posting Facts (issues #1043, #2191, #2193, #2602)

`InventoryEventsListener` dispatches `inventory.events.v1` on `eventType`; every other type on the topic is
ignored without recording its eventId.

| Fact | Posting category | Entry |
| --- | --- | --- |
| `inventory.scrap.posted` (`ScrapPostedV1`) | `INVENTORY_SHRINKAGE`: `SHRINKAGE_EXPENSE` → 5100, `INVENTORY_ASSET` → 1300 | Dr 5100 / Cr 1300 for `quantity × unitCost` |
| `inventory.adjustment.posted` (`InventoryAdjustedV1`, `adjustmentKind` `CYCLE_COUNT` or `MANUAL_ADJUSTMENT`) | `INVENTORY_ADJUSTMENT`: `ADJUSTMENT_LOSS` → 5100, `ADJUSTMENT_GAIN` → 5100, `INVENTORY_ASSET` → 1300 | loss (`quantityDelta < 0`): Dr `ADJUSTMENT_LOSS` / Cr `INVENTORY_ASSET`; gain: Dr `INVENTORY_ASSET` / Cr `ADJUSTMENT_GAIN`, for `abs(quantityDelta) × unitCost` |
| `goodsreceipt.recorded` (`GoodsReceiptRecordedV1`, a delivery received into stock; CAP:550 S41, #2602) | `GOODS_RECEIPT`: `INVENTORY_ASSET` → 1300, `GOODS_RECEIVED_NOT_BILLED` → 2100, `PURCHASE_PRICE_DIFFERENCE` → 5050 | one entry per receipt, per costed line: Dr `INVENTORY_ASSET` at `inventoryValueMinor` / Cr `GOODS_RECEIVED_NOT_BILLED` at `accruedAmountMinor` / Dr or Cr `PURCHASE_PRICE_DIFFERENCE` for `accrued − value` (the `STANDARD` variance, pack-price rounding); an unpriced line (accrual 0, value > 0) credits 5050 at its value on its own journal line, described as unpriced. Every line description carries the receipt line, PO line or sku, `costSource` and `ledgerEntryId` |
| `inventory.product-value.changed` (`ProductValueChangedV1`, manual cost revaluation) | `INVENTORY_REVALUATION`: `INVENTORY_ASSET` → 1300, `REVALUATION_OFFSET` → 5000 (#2186 D7, final) | write-up (`totalValueDelta > 0`): Dr `INVENTORY_ASSET` / Cr `REVALUATION_OFFSET`; write-down: Dr `REVALUATION_OFFSET` / Cr `INVENTORY_ASSET`, for `abs(totalValueDelta)` as delivered — inventory has already multiplied the cost delta by on-hand, accounting never recomputes it |

- **Accounts** resolve through the mapping keys (seeded in `R__seed_reference_accounting.sql`), never hardcoded.
  A gain credits 5100 so count over/short nets in one account (#2186 D2); scrap and count corrections are
  separate categories so finance can remap either (D4). `reasonCode` rides into the entry description only.
- **Date** — the fact's `occurredAt` (business time); the period gate applies.
- **Goods receipts (AW38)** — the currency is checked first: no `currencyCode`, or one other than the ledger's,
  is never booked at par and is held `SUSPENDED / CURRENCY_NOT_SUPPORTED` (ADR-0067 PC-9). Then the line rules,
  over lines with a quantity: lines with zero quantity and no amount are ignored (the sum check still counts
  them); a zero-quantity line that carries an amount is malformed. Accounting never fills in a value Inventory did
  not state. A line without `receiptLineId`, a null `inventoryValueMinor` beside a non-zero accrual, an amount on a
  zero-quantity line, a negative quantity (a receipt only adds stock; returns and corrections arrive as
  `vendorreturn.recorded`), or line accruals that do not sum to `totalAccruedAmountMinor` hold the whole fact
  `SUSPENDED / VALIDATION_ERROR` (excluded from auto-retry, recorded once per receipt, never a partial posting). A line with no value and no
  accrual is uncosted and contributes nothing; when every line is, the fact is `SKIPPED / UNCOSTED_FACT`.
  Nothing accrued and nothing valued is `PROCESSED` with no entry. Amounts convert from minor units by the
  currency's exponent only, never rounded (PC-5 (a)). `costSource` and `ledgerEntryId` are description only:
  never branched on, a missing `ledgerEntryId` never holds a posting. The ingestion record keeps the fact as its
  `payload`, so each posted line (`receiptLineId`, `poLineId`, quantity, accrual, value) stays readable for bill
  matching and 2100 reconciliation. A vendor bill never debits 1300; its approval clears 2100 (Vendor Bill
  Approval above); a bill decision never reverses a receipt.
- **Idempotency** — envelope `eventId` in `processed_events`, checked before any transaction; posting key
  `INVENTORY_SHRINKAGE_GL_POSTING:<scrapId>` / `INVENTORY_ADJUSTMENT_GL_POSTING:<kind>:<adjustmentId>` /
  `INVENTORY_REVALUATION_GL_POSTING:<revaluationId>` / `GOODS_RECEIPT_ACCRUAL:<receiptId>`; journal entry
  `sourceEventId = nameUUIDFromBytes("INVENTORY_SHRINKAGE:" + scrapId)` /
  `nameUUIDFromBytes("INVENTORY_ADJUSTMENT:" + kind + ":" + adjustmentId)` /
  `nameUUIDFromBytes("INVENTORY_REVALUATION:" + revaluationId)` /
  `nameUUIDFromBytes("GOODS_RECEIPT_ACCRUAL:" + receiptId)` (source type `GOODS_RECEIPT_ACCRUAL`). A fact whose posting key has expired is still
  recognised as posted by its `sourceEventId`.
- **Transaction shape** (ADR-0044 as amended by #2146; `OrderEventsListener` has the same shape) — the listener
  method is not transactional. The posting, posting key, ingestion record and processed mark commit together
  in a `REQUIRES_NEW` transaction. A malformed payload (`replica.payload.rejected`) and an uncosted fact are each
  marked in a transaction of their own. `PERIOD_CLOSED`, `PERIOD_HARD_LOCKED`, `MAPPING_NOT_FOUND`, transient
  and unexpected failures propagate unmarked for container retry and then `inventory.events.v1.dlq`; replay
  after the operations fix.
- **Uncosted facts** (`unitCost` null or ≤ 0, ADR-0048 `costSource=NONE`) are never posted. The skip is
  terminal: the fact carries the cost at posting time and a later cost is a different fact. A revaluation has
  no uncosted case (`totalValueDelta` is always computed); a zero delta simply posts no journal entry and is
  recorded `PROCESSED`, not `SKIPPED`.
- **Metrics** — `accounting.inventory.fact.posted{eventType}` (a journal entry was posted) and
  `accounting.inventory.fact.skipped{eventType, reason=UNCOSTED}` (scrap, adjustment and goods receipt), and
  `accounting.inventory.fact.held{eventType, reason=CURRENCY|VALIDATION}` (a goods receipt held, not posted).
- **Ingestion records** (AD-007, #2186 D5) — each consumed fact writes one `AccountingEvent` row, terminal except a currency hold (below):
  `eventType` = the fact type, `sourceSystem = pos-inventory`, `domainKeyId` = `adjustmentId` / `scrapId` /
  `revaluationId`, `ingestionId` = envelope `eventId`, `transactionDate` = business date, `payload` = the fact,
  and a display `eventReference` (`AE-YYYYMM-n`). A posted fact is `PROCESSED` with `journalEntryId` and
  `idempotencyOutcome = NEW`; a re-emitted fact is `PROCESSED`, `DUPLICATE_IGNORED`, linked to the original
  entry; an uncosted scrap or adjustment fact is `SKIPPED` with `failureReasonCode = UNCOSTED_FACT`. Look one up
  with `GET /v1/accounting/events?eventType=inventory.adjustment.posted&domainKeyId=<adjustmentId>` (or
  `eventType=inventory.product-value.changed&domainKeyId=<revaluationId>`).
  **Kafka facts are not REST-retryable**: as recorded by their listener they never end `FAILED` or `SUSPENDED`,
  which are the only statuses
  the retry scheduler and `retryAccountingEvent` select; a failed fact is replayed from the DLQ instead. The
  exceptions are a fact held for its currency (see Ledger currency above): `SUSPENDED / CURRENCY_NOT_SUPPORTED`,
  skipped by the retry scheduler and released only through the audited reprocess; a malformed goods receipt,
  `SUSPENDED / VALIDATION_ERROR`, also skipped by the retry scheduler (its payload never changes); and a settled
  payment's automatic-application holds (#2503). A manual reprocess (`POST /v1/accounting/events/{id}/reprocess`)
  of a held goods receipt never reaches the posting engine: `GoodsReceiptReprocessor` re-runs the receipt's own
  assessment on the stored payload, currency first (a missing currency stays held as `CURRENCY_NOT_SUPPORTED`).
  A fact still invalid keeps its hold and reason. One that now passes posts under
  `GOODS_RECEIPT_ACCRUAL:<receiptId>` (`PROCESSED / NEW`), or closes `PROCESSED / DUPLICATE_IGNORED` when that key
  already posted. A refusal is labelled as the engine labels one (`SUSPENDED / PERIOD_CLOSED`,
  `ACCOUNTING_TIME_ZONE_UNSET`, or `UNMAPPED_EVENT_TYPE` for a missing `GOODS_RECEIPT` mapping; a hard-locked
  period is worded as permanently blocked), and every attempt writes its history row. A held goods receipt can end
  `FAILED` in one case: when an attempt by the retry job throws something unexpected, its `recordFailure` leaves the
  row `FAILED / INTERNAL_ERROR`, until the job's next attempt routes it back through the reprocessor. Two
  reprocesses of one receipt at once answer the loser with the engine's "Concurrent reprocessing detected" conflict.
- **Event envelope contract** (`GET /v1/accounting/events/contract`, issue #2207) — `version`/`fields`/`examples`
  describe the submission envelope as before; four additive optional sections document the rest of the
  ingestion surface, each sourced from the real rules rather than a hand-typed list that could drift:
  `identifierStrategy` (UUIDv7 everywhere; `eventId` is server-minted unless supplied; `domainKeyId` is an
  opaque upstream string), `traceabilityIds` (`traceparent`, `X-Correlation-Id`, `eventId`, `eventReference`,
  `ingestionId`, `journalEntryId`, `domainKeyId`, `invoiceId`, each with where it is carried),
  `processingStatuses` (every `AccountingEventStatus` constant with its meaning, derived from the enum, plus
  the two lifecycles above), and `idempotencyOutcomes` (`restSubmission` — content-hash dedup, 24h window,
  409 `DUPLICATE_EVENT` on a replay — and `factConsumption` — every `IdempotencyOutcome` constant,
  `NEW`/`DUPLICATE_IGNORED`, derived from that enum, plus its two dedup layers: `envelopeDeduplication`
  (a redelivered envelope, same `eventId`, is short-circuited by `processed_events` and writes no row) and
  `postingDeduplication` (per listener, the business key a re-emitted fact is matched on: the deterministic
  `sourceEventId` for inventory / invoice / order journal entries, vendor + bill number for supplier
  invoices, the reimbursement id for warranty — which never records `DUPLICATE_IGNORED` — and, for
  `payment.payment.settled`, the application request id `PAYMENT_SETTLED:<paymentIntentId>` plus the
  held row's event type + `paymentIntentId` + reason).

## Kafka Fact Ingestion Records — the event list (issue #2433)

`GET /v1/accounting/events` (backed by `accounting_event`) is the audit view of every posting Kafka fact
accounting consumed. Each posting listener writes exactly one row per consumed fact through
`KafkaFactIngestionRecorder` (generalised from the inventory recorder of #2191), in the same handler
transaction as the posting and the `processed_events` mark:

| Listener | `eventType` | `sourceSystem` | `domainKeyId` | Row |
|---|---|---|---|---|
| `InventoryEventsListener` | `inventory.scrap.posted`, `inventory.adjustment.posted`, `inventory.product-value.changed`, `goodsreceipt.recorded` | `pos-inventory` | scrap / adjustment / revaluation / receipt id | see Inventory Posting Facts above; a goods receipt can also be `SUSPENDED / CURRENCY_NOT_SUPPORTED` or `SUSPENDED / VALIDATION_ERROR` |
| `InvoiceEventsListener` | `invoice.invoice.updated` | `pos-invoice` | invoice id | `PROCESSED / NEW` + `journalEntryId` when revenue (or its reversal) posts; `PROCESSED / DUPLICATE_IGNORED` + the earlier entry when the cycle was already posted (the `POSTED` fact after every `FINALIZED` one); `PROCESSED / NEW`, no entry, for a zero total or a revert with nothing open; `SKIPPED / NOT_POSTABLE` for a stale fact, a deposit-take invoice, no `finalizedAt`, or a status that neither recognizes nor reverses (`ERROR`) |
| `OrderEventsListener` | `order.session.closed` | `pos-order` | session id | `PROCESSED / NEW` + an entry it posted (the over/short's, else the first drawer movement's; every movement entry carries the `sessionId` dimension, #2513); `PROCESSED / NEW`, no entry, when nothing posts (a zero variance and no movement to post); `PROCESSED / DUPLICATE_IGNORED` when every posting key of the session was already registered; a foreign-currency hold is the `SUSPENDED / CURRENCY_NOT_SUPPORTED` row (Ledger currency above) |
| `SupplierInvoiceEventsListener` | `supplier.invoice.received` | `pos-supplier` | vendor bill id | `PROCESSED / NEW`, no entry (nothing posts on ingest; the bill posts at approval, #2509), for a new bill, a duplicate flagged on the live original and a re-issue of an approved bill recorded as an exception item; `PROCESSED / DUPLICATE_IGNORED` for a duplicate identical to the live bill held, under the duplicate rule above (#2501) |
| `WarrantyEventsListener` | `warranty.reimbursement.submitted`, `warranty.reimbursement.resolved` | `pos-warranty` | reimbursement id | `PROCESSED / NEW`, no entry; `SKIPPED / NOT_POSTABLE` for a stale fact |
| `SettlementEventsListener` | `payment.payment.settled` | `pos-invoice` | `paymentIntentId` | no row when the payment is applied automatically or another path already applied it (the application is the evidence); otherwise one row per Payment Application above: `SKIPPED / NOT_POSTABLE`, `SUSPENDED / INVOICE_NOT_FOUND`, `SUSPENDED / PERIOD_CLOSED`, `FAILED / INVOICE_NOT_ELIGIBLE`, or the `SUSPENDED / CURRENCY_NOT_SUPPORTED` hold; a re-emitted fact already skipped for the same cause, or held for the same reason, writes no second row (#2503) |

- `domainKeyId` is not unique: every fact about the same document (an invoice finalized, posted, then
  cancelled) writes its own row under the same key. `eventReference` (`AE-YYYYMM-n`) is the unique one.
- A redelivery of the same envelope is short-circuited by `processed_events` and writes no row. A
  recording failure is a posting failure: it propagates unmarked for container retry / DLQ.
- Malformed payloads, other event types on these topics, and the replica-only listeners (customer,
  location, invoice manifest, settlement config, work order) write no row. `SettlementEventsListener`
  writes one only for a `payment.payment.settled` fact it did not apply (table above).
- Each listener exposes its codes as `RECORDED_EVENT_TYPES` for the event-type registry (#2436).
- **No backfill.** Rows start with the deploy of #2433. Facts consumed before it (on alpha, 2026-10-03: 2009
  posted invoice-revenue journal entries, plus every register over/short, vendor bill and warranty
  expectation) have no `accounting_event` row and will not get one; their journal entries, `invoice_gl_posting`,
  `vendor_bill` and `warranty_reimbursement_expectation` rows remain the record of them.

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-tenancy-common` — ADR-0062 tenant context, connection binding, Hibernate resolver, Kafka propagation
- `pos-events` — `@EmitEvent` AOP annotation and event registration
- `pos-shared-dtos` — shared invoice and vehicle DTOs

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration` (the pre-2026-09-09 chain was
flattened into the baseline for ADR-0062; see `../durion/docs/architecture/deployment/TENANCY_SCHEMA.md`):

- `V1__baseline_accounting.sql` — the whole schema, with the tenancy schema (`tenant_id`, row-level security,
  tenant-scoped keys) on every scoped table and `tenant_id` as data on the two global outbox tables
  (`event_outbox`, `kafka_event_outbox`, see Multitenancy below); edited in place while in alpha, with `V2` retained only
  for seed data (alpha databases are recreated; see `docs/runbooks/flyway-baseline-reset.md`, "Alpha Cutover")
- `V2__seed_accounting.sql` — versioned seed data: the alpha default tenant's labour and overhead accounts and
  lines. Immutable; the template carries the same accounts for every other tenant
- `V5__tenant_template_provisioning.sql` — `accounting_template_state` and `accounting_template_entry` (what the
  template applier did for each tenant), and the default tenant's `RETREAD_PLANT_ADD_ON` choice (#2526)
- `V7__receivable_payment_remittance.sql` — `receivable_payment.source_invoice_id` and `payment_method`, written
  from then on by the two paths that record a payment (first writer wins, no backfill), and
  `idx_ext_invoice_party_status (tenant_id, party_id, status)` for a customer's open invoices (#2502)
- `R__seed_reference_accounting.sql` — the accounting tenant template, in the **platform tenant** only: the
  reference chart, posting categories, mapping keys, GL mappings, the default GL mapping and the statement
  lines every tenant is provisioned from, plus the retread-plant add-on. It writes no tenant's rows (#2526)

## Development

```bash
./mvnw -pl pos-accounting -am spring-boot:run
```

## Reconciliation manifest replay requests (#2452)

A manifest listener that finds drift sends the owner's `outbox.replay-requested` command through
`OutboxReplayRequests`, which waits up to 30s for the broker's acknowledgement. A request that cannot
be handed to Kafka, that the broker rejects, or that is not acknowledged in time propagates to `KafkaErrorHandlingConfig`, which retries the manifest with backoff and then dead-letters it to `{topic}.dlq`.
Swallowing it would lose the repair for good, because each owner publishes a window's manifest once
and no later manifest covers that window again. Redelivery is safe: a manifest writes nothing, the
comparison only reads, and the replay command is keyed by window start. A manifest that does not parse
is still dropped.
