---
rag_id: accounting.bank-reconciliation
rag_scope: accounting
required_permissions:
  - accounting:reconciliation:view
---

# Bank Reconciliation

## Purpose

RAG id: `accounting.bank-reconciliation`
RAG scope: `accounting`
Required permissions: `accounting:reconciliation:view`
Audience: internal staff who prepare or approve bank reconciliations.
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This document describes the manual bank reconciliation implemented in pos-accounting: bringing in a bank statement,
matching bank transactions to posted ledger lines, explaining what does not match, and submitting and approving the
reconciliation. It covers the journal entries a reconciliation posts and how later postings affect an approved one.

## General concepts

General accounting knowledge, not platform behaviour.

- A bank reconciliation compares the cash balance in the books with "the balance reported by the financial
  institution in the most recent bank statement"; differences come mainly from timing, from items the bank recorded
  that the books have not, and from errors (Sources [1]).
- The standard method adjusts both sides. **Balance per bank:** outstanding checks, "written and recorded in its
  Cash account" but not yet cleared, are subtracted; deposits in transit, recorded in the books but "not been
  processed by the bank", are added; bank errors are corrected. **Balance per books:** items on the statement that
  the books lack, such as bank fees, NSF (returned) checks, interest and electronic transfers, are recorded as
  entries. The statement is reconciled when the adjusted balance per bank equals the adjusted balance per books
  (Sources [2]).
- How the platform maps this (from the code, below): bank-side items are **outstanding items**, which post nothing
  (`DEPOSIT_IN_TRANSIT`, `OUTSTANDING_CHECK`, `BANK_ERROR_PENDING`, `OTHER_LEDGER_TIMING`); book-side items are
  **adjustments**, which post a journal entry to the cash account (`BANK_FEE`, `NSF_FEE`, `INTEREST_EARNED`,
  `TRANSFER`, `OTHER`).

## Permissions and roles

| Code | Grants | Seeded roles |
| --- | --- | --- |
| `accounting:reconciliation:view` | Every read: accounts, statements, imports, transactions, reconciliations, review, report, audit | ADMIN, CONTROLLER, SUPPORT |
| `accounting:reconciliation:adjust` | Preparer: import, enter statements, start, match, register items, post adjustments, submit | ADMIN, CONTROLLER |
| `accounting:reconciliation:approve` | Approver: finalize, return, cancel, supersede, exclude or restore a bank row, reverse an adjustment, download the raw file | ADMIN, CONTROLLER |

The approver may not be the submitter unless the tenant's policy `allowSelfApproval` (`BANK_REC_ALLOW_SELF_APPROVAL`)
is true; a refused self-approval is `403 RECONCILIATION_SELF_APPROVAL` and is audited.

## Bank accounts

- Only an active GL account of subtype `BANK_CASH` that is reconcilable can be reconciled, and only in the ledger
  currency (`422 CURRENCY_NOT_SUPPORTED` otherwise).
- `GET /v1/accounting/bank-accounts` lists those accounts with their profile, reconciliation baseline date, coverage
  and reconciled frontiers, and counts of unexplained bank transactions and open outstanding items.
- `PUT /v1/accounting/bank-accounts/{glAccountId}/profile` sets bank name, account mask, currency, default column
  mapping and statement cycle hint. The baseline is not editable; only an acknowledged statement moves it.

## Bringing in a statement

A statement is a header (`startDate`, `endDate`, `openingBalance`, `closingBalance`) plus its transactions. Each
statement must continue the previous one (opening equals the previous closing, start the day after its end) unless a
`gapAcknowledgement` of at least 10 characters is given; one is required for the account's first statement, refused
on a contiguous one, and moves the account's baseline.

- **File import** (`/v1/accounting/bank-imports`): upload a **CSV** file with the header. Rows are parsed under a
  column mapping and sign convention; the import is `VALIDATED`, or `UPLOADED` when columns still need a mapping
  (`PUT .../{importId}/mapping`). Fix rows with `PUT .../rows/{rowId}` (correct, skip with a reason, or decide a
  possible duplicate). Commit with `POST .../{importId}/commit` once opening plus activity equals closing within one
  minor unit, no row is `REJECTED` and `OUT_OF_WINDOW` rows are skipped; `startReconciliation` starts it in the same
  step. `POST .../discard` abandons an import.
- **Manual entry** (`POST /v1/accounting/bank-statements`): key the header and transactions by hand, for a bank with
  no download or to reconcile to a date.
- Import statuses: `UPLOADED`, `VALIDATED`, `COMMITTED`, `DISCARDED`. Row statuses: `PARSED`, `REJECTED`,
  `CORRECTED`, `SKIPPED`, `POSSIBLE_DUPLICATE`, `OUT_OF_WINDOW`, `COMMITTED`. Statement statuses: `COMMITTED`,
  `SUPERSEDED`.
- Not supported: file formats other than CSV, and live bank feeds. A statementless, feed-backed reconciliation is
  refused with `422 BANK_ACCOUNT_FEED_NOT_LINKED` on every account in this phase.

## Bank transactions

`BankTransactionStatus`: `UNMATCHED`, `POSSIBLE_DUPLICATE`, `MATCHED`, `EXCLUDED`, `REMOVED_BY_SOURCE`.

- `GET /v1/accounting/bank-transactions?glAccountId=` lists an account's rows; "which bank transactions are still
  unmatched" is `status=UNMATCHED`, or `unexplainedOnly=true` for `UNMATCHED` and `POSSIBLE_DUPLICATE` together.
- A row whose fingerprint matches another is `POSSIBLE_DUPLICATE`; `POST .../{id}/duplicate-review` (or the bulk
  form) decides `DISTINCT` (back to `UNMATCHED`) or `DUPLICATE` (excluded, original recorded), with a justification.
- `POST .../{id}/exclude` removes an `UNMATCHED` row from every sum and count while keeping it as evidence (approver,
  justification); `POST .../{id}/restore` brings it back.

## Starting and matching

- `POST /v1/accounting/reconciliations` starts an `IN_PROGRESS` reconciliation of a `COMMITTED` statement; window and
  balances are copied from the statement. No journal entry is posted.
- `GET .../{id}/candidates` ranks ledger lines for one bank row (or bank rows for one ledger line): exact amount +60,
  within tolerance +40, date in window up to +20 (window 7 days by default), reference match +20, similar
  description up to +10.
- `POST .../{id}/auto-match` proposes a `ONE_TO_ONE` match for each unexplained bank row whose top candidate scores
  at least 90 and leads the next by at least 20. Proposals are `PROPOSED`; the system never accepts one.
- `POST .../{id}/matches` records an accepted match of bank rows to posted ledger lines (one-to-one, one-to-many or
  many-to-one) whose totals agree within the matching tolerance; a non-1:1 match, tolerance use, out-of-window dates
  or a former duplicate need a justification of at least 10 characters. Proposals are accepted or rejected with
  `.../matches/{matchId}/accept` or `/reject`; `/unmatch` undoes an accepted match.
- The matching tolerance is one minor unit of the ledger currency: `0.01` for a two-decimal currency such as USD or
  CAD, `1` for a currency without decimals such as JPY. The same tolerance applies to the submit and approval gate.
- `MatchState`: `PROPOSED`, `ACCEPTED`, `REJECTED`, `UNMATCHED`, `BROKEN`. `MatchKind`: `ONE_TO_ONE`, `ONE_TO_MANY`,
  `MANY_TO_ONE`, `ADJUSTMENT`.

## Explaining what does not match

**Outstanding items** are timing differences and post nothing
(`POST /v1/accounting/reconciliations/{id}/outstanding-items`). Kinds:
`DEPOSIT_IN_TRANSIT` (positive ledger line), `OUTSTANDING_CHECK` (negative ledger line), `OTHER_LEDGER_TIMING`, and
`BANK_ERROR_PENDING` on a bank row. An open item carries forward until matched, cleared or released. Statuses:
`OPEN`, `CLEARED`, `CLEARED_IN_GAP`, `VOIDED`, `RELEASED`. An `OTHER_LEDGER_TIMING` item older than the aging days
(default 90) counts as unexplained until reaffirmed (`.../reaffirm`).

**Adjustments** post a real, balanced journal entry through the accounting-period gate
(`POST /v1/accounting/reconciliations/{id}/adjustments`). Types and signs: `BANK_FEE` and `NSF_FEE` negative,
`INTEREST_EARNED` positive, `TRANSFER` either sign against another bank account, `OTHER` either sign to the
clearing account with exactly one link (a bank row, a match residual, or the statement's acknowledged gap).
`GET /v1/accounting/reconciliations/adjustment-types` serves the list. Adjustment statuses: `POSTED`, `REVERSED`.
Reverse a wrong one with `.../{id}/adjustments/{adjustmentId}/reverse`, not with the journal-entry reversal endpoint,
which would leave the reconciliation's links in place. An `OTHER` adjustment above the tenant's approval threshold,
or any `OTHER` that does not settle a match residual while no threshold is set, needs
`accounting:reconciliation:approve`.

## Finishing a reconciliation

`ReconciliationStatus`: `IN_PROGRESS`, `SUBMITTED`, `FINALIZED`, `INVALIDATED`, `SUPERSEDED`, `CANCELLED`. The
endpoints below are under `/v1/accounting/reconciliations`.

1. Review: `GET .../{id}/review` returns the whole workspace and `readiness.canSubmit`; `.../report` the printable
   report; `.../audit` the audit trail.
2. Submit (preparer): `POST .../{id}/submit`. Allowed only when the live difference is within one minor unit of the
   ledger currency and no bank row or ledger line from the baseline on is unexplained
   (`422 RECONCILIATION_NOT_BALANCED` or `RECONCILIATION_HAS_UNEXPLAINED_ITEMS` otherwise). The opening difference
   never blocks.
3. Approve (approver): `POST .../{id}/finalize` recomputes everything under a lock and, if the gate still holds,
   seals the matches and snapshots the approved ending balance. `.../return` sends it back with a reason;
   `.../cancel` abandons it (matches released, posted adjustments stay posted).
4. Correct an approved window: there is no reopen. `.../supersede` starts a successor that re-proposes the old
   matches; when it is approved the predecessor becomes `SUPERSEDED`. A corrected statement is re-imported with
   `supersedesStatementId`.

## After approval: invalidation

A `FINALIZED` reconciliation becomes `INVALIDATED` in the same transaction as the ledger change that undermines it;
the ledger change itself is never refused. Only `FINALIZED` reconciliations are invalidated.

- **Posting:** a journal entry posted on the reconciled account, dated inside the approved window, invalidates it
  (reason `LEDGER_LINE_POSTED`). This holds whether the period was open, reopened or overridden, and a `TRANSFER`'s
  counter line invalidates the counter account's window too.
- **Reversal, match-owner path:** reversing an entry breaks every active match that holds one of the original's
  lines (state `BROKEN`, bank rows back to `UNMATCHED`), voids every `OPEN` outstanding item on those lines, and
  invalidates the `FINALIZED` reconciliation that owns each broken match, even when the reversal is dated outside
  that reconciliation's window (reason `LEDGER_LINE_REVERSED`).
- **Reversal, date path:** the reversal also invalidates any `FINALIZED` reconciliation of the account whose window
  contains the reversal's date, whether or not it held a match on the original.
- The fix for an invalidated window is `.../supersede` (above).

## Period close and bank reconciliation

Period close reads a bank-reconciliation readiness check (`GET /v1/accounting/periods/{periodCode}/close-readiness`)
under the tenant's close policy:

- `ADVISORY`: the close goes ahead and is recorded as not ready.
- `REQUIRED`: a blocking check refuses the close with `422 PERIOD_BANK_RECONCILIATION_INCOMPLETE`; no exception is
  accepted.
- `REQUIRED_WITH_EXCEPTION` (the default): a blocking check refuses the close with the same 422 unless the close
  request carries `bankReconciliationException.justification` **and** the caller holds both `accounting:period:close`
  and `accounting:period:override` (seeded for ADMIN and CONTROLLER). A caller missing either gets
  `403 PERIOD_CLOSE_EXCEPTION_NOT_PERMITTED` and the period stays open. The justification must be 10 to 1000
  characters (`400` otherwise). A granted exception is audited as `PERIOD_CLOSE_BANKREC_EXCEPTION` with the readiness
  snapshot.

## Verified facts

- _Verified: pos-accounting `BankImportController`, `BankStatementController`, `BankTransactionController`,
  `BankAccountController`, `BankReconciliationController`, `ReconciliationMatchController`,
  `ReconciliationOutstandingItemController` and `ReconciliationAdjustmentController` mappings, `@PreAuthorize` codes
  and operation descriptions._
- _Verified: the enums `ReconciliationStatus`, `BankStatementStatus`, `BankTransactionStatus`, `MatchState`,
  `MatchKind`, `OutstandingItemKind`, `OutstandingItemStatus`, `BankAdjustmentType`, `AdjustmentStatus`,
  `BankImportStatus` and `BankImportRowStatus`._
- _Verified: CSV is the only statement parser (`StatementFileParsers`); `ReconciliationCreateRequest` refuses
  feed-backed reconciliations with `BANK_ACCOUNT_FEED_NOT_LINKED`; role grants in pos-security-service
  `R__seed_role_permissions.sql`._

## Sources

Platform sources (repository-relative):

- `pos-accounting/src/main/java/com/positivity/accounting/internal/bankrec/` (controllers, enums, services)
- `pos-accounting/src/main/java/com/positivity/accounting/internal/bankfeed/file/` (statement-file import)
- `pos-accounting/src/main/java/com/positivity/accounting/internal/bankrec/service/FunctionalCurrency.java`
  (`tolerance()`: one minor unit), used by `ReconciliationMatchingServiceImpl` and `ApprovalGate`
- `pos-accounting/src/main/java/com/positivity/accounting/internal/bankrec/service/BankReconciliationLedgerChangeService.java`
  (`onPosted`, `onReversed`) and `ReconciliationLifecycle.invalidate` (only `FINALIZED` is invalidated)
- `pos-accounting/src/main/java/com/positivity/accounting/internal/bankrec/readmodel/BankReconciliationCloseReadiness.java`
  (`decide`), `AccountingPeriodGate.OVERRIDE_AUTHORITY`, `bankrec/intake/Justification.java` (`MIN_LENGTH = 10`),
  `dto/PeriodCloseRequest.java`
- `pos-accounting/README.md` ("Bank reconciliation close readiness and policy (#2305)", "Error codes",
  "Configuration")
- `pos-accounting/src/main/resources/permissions.yaml`
- `pos-security-service/src/main/resources/db/migration/R__seed_role_permissions.sql`
- `durion/domains/accounting/SPEC-manual-bank-reconciliation.md` (accepted specification)

External sources:

1. "Reconciliation (accounting)", sections "In banking" and "Methods", Wikipedia, Wikimedia Foundation.
   <https://en.wikipedia.org/wiki/Reconciliation_(accounting)>. Accessed 2026-10-02.
2. "Bank Reconciliation: In-Depth Explanation with Examples", AccountingCoach.
   <https://www.accountingcoach.com/bank-reconciliation/explanation>. Accessed 2026-10-02.
