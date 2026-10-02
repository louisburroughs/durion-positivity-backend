---
rag_id: accounting.supplier-vendor-bills
rag_scope: accounting
required_permissions:
  - accounting:ap:view
  - accounting:credit-memo:read
---

# Suppliers, Vendor Bills and Credit Memos

## Purpose

RAG id: `accounting.supplier-vendor-bills`
RAG scope: `accounting`
Required permissions: `accounting:ap:view`, `accounting:credit-memo:read`
Audience: internal staff who work accounts payable or customer credits.

This guide explains the accounts payable (AP) side of pos-accounting as it is implemented: the two kinds of supplier
record, how a vendor bill arrives, how it is matched to a receipt, how bills are paid, and how open balances are read.
It also separates the customer credit memo (an accounts receivable document) from a vendor credit note, because both
are called "credit memo" in conversation. "Supplier" and "vendor" mean the same party. Where the platform has no
behaviour for something, this guide says so instead of describing it.

## General concepts

These definitions are general accounting knowledge, not platform behaviour.

- **Accounts payable** is "money owed by a business to its suppliers, shown as a liability on a company's balance
  sheet" (Sources [1]).
- **Two-way and three-way matching.** The simplest check pairs the invoice with the purchase order; the three-way
  match also compares what was physically received, so an invoice is paid only when the order, the receipt and the
  invoice agree (Sources [1], [2]).
- **Credit note or credit memo.** A seller issues it to reduce what a buyer owes under an earlier invoice, for
  example after a return (Sources [3]). Seen from the buyer, a vendor's credit note reduces payables; seen from the
  seller, a credit memo reduces receivables. A debit note is the related document either party may use about an
  amount due (Sources [4]).
- **Payment run.** Larger AP systems select many approved invoices that fall due by a date and pay them together as
  one batch (Sources [5]). The platform has no such batch run; see "Paying vendor bills".

## Supplier records

Two records describe a supplier, owned by different modules.

| Record | Owner | What it holds | Read permission |
| --- | --- | --- | --- |
| Vendor profile | pos-supplier | How the platform reaches a supplier electronically: `supplierRef`, `displayName`, `enabled`, `sandbox`, credentials, accounts and endpoint bindings | `supplier:profile:read` |
| AP vendor directory entry | pos-accounting | `vendorId`, `name`, optional `vendorNumber`, status `ACTIVE` or `INACTIVE` | `accounting:ap:view` |

- The directory entry is created on first sight: when a goods-received bill or a supplier invoice names a vendor the
  directory has never seen. There is no endpoint to create or edit one.
- For a supplier reached over EDIWheel, the pos-supplier `vendorProfileId` is used as the accounting `vendorId`, so
  both records share one identifier.
- Directory reads: `GET /v1/accounting/vendors?name=` (case-insensitive contains match, limit 20, at most 100) and
  `GET /v1/accounting/vendors/{vendorId}`.
- Not supported: supplier payment terms, remit-to addresses, tax registration or a credit limit. None of these is a
  field of either record.

## Vendor bill lifecycle

`VendorBillStatus` values:

| Status | Meaning |
| --- | --- |
| `PENDING_RECEIPT_MATCH` | Recorded and waiting for its counterpart (the vendor's invoice for a receipt, or a receipt for an invoice) |
| `MATCH_EXCEPTION` | Matching found a discrepancy, a medium-confidence score or several candidates; a person must resolve it |
| `CURRENCY_HOLD` | Stated in a currency other than the ledger currency; held, never booked at par (ADR-0067) |
| `APPROVED` | Matched or accepted; eligible for payment |
| `REJECTED` | Declared; no current write path sets it |
| `PAID` | Declared; no current write path sets it (see "Paying vendor bills") |
| `VOIDED` | Voided by an operator while resolving an exception |

A bill carries `billNumber`, `billDate`, an optional `dueDate`, `totalAmount`, `currency`, `vendorId` and
`vendorName`, an optional purchase-order reference, its origin event, and the approval or rejection actor and reason.

## How a vendor bill arrives

1. **From a goods receipt.** `POST /v1/accounting/vendor-bills` (`accounting:ap:pay`) takes the receipt: vendor,
   purchase order, received date and lines (product, description, quantity, unit price). It creates a bill in
   `PENDING_RECEIPT_MATCH` totalling the lines, assigns a number
   `BILL_<first 8 hex of vendorId>_<yyyyMMdd>_<7-digit sequence>`, and emits a GL posting event for the bill. A
   repeated `eventId` returns the existing bill. Receiving an ASN in pos-inventory does not call this endpoint
   automatically today.
2. **From a supplier's electronic invoice.** pos-supplier fetches invoices over EDIWheel and publishes each new one;
   pos-accounting records it as a bill under the vendor's own invoice number and creates no journal entry when it
   arrives. Such a bill has no due date. A missing amount parks it in `MATCH_EXCEPTION`; a foreign currency parks it
   in `CURRENCY_HOLD`; the same number re-issued with another amount or currency is flagged `MATCH_EXCEPTION` instead
   of overwritten. A manual fetch for a date window is
   `POST /v1/supplier/invoices/{supplierRef}/fetches` (`supplier:invoice:fetch`).
3. **A vendor credit note** arrives the same way and is stored as a vendor bill with a negative total.

## Matching an invoice to a receipt

`POST /v1/accounting/vendor-bills/match` (`accounting:ap:pay`) scores the vendor's invoice against every bill of that
vendor in `PENDING_RECEIPT_MATCH`:

- total within 10 %: 40 points; product overlap of the lines (Jaccard): up to 30; invoice date within 7 days of the
  bill: 20, within 30 days: 10; a purchase-order reference on the bill: 5.
- No bill at 50 points or more: rejected (400, no pending receipt). Several at 50 or more: `AMBIGUOUS`, the
  candidates are stored and the best bill goes to `MATCH_EXCEPTION`. One at 70 or more: high confidence; 50 to 69:
  medium confidence, `MATCH_EXCEPTION`.
- A high-confidence match is then checked line by line: same line count, quantity within 0.1 %, unit price within
  5 %, total within 5 %. Failing sets `MATCH_EXCEPTION`; passing approves a high-confidence bill (`APPROVED`), and a
  bill that passes takes the invoice's number and due date.

The code calls this a three-way match, but the vendor invoice carries no purchase-order reference, so the order is
only checked for presence; the comparison is invoice against receipt.

Clearing an exception:

- `GET /v1/accounting/vendor-bills/match-candidates/{invoiceEventId}` lists stored candidates by score;
  `POST .../match-candidates/{candidateId}/select` approves the chosen bill.
- `POST /v1/accounting/vendor-bills/{billId}/resolve-exception` with `ACCEPT` (approve), `VOID` (void) or `CORRECT`
  (back to `PENDING_RECEIPT_MATCH`), a reason and an operator, all recorded.

## Paying vendor bills

- `GET /v1/accounting/ap/bills` lists `APPROVED` bills, oldest due date first, optionally for one `vendorId`.
- `POST /v1/accounting/ap/payments` (`accounting:ap:pay`) pays **one vendor** through the payment gateway:
  `vendorId`, `grossAmount`, `currency`, `paymentRef` (idempotency key) and `paymentMethod` (`ACH`, `CHECK`, `WIRE`,
  `CREDIT_CARD`, `OTHER`). Explicit allocations must name `APPROVED` bills of that vendor and may not exceed the
  gross; without them the payment is spread over the vendor's approved bills, oldest due date first, partially paying
  the last one. Anything left is `unappliedAmount`.
- `APPaymentStatus`: `INITIATED`, `GATEWAY_PENDING`, `GATEWAY_FAILED`, `GATEWAY_SUCCEEDED`, `GL_POST_PENDING`,
  `GL_POSTED`, `GL_POST_FAILED`. Read with `GET .../ap/payments/{paymentId}` or `.../payments/by-ref/{paymentRef}`.
- A paid bill keeps status `APPROVED`; what remains open is its total minus its allocations.
- Not supported: scheduled or multi-vendor payment runs, payment approval workflow, and applying a vendor credit
  note against other bills.

## Open balances and due dates

- **What we owe a vendor:** `GET /v1/accounting/reports/financial/aged-payables?asOfDate=`
  (`reporting:view:financial-statements`) gives per-vendor open balances in buckets 0-30, 31-60, 61-90 and 90+ days
  past due. Open means `PENDING_RECEIPT_MATCH`, `MATCH_EXCEPTION` or `APPROVED`, minus allocations; only positive
  balances count, foreign-currency bills are left out, and a bill with no due date ages from its bill date.
- **Bills due in a window:** `GET /v1/accounting/vendor-bills?dueFrom=&dueTo=&status=` (`accounting:analytics:view`),
  window at most 366 days, ordered by due date.
- **Spend by vendor:** `GET /v1/accounting/analytics/vendor-spend` (`accounting:analytics:view`), top vendors by paid
  amount in a date window.

## Customer credit memos and customer credits

These are accounts receivable documents: money the business owes back to a **customer**, never to a supplier.

- **Credit memo** (`/v1/accounting/credit-memos`): issued against a finalized customer invoice with an amount,
  reason code and optional note (`accounting:credit-memo:create`). The amount is the pre-tax part; tax is reversed in
  proportion from the invoice's frozen tax, and amount plus tax may not exceed the balance due. It is posted at once
  and numbered `CM-<yyyyMM>-<sequence>`. Statuses `DRAFT`, `POSTED`, `APPLIED`, `VOIDED`; creation goes straight to
  `POSTED` and no current path sets `DRAFT` or `APPLIED`. Void (`accounting:credit-memo:void`) needs a `POSTED` memo
  and a reason. List and read need `accounting:credit-memo:read`.
- **Customer credit** (`/v1/accounting/customer-credits`): a standing credit created when a customer overpays.
  Statuses `AVAILABLE`, `PARTIALLY_CONSUMED`, `CONSUMED`. It can be applied to that customer's invoice
  (`accounting:customer-credit:apply`) or refunded (`accounting:customer-credit:refund`); both are idempotent on
  `requestId` and refused when the period is not open.
- "Store credit" on a sales return is recorded by pos-order as the refund method, but creating the credit in
  accounting is not wired yet.
- An invoice adjustment in pos-invoice that would make the total negative is refused; a credit memo is the route.

## Verified facts

- _Verified: pos-accounting `VendorBillController`, `APPaymentController`, `VendorDirectoryController`,
  `CreditMemoController`, `CustomerCreditController`, `FinancialReportingController` and
  `AccountingAnalyticsController` mappings and `@PreAuthorize` codes._
- _Verified: `VendorBillServiceImpl` scoring (40/30/20-10/5, thresholds 50 and 70), tolerances (0.1 % quantity, 5 %
  price and total) and `BILL_%s_%s_%07d` numbering; `SupplierInvoiceEventsListener` currency hold, re-issue and
  credit-note sign rules._
- _Verified: `APPaymentServiceImpl` allocation rules; no code sets `VendorBillStatus.PAID` or `REJECTED`._
- _Verified: `CreditMemoServiceImpl` tax reversal, `CM-{YYYYMM}` reference scope and the absence of a write to
  `DRAFT` or `APPLIED`; `ReturnOrderServiceImpl` store-credit refund recording._

## Sources

Platform sources (repository-relative):

- `pos-accounting/src/main/java/com/positivity/accounting/internal/controller/VendorBillController.java`
- `pos-accounting/src/main/java/com/positivity/accounting/internal/service/VendorBillServiceImpl.java`
- `pos-accounting/src/main/java/com/positivity/accounting/internal/service/SupplierInvoiceEventsListener.java`
- `pos-accounting/src/main/java/com/positivity/accounting/internal/enums/VendorBillStatus.java`
- `pos-accounting/src/main/java/com/positivity/accounting/internal/controller/APPaymentController.java`
- `pos-accounting/src/main/java/com/positivity/accounting/internal/service/APPaymentServiceImpl.java`
- `pos-accounting/src/main/java/com/positivity/accounting/internal/controller/VendorDirectoryController.java`
- `pos-accounting/src/main/java/com/positivity/accounting/internal/service/FinancialReportingServiceImpl.java`
- `pos-accounting/src/main/java/com/positivity/accounting/internal/service/CreditMemoServiceImpl.java`
- `pos-accounting/src/main/java/com/positivity/accounting/internal/service/CustomerCreditServiceImpl.java`
- `pos-accounting/src/main/resources/permissions.yaml`
- `pos-supplier/README.md`
- `pos-supplier/src/main/java/com/positivity/supplier/internal/controller/SupplierInvoiceFetchController.java`
- `pos-domain-events/src/main/java/com/positivity/domainevents/supplier/SupplierInvoiceReceivedV1.java`
- `pos-order/src/main/java/com/positivity/order/internal/service/ReturnOrderServiceImpl.java`
- `pos-invoice/src/main/java/com/positivity/invoice/internal/service/InvoiceServiceImpl.java`
- `durion/domains/accounting/.business-rules/AGENT_GUIDE.md`; `durion/docs/adr/` ADR-0044, ADR-0067

External sources:

1. "Accounts payable", Wikipedia, Wikimedia Foundation. <https://en.wikipedia.org/wiki/Accounts_payable>. Accessed
   2026-10-02.
2. "Invoice processing", Wikipedia, Wikimedia Foundation. <https://en.wikipedia.org/wiki/Invoice_processing>.
   Accessed 2026-10-02.
3. "Credit note", Wikipedia, Wikimedia Foundation. <https://en.wikipedia.org/wiki/Credit_note>. Accessed 2026-10-02.
4. "Debit note", Wikipedia, Wikimedia Foundation. <https://en.wikipedia.org/wiki/Debit_note>. Accessed 2026-10-02.
5. "Payment Process Requests", Oracle Fusion Cloud Financials: Using Payables Invoice to Pay 25D, Oracle.
   <https://docs.oracle.com/en/cloud/saas/financials/25d/fappp/payment-process-requests.html>. Accessed 2026-10-02
   (read through a search-result extract; the page itself was not reachable from the authoring environment).
